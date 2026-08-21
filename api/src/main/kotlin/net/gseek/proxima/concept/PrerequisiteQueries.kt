package net.gseek.proxima.concept

import net.gseek.proxima.domain.Concept
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

/**
 * Reads of `concept_edge` **past the one level anything here has ever read.**
 *
 * ## What was already true before this file existed
 *
 * `V1__baseline.sql` has created `concept_edge` since the first commit — `prerequisite_id`,
 * `concept_id`, a weight, a uniqueness constraint on the pair, and a table comment saying
 * that acyclicity cannot be a `CHECK` and is asserted by a test instead. The generator emits
 * 8,994 edges over 3,000 concepts and guarantees the DAG property **by construction**, every
 * edge running from a lower concept id to a higher one.
 *
 * So the graph is not new. What is new is reading it **transitively**.
 * `RecommendationQueries` consults it exactly one level deep, as a `NOT EXISTS` over direct
 * prerequisites, and at depth 1 every question this file is about has the same answer:
 * one walk per concept, no revisits, no cycles reachable, no page that moves.
 *
 * ## Not all of these ship, and that is deliberate
 *
 * [closure] is the shipped read. [walksUnionAll] and [walksUnionDistinct] are the arms it is
 * compared against, kept runnable for `LearnerPageQueries`' reason — *a comparison needs the
 * losing options to be runnable rather than described.* Each says so on itself.
 *
 * ## Why native SQL and not JPQL, QueryDSL, or an entity
 *
 * Three reasons, and only the first is about syntax.
 *
 * **JPQL has no recursive query.** `WITH RECURSIVE` is SQL:1999 and JPA does not surface it,
 * so the choice is native SQL or an application loop, and `docs/reports/R20` is the
 * measurement of what the application loop costs.
 *
 * **There is deliberately no `ConceptEdge` entity.** `PersistenceUnitGateTest` asserts the
 * persistence unit holds exactly five entities, and it asserts a *set* rather than a count
 * so that whoever adds one has to say which. Adding a sixth to serve a read-only traversal
 * would also walk straight back into what `R4` measured and `R8` gated: a lazy association
 * hands the caller an N+1 and the service that hands it out looks clean. **A closure read
 * wants values, not a managed graph.**
 *
 * **The rule is fixed, not assembled.** `ADR-001` chose QueryDSL for queries built from
 * optional predicates. These are not; the same reasoning `RecommendationQueries` records
 * applies unchanged.
 */
interface PrerequisiteQueries : Repository<Concept, Long> {

    /**
     * **One level.** The statement an application-side walk issues once per iteration.
     *
     * Ordered by id rather than by anything textual. `R9` §8 put a standing risk against
     * every `order by` on text in this repository — `postgres:16-alpine` is musl-built and
     * its declared `en_US.utf8` does not collate locale-aware — and a traversal has no
     * reason to take on that risk to produce a deterministic order.
     */
    @Query(
        value = """
        select e.prerequisite_id
          from concept_edge e
         where e.concept_id in (:conceptIds)
         order by e.prerequisite_id
        """,
        nativeQuery = true,
    )
    fun directPrerequisitesOf(@Param("conceptIds") conceptIds: Collection<Long>): List<Long>

    /**
     * **The shipped transitive read: one statement, at any depth.**
     *
     * `union` rather than `union all` in the recursive term, and that word is the whole
     * difference between this query and [walksUnionAll]. PostgreSQL's recursive `union`
     * discards a row that duplicates one already produced, so a concept reachable by four
     * hundred distinct paths at the same depth is carried once instead of four hundred
     * times. **Measured, on the shipped graph, in `docs/reports/R20` §3** — it is not a
     * micro-optimisation, it is the difference between a bounded working table and one that
     * grows geometrically with `maxDepth`.
     *
     * The outer `group by` collapses the remaining `(concept, depth)` rows to the **shortest**
     * path to each concept, which is the only depth that means anything: *how far below this
     * concept does that one sit at the closest*.
     *
     * Aliases are quoted because PostgreSQL folds unquoted identifiers to lower case and the
     * projection binds by property name — the reason `RecommendationQueries` gives.
     */
    @Query(
        value = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1
              from concept_edge e
             where e.concept_id = :conceptId
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e
              join walk w on e.concept_id = w.prerequisite_id
             where w.depth < :maxDepth
        )
        select w.prerequisite_id as "conceptId",
               min(w.depth)      as "depth"
          from walk w
         group by w.prerequisite_id
         order by min(w.depth), w.prerequisite_id
        """,
        nativeQuery = true,
    )
    fun closure(
        @Param("conceptId") conceptId: Long,
        @Param("maxDepth") maxDepth: Int,
    ): List<PrerequisiteRow>

    /**
     * **Not the shipped read.** The same traversal with `union all`, returning the working
     * table itself — **one row per distinct walk**, ungrouped.
     *
     * This is what almost every `WITH RECURSIVE` example on the internet is, and on a tree it
     * is correct and cheap. A prerequisite graph is not a tree: concepts share ancestors, so
     * the number of walks of length `d` is not the number of concepts reachable in `d` steps.
     * On the shipped graph, from the last concept: **606 concepts at depth 14, and 7,174,452
     * walks to find them.**
     *
     * Kept runnable so that `R20` compares against a measured arm rather than a described
     * one, and so that `R21` has something a cycle can actually kill.
     */
    @Query(
        value = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1
              from concept_edge e
             where e.concept_id = :conceptId
            union all
            select e.prerequisite_id, w.depth + 1
              from concept_edge e
              join walk w on e.concept_id = w.prerequisite_id
             where w.depth < :maxDepth
        )
        select w.prerequisite_id as "conceptId",
               w.depth           as "depth"
          from walk w
        """,
        nativeQuery = true,
    )
    fun walksUnionAll(
        @Param("conceptId") conceptId: Long,
        @Param("maxDepth") maxDepth: Int,
    ): List<PrerequisiteRow>

    /**
     * **Not the shipped read.** [closure] without the outer `group by`, so the working table
     * is visible as rows.
     *
     * It exists to make one number quotable: how many rows the deduplicating recursion
     * actually carries. Comparing it against [walksUnionAll] at the same depth is the
     * measurement `R20` §3 turns on, and comparing the two on a **cyclic** graph is `R21`'s
     * second death — `union` deduplicates whole rows, and a row carrying a depth counter is
     * never a duplicate of one carrying a different depth.
     */
    @Query(
        value = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1
              from concept_edge e
             where e.concept_id = :conceptId
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e
              join walk w on e.concept_id = w.prerequisite_id
             where w.depth < :maxDepth
        )
        select w.prerequisite_id as "conceptId",
               w.depth           as "depth"
          from walk w
        """,
        nativeQuery = true,
    )
    fun walksUnionDistinct(
        @Param("conceptId") conceptId: Long,
        @Param("maxDepth") maxDepth: Int,
    ): List<PrerequisiteRow>
}

/**
 * One concept in another's prerequisite closure, fully materialised.
 *
 * [depth] is the **shortest** number of prerequisite edges between the two when it comes
 * from [PrerequisiteQueries.closure], and the length of one particular walk when it comes
 * from an ungrouped arm. Holds no reference to a session, for `R4`'s reason.
 */
interface PrerequisiteRow {
    val conceptId: Long
    val depth: Int
}
