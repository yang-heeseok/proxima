package net.gseek.proxima.concept

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

/**
 * **How many statements a depth-`d` prerequisite walk costs, as exact numbers.**
 *
 * `R8` established the instrument and the rule: counts are `assertEquals`, never `<=`,
 * because *a bound drifts upward one honest commit at a time*. `StatementCounter` is
 * imported rather than reimplemented — it already refuses to report `0` when Hibernate
 * statistics are off, which is the control this measurement would otherwise need to invent.
 *
 * ## Why the graph is installed rather than mocked
 *
 * The numbers below are properties of a **specific graph**, not of the code. A walk over a
 * chain costs `d` statements and a walk over the shipped curriculum costs 138, and only one
 * of those two numbers tells a reader anything. [SeedConceptGraph] reproduces the shipped
 * `concept_edge` — 3,000 concepts, 8,994 edges — and checks itself against the figures
 * `PrerequisiteDepthTest` measured on the real generator.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, StatementCounter::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrerequisiteTraversalCountTest {

    @Autowired private lateinit var graph: PrerequisiteGraph
    @Autowired private lateinit var counter: StatementCounter
    @Autowired private lateinit var jdbc: JdbcTemplate

    /** `concept.id` of the graph's last concept — the deepest place to stand. */
    private var top = 0L

    @BeforeAll
    fun install() {
        println(SeedConceptGraph.assertMatchesTheShippedGraph())
        top = SeedConceptGraph.install(jdbc)[SeedConceptGraph.CONCEPTS]
    }

    @AfterAll
    fun uninstall() = SeedConceptGraph.remove(jdbc)

    /**
     * **The defect, with its number.**
     *
     * A recursive walk asks each node for its prerequisites, so it issues one statement per
     * node it visits. At depth 6 from the last concept that is **138 statements returning
     * 202 concepts** — and the count is a function of the *answer's size*, which is the
     * property `R8` §3.4 named as the definition of an N+1: it grows with the rows.
     *
     * The 138 is not 202 because the last level's newly-found concepts are never themselves
     * queried. That difference is the kind of thing an upper bound would have hidden.
     */
    @Test
    fun `a node-at-a-time walk issues one statement per visited concept`() {
        val counted = counter.count { graph.closureByNodeWalk(top, maxDepth = 6) }

        assertEquals(202, counted.result.size, "the fixture graph is not the shipped one")
        assertEquals(
            138, counted.statements,
            "a node-at-a-time walk to depth 6 issued ${counted.statements} statements for " +
                "${counted.result.size} concepts. This number is the report: it is 1 + the " +
                "count of concepts found at depths 1..5, every one of them its own round trip",
        )
    }

    /**
     * The improvement anybody makes next, and it is a real one — but the count is still
     * proportional to something the caller does not control.
     */
    @Test
    fun `a level-batched walk issues one statement per level`() {
        val counted = counter.count { graph.closureByLevelWalk(top, maxDepth = 6) }

        assertEquals(202, counted.result.size)
        assertEquals(
            6, counted.statements,
            "a level-batched walk to depth 6 issued ${counted.statements} statements. It " +
                "should be exactly one per level asked for",
        )
    }

    /**
     * **The green side: one statement, at any depth.**
     *
     * The application issues nothing per node and nothing per level. The count is 1 at
     * depth 3 and 1 at depth 12, which is the property no application-side walk can have,
     * because the recursion happens where the data is.
     */
    @Test
    fun `the transitive closure is one statement at any depth`() {
        val shallow = counter.count { graph.closure(top, maxDepth = 3) }
        val deep = counter.count { graph.closure(top, maxDepth = 12) }

        assertEquals(29, shallow.result.size, "the fixture graph is not the shipped one")
        assertEquals(511, deep.result.size, "the fixture graph is not the shipped one")
        assertEquals(1, shallow.statements)
        assertEquals(
            1, deep.statements,
            "the closure read must be one statement whatever depth is asked for. It " +
                "returned ${deep.result.size} concepts in ${deep.statements}",
        )
    }

    /**
     * The three arms return **the same concepts**, or none of the counts above compares
     * anything.
     *
     * This is the control `R0` §9 says every instrument needs beside the one that proves it
     * is alive: not *is the counter working*, but *is it aimed at the same question in all
     * three arms*.
     */
    @Test
    fun `all three arms return the same closure`() {
        val byNode = graph.closureByNodeWalk(top, maxDepth = 6)
        val byLevel = graph.closureByLevelWalk(top, maxDepth = 6)
        val bySql = graph.closure(top, maxDepth = 6).map { it.conceptId }.toSet()

        assertEquals(byNode, byLevel, "the two application walks disagree")
        assertEquals(byNode, bySql, "the application walk and the recursive statement disagree")
        assertEquals(202, bySql.size)
    }

    /**
     * **`union` against `union all`, as the row count each recursion actually carries.**
     *
     * Both are one statement, so the statement counter cannot tell them apart, and every
     * functional test passes on either. The difference is inside: `union all` emits one row
     * per **walk** and `union` emits one per `(concept, depth)` pair it has not already
     * produced.
     *
     * The `union all` column is checked against arithmetic done in a different module on a
     * different graph representation — `PrerequisiteDepthTest.walksTo` counts distinct walks
     * without touching a database. Two independent computations agreeing is what makes this
     * a measurement rather than a printout.
     */
    @Test
    fun `union all carries one row per walk and union carries one per concept and depth`() {
        // Computed by PrerequisiteDepthTest.walksTo, in the seed module, over an in-memory
        // graph and without a database. Two independent computations agreeing is what makes
        // the column below a measurement rather than a printout.
        val walks = listOf(3, 12, 39, 120, 363, 1_092, 3_279, 9_840, 29_523)

        val measuredAll = (1..9).map { graph.walkRowsUnionAll(top, it) }
        val measuredUnion = (1..9).map { graph.walkRowsUnion(top, it) }

        println("  depth   union all      union   ratio")
        for (d in 1..9) {
            println(
                "  %5d   %9d  %9d   %5.1fx".format(
                    d, measuredAll[d - 1], measuredUnion[d - 1],
                    measuredAll[d - 1].toDouble() / measuredUnion[d - 1],
                ),
            )
        }

        assertEquals(
            walks, measuredAll,
            "the union all recursion's row counts do not match the distinct-walk counts " +
                "PrerequisiteDepthTest computes for the same graph. One of the two is wrong",
        )
        assertEquals(
            UNION_ROWS, measuredUnion,
            "the union recursion's working table changed size. It is bounded by " +
                "(concepts x depths) and not by walks, and that bound is the finding",
        )
        // The property, rather than any one row of the table: the gap between the two forms
        // is a function of DEPTH. At depth 1 they are the same query. By depth 9 they are
        // not, and nothing about the SQL text changed in between.
        val ratioAtOne = measuredAll[0].toDouble() / measuredUnion[0]
        val ratioAtNine = measuredAll[8].toDouble() / measuredUnion[8]
        assertTrue(
            ratioAtNine > 10 * ratioAtOne,
            "the union all / union ratio went from $ratioAtOne at depth 1 to $ratioAtNine " +
                "at depth 9. If it stops growing, this graph has stopped sharing ancestors " +
                "and the comparison has lost its subject",
        )

        // And the deduplicating form is still not one row per concept: 1,103 rows carry 365
        // concepts at depth 9, because `union` deduplicates whole rows and a concept
        // reachable at depths 4 and 7 is two different rows. See R20 section 3.3.
        assertEquals(
            365, graph.closure(top, 9).size,
            "the closure at depth 9 is 365 concepts; the union working table carried " +
                "${measuredUnion[8]} rows to produce them",
        )
    }

    /**
     * **The property that decides it**, stated so that neither number above can be
     * satisfied by luck: the node-at-a-time walk gets more expensive as the answer gets
     * bigger, and the level walk does not.
     */
    @Test
    fun `only the node walk's statement count depends on the size of the answer`() {
        val shallowNode = counter.count { graph.closureByNodeWalk(top, maxDepth = 3) }
        val deepNode = counter.count { graph.closureByNodeWalk(top, maxDepth = 6) }
        val shallowLevel = counter.count { graph.closureByLevelWalk(top, maxDepth = 3) }
        val deepLevel = counter.count { graph.closureByLevelWalk(top, maxDepth = 6) }

        assertTrue(deepNode.result.size > shallowNode.result.size, "the two depths must differ")

        assertEquals(12, shallowNode.statements, "1 + 3 + 8 concepts queried at depths 0..2")
        assertEquals(138, deepNode.statements)
        assertEquals(3, shallowLevel.statements)
        assertEquals(6, deepLevel.statements)

        assertEquals(
            deepNode.result.size, deepLevel.result.size,
            "the two walks must return the same concepts, or the comparison is between two " +
                "different questions",
        )
    }

    private companion object {
        /**
         * The `union` recursion's working-table size at depths 1..9, measured on
         * 2026-08-21 against `postgres:16-alpine` (server 16.14) on the shipped graph.
         *
         * Pinned rather than recomputed, for `R8` §3.2's reason: a number nobody has to
         * restate is a number that drifts.
         */
        val UNION_ROWS = listOf(3, 11, 30, 78, 181, 343, 547, 804, 1_103)
    }
}
