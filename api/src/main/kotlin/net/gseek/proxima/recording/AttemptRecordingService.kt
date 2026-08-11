package net.gseek.proxima.recording

import net.gseek.proxima.domain.Attempt
import net.gseek.proxima.domain.AttemptRepository
import net.gseek.proxima.domain.ConceptRepository
import net.gseek.proxima.domain.ItemRepository
import net.gseek.proxima.domain.LearnerRepository
import net.gseek.proxima.domain.Mastery
import net.gseek.proxima.domain.MasteryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/** One learner meeting one item once, and what it does to their mastery of a concept. */
data class Recording(
    val itemId: Long,
    val conceptId: Long,
    val correct: Boolean,
    val elapsedMs: Int,
    val at: Instant,
    val scoreDelta: BigDecimal,
)

/**
 * Records attempts and updates the mastery each one affects.
 *
 * **This class is the `red` state of `T3`. Do not fix it here — the fix is a separate
 * commit, and the pair is the evidence.**
 *
 * Every recording writes twice: an `attempt` row, then the learner's `mastery` row. The two
 * writes are one unit — an attempt that was counted but whose mastery was not updated is a
 * learner whose history and whose state disagree, and nothing in the system reconciles them
 * afterwards. `record` is annotated `@Transactional` to say exactly that.
 *
 * The annotation does nothing. `recordAll` calls `record` through `this`, so the call never
 * crosses the proxy Spring installed, and `@Transactional` on the far side of a call that
 * does not reach the proxy is a comment. Each `save` then runs in the transaction Spring
 * Data opens for it, and commits on its own.
 *
 * The failure used to demonstrate this is a **business rule**, not a database constraint,
 * and that choice was made after trying the other one.
 *
 * `mastery.score` carries a `CHECK` of `0 <= score <= 1` in `V1`, so pushing a learner past
 * 1.000 is rejected by the database — which looks like the more honest failure to use,
 * because nothing is injected. It was tried first and it drags a second, unrelated
 * mechanism into `T3`: on PostgreSQL a constraint violation **aborts the whole
 * transaction**, so every read after it fails too, with
 * `org.hibernate.AssertionFailure: Entry for instance of 'Mastery' has a null identifier`
 * rather than anything about transactions. That behaviour is real and it is `T6`'s subject.
 * Using it here would have measured two things at once and reported one number.
 *
 * So the second write fails on a rule the application checks. The first write has already
 * committed by then, which is the only thing this class is about.
 */
@Service
class AttemptRecordingService(
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
    private val items: ItemRepository,
    private val attempts: AttemptRepository,
    private val masteries: MasteryRepository,
) {

    /**
     * Records a batch, each recording atomic on its own.
     *
     * That is what this method intends and it is not what it does. The loop calls `record`
     * directly, which is the ordinary way anyone writes a loop and the reason this defect
     * is so easy to introduce.
     */
    fun recordAll(learnerId: Long, recordings: List<Recording>) {
        recordings.forEach { record(learnerId, it) }
    }

    @Transactional
    fun record(learnerId: Long, recording: Recording) {
        val learner = learners.getReferenceById(learnerId)
        val item = items.getReferenceById(recording.itemId)

        attempts.save(
            Attempt(
                learner = learner,
                item = item,
                correct = recording.correct,
                elapsedMs = recording.elapsedMs,
                attemptedAt = recording.at,
            ),
        )

        val mastery = masteries.findByLearnerIdAndConceptId(learnerId, recording.conceptId)
            ?: Mastery(learner = learner, concept = concepts.getReferenceById(recording.conceptId))

        val updated = mastery.score.add(recording.scoreDelta)
        require(updated <= BigDecimal.ONE) {
            "mastery score would reach $updated, which is outside the 0..1 band"
        }

        mastery.attemptsCount += 1
        mastery.score = updated
        mastery.updatedAt = recording.at
        masteries.save(mastery)
    }
}
