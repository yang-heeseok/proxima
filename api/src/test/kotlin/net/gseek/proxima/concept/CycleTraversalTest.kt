package net.gseek.proxima.concept

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.perf.StatementCounter
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
 * **Three ways a cycle kills a prerequisite traversal, and they are not the same way.**
 *
 * `V1__baseline.sql` records, in a table comment, that acyclicity cannot be a `CHECK`
 * constraint and is asserted by a test instead. The test it means asserts that **the
 * generator** produces no cycle. Nothing asserts what happens when something else does, and
 * `concept_edge` will accept a backwards edge without complaint: `uk_concept_edge` sees a
 * pair no forward edge produces, and `ck_concept_edge_no_self` is satisfied.
 *
 * So this runs the same graph twice — once as shipped, once with three cycles injected by
 * `Generator`'s opt-in parameter — through four forms of the same read, and records what
 * each one does.
 *
 * ## Why the SQL arms run on their own connection
 *
 * Two of them do not terminate, so they need `statement_timeout`. Setting that through the
 * pooled `JdbcTemplate` would leave it on a **pooled connection**, where it outlives this
 * class and lands on whichever test draws that connection next. A `DriverManager`
 * connection to the same container is thrown away when this class ends.
 *
 * ## What is quoted and what is asserted
 *
 * The SQLSTATE and the server's own message are quoted verbatim — `measurement-discipline`
 * rule 4, *a summarised log line is an opinion*. The timeout **value** is not a measurement
 * and nothing is concluded from how long anything took; the finding is which arm reaches an
 * answer at all, and that is categorical.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, StatementCounter::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CycleTraversalTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var graph: PrerequisiteGraph
    @Autowired private lateinit var counter: StatementCounter
    @Autowired private lateinit var postgres: PostgreSQLContainer

    private var top = 0L

    /**
     * A start from which a walk can **enter a cycle and leave it again**.
     *
     * The injected back-edges are `(3000 -> 2889)`, `(2250 -> 2121)` and `(1500 -> 1327)` in
     * ordinal terms — read as *prerequisite -> concept*, so standing on concept 2121 offers
     * 2250 as a prerequisite even though 2250 is above it.
     *
     * A path array blocks a revisit **within one walk**, and the base term seeds the path
     * with the starting concept. So starting *at* a cycle's head — which 3000 and 2250 both
     * are — the back-edge is refused on first sight and the cyclic graph produces exactly
     * the rows the acyclic one does. That is not the path guard being free; it is the
     * measurement standing in the one place where the cycle cannot be entered.
     *
     * This start is chosen at run time as a concept holding 2121 as a **direct** prerequisite,
     * so the walk meets the back-edge at depth 2 with 2250 not yet in its path.
     */
    private var intoCycle = 0L

    @BeforeAll
    fun install() {
        println(SeedConceptGraph.assertMatchesTheShippedGraph())
        println(SeedConceptGraph.assertCyclesWereInjected(BACK_EDGES))
        val ids = SeedConceptGraph.install(jdbc, backEdges = BACK_EDGES)
        top = ids[SeedConceptGraph.CONCEPTS]
        val cycleTail = ids[2_121]
        intoCycle = jdbc.queryForObject(
            "select min(concept_id) from concept_edge where prerequisite_id = ? and concept_id > ?",
            Long::class.java, cycleTail, cycleTail,
        ) ?: error(
            "no concept holds the cycle's tail as a direct prerequisite, so no walk can " +
                "enter the cycle at a node whose exit is not already in its path",
        )
        jdbc.execute("analyze concept_edge")
    }

    @AfterAll
    fun uninstall() = SeedConceptGraph.remove(jdbc)

    /**
     * **Death 1 — `union all`, and the symptom that does not name the cause.**
     *
     * An unbounded `union all` recursion over a cyclic graph cannot terminate: there is
     * always another walk. It is cancelled by `statement_timeout` with SQLSTATE `57014`.
     *
     * **The same query dies the same way on the acyclic graph**, because a DAG whose
     * concepts share ancestors has 7,174,452 walks at depth 14 and a longest chain of 294.
     * Finite is not the same as reachable. So an operator holding a `57014` from this query
     * learns that the recursion did not finish and **nothing whatever about whether the data
     * has a cycle in it** — which is the diagnosis they will spend the outage looking for.
     */
    @Test
    fun `union all does not terminate, and would not terminate without a cycle either`() {
        val cyclic = runWithTimeout(UNBOUNDED_UNION_ALL)
        val acyclic = onAcyclicCopy { runWithTimeout(UNBOUNDED_UNION_ALL) }

        println("union all, unbounded, cyclic  : $cyclic")
        println("union all, unbounded, acyclic : $acyclic")

        assertEquals("57014", cyclic.sqlState, "expected a statement_timeout on the cyclic graph")
        assertEquals(
            "57014", acyclic.sqlState,
            "the SAME query completed on the acyclic graph. Then 57014 DOES distinguish a " +
                "cycle from a DAG, and R21 §3.1's whole point is wrong",
        )
        assertTrue(
            cyclic.message.contains("statement timeout"),
            "the server's message was '${cyclic.message}', which is not a timeout",
        )
    }

    /**
     * **Death 2 — `union`, which people reach for *because* it is supposed to stop cycles,
     * and the depth column that turns it off.**
     *
     * PostgreSQL's recursive `union` discards a row duplicating one already produced, and
     * that is the standard cycle defence. It defends **rows**, not nodes. A row carrying
     * `depth` is never a duplicate of the same node at a different depth, so on a cycle the
     * depth counter climbs forever and every row is new.
     *
     * Project the node alone and the identical query terminates. **The column that makes the
     * result useful is the column that removes the protection**, and nothing warns.
     */
    @Test
    fun `union protects against a cycle only while nothing that changes each round is projected`() {
        val withDepth = runWithTimeout(UNBOUNDED_UNION_WITH_DEPTH)
        val nodeOnly = runWithTimeout(UNBOUNDED_UNION_NODE_ONLY)
        val withDepthAcyclic = onAcyclicCopy { runWithTimeout(UNBOUNDED_UNION_WITH_DEPTH) }

        println("union + depth, cyclic  : $withDepth")
        println("union, node only, cyclic: $nodeOnly")
        println("union + depth, acyclic  : $withDepthAcyclic")

        assertEquals(
            "57014", withDepth.sqlState,
            "`union` with a depth column terminated on a cyclic graph. If PostgreSQL has " +
                "started deduplicating on something other than the whole row, this finding " +
                "has expired",
        )
        assertEquals(
            null, nodeOnly.sqlState,
            "`union` projecting only the node did NOT terminate on a cyclic graph. That is " +
                "the arm that is supposed to be safe, and it is the only cycle defence in " +
                "this test that costs nothing",
        )
        assertEquals(
            null, withDepthAcyclic.sqlState,
            "`union` with a depth column did not terminate on the ACYCLIC graph either, so " +
                "the cycle is not what killed it and §3.2 is attributing the death wrongly",
        )
        assertEquals(
            2_790, nodeOnly.rows,
            "the node-only recursion reached ${nodeOnly.rows} concepts of the graph's 3,000",
        )
        assertEquals(
            303_948, withDepthAcyclic.rows,
            "and this is what the SAFE arm costs when it is safe: ${withDepthAcyclic.rows} " +
                "rows to describe 3,000 concepts, because `union` deduplicates whole rows " +
                "and the shipped graph's longest chain is 294 edges long. The arm that " +
                "survives is not the arm that is cheap",
        )
    }

    /**
     * **Where you stand decides whether the path guard costs anything**, and the obvious
     * place to stand is the one place it costs nothing.
     *
     * A path array carries the start concept from the base term onward —
     * `array[e.concept_id, e.prerequisite_id]`. A cycle whose head **is** the start is
     * therefore refused on first sight, and the recursion over the cyclic graph produces
     * **exactly** the rows it produces over the acyclic one.
     *
     * Start one edge above the cycle's tail instead and the back-edge is a live branch: the
     * walk climbs back to a concept it has not personally visited and descends through the
     * whole region again at a different depth.
     *
     * **This is the control this class most needed.** The first version of it started at the
     * top of the graph, saw 1,092 rows on the cyclic graph and 1,092 on the acyclic one, and
     * would have published *the path guard costs nothing* — a true sentence about the one
     * starting concept where the guard has nothing to do.
     */
    @Test
    fun `the path guard's cost depends on where the walk enters the cycle`() {
        val fromTop = runWithTimeout(pathGuarded(6, top))
        val fromEntry = runWithTimeout(pathGuarded(6, intoCycle))
        val fromEntryAcyclic = onAcyclicCopy { runWithTimeout(pathGuarded(6, intoCycle)) }

        println("path-guarded depth 6, from the cycle's head : $fromTop")
        println("path-guarded depth 6, from above its tail   : $fromEntry")
        println("path-guarded depth 6, same start, no cycles : $fromEntryAcyclic")

        assertEquals(
            1_092, fromTop.rows,
            "from the cycle's own head the guarded recursion carried ${fromTop.rows} rows, " +
                "and PrerequisiteDepthTest computes 1,092 walks at depth 6 on the acyclic " +
                "graph. If those have stopped being equal, the start concept is no longer " +
                "seeded into the path array",
        )
        assertTrue(
            fromEntry.rows > fromEntryAcyclic.rows,
            "from a concept holding the cycle's tail as a direct prerequisite, the cyclic " +
                "graph carried ${fromEntry.rows} rows and the acyclic one " +
                "${fromEntryAcyclic.rows}. Equal means the walk never entered the cycle and " +
                "this test is measuring nothing",
        )
    }

    /**
     * **Death 3 — the application-side walk, which the database never reports at all.**
     *
     * No statement is slow. No statement times out. `statement_timeout` cannot fire, because
     * every individual query is a two-row index lookup. The loop simply does not end, inside
     * a `@Transactional(readOnly = true)` method, **holding one pooled connection for the
     * whole of it.**
     *
     * That is `T1`'s mechanism arriving from the other direction: `R2` measured a request
     * holding a connection while it slept, and this is a request holding one while it works.
     * The database is idle and the pool drains, which is the shape `R2` §1 had to correct
     * once already.
     *
     * The `seen` set is what removes this, and the walk that has one is the **only** arm in
     * this class that is safe on a cyclic graph without giving anything up. `R21` §5 is
     * about how uncomfortable that is next to `R20`'s conclusion.
     */
    @Test
    fun `an application walk without a seen set never returns, and nothing in the database notices`() {
        val unguarded = counter.count {
            graph.closureByUnguardedWalk(top, maxDepth = 40, statementCap = 4_000)
        }
        val guarded = counter.count { graph.closureByNodeWalk(top, maxDepth = 40) }

        println("unguarded: statements=${unguarded.result.statements} hitCap=${unguarded.result.hitCap} visits=${unguarded.result.visits}")
        println("guarded  : concepts=${guarded.result.size} statements=${guarded.statements}")

        assertTrue(
            unguarded.result.hitCap,
            "the unguarded walk finished on its own, in ${unguarded.result.statements} " +
                "statements. It is not supposed to be able to: the graph has a cycle and " +
                "the walk has no memory",
        )
        assertEquals(
            4_000, unguarded.statements,
            "the cap is on statements, so the counter must agree with it exactly. A " +
                "difference means the walk is issuing statements the cap does not see",
        )
        assertTrue(
            guarded.result.isNotEmpty() && guarded.statements < 4_000,
            "the guarded walk returned ${guarded.result.size} concepts in " +
                "${guarded.statements} statements; it must terminate well inside the cap",
        )
    }

    /**
     * **The fourth arm, which fixes the cycle and does not fix the cost.**
     *
     * A path array — `not (x = any(path))` — is the textbook cycle guard, and it works: the
     * recursion cannot revisit a concept **along one walk**. It has nothing to say about the
     * same concept being reached along a thousand different walks, which is the shape a DAG
     * with shared ancestors has anyway.
     *
     * So it is the arm that turns a cycle problem into a `R20` problem, and `R20` already
     * measured what that costs: 7,174,452 walks for 606 concepts at depth 14.
     */
    @Test
    fun `a path array stops the cycle and leaves the walk explosion untouched`() {
        val guardedUnbounded = runWithTimeout(UNBOUNDED_UNION_ALL_PATH_GUARDED)
        val guardedBounded = runWithTimeout(pathGuarded(6, top))

        println("path-guarded, unbounded, cyclic: $guardedUnbounded")
        println("path-guarded, depth 6,   cyclic: $guardedBounded")

        assertEquals(
            "57014", guardedUnbounded.sqlState,
            "the path-guarded recursion terminated unbounded. On a graph with a longest " +
                "chain of 294 and three prerequisites per concept the number of SIMPLE " +
                "paths is not reachable either, and §3.4 says the guard is not a cost fix",
        )
        assertEquals(
            null, guardedBounded.sqlState,
            "the path-guarded recursion did not finish even at depth 6",
        )
        assertEquals(
            1_092, guardedBounded.rows,
            "the path-guarded recursion carried ${guardedBounded.rows} rows to depth 6 on " +
                "the CYCLIC graph, and PrerequisiteDepthTest computes 1,092 walks at depth " +
                "6 on the acyclic one. Equal is the finding: the guard bought termination " +
                "and not one row of saving. See the test below for why they are equal here " +
                "and are not equal from any other starting concept",
        )
    }

    /**
     * **The green side, and the uncomfortable reason it is green.**
     *
     * `PrerequisiteQueries.closure` — the read this slice ships — returns on the cyclic
     * graph, in one statement, with the right concepts in it. Every other arm above either
     * hangs or gives something up.
     *
     * **It is not `union` that saves it.** `R21` §3.2 measured the identical query without
     * the bound and it did not terminate. What saves it is `where w.depth < :maxDepth`, a
     * clause written for `R20`'s reasons — the closure of a 294-edge chain is not a thing
     * anybody wants — with no cycle in mind at all.
     *
     * So the shipped read is cycle-safe **by accident of an unrelated bound**, and the moment
     * somebody raises `maxDepth` to 294 to get the full closure, it stops being. That is not
     * a property to leave undocumented, and `ADR-011` is where it stops being an accident.
     */
    @Test
    fun `the shipped read returns on a cyclic graph, and the depth bound is the only reason`() {
        val bounded = counter.count { graph.closure(top, maxDepth = 12) }
        val unbounded = runWithTimeout(UNBOUNDED_UNION_WITH_DEPTH)

        println("closure(top, 12) on the cyclic graph: ${bounded.result.size} concepts in ${bounded.statements} statement")
        println("the same recursion unbounded        : $unbounded")

        assertEquals(1, bounded.statements, "the shipped read is one statement, cycle or not")
        assertTrue(
            bounded.result.isNotEmpty(),
            "the shipped read returned nothing on the cyclic graph, so it did not survive " +
                "it -- it merely failed quietly, which is worse",
        )
        assertEquals(
            "57014", unbounded.sqlState,
            "the same query without the depth bound completed on a cyclic graph, which " +
                "would mean `union` is what makes the shipped read safe. It is not, and the " +
                "difference decides ADR-011",
        )
    }

    // -----------------------------------------------------------------------------------

    /**
     * Runs [sql] on a private connection with `statement_timeout` set, and reports either
     * the row count or the server's own refusal. Never throws for a timeout — a timeout is
     * a result here, not a failure.
     */
    private fun runWithTimeout(sql: String): ArmResult = connect().use { c ->
        c.createStatement().use { s -> s.execute("set statement_timeout = '${TIMEOUT_MS}ms'") }
        try {
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    var n = 0
                    while (rs.next()) n++
                    ArmResult(rows = n, sqlState = null, message = "completed")
                }
            }
        } catch (e: SQLException) {
            ArmResult(rows = -1, sqlState = e.sqlState, message = e.message.orEmpty().trim())
        }
    }

    /**
     * Runs [block] with the injected back-edges removed and puts them back afterwards.
     *
     * The control that makes every "the cycle killed it" claim above falsifiable: the same
     * statement, the same connection settings, the same data minus three rows.
     */
    private fun <T> onAcyclicCopy(block: () -> T): T {
        val removed = jdbc.queryForList(
            """
            select prerequisite_id, concept_id, weight from concept_edge
             where prerequisite_id > concept_id
               and concept_id in (select id from concept where code like '${SeedConceptGraph.CODE_PREFIX}%')
            """.trimIndent(),
        )
        check(removed.size == BACK_EDGES) {
            "expected $BACK_EDGES backwards edges in the database, found ${removed.size}"
        }
        jdbc.update(
            """
            delete from concept_edge
             where prerequisite_id > concept_id
               and concept_id in (select id from concept where code like '${SeedConceptGraph.CODE_PREFIX}%')
            """.trimIndent(),
        )
        try {
            return block()
        } finally {
            removed.forEach { row ->
                jdbc.update(
                    "insert into concept_edge (prerequisite_id, concept_id, weight) values (?, ?, ?)",
                    row["prerequisite_id"], row["concept_id"], row["weight"],
                )
            }
        }
    }

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private val UNBOUNDED_UNION_ALL get() = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1 from concept_edge e where e.concept_id = $top
            union all
            select e.prerequisite_id, w.depth + 1
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
        )
        select prerequisite_id, depth from walk
    """.trimIndent()

    private val UNBOUNDED_UNION_WITH_DEPTH get() = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1 from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
        )
        select prerequisite_id, depth from walk
    """.trimIndent()

    private val UNBOUNDED_UNION_NODE_ONLY get() = """
        with recursive walk (prerequisite_id) as (
            select e.prerequisite_id from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
        )
        select prerequisite_id from walk
    """.trimIndent()

    private val UNBOUNDED_UNION_ALL_PATH_GUARDED get() = pathGuarded(null, top)

    private fun pathGuarded(depth: Int?, start: Long) = """
        with recursive walk (prerequisite_id, depth, path) as (
            select e.prerequisite_id, 1, array[e.concept_id, e.prerequisite_id]
              from concept_edge e where e.concept_id = $start
            union all
            select e.prerequisite_id, w.depth + 1, w.path || e.prerequisite_id
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
             where not (e.prerequisite_id = any(w.path))
               ${if (depth == null) "" else "and w.depth < $depth"}
        )
        select prerequisite_id, depth from walk
    """.trimIndent()

    private companion object {
        const val BACK_EDGES = 3

        /**
         * Long enough that nothing is cancelled for being merely slow — every arm that does
         * terminate here does so in single-digit milliseconds — and short enough that four
         * cancellations do not dominate the suite. **Nothing is concluded from this number**;
         * it separates *reaches an answer* from *does not*, which is categorical.
         */
        const val TIMEOUT_MS = 3_000
    }
}

/** What one arm did: rows, or the server's refusal. `rows = -1` when it did not finish. */
class ArmResult(val rows: Int, val sqlState: String?, val message: String) {
    override fun toString(): String =
        if (sqlState == null) "completed, $rows rows" else "$sqlState -- $message"
}
