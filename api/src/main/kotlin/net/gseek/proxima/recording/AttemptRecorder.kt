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
 * ## What this still does not solve
 *
 * `AttemptRecordingService.recordAll` stops at the first failure, so a batch may be
 * partially recorded and the caller is not told which recordings landed. That is a
 * consequence of choosing per-recording atomicity and it is **not fixed here** — it is a
 * different decision (report per-item outcomes, or make the batch the unit) and it needs a
 * requirement, not a refactor. Recorded in the report's remaining-risk section rather than
 * quietly resolved.
 */
@Service
class AttemptRecorder(
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
    private val items: ItemRepository,
    private val attempts: AttemptRepository,
    private val masteries: MasteryRepository,
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
