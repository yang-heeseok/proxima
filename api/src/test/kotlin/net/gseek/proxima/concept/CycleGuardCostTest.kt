package net.gseek.proxima.concept

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * **The two arms of `ADR-010`: refuse a cycle on write, or survive one on read.**
 *
 * `R21` measured what a cycle does to four forms of the same read. It does not answer the
 * question that follows, which is not *how do we stop it* but **where**. Both places cost
 * something, and a decision made with one of the two priced is not a decision.
 *
 * | arm | what it costs | who pays |
 * | --- | --- | --- |
 * | refuse on write | a recursive reachability query per inserted edge | whoever edits the curriculum |
 * | survive on read | a bound, a guard, or a lost depth column | every request, for ever |
 *
 * And there is a third thing, which is what actually decides it: **the write-side guard is
 * not race-safe, and no constraint can make it so.** `R7` measured two concurrent requests
 * both passing an application-level existence check; `V3` closed that with a unique
 * constraint, because uniqueness is a property of a row. **Acyclicity is a property of the
 * whole graph**, which is exactly what `V1`'s table comment says a `CHECK` cannot express —
 * and it is equally what a trigger cannot express under concurrency.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CycleGuardCostTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var postgres: PostgreSQLContainer

    private lateinit var ids: LongArray
    private var top = 0L

    @BeforeAll
    fun install() {
        ids = SeedConceptGraph.install(jdbc)
        top = ids[SeedConceptGraph.CONCEPTS]
        jdbc.execute("analyze concept_edge")
    }

    @AfterAll
    fun uninstall() {
        dropGuard()
        SeedConceptGraph.remove(jdbc)
    }

    // -----------------------------------------------------------------------------------
    // Arm A -- refuse on write
    // -----------------------------------------------------------------------------------

    /**
     * **What the write-side guard costs an insert.**
     *
     * One recursive reachability query per row, on top of the insert. Measured as the
     * elapsed time for 1,000 valid edges with the trigger and without it, three runs each,
     * median — and as a **plan**, which is the half that survives being moved to another
     * machine.
     *
     * The edges inserted are real forward edges the graph does not already hold: concept `c`
     * gains `c - 61` as a prerequisite, which is one past the 60-wide window the generator
     * draws from, so `uk_concept_edge` cannot refuse it and no cycle can result.
     */
    @Test
    fun `the write-side guard costs one recursive query per inserted edge`() {
        val without = (1..3).map { timeInserts(guarded = false) }.sorted()[1]
        val with = (1..3).map { timeInserts(guarded = true) }.sorted()[1]

        println("insert $INSERTS edges, median of 3")
        println("  no guard : $without ms")
        println("  guarded  : $with ms   (${"%.1f".format(with.toDouble() / without)}x)")

        installGuard()
        val plan = explainGuardCheck(ids[2_500], ids[2_900])
        dropGuard()
        println("the guard's own check, planned:")
        println(plan)

        assertTrue(
            with > without,
            "the guarded inserts ($with ms) were not slower than the unguarded ones " +
                "($without ms). A recursive query per row is not free, and if it measures " +
                "as free then the trigger is not firing",
        )
        assertTrue(
            plan.contains("Recursive Union"),
            "the guard's check was not planned as a recursive union, so it is not walking " +
                "the graph and it is not the check this arm is about:\n$plan",
        )
    }

    /**
     * **And the bulk path pays it too**, which is the cost that decides whether this ships.
     *
     * Nothing in this application inserts a `concept_edge`. The only writer is `seed/`'s
     * loader, and a `BEFORE INSERT ... FOR EACH ROW` trigger fires per row however the rows
     * arrive — so a guard added for a write path that does not exist would be charged to the
     * one that does. Measured here as a single set-based `insert ... select`, which is the
     * closest shape to a bulk load this module can reach: the PostgreSQL driver is
     * `runtimeOnly` in `api/build.gradle.kts`, so `CopyManager` is not on the test compile
     * classpath and **the `COPY` figure itself is 미측정.**
     */
    @Test
    fun `a set-based insert pays the guard once per row, not once per statement`() {
        clearAddedEdges()
        val setBased = timeSetBasedInsert(guarded = true) - timeSetBasedInsert(guarded = false)
        val batched = timeInserts(guarded = true) - timeInserts(guarded = false)
        clearAddedEdges()

        val perRowSet = setBased.toDouble() / INSERTS
        val perRowBatch = batched.toDouble() / INSERTS
        println("added by the guard, $INSERTS edges")
        println("  one `insert ... select` : $setBased ms  (${"%.2f".format(perRowSet)} ms/row)")
        println("  1,000 batched inserts   : $batched ms  (${"%.2f".format(perRowBatch)} ms/row)")

        // THE RATIO IS THE WRONG NUMBER HERE and this is where that becomes visible. The
        // set-based arm's unguarded baseline is inflated by its own `right(code,6)::int`
        // join, so it reports about 2x while the batched arm reports about 50x -- for the
        // same trigger doing the same work. The per-row DELTA is the figure that is a
        // property of the guard rather than of whatever it was added to.
        assertTrue(
            perRowSet > 0.5 * perRowBatch && perRowSet < 2.0 * perRowBatch,
            "the guard added ${"%.2f".format(perRowSet)} ms/row to a set-based insert and " +
                "${"%.2f".format(perRowBatch)} ms/row to a batch of single inserts. A row " +
                "trigger fires once per row whatever the statement shape, so these must be " +
                "the same number; if they are not, one of the two arms is not running it",
        )
    }

    /**
     * The guard **does** refuse a cycle when one transaction adds it alone. Quoted verbatim,
     * because a guard that refuses with an unreadable message is a guard people disable.
     */
    @Test
    fun `the write-side guard refuses an edge that would close a cycle`() {
        installGuard()
        try {
            // ordinal 2889 is reachable from 3000 -- it is two hops down the prerequisite
            // chain -- so declaring 3000 a prerequisite OF 2889 closes a cycle.
            val e = assertThrows { insertEdge(jdbc, ids[SeedConceptGraph.CONCEPTS], ids[2_889]) }
            println("refused: ${e.sqlState} -- ${e.message?.trim()}")

            assertEquals("23514", e.sqlState, "the guard raised the wrong SQLSTATE")
            assertTrue(
                e.message.orEmpty().contains("cycle"),
                "the refusal does not say what it refused: ${e.message}",
            )
        } finally {
            dropGuard()
        }
    }

    /**
     * **And two transactions add it together, and it passes.**
     *
     * Each edge is checked against the graph **as that transaction can see it**, and neither
     * transaction can see the other's uncommitted row. Two edges that are individually fine
     * are jointly a cycle, both commit, and the guard reports nothing.
     *
     * This is `R7`'s defect with one difference that decides `ADR-010`. There, the fix was a
     * unique constraint, because **uniqueness is a property of a row** and the database can
     * hold it. Acyclicity is a property of the whole graph. `V1`'s own table comment says a
     * `CHECK` cannot express it; this measures that a trigger cannot either, and the only
     * things that could — `SERIALIZABLE`, or a table-level lock on every edge insert — are
     * not free and are not what a trigger gives you.
     */
    @Test
    fun `two concurrent inserts each pass the guard and together make a cycle`() {
        installGuard()
        val a = freshConcept("cyc-a")
        val b = freshConcept("cyc-b")
        try {
            val one = connect()
            val two = connect()
            try {
                one.autoCommit = false
                two.autoCommit = false

                // Each transaction checks a graph in which the other's edge does not exist.
                insertEdge(one, a, b)
                insertEdge(two, b, a)

                one.commit()
                two.commit()
            } finally {
                one.close(); two.close()
            }

            val bothPresent = jdbc.queryForObject(
                "select count(*) from concept_edge where (prerequisite_id = ? and concept_id = ?) " +
                    "or (prerequisite_id = ? and concept_id = ?)",
                Int::class.java, a, b, b, a,
            )
            assertEquals(
                2, bothPresent,
                "only $bothPresent of the two edges landed. The race did not happen and " +
                    "this test is asserting nothing -- check that both transactions really " +
                    "ran concurrently rather than one after the other",
            )

            // AND HERE IS THE SHARPEST WAY TO SAY IT. Re-issue the statement the guard
            // accepted thirty milliseconds ago. It is now refused -- by the guard, 23514,
            // not by uk_concept_edge -- because the OTHER transaction's edge has since
            // become visible and the reachability check now finds the cycle.
            //
            // The same statement, accepted and then refused, with nothing in between except
            // somebody else committing. That is the whole of what a per-row check can and
            // cannot do about a whole-graph property, in one observation.
            val again = try {
                insertEdge(jdbc, a, b); "accepted"
            } catch (e: SQLException) {
                "${e.sqlState} -- ${e.message?.trim()}"
            }
            println("cycle present: yes. re-issuing the accepted statement now gives: $again")
            assertTrue(
                again.startsWith("23514"),
                "the re-issued statement gave '$again'. It is expected to be refused by the " +
                    "guard, which would have refused it the first time too if the other " +
                    "transaction had committed a moment earlier",
            )
        } finally {
            dropGuard()
            jdbc.update("delete from concept_edge where prerequisite_id in (?, ?) or concept_id in (?, ?)", a, b, a, b)
            jdbc.update("delete from concept where id in (?, ?)", a, b)
        }
    }

    // -----------------------------------------------------------------------------------
    // Arm B -- survive on read
    // -----------------------------------------------------------------------------------

    /**
     * **What the read-side defence costs when there is nothing to defend against.**
     *
     * The write arm's cost falls on whoever edits a curriculum, which happens rarely. The
     * read arm's cost falls on every request, on a graph that is acyclic today and will be
     * acyclic on almost every request for ever. So the number that matters is not what the
     * guard costs on a cyclic graph — it is what it costs on the graph that actually ships.
     *
     * Three arms of the same read, on the shipped acyclic graph, median of three.
     *
     * **And the path array turns out not to be free at all, for a reason that has nothing to
     * do with cycles.** `union` deduplicates whole rows. Adding a `path` column to the
     * recursive term makes every row unique — every walk has its own path — so the
     * deduplication stops happening and the query degenerates into `union all`. At depth 6
     * that is 1,092 rows instead of 343.
     *
     * This is the **third** time the same mechanism appears in this slice. `R21` §3.2 found
     * a `depth` column doing it, and here a `path` array does it, and a path array is
     * precisely what every reference recommends as the cycle guard. **Following the standard
     * advice silently removes the deduplication you already had**, on a graph that has no
     * cycle in it, and nothing warns.
     */
    @Test
    fun `what a read-side cycle defence costs on a graph that has no cycle`() {
        println("depth  arm                          rows      exec ms (median of 3)")
        listOf(6, 12).forEach { depth ->
            val plain = medianOf(shippedClosure(depth))
            val guarded = medianOf(pathGuardedClosure(depth))
            val distinct = medianOf(distinctOutsideClosure(depth))
            println("%5d  union + depth          %8d      %.3f".format(depth, plain.rows, plain.ms))
            println("%5d  union + depth + path   %8d      %.3f".format(depth, guarded.rows, guarded.ms))
            println("%5d  the same, distinct out %8d      %.3f".format(depth, distinct.rows, distinct.ms))

            assertTrue(
                guarded.rows > 2 * plain.rows,
                "at depth $depth the path-guarded form carried ${guarded.rows} rows and the " +
                    "plain one ${plain.rows}, on a graph with no cycle in it. The path " +
                    "column is supposed to defeat `union`'s row deduplication entirely; if " +
                    "it has stopped doing so, R21 §3.5 is describing something that no " +
                    "longer happens",
            )
            assertTrue(
                distinct.rows < plain.rows,
                "the outer `distinct` returned ${distinct.rows} rows and the working table " +
                    "carried ${plain.rows}. One row per concept must be the smaller number",
            )
        }
    }

    // -----------------------------------------------------------------------------------

    private fun installGuard() {
        jdbc.execute(GUARD_FUNCTION)
        jdbc.execute(
            "create trigger tg_concept_edge_no_cycle before insert on concept_edge " +
                "for each row execute function refuse_concept_edge_cycle()",
        )
    }

    private fun dropGuard() {
        jdbc.execute("drop trigger if exists tg_concept_edge_no_cycle on concept_edge")
        jdbc.execute("drop function if exists refuse_concept_edge_cycle()")
    }

    /** Inserts [INSERTS] fresh forward edges and returns the elapsed milliseconds. */
    private fun timeInserts(guarded: Boolean): Long {
        clearAddedEdges()
        if (guarded) installGuard()
        val started = System.nanoTime()
        jdbc.batchUpdate(
            "insert into concept_edge (prerequisite_id, concept_id, weight) values (?, ?, 1.000)",
            (0 until INSERTS).map { i ->
                val c = FIRST_ADDED + i
                arrayOf<Any>(ids[c - 61], ids[c])
            },
        )
        val ms = (System.nanoTime() - started) / 1_000_000
        if (guarded) dropGuard()
        clearAddedEdges()
        return ms
    }

    /** The same edges as [timeInserts], in one statement instead of a thousand. */
    private fun timeSetBasedInsert(guarded: Boolean): Long {
        clearAddedEdges()
        if (guarded) installGuard()
        val started = System.nanoTime()
        jdbc.update(
            """
            insert into concept_edge (prerequisite_id, concept_id, weight)
            select p.id, c.id, 1.000
              from concept c
              join concept p on right(p.code, 6)::int = right(c.code, 6)::int - 61
             where c.code like '${SeedConceptGraph.CODE_PREFIX}%'
               and p.code like '${SeedConceptGraph.CODE_PREFIX}%'
               and c.code ~ '^gcpt-[0-9]{6}$'
               and p.code ~ '^gcpt-[0-9]{6}$'
               and right(c.code, 6)::int between $FIRST_ADDED and ${FIRST_ADDED + INSERTS - 1}
            """.trimIndent(),
        )
        val ms = (System.nanoTime() - started) / 1_000_000
        if (guarded) dropGuard()
        return ms
    }

    /**
     * Removes only the edges [timeInserts] adds. Identified by their distance rather than by
     * a marker column: the generator never draws a prerequisite more than 60 below its
     * concept, so a gap of exactly 61 belongs to this test and to nothing else.
     */
    private fun clearAddedEdges() {
        jdbc.update(
            """
            delete from concept_edge e
             using concept p, concept c
             where p.id = e.prerequisite_id and c.id = e.concept_id
               and p.code like '${SeedConceptGraph.CODE_PREFIX}%'
               and c.code like '${SeedConceptGraph.CODE_PREFIX}%'
               and c.code ~ '^gcpt-[0-9]{6}$'
               and p.code ~ '^gcpt-[0-9]{6}$'
               and (right(c.code, 6)::int - right(p.code, 6)::int) = 61
            """.trimIndent(),
        )
    }

    /**
     * The guard's check, planned. The literal is **cast**, and that cast is the copy's tax:
     * in the trigger the base term is `new.prerequisite_id`, a `bigint` column, and here it
     * is an integer literal — so PostgreSQL refuses the recursive query outright with
     * *"recursive query column 1 has type integer in non-recursive term but bigint
     * overall"*. `MigrationDeduplicationTest`'s rule, met from a direction that at least
     * fails loudly: a copy of a statement is not the statement.
     */
    private fun explainGuardCheck(from: Long, to: Long): String = jdbc.queryForList(
        """
        explain
        with recursive up (concept_id) as (
            select ${from}::bigint
            union
            select e.prerequisite_id from concept_edge e join up u on e.concept_id = u.concept_id
        )
        select 1 from up where concept_id = $to
        """.trimIndent(),
        String::class.java,
    ).joinToString("\n")

    private fun freshConcept(suffix: String): Long = jdbc.queryForObject(
        "insert into concept (code, name, grade_band) values (?, ?, 'G5-6') returning id",
        Long::class.java, "${SeedConceptGraph.CODE_PREFIX}$suffix", "Concept $suffix",
    )!!

    private fun insertEdge(jdbc: JdbcTemplate, prerequisite: Long, concept: Long) {
        try {
            jdbc.update(
                "insert into concept_edge (prerequisite_id, concept_id, weight) values (?, ?, 1.000)",
                prerequisite, concept,
            )
        } catch (e: org.springframework.dao.DataAccessException) {
            throw e.mostSpecificCause as? SQLException ?: e
        }
    }

    private fun insertEdge(c: Connection, prerequisite: Long, concept: Long) {
        c.prepareStatement(
            "insert into concept_edge (prerequisite_id, concept_id, weight) values (?, ?, 1.000)",
        ).use { s ->
            s.setLong(1, prerequisite); s.setLong(2, concept); s.executeUpdate()
        }
    }

    private fun assertThrows(block: () -> Unit): SQLException = try {
        block()
        error("expected the guard to refuse this edge and it did not")
    } catch (e: SQLException) {
        e
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private class Timed(val rows: Int, val ms: Double)

    private fun medianOf(sql: String): Timed =
        (1..3).map { run(sql) }.sortedBy { it.ms }[1]

    private fun run(sql: String): Timed = connect().use { c ->
        val started = System.nanoTime()
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                var n = 0
                while (rs.next()) n++
                Timed(n, (System.nanoTime() - started) / 1_000_000.0)
            }
        }
    }

    private fun shippedClosure(depth: Int) = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1 from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
             where w.depth < $depth
        )
        select prerequisite_id, depth from walk
    """.trimIndent()

    private fun pathGuardedClosure(depth: Int) = """
        with recursive walk (prerequisite_id, depth, path) as (
            select e.prerequisite_id, 1, array[e.concept_id, e.prerequisite_id]
              from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1, w.path || e.prerequisite_id
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
             where w.depth < $depth and not (e.prerequisite_id = any(w.path))
        )
        select prerequisite_id, depth from walk
    """.trimIndent()

    /**
     * The bounded read with the deduplication moved OUTSIDE the recursion.
     *
     * Not a cycle defence and not offered as one -- it is here so the table has a row
     * saying what the answer actually is, against two rows saying what carrying it costs.
     * A true node-only recursion cannot have a depth bound, because a bound needs a depth
     * column and a depth column is what defeats `union`. That is not a limitation of this
     * test; it is the reason `ADR-011` exists.
     */
    private fun distinctOutsideClosure(depth: Int) = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1 from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
             where w.depth < $depth
        )
        select distinct prerequisite_id from walk
    """.trimIndent()

    private companion object {
        const val INSERTS = 1_000

        /** The first concept ordinal that has a `c - 61` to point at, plus room for INSERTS. */
        const val FIRST_ADDED = 1_500

        /**
         * The write-side guard, as a `BEFORE INSERT` trigger.
         *
         * `union` and **not** `union all`, and the node alone is projected — which is `R21`
         * §3.2's finding applied: a guard that hangs on data that is already cyclic is worse
         * than no guard, because it takes the write path down while diagnosing it.
         *
         * `23514` is `check_violation`, chosen so a caller that already handles a `CHECK`
         * failure handles this identically. It is not a `CHECK`; it is the thing `V1`'s table
         * comment says a `CHECK` cannot be.
         */
        val GUARD_FUNCTION = """
            create or replace function refuse_concept_edge_cycle() returns trigger as ${'$'}${'$'}
            begin
                if exists (
                    with recursive up (concept_id) as (
                        select new.prerequisite_id
                        union
                        select e.prerequisite_id
                          from concept_edge e join up u on e.concept_id = u.concept_id
                    )
                    select 1 from up where concept_id = new.concept_id
                ) then
                    raise exception 'concept_edge % -> % would close a prerequisite cycle',
                        new.prerequisite_id, new.concept_id using errcode = '23514';
                end if;
                return new;
            end;
            ${'$'}${'$'} language plpgsql
        """.trimIndent()
    }
}
