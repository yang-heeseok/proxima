package net.gseek.proxima.concept

import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt
import org.springframework.jdbc.core.JdbcTemplate

/**
 * **The shipped `concept_edge`, rebuilt inside a test container.**
 *
 * ## Why this is a reproduction and not the seed itself
 *
 * `seed/` is a separate Gradle module and `api` does not depend on it — deliberately, and
 * `seed/build.gradle.kts` says why: the generator must not be reachable from a place where
 * it is tempting to point at a real database. Adding the dependency means editing
 * `api/build.gradle.kts`, which is not a change a traversal measurement gets to make.
 *
 * So the edge-drawing loop of `Generator.writeConceptEdges` is reproduced here, from the
 * same seed value, drawing from the same stream in the same order — **including the weight
 * draw, which consumes from that stream and would shift every subsequent edge if it were
 * skipped.**
 *
 * ## Why a copy is acceptable here and is not acceptable in `V3`
 *
 * `MigrationDeduplicationTest` refuses to copy the statement it tests, because *a copy
 * drifts, and a drifted copy passes while the migration is wrong*. That argument applies
 * exactly when nothing else can tell that the copy drifted.
 *
 * Something can, here. `PrerequisiteDepthTest` measures the real generator's output —
 * 8,994 edges, a longest chain of 294, and 3 / 11 / 29 / 70 / 137 / 202 concepts reachable
 * from the last concept at depths 1..6 — and [assertMatchesTheShippedGraph] requires this
 * reproduction to produce those same numbers. A drift breaks the reproduction and the seed
 * digests at once, and the failure names which.
 *
 * **If the two ever disagree, this class is wrong and the seed module is right.**
 */
object SeedConceptGraph {

    /** `Scale.FULL.concepts`. */
    const val CONCEPTS = 3_000

    /** `Scale.FULL.prerequisitesPerConcept`. */
    private const val PREREQUISITES_PER_CONCEPT = 3

    /** The locality window in `Generator.writeConceptEdges`. */
    private const val WINDOW = 60

    /** `net.gseek.proxima.seed.SEED_VALUE`. */
    private const val SEED_VALUE = 20260810L

    /** `Generator.rng(3)` — the stream `concept_edge` is drawn from, and only that one. */
    private const val EDGE_STREAM = SEED_VALUE + 3 * 1_000_003L

    /** Rows the shipped generator emits, and therefore rows this must emit. */
    const val EXPECTED_EDGES = 8_994

    /** Prefix for the concepts this fixture owns, so teardown cannot touch anyone else's. */
    const val CODE_PREFIX = "gcpt-"

    /**
     * `(prerequisite ordinal, concept ordinal, weight)`, one-based, in emission order.
     *
     * Ordinals rather than ids: the database assigns ids from an identity sequence that
     * other test classes have already advanced, so the fixture maps ordinal to id after
     * inserting rather than assuming 1..3000.
     */
    fun edges(backEdges: Int = 0): List<Triple<Int, Int, Double>> {
        val r = Random(EDGE_STREAM)
        val out = ArrayList<Triple<Int, Int, Double>>(EXPECTED_EDGES)
        val firstPrerequisite = if (backEdges > 0) IntArray(CONCEPTS + 1) else null
        for (conceptId in 2..CONCEPTS) {
            val available = conceptId - 1
            val wanted = min(PREREQUISITES_PER_CONCEPT, available)
            val chosen = HashSet<Int>(wanted * 2)
            var guard = 0
            while (chosen.size < wanted && guard < wanted * 20) {
                guard++
                val window = min(available, WINDOW)
                val candidate = available - r.nextInt(window)
                if (candidate in 1 until conceptId) chosen.add(candidate)
            }
            for (prereq in chosen.sorted()) {
                // Drawn even though the traversal never reads it. Removing this line moves
                // the stream and silently produces a DIFFERENT graph that still looks
                // plausible -- which is why the assertions below are numbers and not shapes.
                val weight = 0.400 + r.nextInt(601) / 1000.0
                out.add(Triple(prereq, conceptId, weight))
                if (firstPrerequisite != null && firstPrerequisite[conceptId] == 0) {
                    firstPrerequisite[conceptId] = prereq
                }
            }
        }
        if (firstPrerequisite != null) {
            // Mirrors `Generator.writeBackEdges`. Same reproduction argument as the forward
            // loop, and the same control: `assertMatchesTheShippedGraph` still checks the
            // forward half against the seed module's measured numbers, and
            // `assertCyclesWereInjected` checks that the back half actually broke the DAG.
            for (i in 0 until backEdges) {
                var node = CONCEPTS - i * (CONCEPTS / (backEdges + 1))
                if (node < 2) continue
                val from = node
                var hops = 0
                while (hops < 2 + i) {
                    val next = firstPrerequisite[node]
                    if (next == 0) break
                    node = next
                    hops++
                }
                if (hops == 0) continue
                out.add(Triple(from, node, 1.000))
            }
        }
        return out
    }

    /**
     * Inserts the graph and returns `ordinal -> concept.id`, one-based (index 0 unused).
     *
     * Concepts are read back by code rather than by assuming the identity sequence handed
     * out a contiguous block. It would have, today; it is not a property anything asserts.
     */
    fun install(jdbc: JdbcTemplate, backEdges: Int = 0): LongArray {
        jdbc.update(
            """
            insert into concept (code, name, grade_band)
            select '$CODE_PREFIX' || lpad(g::text, 6, '0'), 'Concept ' || g, 'G5-6'
              from generate_series(1, $CONCEPTS) g
            """.trimIndent(),
        )

        val ids = LongArray(CONCEPTS + 1)
        jdbc.queryForList("select id, code from concept where code like '$CODE_PREFIX%'")
            .forEach { row ->
                val ordinal = (row["code"] as String).removePrefix(CODE_PREFIX).toInt()
                ids[ordinal] = (row["id"] as Number).toLong()
            }

        val edges = edges(backEdges)
        jdbc.batchUpdate(
            "insert into concept_edge (prerequisite_id, concept_id, weight) values (?, ?, ?)",
            edges.map { (p, c, w) ->
                arrayOf<Any>(ids[p], ids[c], (w * 1000).roundToInt() / 1000.0)
            },
        )
        return ids
    }

