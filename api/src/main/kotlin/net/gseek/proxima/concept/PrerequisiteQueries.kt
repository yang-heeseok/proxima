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
 * ## Why native SQL and not JPQL, QueryDSL, or an entity
 *
 * Three reasons, and only the first is about syntax.
 *
 * **JPQL has no recursive query.** `WITH RECURSIVE` is SQL:1999 and JPA does not surface it,
 * so the choice is native SQL or an application loop, and the whole of `docs/reports/R20` is
 * the measurement of what the application loop costs.
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
}
