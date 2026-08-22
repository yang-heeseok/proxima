package net.gseek.proxima.recording

import java.math.BigDecimal
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

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
 * What became of one recording in a batch.
 *
 * **There is no `NotAttempted`, and its absence is the finding.** A third state was drafted
 * for *"the batch stopped before reaching this one"* and then removed, because under the `red`
 * arm no list is returned at all — the caller gets an exception and nothing else. The
 * recordings that were never attempted have no representation because **there was nothing to
 * represent them in.** That is the defect, stated as a type.
 */
sealed interface RecordingOutcome {
    val index: Int

    data class Recorded(override val index: Int) : RecordingOutcome
    data class Rejected(override val index: Int, val reason: String) : RecordingOutcome
}

/**
 * Records batches of attempts.
 *
 * **This class holds no transaction and no `@Transactional`, deliberately.** It
 * orchestrates; the unit of work is [AttemptRecorder.record], and it is reached through a
 * bean, so the call crosses the proxy and the boundary exists. See [AttemptRecorder] for
 * why the fix is a separate bean rather than a self-injection.
 *
 * ## A second property was riding on that same absence, and it was never written down
 *
 * The paragraph above gives `R1`'s reason — *where a boundary belongs*. There was a second,
 * unstated one: the `per-item-outcomes` loop below **catches** a rejection and continues, and
 * that is only safe while no transaction spans the batch.
 *
 * **The absence of an annotation on this class was never what made it safe**, because the
 * annotation can also arrive on a *caller*. Any `@Transactional` service that calls
 * [recordAll] — an ordinary thing to write — used to turn every rejection into total loss:
 * the inner recording marked the shared transaction rollback-only, this loop caught the
 * exception and built its outcome list, and the caller's commit discarded the list and every
 * recording in it. `R40` §3.4 measured it at **four valid recordings, zero rows**.
 *
 * ⭐ **That is fixed on [AttemptRecorder.record] rather than here**, by `REQUIRES_NEW`, so the
 * unit of work is one recording regardless of who calls it. `ADR-020` records the decision and
 * what it gives up. This class is unchanged; the sentence is here because the property it
 * depended on was invisible.
 *
 * The loop below is identical to the one that was broken at `21e7162`. **That is the point
 * of the fix:** the call site did not have to learn an unusual syntax to become correct —
 * the boundary moved to where the unit of work actually is.
 */
@Service
class AttemptRecordingService(
    private val recorder: AttemptRecorder,
    /**
     * Which arm of `R14` is in force. Both live in one binary — `R4` §2's argument.
     *
     * | value | behaviour |
     * | --- | --- |
     * | `stop-at-first-failure` | the first rejection propagates; everything after it is never attempted, and the caller is told only that *something* failed. **`red`** |
     * | `per-item-outcomes` | every recording is attempted and its result returned |
     */
    @Value("\${proxima.recording.batch:per-item-outcomes}")
    private val batch: String,
) {

    /**
     * Records each recording as its own unit of work, and says what happened to each.
     *
     * **Continuing past a rejection is not a softening of the rule — it is the rule.**
     * `AttemptRecorder`'s KDoc has said since `T3` that the unit of work is one recording
     * because *attempts are independent events, and one learner's invalid submission is not a
     * reason to discard the valid ones recorded beside it.* The `red` arm below discards them
     * anyway: it never attempts recordings four and five because recording three was bad.
     * **The stated domain decision and the shipped loop disagreed, and `R14` §3 is the
     * measurement of by how much.**
     *
     * The per-recording boundary is untouched. `AttemptRecorder.record` still throws, still
     * runs in its own transaction, and a rejected recording still leaves nothing behind —
     * `AttemptRecordingAtomicityTest` asserts exactly that, and this method's contract does
     * not weaken it.
     */
    fun recordAll(learnerId: Long, recordings: List<Recording>): List<RecordingOutcome> =
        when (batch) {
            // The shipped loop from `21e7162` until R14. The first rejection propagates out
            // of this method, so the list below is only ever reached when nothing failed --
            // which is why the caller of a partially applied batch learns nothing about it.
            "stop-at-first-failure" -> {
                recordings.forEach { recorder.record(learnerId, it) }
                recordings.indices.map { RecordingOutcome.Recorded(it) }
            }

            "per-item-outcomes" ->
                recordings.mapIndexed { i, r ->
                    try {
                        recorder.record(learnerId, r)
                        RecordingOutcome.Recorded(i)
                    } catch (e: Exception) {
                        // Deliberately broad. A recording rejected by the score guard, by a
                        // constraint, or by a lock timeout are the same event to a caller
                        // holding a batch: this one did not land, the others still can.
                        // The reason is carried rather than flattened.
                        RecordingOutcome.Rejected(i, e.javaClass.simpleName + ": " + e.message?.lineSequence()?.first())
                    }
                }

            else -> error("unknown proxima.recording.batch: $batch")
        }
}
