package net.gseek.proxima.seed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What the shipped prerequisite graph is past depth 1.**
 *
 * `V1__baseline.sql` creates `concept_edge`, [Generator] emits it, and `GeneratorTest`
 * asserts it is acyclic. None of those says how *deep* it is, how many concepts a
 * transitive walk reaches, or how many walks that costs — and until this class ran, all
 * three were 미측정.
 *
 * They are not decoration. `RecommendationQueries` reads this table exactly one level deep,
 * as a `NOT EXISTS` over direct prerequisites; the moment anything reads it transitively,
 * the numbers below decide whether that read is affordable. [PrerequisiteGraphFacts.walksTo]
 * in particular is the difference between a `UNION ALL` recursion and one that
 * deduplicates: **a prerequisite graph is a DAG and not a tree, so the number of distinct
 * walks of length `d` is not the number of concepts reachable in `d` steps.**
 *
 * This runs against [Scale.FULL] rather than [Scale.TINY] because the question is about the
 * dataset every number in `docs/reports/` is taken against, and depth is precisely the
 * property that does not survive being shrunk: `Scale.TINY` has 40 concepts drawn from a
 * 60-wide window, so every concept's prerequisites are simply "everything below it".
 */
class PrerequisiteDepthTest {

    /**
     * The shipped graph, structurally. **Printed as well as asserted**, because the
     * assertions pin what must not drift and the print is what a report quotes.
     */
    @Test
    fun `the shipped prerequisite graph's depth and shape`() {
        val g = FACTS
        println(g.render())

        assertEquals(8_994, g.edges, "the shipped edge count moved; domain-model.md says 8,994")
        assertTrue(
            g.longestPath >= 2,
            "the deepest prerequisite chain is ${g.longestPath} edges — at depth 1 there is " +
                "nothing for a transitive read to find and this measurement is vacuous",
        )
    }

    /**
     * **The number that separates a `UNION ALL` recursion from a deduplicating one.**
     *
     * A `WITH RECURSIVE ... UNION ALL` emits one row per *walk*, not one per concept. On a
     * tree those are the same number. On a DAG whose concepts share ancestors — which is
     * what a curriculum is — they diverge geometrically, and the divergence is invisible at
     * depth 1, which is the only depth anything here has ever read.
     */
    @Test
    fun `walks outgrow reachable concepts with depth`() {
        val g = FACTS
        val start = Scale.FULL.concepts // the last concept: the deepest place to stand

        println("from concept $start")
        println("  depth   reachable        walks")
        for (d in 1..14) {
            println("  %5d   %9d  %15s".format(d, g.reachableWithin(start, d), fmt(g.walksTo(start, d))))
        }

        assertTrue(
            g.walksTo(start, 8) > g.reachableWithin(start, 8).toLong(),
            "walks and reachable concepts are equal at depth 8, which would mean this graph " +
                "is a tree. V1 declares it a DAG and the distinction is the whole point",
        )
    }

    private fun fmt(v: Long): String = if (v == Long.MAX_VALUE) "overflowed Long" else "%,d".format(v)

    private companion object {

        /**
         * Generated once. `Scale.FULL` writes ~174 MB across seven files and only one of
         * them is read here, so the rest are deleted as soon as the graph is parsed —
         * generating twice would double the slowest thing in this module.
         */
        val FACTS: PrerequisiteGraphFacts by lazy {
            val dir = Files.createTempDirectory("seed-depth")
            try {
                Generator(Scale.FULL).generateAll(dir)
                PrerequisiteGraphFacts.of(dir.resolve("concept_edge.tsv"), Scale.FULL.concepts)
            } finally {
                Files.list(dir).use { s -> s.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(dir)
            }
        }
    }
}

/**
 * Structural facts about a prerequisite DAG, read out of a generated `concept_edge.tsv`.
 *
 * Kept in the test sources rather than in `main` because nothing in the generator consults
 * it: it answers questions *about* the emitted graph, and putting it in `main` would imply
 * the generator uses it to decide something.
 */
class PrerequisiteGraphFacts private constructor(
    private val prerequisitesOf: Array<IntArray>,
    val edges: Int,
) {

    /** The longest prerequisite chain anywhere in the graph, in edges. */
    val longestPath: Int by lazy {
        // Every edge runs from a lower concept id to a higher one -- the generator's own
        // guarantee, asserted by GeneratorTest -- so ascending id order IS a topological
        // order and one pass suffices. No Kahn queue needed here.
        val depth = IntArray(prerequisitesOf.size)
        for (c in prerequisitesOf.indices) {
            for (p in prerequisitesOf[c]) {
                if (depth[p] + 1 > depth[c]) depth[c] = depth[p] + 1
            }
        }
        depth.max()
    }

    /** How many concepts have at least one prerequisite. */
    val withPrerequisites: Int
        get() = (1 until prerequisitesOf.size).count { prerequisitesOf[it].isNotEmpty() }

    /** Distinct concepts reachable from [start] in at most [maxDepth] steps. */
    fun reachableWithin(start: Int, maxDepth: Int): Int {
        val seen = HashSet<Int>()
        var frontier = setOf(start)
        repeat(maxDepth) {
            val next = HashSet<Int>()
            for (n in frontier) for (p in prerequisitesOf[n]) if (seen.add(p)) next.add(p)
            frontier = next
        }
        return seen.size
    }

    /**
     * Distinct **walks** of length 1..[maxDepth] from [start] — the row count a `UNION ALL`
     * recursion emits before anything deduplicates it.
     *
     * Saturates at [Long.MAX_VALUE] rather than wrapping. A silently negative row count in a
     * report about row counts is exactly the sort of number this repository refuses.
     */
    fun walksTo(start: Int, maxDepth: Int): Long {
        var frontier = mapOf(start to 1L)
        var total = 0L
        repeat(maxDepth) {
            val next = HashMap<Int, Long>()
            for ((n, ways) in frontier) {
                for (p in prerequisitesOf[n]) next.merge(p, ways) { a, b -> satAdd(a, b) }
            }
            total = satAdd(total, next.values.fold(0L, ::satAdd))
            frontier = next
        }
        return total
    }

    fun render(): String = buildString {
        appendLine("concepts                    : ${prerequisitesOf.size - 1}")
        appendLine("edges                       : $edges")
        appendLine("longest chain               : $longestPath edges")
        appendLine("concepts with a prerequisite: $withPrerequisites")
        appendLine("mean prerequisites, of those: %.3f".format(edges.toDouble() / withPrerequisites))
    }

    companion object {

        fun of(tsv: Path, concepts: Int): PrerequisiteGraphFacts {
            val lists = Array(concepts + 1) { mutableListOf<Int>() }
            var edges = 0
            tsv.bufferedReader().use { r ->
                r.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val f = line.split('\t')
                    lists[f[2].toInt()].add(f[1].toInt()) // concept -> prerequisite
                    edges++
                }
            }
            return PrerequisiteGraphFacts(Array(lists.size) { lists[it].toIntArray() }, edges)
        }

        private fun satAdd(a: Long, b: Long): Long {
            val s = a + b
            return if (((a xor s) and (b xor s)) < 0) Long.MAX_VALUE else s
        }
    }
}
