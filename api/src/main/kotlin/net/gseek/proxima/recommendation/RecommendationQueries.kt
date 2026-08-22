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
 * **What is deliberately not implemented yet — and the reason first recorded here expired
 * ten days before anything noticed.** Step 4 of the rule filters to a difficulty band matched
 * to the learner's *recent accuracy*. The reason written here was the cost of a second pass
 * over `attempt`, three million rows whose only index at the time was its primary key.
 * `V2__attempt_learner_time_index.sql` created `(learner_id, attempted_at)` on 2026-08-12 and
 * `R3` measured that read at **0.056 ms against 36.6 ms** without it. The justification was
 * spent from that day, and this sentence went on giving it until 2026-08-22 — because
 * nothing here read a KDoc until `docs-consistency.yml` CHECK 5 did.
 *
 * The band is passed in instead, and that is still a real deviation from the documented rule.
 * **What blocks step 4 now is a decision rather than a cost:** *recent* can mean a count of
 * attempts or a span of days, and those are different questions that disagree on this
 * dataset. Recorded here rather than in a commit message, because the deviation belongs
 * where someone reads the query.
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

    /**
     * **Step 4 read as *the last `n` attempts*** — fixes the sample size, lets the period it
     * spans vary with whatever cadence the learner happens to have.
     *
     * One learner, newest first, small limit: the exact query shape `R3` measured `V2`'s
     * index against. The outcomes are aggregated in the application rather than in SQL
     * because the row count is bounded by `n` and returning them keeps the caller able to
     * say **how many** the band came off, which `RecentAccuracy.Evidence` requires.
     *
     * **`a.id desc` is a tie-break and it is load-bearing.** `attempted_at` is not unique —
     * nothing in the schema makes it so — and `ORDER BY` on a non-unique key leaves tied
     * rows in an order the database is entitled to change between two runs of the same
     * statement. Without this the twentieth row is whichever of the tied ones the plan
     * happened to reach, so the band would wobble with the plan. `R44` §3 measures what the
     * tie-break costs the index; the general form of this defect belongs to slice G.
     */
    @Query(
        value = """
        select a.correct
          from attempt a
         where a.learner_id = :learnerId
         order by a.attempted_at desc, a.id desc
         limit :n
        """,
        nativeQuery = true,
    )
    fun recentOutcomesByCount(
        @Param("learnerId") learnerId: Long,
        @Param("n") n: Int,
    ): List<Boolean>

    /**
     * **Step 4 read as *the last `d` days*** — fixes the period, lets the sample size vary.
     *
     * Aggregated in SQL because the row count is **not** bounded here: it is however many
     * attempts that learner made in the window, which for a heavy learner on this dataset is
     * in the hundreds. Returning the rows to count them in the application would make the
     * cost of this definition a function of the learner's activity, which is precisely the
     * asymmetry between the two readings that `ADR-021` is deciding about.
     *
     * `count(*) filter (where …)` rather than `sum(case …)`: same plan, and it is the form
     * that says what it means.
     *
     * ⚠️ **`since` is supplied by the caller and is not `now() - d`.** Whose clock, and
     * whose midnight, is a separate question this method deliberately does not answer — see
     * `RecommendationService` and `R45`.
     */
    @Query(
        value = """
        select count(*)                          as "attempts",
               count(*) filter (where a.correct)  as "correct"
          from attempt a
         where a.learner_id = :learnerId
           and a.attempted_at >= :since
        """,
        nativeQuery = true,
    )
    fun recentOutcomeCountsSince(
        @Param("learnerId") learnerId: Long,
        @Param("since") since: Instant,
    ): OutcomeCounts
}

/**
 * The two numbers a band is computed from, kept together.
 *
 * `attempts` is not a diagnostic. A band off three attempts and a band off three hundred are
 * different claims and this is what carries the difference to the caller.
 */
interface OutcomeCounts {
    val attempts: Long
    val correct: Long
}

/** A row of the recommendation, fully materialised. Holds no reference to a session. */
interface RecommendationRow {
    val itemCode: String
    val conceptName: String
    val difficulty: Short
    val itemId: Long
}
