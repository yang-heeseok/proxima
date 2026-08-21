package net.gseek.proxima.seed

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The opt-in cycle, and the proof that opting out changes nothing.**
 *
 * `GeneratorTest` asserts `concept_edge` is acyclic and that every edge runs forward. Those
 * are properties of the *shipped* graph and they stay true — this class exists because
 * `docs/reports/R21` needs a graph where they are false, and it needs it to be reproducible
 * rather than assembled by hand in whichever test happens to want one.
 *
 * ## The property this class is really about
 *
 * Not that a cycle can be produced. That a cycle can be produced **without moving a single
 * byte of the shipped dataset.** `SeedDigestTest` pins SHA-256 for `Scale.TINY` and
 * `Scale.FULL`, and its own KDoc says what a failure there means: either the generator
 * changed, in which case every number in `docs/reports/` was taken against a dataset that
 * no longer exists, or the platform did, in which case `PUB-7` is false. Neither is fixed by
 * editing the expected values.
 *
 * So the first test below is the one that matters, and it is deliberately not a digest pin
 * of its own: it compares the two code paths **against each other**, which catches a change
 * that moved both.
 */
class CyclicGeneratorTest {

    @Test
    fun `asking for no back edges produces the same bytes as not asking`() {
        val implicit = generateInto(Files.createTempDirectory("cycle-implicit"), backEdges = 0)
        val explicit = generateInto(Files.createTempDirectory("cycle-explicit"), backEdges = null)

        assertEquals(
            explicit, implicit,
            "passing backEdges = 0 produced different bytes from not passing it at all. " +
                "The parameter is supposed to be inert at its default; if it is not, every " +
                "digest in SeedDigestTest is now a digest of something else",
        )
    }

    @Test
    fun `the shipped edge count is untouched by the parameter existing`() {
        val dir = Files.createTempDirectory("cycle-count")
        Generator(Scale.TINY).generateAll(dir)
        val shipped = edgesOf(dir)

        val dir2 = Files.createTempDirectory("cycle-count-2")
        Generator(Scale.TINY, backEdges = 3).generateAll(dir2)
        val cyclic = edgesOf(dir2)

        assertEquals(
            shipped.size + 3, cyclic.size,
            "asking for 3 back-edges produced ${cyclic.size - shipped.size} extra edges",
        )
        assertEquals(
            shipped, cyclic.take(shipped.size),
            "the back-edges were not appended -- the forward edges themselves moved, which " +
                "means the injection is not the additive thing it claims to be",
        )
    }

    @Test
    fun `the injected edges run backwards, which no forward edge can`() {
        val dir = Files.createTempDirectory("cycle-direction")
        Generator(Scale.TINY, backEdges = 3).generateAll(dir)

        val backwards = edgesOf(dir).filter { (p, c) -> p >= c }

        assertEquals(
            3, backwards.size,
            "expected exactly 3 edges running from a higher concept id to a lower one, " +
                "found ${backwards.size}: $backwards",
        )
        assertTrue(
            backwards.none { (p, c) -> p == c },
            "an injected edge is a self-loop, which ck_concept_edge_no_self would refuse -- " +
                "the cycle has to be one the database will actually accept: $backwards",
        )
        assertEquals(
            backwards.size, backwards.toSet().size,
            "two injected edges are the same pair, which uk_concept_edge would refuse",
        )
    }

    /**
     * **The cycle, observed by the same algorithm that asserts its absence.**
     *
     * `GeneratorTest` runs Kahn's algorithm and requires every node to be ordered. This runs
     * the identical algorithm and requires that some are not — so the two tests cannot both
     * be passing for a reason unrelated to the graph.
     */
    @Test
    fun `Kahn's algorithm cannot order the injected graph`() {
        val dir = Files.createTempDirectory("cycle-kahn")
        Generator(Scale.TINY, backEdges = 3).generateAll(dir)
        val edges = edgesOf(dir)

        val nodes = (edges.map { it.first } + edges.map { it.second }).toSet()
        val outgoing = edges.groupBy({ it.first }, { it.second })
        val indegree = HashMap<Int, Int>()
        nodes.forEach { indegree[it] = 0 }
        edges.forEach { (_, to) -> indegree[to] = indegree[to]!! + 1 }

        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            visited++
            outgoing[n].orEmpty().forEach { m ->
                indegree[m] = indegree[m]!! - 1
                if (indegree[m] == 0) queue.addLast(m)
            }
        }

        assertTrue(
            visited < nodes.size,
            "every one of the ${nodes.size} nodes was topologically ordered, so the " +
                "injected graph is still a DAG and R21 has nothing to measure",
        )
        println("unorderable concepts: ${nodes.size - visited} of ${nodes.size}")
    }

    private fun edgesOf(dir: Path): List<Pair<Int, Int>> =
        dir.resolve("concept_edge.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { val f = it.split('\t'); f[1].toInt() to f[2].toInt() }

    private fun generateInto(dir: Path, backEdges: Int?): Map<String, String> {
        if (backEdges == null) Generator(Scale.TINY).generateAll(dir)
        else Generator(Scale.TINY, backEdges = backEdges).generateAll(dir)
        return Files.list(dir).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }.associate { path ->
                val md = MessageDigest.getInstance("SHA-256")
                path.fileName.toString().removeSuffix(".tsv") to
                    md.digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
    }
}
