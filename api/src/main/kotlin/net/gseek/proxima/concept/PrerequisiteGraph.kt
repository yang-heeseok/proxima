package net.gseek.proxima.concept

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The transitive prerequisite closure of a concept — *everything a learner has to
 * understand before this*, rather than only the level immediately below it.
 *
 * [closure] is the read. [closureByNodeWalk] and [closureByLevelWalk] walk the graph in the
 * application instead, and they are kept because `docs/reports/R20` is a comparison: they
 * are the two shapes anybody writes on discovering that JPQL has no recursive query. One
 * round trip per node, or one round trip per level. Neither is wrong, both return the right
 * answer, and every functional test passes on either.
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
 * curriculum says a learner's prerequisites stop at depth 6, and `ADR-011` is where that
 * distinction is argued rather than assumed.
 */
@Service
class PrerequisiteGraph(private val queries: PrerequisiteQueries) {

    /**
     * **One statement, at any depth and any answer size.**
     *
     * The application does not walk anything: the whole traversal is one problem handed to
     * the planner, which is the difference `R20` measures. What the caller gets back is
     * values — nothing here can trigger a round trip after the transaction closes, which is
     * `R4`'s condition for the connection actually going back to the pool.
     */
    @Transactional(readOnly = true)
    fun closure(conceptId: Long, maxDepth: Int): List<PrerequisiteRow> {
        require(maxDepth >= 1) { "maxDepth must be at least 1, was $maxDepth" }
        return queries.closure(conceptId, maxDepth)
    }

    /**
     * How many rows the `union all` recursion's working table carries — **not** how many
     * concepts it found. One statement, and the number it returns is the point.
     *
     * Kept beside [closure] rather than in a test, for `LearnerPageQueries`' reason: a
     * comparison needs the losing option to be runnable rather than described.
     */
    @Transactional(readOnly = true)
    fun walkRowsUnionAll(conceptId: Long, maxDepth: Int): Int =
        queries.walksUnionAll(conceptId, maxDepth).size

    /** The same count for the `union` form. Not the shipped read either — see above. */
    @Transactional(readOnly = true)
    fun walkRowsUnion(conceptId: Long, maxDepth: Int): Int =
        queries.walksUnionDistinct(conceptId, maxDepth).size

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
