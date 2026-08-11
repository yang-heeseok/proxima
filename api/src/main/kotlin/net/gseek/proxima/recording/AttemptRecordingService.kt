package net.gseek.proxima.recording

import org.springframework.stereotype.Service
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
 * Records batches of attempts.
 *
 * **This class holds no transaction and no `@Transactional`, deliberately.** It
 * orchestrates; the unit of work is [AttemptRecorder.record], and it is reached through a
 * bean, so the call crosses the proxy and the boundary exists. See [AttemptRecorder] for
 * why the fix is a separate bean rather than a self-injection.
 *
 * The loop below is identical to the one that was broken at `21e7162`. **That is the point
 * of the fix:** the call site did not have to learn an unusual syntax to become correct —
 * the boundary moved to where the unit of work actually is.
 */
@Service
class AttemptRecordingService(
    private val recorder: AttemptRecorder,
) {

    /**
     * Records each recording as its own unit of work.
     *
     * **Stops at the first failure**, so a batch may be partially recorded and the caller
     * is told only that something failed, not which recordings landed. That follows from
     * choosing per-recording atomicity and is a known gap rather than an oversight — see
     * the remaining-risk section of the `T3` report.
     */
    fun recordAll(learnerId: Long, recordings: List<Recording>) {
        recordings.forEach { recorder.record(learnerId, it) }
    }
}
