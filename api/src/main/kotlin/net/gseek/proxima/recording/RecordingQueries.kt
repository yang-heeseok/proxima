package net.gseek.proxima.recording

import java.math.BigDecimal
import java.time.Instant
import net.gseek.proxima.domain.Mastery
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

/**
 * The two statements one recording needs, both of them arms that `R6` and `R7` already
 * measured as winning.
 *
 * ## Why this exists rather than a read, a mutate, and a save
 *
 * `R6` §8 recorded why `AttemptRecorder` kept the read-modify-write arm that rejected 82% of
 * writes under contention: **`score` carries a business rule** — the result must stay inside
 * the `0..1` band — **and an atomic statement cannot express a rule.** Pushing the rule down
 * to `V1`'s `ck_mastery_score` would turn a violation into a constraint error, and `R1` §9
 * measured what that does on PostgreSQL: the whole transaction is aborted and everything
 * after it fails for a reason that has nothing to do with the cause.
 *
 * **That reasoning had a gap. A rule does not have to be a constraint — it can be a
 * predicate.** `and score + :delta between 0 and 1.000` in the `WHERE` clause means a
 * recording that would leave the band simply **matches no row**. Nothing is raised, nothing
 * is aborted, and the caller gets `0` back and decides what it means. The transaction is
 * still healthy afterwards, which is why [applyRecording]'s caller can read the row to say
 * what the score would have become.
 */
interface RecordingQueries : Repository<Mastery, Long> {

    /**
     * `R7`'s winning arm. Creates the row if it is not there, does nothing if it is.
     *
     * `on conflict` **requires** `V3`'s unique constraint rather than replacing it — `R7`
     * §3 measured every `on conflict do nothing` failing against `V1`, all eight of them.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
            "values (:learnerId, :conceptId, 0.000, 0, 0, :at) " +
            "on conflict (learner_id, concept_id) do nothing",
        nativeQuery = true,
    )
    fun ensureExists(
        @Param("learnerId") learnerId: Long,
        @Param("conceptId") conceptId: Long,
        @Param("at") at: Instant,
    ): Int

    /**
     * `R6`'s winning arm, carrying the business rule as a predicate.
     *
     * **`version` is incremented by hand.** The column is mapped `@Version`, so JPA maintains
     * it on the paths that go through the entity; a native statement that changed the row and
     * left the version alone would leave the optimistic-locking column lying about whether the
     * row had moved. Nothing in the application currently reads it that way — and *currently*
     * is exactly the word that makes it worth keeping honest.
     *
     * @return rows updated. **`1` means applied; `0` means the row is missing or the band
     *   would be left.** The caller distinguishes those, and can, because no error was raised.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "update mastery " +
            "   set attempts_count = attempts_count + 1, " +
            "       score          = score + :delta, " +
            "       version        = version + 1, " +
            "       updated_at     = :at " +
            " where learner_id = :learnerId " +
            "   and concept_id = :conceptId " +
            "   and score + :delta between 0 and 1.000",
        nativeQuery = true,
    )
    fun applyRecording(
        @Param("learnerId") learnerId: Long,
        @Param("conceptId") conceptId: Long,
        @Param("delta") delta: BigDecimal,
        @Param("at") at: Instant,
    ): Int
}
