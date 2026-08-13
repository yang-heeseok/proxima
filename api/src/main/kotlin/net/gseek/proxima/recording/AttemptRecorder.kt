package net.gseek.proxima.recording

import net.gseek.proxima.domain.Attempt
import net.gseek.proxima.domain.AttemptRepository
import net.gseek.proxima.domain.ConceptRepository
import net.gseek.proxima.domain.ItemRepository
import net.gseek.proxima.domain.Learner
import net.gseek.proxima.domain.LearnerRepository
import net.gseek.proxima.domain.Mastery
import net.gseek.proxima.domain.MasteryRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * One recording: the attempt, and the mastery it moves. **This class is the unit of work.**
 *
 * ## Why this is a separate bean, and not `self` injected into the caller
 *
 * The defect this replaces was a `@Transactional` method invoked from a loop in the same
 * class, so the call never reached the proxy and the annotation was inert
 * (`21e7162`). Two things fix the symptom:
 *
 * 1. inject the service into itself and call `self.record(…)`, or reach the proxy through
 *    `AopContext.currentProxy()`;
 * 2. move the boundary onto a bean the caller has to go through.
 *
 * **Only the second is a fix.** The first leaves a class in which `record(…)` and
 * `self.record(…)` mean different things while looking identical, and the next person to
 * write the obvious one silently reintroduces the defect. A correctness property that
 * depends on everyone remembering an unusual call syntax is not a property.
 *
 * The second is also the answer to the question the defect was really asking, which is not
 * *how do I reach the proxy* but **what is the unit of work.** Here it is one recording,
 * not the batch: attempts are independent events, and one learner's invalid submission is
 * not a reason to discard the valid ones recorded beside it. That is a domain decision, and
 * once it is made the boundary has an obvious home — this class — and the proxy question
 * disappears rather than being worked around.
 *
 * ## What this used to say it did not solve
 *
 * > `AttemptRecordingService.recordAll` stops at the first failure, so a batch may be
 * > partially recorded and the caller is not told which recordings landed. […] it needs a
 * > requirement, not a refactor.
 *
 * **That paragraph contradicted the one above it, in this file, for four days.** The
 * paragraph above says a learner's invalid submission must not discard the valid recordings
 * beside it; the loop discarded them, by never attempting them.
 *
 * *"It needs a requirement, not a refactor"* was true about **which shape to choose** and was
 * used as a reason not to measure what was happening. `R14` measured it: of four valid
 * recordings in a batch of five, **two landed.** Fixed by `proxima.recording.batch:
 * per-item-outcomes` — every recording attempted, every outcome returned. The unit of work
 * here is unchanged.
 */
@Service
class AttemptRecorder(
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
    private val items: ItemRepository,
    private val attempts: AttemptRepository,
    private val masteries: MasteryRepository,
    private val queries: RecordingQueries,
    /**
     * **Which arm of `R12` is in force.** Both live in one binary — `R4` §2's argument.
     *
     * | value | behaviour |
     * | --- | --- |
     * | `read-modify-write` | read the row, mutate it, save it. **`red`** — the arm `R6` measured as second-worst, and `R7`'s check-then-insert on top of it |
     * | `atomic-guarded` | ensure the row, then one statement carrying the rule as a predicate |
     */
    @Value("\${proxima.recording.mastery-update:atomic-guarded}")
    private val masteryUpdate: String,
) {

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

        when (masteryUpdate) {
            "read-modify-write" -> viaEntity(learnerId, recording, learner)
            "atomic-guarded" -> viaGuardedStatement(learnerId, recording)
            else -> error("unknown proxima.recording.mastery-update: $masteryUpdate")
        }
    }

    /**
     * `red`. Two measured defects in five lines, and neither is visible in a code review of
     * the lines themselves.
     *
     * `findByLearnerIdAndConceptId(...) ?: Mastery(...)` is `R7`'s check-then-insert: two
     * requests both find nothing and both insert. Since `V3` that raises instead of
     * duplicating, which is better and is still a failure.
     *
     * The read, the mutate and the save are `R6`'s `entity + @Version` arm: **1,000
     * increments on one row kept 180 and rejected 820.**
     */
    private fun viaEntity(learnerId: Long, recording: Recording, learner: Learner) {
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

    /**
     * `green`. `R7`'s upsert, then `R6`'s atomic statement with the business rule inside its
     * `WHERE` clause.
     *
     * **The failure path is the interesting part.** `applyRecording` returning `0` is not an
     * error the database raised — no constraint was violated and the transaction is intact —
     * so the row can still be read to say what the score would have become. A rule expressed
     * as `ck_mastery_score` could not do that: `R1` §9 measured PostgreSQL aborting the whole
     * transaction on a constraint violation, after which every read fails for an unrelated
     * reason.
     */
    private fun viaGuardedStatement(learnerId: Long, recording: Recording) {
        queries.ensureExists(learnerId, recording.conceptId, recording.at)

        val applied = queries.applyRecording(
            learnerId = learnerId,
            conceptId = recording.conceptId,
            delta = recording.scoreDelta,
            at = recording.at,
        )

        if (applied != 1) {
            val current = masteries.findByLearnerIdAndConceptId(learnerId, recording.conceptId)
            throw IllegalArgumentException(
                "mastery score would reach ${current?.score?.add(recording.scoreDelta)}, " +
                    "which is outside the 0..1 band",
            )
        }
    }
}
