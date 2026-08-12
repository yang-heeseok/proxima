package net.gseek.proxima.recommendation

import java.time.Instant
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import net.gseek.proxima.domain.Item

/**
 * The recommendation rule of `docs/explanation/domain-model.md`, as one statement.
 *
 * **Native SQL rather than JPQL or QueryDSL, and the reason is the shape of the rule.**
 * Step 2 — *every prerequisite of that concept is already mastered* — is a `NOT EXISTS`
 * over a join against a second copy of `mastery`, correlated to the outer row. JPQL
 * expresses that badly and QueryDSL expresses it verbosely, and neither buys anything here:
 * this query is fixed, not assembled from optional predicates, so the type-safe
 * construction `ADR-001` bought is not what this needs.
 *
 * **What is deliberately not implemented yet.** Step 4 of the rule filters to a difficulty
 * band matched to the learner's *recent accuracy*. Computing that means a second pass over
 * `attempt`, which is three million rows with **no index on `(learner_id, attempted_at)`**
 * — absent on purpose, see `ADR-002`, and the subject of `T4`. The band is passed in
 * instead. That is a real deviation from the documented rule and it is recorded here rather
 * than in a commit message, because the deviation belongs where someone reads the query.
 */
interface RecommendationQueries : Repository<Item, Long> {

    @Query(
        value = """
        with target_concepts as (
            select m.concept_id
              from mastery m
             where m.learner_id = :learnerId
               and m.score < 0.700
               and not exists (
                     select 1
                       from concept_edge e
                       left join mastery pm
                              on pm.concept_id = e.prerequisite_id
                             and pm.learner_id = :learnerId
                      where e.concept_id = m.concept_id
                        and (pm.score is null or pm.score < 0.700)
                   )
        )
        select i.id
          from item i
          join item_concept ic on ic.item_id = i.id
         where ic.concept_id in (select concept_id from target_concepts)
           and i.is_active
           and i.difficulty between :minDifficulty and :maxDifficulty
           and not exists (
                 select 1
                   from attempt a
                  where a.learner_id = :learnerId
                    and a.item_id = i.id
                    and a.attempted_at >= :notAttemptedSince
               )
         order by i.difficulty, i.id
         limit :limit
        """,
        nativeQuery = true,
    )
    fun findRecommendedItemIds(
        @Param("learnerId") learnerId: Long,
        @Param("minDifficulty") minDifficulty: Int,
        @Param("maxDifficulty") maxDifficulty: Int,
        @Param("notAttemptedSince") notAttemptedSince: Instant,
        @Param("limit") limit: Int,
    ): List<Long>

    /**
     * The same rule, returning **everything the response needs**, in one statement.
     *
     * The difference from [findRecommendedItemIds] is not the SQL — it is what the caller
     * is left holding. This returns values. Nothing it returns can trigger a database round
     * trip later, so the connection goes back to the pool when the transaction ends and
     * stays there while the request does whatever else it has to do.
     *
     * Aliases are **quoted** because PostgreSQL folds unquoted identifiers to lower case,
     * and the projection binds by property name.
     */
    @Query(
        value = """
        with target_concepts as (
            select m.concept_id
              from mastery m
             where m.learner_id = :learnerId
               and m.score < 0.700
               and not exists (
                     select 1
                       from concept_edge e
                       left join mastery pm
                              on pm.concept_id = e.prerequisite_id
                             and pm.learner_id = :learnerId
                      where e.concept_id = m.concept_id
                        and (pm.score is null or pm.score < 0.700)
                   )
        )
        select i.code       as "itemCode",
               c.name       as "conceptName",
               i.difficulty as "difficulty",
               i.id         as "itemId"
          from item i
          join concept c on c.id = i.concept_primary_id
          join item_concept ic on ic.item_id = i.id
         where ic.concept_id in (select concept_id from target_concepts)
           and i.is_active
           and i.difficulty between :minDifficulty and :maxDifficulty
           and not exists (
                 select 1
                   from attempt a
                  where a.learner_id = :learnerId
                    and a.item_id = i.id
                    and a.attempted_at >= :notAttemptedSince
               )
         order by i.difficulty, i.id
         limit :limit
        """,
        nativeQuery = true,
    )
    fun findRecommendations(
        @Param("learnerId") learnerId: Long,
        @Param("minDifficulty") minDifficulty: Int,
        @Param("maxDifficulty") maxDifficulty: Int,
        @Param("notAttemptedSince") notAttemptedSince: Instant,
        @Param("limit") limit: Int,
    ): List<RecommendationRow>
}

/** A row of the recommendation, fully materialised. Holds no reference to a session. */
interface RecommendationRow {
    val itemCode: String
    val conceptName: String
    val difficulty: Short
    val itemId: Long
}