    /** Prefix for the items [installItems] owns. */
    const val ITEM_PREFIX = "gitm-"

    /**
     * One item per concept, so a page of *problems* can be taken over an expanded graph.
     *
     * Difficulty is `1 + (ordinal * 7) % 10` — deterministic, spread over the whole `1..10`
     * band `ck_item_difficulty` allows, and **deliberately uncorrelated with concept id**.
     * A difficulty that rose with the concept id would make `order by difficulty, id` and
     * `order by depth, id` agree by accident, and `ExpandedPagingTest` is entirely about
     * them disagreeing.
     */
    fun installItems(jdbc: JdbcTemplate, ids: LongArray) {
        jdbc.batchUpdate(
            "insert into item (code, concept_primary_id, difficulty, is_active) values (?, ?, ?, true)",
            (1..CONCEPTS).map { ordinal ->
                arrayOf<Any>(
                    "$ITEM_PREFIX${ordinal.toString().padStart(6, '0')}",
                    ids[ordinal],
                    (1 + (ordinal * 7) % 10).toShort(),
                )
            },
        )
        jdbc.update(
            """
            insert into item_concept (item_id, concept_id, weight)
            select i.id, i.concept_primary_id, 1.000
              from item i where i.code like '$ITEM_PREFIX%'
            """.trimIndent(),
        )
    }

    /** Removes everything [install] and [installItems] added, and nothing else. */
    fun remove(jdbc: JdbcTemplate) {
        jdbc.update(
            "delete from item_concept where item_id in (select id from item where code like '$ITEM_PREFIX%')",
        )
        jdbc.update("delete from item where code like '$ITEM_PREFIX%'")
        jdbc.update(
            """
            delete from concept_edge
             where concept_id in (select id from concept where code like '$CODE_PREFIX%')
                or prerequisite_id in (select id from concept where code like '$CODE_PREFIX%')
            """.trimIndent(),
        )
        jdbc.update("delete from concept where code like '$CODE_PREFIX%'")
    }

    /**
     * The control on the reproduction: these are `PrerequisiteDepthTest`'s numbers, taken
     * from the real generator at `Scale.FULL`.
     *
     * Concepts reachable from concept 3000 at depths 1..6, and the total edge count.
     */
    val REACHABLE_FROM_LAST_CONCEPT = listOf(3, 11, 29, 70, 137, 202)

    /**
     * Recomputes the depth facts from [edges] in memory and compares them against the
     * numbers the seed module measured. Called by every test class that installs the graph,
     * because a fixture nobody checks is a fixture that can be quietly wrong.
     */
    fun assertMatchesTheShippedGraph(): String {
        val edges = edges()
        val prerequisitesOf = Array(CONCEPTS + 1) { mutableListOf<Int>() }
        edges.forEach { (p, c, _) -> prerequisitesOf[c].add(p) }

        val reachable = ArrayList<Int>()
        val seen = HashSet<Int>()
        var frontier = setOf(CONCEPTS)
        repeat(REACHABLE_FROM_LAST_CONCEPT.size) {
            val next = HashSet<Int>()
            for (n in frontier) for (p in prerequisitesOf[n]) if (seen.add(p)) next.add(p)
            frontier = next
            reachable.add(seen.size)
        }

        check(edges.size == EXPECTED_EDGES) {
            "this fixture produced ${edges.size} edges and the shipped generator produces " +
                "$EXPECTED_EDGES. The reproduction in SeedConceptGraph has drifted from " +
                "Generator.writeConceptEdges -- the seed module is the authority"
        }
        check(reachable == REACHABLE_FROM_LAST_CONCEPT) {
            "this fixture reaches $reachable concepts at depths 1..6 and the shipped graph " +
                "reaches $REACHABLE_FROM_LAST_CONCEPT (PrerequisiteDepthTest). The " +
                "reproduction has drifted"
        }
        return "reproduction matches the shipped graph: ${edges.size} edges, reachable $reachable"
    }

    /**
     * The control on the **other** half of the fixture: the injected edges really do break
     * the DAG, checked by the algorithm `GeneratorTest` uses to assert they do not.
     *
     * Without this, a cycle report could be written against a graph that has no cycle, and
     * every arm in it would terminate for the right reason and the wrong one.
     */
    fun assertCyclesWereInjected(backEdges: Int): String {
        require(backEdges > 0)
        val edges = edges(backEdges).map { it.first to it.second }

        val backwards = edges.filter { (p, c) -> p >= c }
        check(backwards.size == backEdges) {
            "asked for $backEdges back-edges and the fixture produced ${backwards.size}"
        }

        // Kahn's algorithm, the same one GeneratorTest runs to assert the opposite.
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
        check(visited < nodes.size) {
            "every one of the ${nodes.size} concepts was topologically ordered, so this " +
                "graph is still a DAG and a report about cycles has no subject"
        }
        return "cycles injected: ${nodes.size - visited} of ${nodes.size} concepts are " +
            "unorderable, back-edges $backwards"
    }
}
