package net.gseek.proxima.seed

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two properties the generator has to hold, and neither is checkable by the database.
 *
 * 1. **Determinism.** `PUB-7` says the dataset is code rather than a committed file. That
 *    is only a real substitute for publishing rows if the same seed value produces the same
 *    bytes on someone else's machine — otherwise a reader cannot reproduce a number and the
 *    benchmark is an anecdote.
 * 2. **Acyclicity.** `concept_edge` is a DAG. A `CHECK` constraint sees a single row and a
 *    cycle is a property of the whole graph, so the database cannot express it. `V1`
 *    records that gap in a comment; this is the test that comment refers to.
 */
class GeneratorTest {

    @Test
    fun `the same seed produces byte-identical files`() {
        val a = generateInto(Files.createTempDirectory("seed-a"))
        val b = generateInto(Files.createTempDirectory("seed-b"))

        assertEquals(a.keys, b.keys)
        for (table in a.keys) {
            assertEquals(
                a[table], b[table],
                "$table.tsv differs between two runs at the same seed value -- the dataset " +
                    "is not reproducible, and PUB-7 depends on it being so",
            )
        }
    }

    @Test
    fun `a different seed produces different files`() {
        // Guards the test above from passing for the wrong reason: a generator that wrote
        // nothing, or wrote constants, would be perfectly "deterministic".
        val dir = Files.createTempDirectory("seed-c")
        Generator(Scale.TINY, seed = SEED_VALUE + 1).generateAll(dir)
        val other = digests(dir)
        val base = generateInto(Files.createTempDirectory("seed-d"))

        assertTrue(
            other["attempt"] != base["attempt"],
            "changing the seed value changed nothing -- the generator is ignoring it",
        )
    }

    @Test
    fun `concept_edge is acyclic`() {
        val dir = Files.createTempDirectory("seed-dag")
        Generator(Scale.TINY).generateAll(dir)

        val edges = dir.resolve("concept_edge.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val f = line.split('\t')
                f[1].toInt() to f[2].toInt() // prerequisite -> concept
            }
        assertTrue(edges.isNotEmpty(), "no edges were generated, so this proves nothing")

        // Kahn's algorithm. If any node remains after the queue drains, it is in a cycle.
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

        assertEquals(
            nodes.size, visited,
            "concept_edge contains a cycle -- ${nodes.size - visited} concepts could not be " +
                "topologically ordered. A prerequisite graph with a cycle describes a " +
                "concept that must be learned before itself",
        )
    }

    @Test
    fun `every edge runs from a lower concept id to a higher one`() {
        // The construction that makes the test above true by design rather than by luck.
        // Asserted separately so that a change which reintroduces backward edges fails
        // here, naming the cause, rather than only failing the cycle check downstream.
        val dir = Files.createTempDirectory("seed-order")
        Generator(Scale.TINY).generateAll(dir)

        val backwards = dir.resolve("concept_edge.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it[1].toInt() >= it[2].toInt() }

        assertTrue(backwards.isEmpty(), "found edges that do not run forward: $backwards")
    }

    @Test
    fun `mastery holds one row per learner and concept`() {
        // V1 carries no unique constraint to enforce this -- deliberately, see ADR-002 --
        // so the generator has to hold it and something has to check that it does. T6 is
        // about a race between two requests, not about the seed arriving dirty.
        val dir = Files.createTempDirectory("seed-mastery")
        Generator(Scale.TINY).generateAll(dir)

        val pairs = dir.resolve("mastery.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { val f = it.split('\t'); f[1] to f[2] }

        assertEquals(
            pairs.size, pairs.toSet().size,
            "the generated mastery rows contain a duplicate (learner_id, concept_id)",
        )
    }

    @Test
    fun `no generated identifier could be mistaken for a real one`() {
        // The other half of the CI guard in no-learner-data.yml: the workflow checks the
        // tree for identifier shapes, and this checks what the generator emits. PUB-7.
        val dir = Files.createTempDirectory("seed-ids")
        Generator(Scale.TINY).generateAll(dir)

        val refs = dir.resolve("learner.tsv").readLines()
            .filter { it.isNotBlank() }
            .map { it.split('\t')[1] }

        assertTrue(refs.isNotEmpty())
        val shape = Regex("""^learner-\d{6}$""")
        assertTrue(
            refs.all { shape.matches(it) },
            "a learner reference did not match the generated shape: " +
                refs.filterNot { shape.matches(it) },
        )
    }

    private fun generateInto(dir: Path): Map<String, String> {
        Generator(Scale.TINY).generateAll(dir)
        return digests(dir)
    }

    private fun digests(dir: Path): Map<String, String> =
        Files.list(dir).use { stream ->
            stream.toList().sortedBy { it.fileName.toString() }.associate { path ->
                val md = MessageDigest.getInstance("SHA-256")
                path.fileName.toString().removeSuffix(".tsv") to
                    md.digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
        }
}
