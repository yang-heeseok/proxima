package net.gseek.proxima.concept

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * **Which index the transitive read wants, and whether the schema already has it.**
 *
 * `concept_edge` carries `uk_concept_edge unique (prerequisite_id, concept_id)`, and a
 * unique constraint in PostgreSQL is implemented as a B-tree — so there *is* an index over
 * both columns. It leads on `prerequisite_id`.
 *
 * A prerequisite traversal reads the other way. `where e.concept_id = ...` cannot restrict a
 * B-tree whose leading column is `prerequisite_id`; it can only be satisfied by scanning it.
 * That is `R3`'s finding — *column order is load-bearing, and the wrong order still serves
 * the shallow query* — arriving in a second table, and `R16`'s finding — *a constraint added
 * for correctness turned out to be an index the read path needed* — arriving with its sign
 * flipped.
 *
 * ## Why this measures both arms in one session
 *
 * `ADR-002` requires an index to arrive in the same commit as the measurement that justifies
 * it. The index is therefore created and dropped **inside this test**, exactly as `R16`
 * measured `uk_mastery_learner_concept`: the same binary on the same afternoon, one
 * difference. The arms stay comparable after `V4` ships because the test restores whatever
 * state it found — recreating from `pg_indexes.indexdef`, so there is no second copy of the
 * DDL anywhere to drift.
 *
 * ## What is asserted, and what is only printed
 *
 * `measurement-discipline.md` rule 9 — **CI asserts nothing that is a duration.** So the
 * assertions are categorical: which scan node the planner chose, and how many rows the
 * recursive term fed through it. Both are properties of the plan and the data and survive
 * being moved to another machine. Durations are printed as a median of three, for the
 * report to quote under its own environment block.
 *
 * ## The method order is load-bearing, and it is guarded rather than hoped
 *
 * **`VACUUM` is a side effect on a shared table that cannot be undone.** It sets the
 * visibility map, and nothing in this class puts it back — so every measurement taken *after*
 * a vacuum is in a different condition from every measurement taken before one, and this class
 * contains both kinds.
 *
 * `both candidates priced after a vacuum` is therefore `@Order(Int.MAX_VALUE)`: it runs last,
 * after `an index only scan is not index only until a vacuum has run` has established the
 * before-state it needs, and after the two arms that reproduce `R20` §3.6's pre-vacuum table.
 *
 * **This was found by breaking it.** Added without the ordering, the new arm vacuumed the
 * table first and the before/after test failed on `Heap Fetches: 0` where it required a
 * positive number — and its message had already named the cause it could not distinguish:
 * *"either something vacuumed this table … or PostgreSQL has changed when it sets the
 * visibility map."* Something did. It was the test added beside it.
 *
 * So the coupling is real and **the guard against getting the order wrong already existed**:
 * that assertion fails loudly the moment anything vacuums ahead of it. That is why the order
 * is expressed as an annotation and the danger is written down, rather than the two tests
 * being given separate tables — a separate table would remove the coupling and also remove the
 * only thing that reports it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PrerequisiteIndexTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    private var top = 0L

    /**
     * The `CREATE INDEX` PostgreSQL itself reports for the index, captured before anything
     * is dropped, so that teardown restores exactly what the migrations built.
     *
     * Read out of `pg_indexes` rather than copied from `V4`, for `MigrationDeduplicationTest`
     * and `PopulatedMigrationTest`'s reason: **a copy drifts, and a drifted copy passes
     * while the thing it copied is wrong.** Null before `V4` exists, and then this class
     * simply leaves the index absent, which is also the right restoration.
     */
    private var indexDefinitionOnEntry: String? = null

    @BeforeAll
    fun install() {
        indexDefinitionOnEntry = jdbc.queryForList(
            "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
            String::class.java,
            INDEX,
        ).firstOrNull()
        top = SeedConceptGraph.install(jdbc)[SeedConceptGraph.CONCEPTS]
        jdbc.execute("analyze concept_edge")
    }

    @AfterAll
    fun uninstall() {
        // Restored even if a test above failed. BaselineMigrationTest asserts the EXACT set
        // of performance indexes in this schema, so a difference left behind here would fail
        // a different class with a message about a report that never justified it.
        jdbc.execute("drop index if exists $INDEX")
        indexDefinitionOnEntry?.let { jdbc.execute(it) }
        SeedConceptGraph.remove(jdbc)
    }

    /**
     * **The comparison, at three depths.**
     *
     * Three depths rather than one because `measurement-discipline.md` §*The knee* refuses a
     * single data point: the recursive term is re-executed once per iteration, so the cost
     * of a missing index here is multiplied by depth rather than merely present at it, and
     * one depth cannot show that.
     */
    @Test
    fun `the closure read against the index the schema has, and the one it does not`() {
        val results = DEPTHS.map { depth ->
            withoutIndex()
            val without = median(depth)
            withIndex()
            val with = median(depth)
            depth to (without to with)
        }
        withoutIndex()

        println("depth  arm      scans                              rows fed   buffers      exec ms (median of 3)")
        results.forEach { (depth, arms) ->
            val (without, with) = arms
            println("%5d  no idx   %-34s %8d   hit=%-5d   %.3f".format(
                depth, without.scans, without.rowsThroughConceptEdge, without.bufferHits, without.executionMs,
            ))
            println("%5d  idx      %-34s %8d   hit=%-5d   %.3f".format(
                depth, with.scans, with.rowsThroughConceptEdge, with.bufferHits, with.executionMs,
            ))
        }

        results.forEach { (depth, arms) ->
            val (without, with) = arms
            assertTrue(
                without.scans.contains("Seq Scan"),
                "at depth $depth the planner did NOT choose a sequential scan without the " +
                    "index. That is the arm this comparison is about; if it has stopped " +
                    "happening, the finding has expired and the report has to say so",
            )
            assertEquals(
                listOf("Index Scan", "Index Scan"), with.scans,
                "at depth $depth the planner did not use $INDEX for both terms of the " +
                    "recursion. 8,994 edges is small enough that a sequential scan can " +
                    "win, and if it now does, V4 is not justified and should not exist",
            )
        }

        // Exact, not a bound. R8 §3.2: a bound drifts upward one honest commit at a time.
        assertEquals(
            ROWS_WITHOUT, results.map { it.second.first.rowsThroughConceptEdge },
            "the unindexed arm feeds `3 + 8994 x (depth - 1)` rows through concept_edge -- " +
                "the whole table, once per recursive iteration",
        )
        assertEquals(
            ROWS_WITH, results.map { it.second.second.rowsThroughConceptEdge },
            "the indexed arm feeds one probe's worth per frontier row",
        )

        // AND THE PROPERTY THAT WAS NOT EXPECTED. The first version of this test asserted a
        // flat `20x better at every depth` and was refused at depth 12, where the real
        // figure is 18.2x. The index's advantage SHRINKS with depth, because the unindexed
        // arm's cost grows linearly with iterations while the indexed arm's grows with the
        // frontier -- and the frontier is what depth makes big. Lowering the threshold to
        // get a green would have thrown away the finding.
        val ratios = results.map {
            it.second.first.rowsThroughConceptEdge.toDouble() / it.second.second.rowsThroughConceptEdge
        }
        assertTrue(
            ratios == ratios.sortedDescending() && ratios.first() > 5 * ratios.last(),
            "the index's row-count advantage was $ratios across depths $DEPTHS. It is " +
                "supposed to fall away with depth; if it stops doing so, R20 §3.5 is " +
                "describing something that no longer happens",
        )
    }

    /**
     * **The counter-intuitive half, pinned so nobody quietly drops it from the report.**
     *
     * The indexed plan is faster and touches **more** buffers. It is not paradoxical: the
     * sequential scan reads 67 pages and pushes 8,994 rows per iteration through a hash
     * join, while the index probes 181 times, touching a page per probe and producing three
     * rows each. Pages touched is not work done.
     *
     * This matters beyond this test. Buffers were chosen *because* rule 9 forbids asserting
     * a duration — and on this query they point the wrong way. **A machine-independent
     * metric is not automatically the right metric**, and the one that is right here is rows
     * fed through the scan.
     *
     * **And the sign depends on depth.** At depth 3 the indexed plan touches 36 buffers
     * against 204 and is the cheaper arm on both counts; the crossover sits between depth 3
     * and depth 6, and by depth 12 it is 5,445 against 804. Measured at 12 for that reason:
     * a control asserted at 6 would sit 1.35× from an inversion.
     */
    @Test
    fun `the faster plan touches more buffers, and that is not a contradiction`() {
        withoutIndex()
        val without = median(12)
        withIndex()
        val with = median(12)
        withoutIndex()

        assertTrue(
            with.bufferHits > without.bufferHits,
            "the indexed plan touched ${with.bufferHits} buffers against " +
                "${without.bufferHits}. If this ever inverts, R20 §3.4 is describing a " +
                "behaviour that no longer happens",
        )
        assertTrue(
            with.rowsThroughConceptEdge < without.rowsThroughConceptEdge,
            "and it must still feed fewer rows, or the two halves of §3.4 disagree",
        )
    }

    /**
     * **The candidates, priced.** `V2`'s comment sets the standard: an index that ships here
     * names the alternatives it beat and what they cost, because `BaselineMigrationTest`
     * asserts the exact set and *an index nobody measured is exactly what that is there to
     * catch.*
     *
     * `(concept_id)` alone is the minimal thing that answers `where concept_id = ?`. The
     * second column is what lets PostgreSQL answer the traversal **without touching the
     * heap** — the recursive term selects `prerequisite_id` and nothing else, so with both
     * columns present the index is covering for this query.
     *
     * Whether that is worth the bytes is a measurement and not an opinion. `R3` rejected the
     * covering variant on `attempt` for exactly this reason: 87% more space for a difference
     * at the edge of noise.
     */
    @Test
    fun `the two index candidates, priced against each other and against neither`() {
        val arms = linkedMapOf<String, String?>(
            "none" to null,
            "(concept_id)" to "create index $INDEX on concept_edge (concept_id)",
            "(concept_id, prerequisite_id)" to
                "create index $INDEX on concept_edge (concept_id, prerequisite_id)",
        )

        println("candidate                        bytes   depth  rows fed   exec ms (median of 3)")
        val chosen = HashMap<String, Long>()
        arms.forEach { (name, ddl) ->
            jdbc.execute("drop index if exists $INDEX")
            ddl?.let { jdbc.execute(it) }
            jdbc.execute("analyze concept_edge")
            val bytes = if (ddl == null) 0L else jdbc.queryForObject(
                "select pg_relation_size(?::regclass)", Long::class.java, INDEX,
            )!!
            listOf(6, 12).forEach { depth ->
                val arm = median(depth)
                println(
                    "%-30s %8d   %5d  %8d   %.3f".format(
                        name, bytes, depth, arm.rowsThroughConceptEdge, arm.executionMs,
                    ),
                )
                println("      scans: ${arm.scans}")
                if (depth == 12) chosen[name] = arm.rowsThroughConceptEdge
            }
        }
        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("analyze concept_edge")

        assertEquals(
            chosen["(concept_id)"], chosen["(concept_id, prerequisite_id)"],
            "the two candidates fed different numbers of rows through concept_edge. They " +
                "should not -- they answer the same predicate, and the second column buys " +
                "heap avoidance rather than selectivity. A difference means one of them is " +
                "not being used for the lookup at all",
        )
        assertTrue(
            chosen["none"]!! > chosen["(concept_id)"]!!,
            "neither candidate beat having no index, which would mean 8,994 edges is too " +
                "small to index and V4 is not justified",
        )
    }

    /**
     * **Why the covering candidate lost, and the condition under which it would not have.**
     *
     * `(concept_id, prerequisite_id)` produces an `Index Only Scan` and is **slower** than
     * `(concept_id)`, which produces a plain `Index Scan`. That looks wrong until the plan is
     * read to the end: `Heap Fetches: 543`.
     *
     * An index-only scan is only index-only when PostgreSQL can trust the **visibility map**
     * to say a page holds nothing but rows visible to everyone. The visibility map is set by
     * `VACUUM` and by nothing else — `ANALYZE` does not set it, and neither does `COPY`. A
     * freshly loaded table therefore has an empty visibility map, so every "index only" row
     * still goes to the heap, and the wider index has bought a second column's worth of
     * bytes to pay for a trip it still makes.
     *
     * **This is not an obscure corner. It is the state the seed loader leaves the database
     * in**: `Main.kt` runs `generate`, `load` (a `COPY`), and `analyze`. There is no
     * `vacuum` step, deliberately — `T4` needed stale statistics — and one consequence is
     * that every index-only scan in this repository's first measurement after a load is not
     * index-only.
     */
    @Test
    fun `an index only scan is not index only until a vacuum has run`() {
        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("create index $INDEX on concept_edge (concept_id, prerequisite_id)")
        jdbc.execute("analyze concept_edge")
        val beforeVacuum = median(12)

        jdbc.execute("vacuum (analyze) concept_edge")
        val afterVacuum = median(12)

        val fetchesBefore = heapFetches(beforeVacuum.plan)
        val fetchesAfter = heapFetches(afterVacuum.plan)
        println("covering index, depth 12")
        println("  before vacuum: heap fetches=$fetchesBefore buffers=${beforeVacuum.bufferHits} exec=${beforeVacuum.executionMs} ms")
        println("  after  vacuum: heap fetches=$fetchesAfter buffers=${afterVacuum.bufferHits} exec=${afterVacuum.executionMs} ms")

        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("analyze concept_edge")

        assertTrue(
            fetchesBefore > 0,
            "the index-only scan reported no heap fetches before any vacuum ran. Either " +
                "something vacuumed this table -- autovacuum is a real possibility on a " +
                "container that has been up a while -- or PostgreSQL has changed when it " +
                "sets the visibility map, and either way this finding needs re-taking",
        )
        assertEquals(
            0, fetchesAfter,
            "after `vacuum (analyze)` the index-only scan still made $fetchesAfter heap " +
                "fetches. The visibility map is what makes an index-only scan index-only, " +
                "and if VACUUM no longer sets it the covering column buys nothing ever",
        )
    }

    /**
     * **The arm `OPEN-11` was opened for.**
     *
     * `R20` §3.6 priced the two candidates **before** any vacuum and then measured the
     * covering one **either side** of a vacuum. So the only cross-candidate comparison
     * available afterwards was *covering after* against *single before* — two different
     * conditions, which `measurement-discipline.md` rule 3 refuses.
     *
     * That left the row undecidable rather than merely unanswered. The question `OPEN-11`
     * asks is whether this repository has twice rejected a covering index against a database
     * that could not pay for one — `R3` on `attempt`, `R20` on `concept_edge` — and **neither
     * report could answer it**, because neither measured the alternative in the state the
     * remedy needs.
     *
     * One arm closes it: both candidates, both vacuumed, same session, same fixture.
     *
     * **The precondition is asserted rather than assumed**, in the shape `ADR-015` settled the
     * same morning: the two candidates must feed the *same* number of rows through
     * `concept_edge`. They answer the same predicate, so the second column buys heap
     * avoidance and not selectivity. If the counts diverge, one of them is not being used for
     * the lookup at all and the comparison is between two different plans rather than two
     * indexes.
     */
    @Test
    @Order(Int.MAX_VALUE)
    fun `both candidates priced after a vacuum, the comparison OPEN-11 was missing`() {
        // `fetches` is null when the plan carries no `Heap Fetches:` line at all, which is the
        // case for an Index Scan -- it always visits the heap and does not count the visits.
        // Printing 0 for both "counted zero" and "no such field" would say the single-column
        // arm avoided the heap, which is the opposite of what its plan does. R5's mistake in
        // miniature: a missing measurement rendered as a measured zero.
        data class Priced(
            val bytes: Long,
            val medianMs: Double,
            val spread: Double,
            val fetches: Int?,
            val scans: List<String>,
            val rows: Long,
        )

        val arms = linkedMapOf(
            "(concept_id)" to "create index $INDEX on concept_edge (concept_id)",
            "(concept_id, prerequisite_id)" to
                "create index $INDEX on concept_edge (concept_id, prerequisite_id)",
        )

        val priced = LinkedHashMap<String, Priced>()
        arms.forEach { (name, ddl) ->
            jdbc.execute("drop index if exists $INDEX")
            jdbc.execute(ddl)
            jdbc.execute("analyze concept_edge")
            jdbc.execute("vacuum (analyze) concept_edge")

            val runs = (1..3).map { single(12) }.sortedBy { it.executionMs }
            val med = runs[1]
            priced[name] = Priced(
                bytes = jdbc.queryForObject(
                    "select pg_relation_size(?::regclass)", Long::class.java, INDEX,
                )!!,
                medianMs = med.executionMs,
                spread = (runs[2].executionMs - runs[0].executionMs) / med.executionMs,
                fetches = if (med.plan.contains("Heap Fetches:")) heapFetches(med.plan) else null,
                scans = med.scans,
                rows = med.rowsThroughConceptEdge,
            )
        }

        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("analyze concept_edge")

        println("OPEN-11 >>> after `vacuum (analyze) concept_edge`, depth 12, median of 3")
        println("OPEN-11 >>> candidate                       bytes   exec ms   spread   heap fetches   rows fed   scan")
        priced.forEach { (name, p) ->
            println(
                "OPEN-11 >>> %-28s %8d   %7.3f   %5.1f%%   %12s   %8d   %s"
                    .format(
                        name, p.bytes, p.medianMs, p.spread * 100,
                        p.fetches?.toString() ?: "n/a", p.rows, p.scans.distinct().joinToString("+"),
                    ),
            )
        }
        val single = priced["(concept_id)"]!!
        val covering = priced["(concept_id, prerequisite_id)"]!!
        val ratio = single.medianMs / covering.medianMs
        val worstSpread = maxOf(single.spread, covering.spread)
        println(
            "OPEN-11 >>> covering is %.2fx the single column; worst spread %.1f%% — %s"
                .format(ratio, worstSpread * 100, if ((ratio - 1.0) > worstSpread) "outside" else "INSIDE the noise"),
        )
        println(
            "OPEN-11 >>> and it costs %.0f%% more space".format(
                (covering.bytes.toDouble() / single.bytes - 1.0) * 100,
            ),
        )

        assertEquals(
            single.rows, covering.rows,
            "the two candidates fed different numbers of rows through concept_edge after a " +
                "vacuum, so they are not answering the same predicate and this is not a " +
                "comparison between two indexes. The second column buys heap avoidance, not " +
                "selectivity",
        )
        assertTrue(
            covering.fetches == 0,
            "the covering candidate reported ${covering.fetches} heap fetches after " +
                "`vacuum (analyze)`, so it is not index-only and the arm is measuring " +
                "something other than what OPEN-11 asks about",
        )
        assertTrue(
            covering.bytes > single.bytes,
            "the covering index is not larger than the single-column one, which cannot be " +
                "true of an index with an extra column and means the wrong relation was sized",
        )
    }

    private fun heapFetches(plan: String): Int =
        Regex("""Heap Fetches: (\d+)""").findAll(plan).sumOf { it.groupValues[1].toInt() }

    /**
     * **The control on the parser**, in the shape `R0` §9 asked for: not *is the instrument
     * alive*, but *is it reading the thing it claims to read*.
     *
     * The buffer figure is taken from the plan's **root node**, because PostgreSQL reports
     * `Buffers:` cumulatively — a child's count is already inside its parent's. The first
     * version of this class summed every `shared hit=` in the text and reported **2,392 for
     * a plan whose root says 405**, a 5.9× over-count that looked entirely plausible.
     *
     * That is `R5`'s `pg_stat_user_tables` delta again: **a cumulative counter read as if it
     * were an increment.** Same mistake, different counter, four months later, by an author
     * who had read the report about it.
     */
    @Test
    fun `the buffer figure is the root node's and not the sum of every node's`() {
        withoutIndex()
        val arm = single(6)

        val everyBufferLine = Regex("""shared hit=(\d+)""")
            .findAll(arm.plan).sumOf { it.groupValues[1].toInt() }

        assertTrue(
            everyBufferLine > arm.bufferHits,
            "summing every 'shared hit=' in the plan gave $everyBufferLine and the root " +
                "node reports ${arm.bufferHits}. If those are equal this plan has one node " +
                "with buffers, and this control is asserting nothing",
        )
        // What "cumulative" actually means, asserted rather than assumed: no node in the
        // tree can report more buffers than the root, because the root's figure already
        // contains every one of them. The planning line is excluded -- it is not a node.
        val nodeFigures = Regex("""shared hit=(\d+)""")
            .findAll(arm.plan.substringBefore("Planning:"))
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue(nodeFigures.size > 1, "only one node reports buffers; nothing to compare")
        assertEquals(
            arm.bufferHits, nodeFigures.max(),
            "a node deeper in the plan reports more buffers than the root. Either EXPLAIN " +
                "has stopped reporting Buffers cumulatively, or this parser is not reading " +
                "the root -- and the whole buffer column in R20 §3.4 depends on which",
        )
    }

    // -----------------------------------------------------------------------------------

    /**
     * The index **`V4` ships**, and not the covering variant it beat. The "with" arm has to
     * be the state a reader gets, or the comparison prices something nobody runs.
     */
    private fun withIndex() {
        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("create index $INDEX on concept_edge (concept_id)")
        jdbc.execute("analyze concept_edge")
    }

    private fun withoutIndex() {
        jdbc.execute("drop index if exists $INDEX")
        jdbc.execute("analyze concept_edge")
    }

    /** Three runs, median execution time. Rule 5. The plan of the median run is kept. */
    private fun median(depth: Int): ExplainOutput =
        (1..3).map { single(depth) }.sortedBy { it.executionMs }[1]

    private fun single(depth: Int): ExplainOutput = ExplainOutput(explain(depth))

    /**
     * `EXPLAIN (ANALYZE, BUFFERS)` of the shipped closure, verbatim.
     *
     * The SQL is written out here because Spring Data holds `PrerequisiteQueries.closure`'s
     * text as an annotation value that is not addressable at run time. **The two are held
     * together by the row counts**: this plan returns 202 concepts at depth 6 and so does
     * `PrerequisiteTraversalCountTest`, over the same fixture. A divergence moves one and
     * not the other.
     */
    private fun explain(depth: Int): String = jdbc.queryForList(
        """
        explain (analyze, buffers)
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1
              from concept_edge e
             where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e
              join walk w on e.concept_id = w.prerequisite_id
             where w.depth < $depth
        )
        select w.prerequisite_id, min(w.depth)
          from walk w
         group by w.prerequisite_id
         order by min(w.depth), w.prerequisite_id
        """.trimIndent(),
        String::class.java,
    ).joinToString("\n")

    private companion object {
        const val INDEX = "ix_concept_edge_concept"
        val DEPTHS = listOf(3, 6, 12)

        /**
         * Rows fed through `concept_edge`, at depths 3 / 6 / 12, measured 2026-08-21
         * against `postgres:16-alpine` (server 16.14) on the shipped graph.
         *
         * The unindexed figures are `3 + 8994 x (depth - 1)` exactly -- the whole table,
         * once per recursive iteration, plus the base term.
         */
        val ROWS_WITHOUT = listOf(17_991L, 44_973L, 98_937L)
        val ROWS_WITH = listOf(36L, 546L, 5_424L)
    }
}

