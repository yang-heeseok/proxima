package net.gseek.proxima.concept

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The transitive prerequisite closure of a concept — *everything a learner has to
 * understand before this*, rather than only the level immediately below it.
 *
 * **Both methods here walk the graph in the application, and that is the state
 * `docs/reports/R20` measures.** They are the two shapes anybody writes when JPQL turns out
 * to have no recursive query: one round trip per node, or one round trip per level. Neither
 * is wrong, both return the right answer, and every functional test passes on either.
 *
 * The number they differ on is how many statements they issue, which is exactly the number
 * `R8` established this repository measures rather than reviews.
 *
 * ## Why [maxDepth] is a parameter and not a constant
 *
 * The shipped graph's longest prerequisite chain is **294 edges** — measured, at
 * `Scale.FULL`, by `PrerequisiteDepthTest`. An unbounded closure is therefore not a
 * hypothetical cost, and a depth bound is the only thing standing between this read and
 * that chain. It is a **liveness bound and not a domain statement**: nothing about the
 * curriculum says a learner's prerequisites stop at depth 6.
 */
@Service
class PrerequisiteGraph(private val queries: PrerequisiteQueries) {

    /**
     * **One statement per visited concept.** The shape written first, because it is the one
     * a recursive function produces: ask this node for its prerequisites, then ask each of
     * those for theirs.
     *
     * The `seen` set is not an optimisation, it is the only reason this terminates in
     * bounded time on a DAG: the prerequisite graph shares ancestors heavily, so a walk
     * without it revisits the same concept along every distinct path to it —
     * **7,174,452 walks to reach 606 concepts at depth 14**, measured on the shipped graph.
     */
    @Transactional(readOnly = true)
    fun closureByNodeWalk(conceptId: Long, maxDepth: Int): Set<Long> {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
        val seen = LinkedHashSet<Long>()
        var frontier = listOf(conceptId)
        repeat(maxDepth) {
            val next = ArrayList<Long>()
            for (node in frontier) {
                // ONE STATEMENT, HERE, PER NODE. This is the line R20 is about.
                for (prerequisite in queries.directPrerequisitesOf(listOf(node))) {
                    if (seen.add(prerequisite)) next.add(prerequisite)
                }
            }
            frontier = next
        }
        return seen
    }

    /**
     * **One statement per level.** The obvious improvement, and it is a real one: the
     * statement count stops depending on the size of the answer and starts depending only
     * on the depth asked for.
     *
     * It is included because a report that compares the worst shape against the best one
     * has not shown its reader anything they could not guess. The interesting comparison is
     * against the version somebody has already thought about — and this one still issues a
     * round trip per level, still holds a connection for the whole walk, and still cannot
     * be given to the planner as a single problem.
     */
    @Transactional(readOnly = true)
    fun closureByLevelWalk(conceptId: Long, maxDepth: Int): Set<Long> {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
        val seen = LinkedHashSet<Long>()
        var frontier = listOf(conceptId)
        repeat(maxDepth) {
            if (frontier.isEmpty()) return@repeat
            // ONE STATEMENT PER LEVEL, whatever the level holds.
            val next = queries.directPrerequisitesOf(frontier).filter { seen.add(it) }
            frontier = next
        }
        return seen
    }
}
