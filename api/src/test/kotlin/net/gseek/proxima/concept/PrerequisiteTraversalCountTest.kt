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
}