/**
 * One `EXPLAIN (ANALYZE, BUFFERS)` output, parsed.
 *
 * Every field is read out of the plan text rather than measured separately, so there is no
 * way for the numbers reported to belong to a different execution than the plan printed
 * beside them.
 */
class ExplainOutput(val plan: String) {

    /**
     * The **root node's** buffer count, which is the whole plan's.
     *
     * PostgreSQL reports `Buffers:` cumulatively: a child's figure is already included in
     * its parent's, and the root is printed first. Summing them multiplies by the depth of
     * the plan tree — see `the buffer figure is the root node's` above.
     */
    val bufferHits: Int =
        Regex("""shared hit=(\d+)""").find(plan)?.groupValues?.get(1)?.toInt() ?: 0

    val executionMs: Double =
        Regex("""Execution Time: ([0-9.]+) ms""").find(plan)?.groupValues?.get(1)?.toDouble()
            ?: error("EXPLAIN produced no Execution Time; it was not run with ANALYZE")

    /** Which scan nodes the planner chose, in plan order. */
    val scans: List<String> =
        Regex("""(Seq Scan|Index Scan|Index Only Scan|Bitmap Heap Scan)""")
            .findAll(plan).map { it.value }.toList()

    /**
     * **Rows actually fed through `concept_edge`, across every execution of every node that
     * reads it** — `rows × loops`, summed.
     *
     * This is the number the index comparison turns on and it is categorical: a property of
     * the plan and the data, not of the machine. `loops` is what makes it the right metric
     * for a recursive CTE, because the recursive term is re-executed once per iteration and
     * a per-node `rows` reading hides that entirely.
     */
    val rowsThroughConceptEdge: Long = plan.lines()
        .filter { it.contains("on concept_edge") && it.contains("actual time=") }
        .sumOf { line ->
            val m = Regex("""rows=(\d+) loops=(\d+)""").find(line)
                ?: error("a concept_edge scan node carried no rows/loops:\n$line")
            m.groupValues[1].toLong() * m.groupValues[2].toLong()
        }
}
