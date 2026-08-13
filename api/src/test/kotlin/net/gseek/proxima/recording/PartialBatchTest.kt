package net.gseek.proxima.recording

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Shared by both arms of `R14`: **five recordings, the third of them invalid.**
 *
 * Deltas of `0.100` with a `1.500` in the middle. The bad one is rejected by the score guard
 * `R12` installed — `0.200 + 1.500` leaves the `0..1` band — so it is a domain rejection and
 * not an infrastructure failure, which is the case a batch API has to be right about.
 */
internal object PartialBatch {
    val DELTAS = listOf("0.100", "0.100", "1.500", "0.100", "0.100")
    const val VALID = 4

    class Observed(
        val outcome: String,
        val attemptRows: Long,
        val attemptsCount: Int,
        val score: BigDecimal,
    ) {
        override fun toString() =
            "caller saw %-58s attempt rows %d  attempts_count %d  score %s"
                .format(outcome, attemptRows, attemptsCount, score.toPlainString())
    }
}

/**
 * `R14`, `red` — **what a caller learns when the third of five recordings is rejected.**
 *
 * `AttemptRecorder`'s KDoc has said since `T3` that the unit of work is one recording, because
 * *attempts are independent events, and one learner's invalid submission is not a reason to
 * discard the valid ones recorded beside it.* The loop then discarded them anyway: the third
 * recording's exception leaves the fourth and fifth unattempted.
 *
 * **That was read off the code and never observed.** `R12` is what makes that distinction
 * worth acting on — `R6` §8's three true premises supported a false conclusion and stood for
 * three days because nobody tested them. This measures the claim instead of repeating it.
 */
@SpringBootTest(properties = ["proxima.recording.batch=stop-at-first-failure"])
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class PartialBatchTest {

    @Autowired private lateinit var service: AttemptRecordingService
    @Autowired private lateinit var fixture: RecordingFixture
    @Autowired private lateinit var masteries: MasteryRepository

    @AfterEach
    fun cleanUp() = fixture.clear()

    @Test
    fun `a batch that stops at the first failure`() {
        val scene = fixture.scene()
        val recordings = PartialBatch.DELTAS.map { fixture.recording(scene, BigDecimal(it)) }

        val outcome = try {
            service.recordAll(scene.learnerId, recordings).toString()
        } catch (e: Exception) {
            "threw ${e.javaClass.simpleName}, and nothing about which landed"
        }

        val mastery = fixture.masteryOf(scene)
        val observed = PartialBatch.Observed(
            outcome = outcome,
            attemptRows = fixture.countAttempts(),
            attemptsCount = mastery?.attemptsCount ?: 0,
            score = mastery?.score ?: BigDecimal.ZERO,
        )

        println()
        println("R14-RED >>> proxima.recording.batch = stop-at-first-failure")
        println("  5 recordings, deltas ${PartialBatch.DELTAS}, the third one invalid")
        println("  $observed")
        println()

        assertTrue(
            observed.attemptRows < PartialBatch.VALID,
            "every valid recording landed, so this is no longer the red arm: $observed",
        )
    }
}

/**
 * `R14`, `green` and its regression gate — **every recording is attempted, and the caller is
 * told which ones landed.**
 *
 * ## What this does not change
 *
 * The per-recording transaction. `AttemptRecorder.record` still throws, still runs in its own
 * transaction, and a rejected recording still leaves nothing behind — `T3`'s property, still
 * asserted by `AttemptRecordingAtomicityTest`. Continuing past a rejection is a decision about
 * the **batch**, not about the unit of work.
 *
 * ## No property override
 *
 * Same annotations as `AttemptRecordingAtomicityTest`, so Spring's context cache serves both
 * and the gate asserts **what production is configured to do**.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class PartialBatchGateTest {

    @Autowired private lateinit var service: AttemptRecordingService
    @Autowired private lateinit var fixture: RecordingFixture

    @AfterEach
    fun cleanUp() = fixture.clear()

    @Test
    fun `a rejected recording does not discard the valid ones after it`() {
        val scene = fixture.scene()
        val recordings = PartialBatch.DELTAS.map { fixture.recording(scene, BigDecimal(it)) }

        val outcomes = service.recordAll(scene.learnerId, recordings)
        val mastery = fixture.masteryOf(scene)
        val observed = PartialBatch.Observed(
            outcome = outcomes.joinToString { if (it is RecordingOutcome.Recorded) "ok" else "rejected" },
            attemptRows = fixture.countAttempts(),
            attemptsCount = mastery?.attemptsCount ?: 0,
            score = mastery?.score ?: BigDecimal.ZERO,
        )

        println()
        println("R14-GREEN >>> the shipped configuration")
        println("  5 recordings, deltas ${PartialBatch.DELTAS}, the third one invalid")
        println("  $observed")
        outcomes.filterIsInstance<RecordingOutcome.Rejected>()
            .forEach { println("    index ${it.index}: ${it.reason}") }
        println()

        assertEquals(
            PartialBatch.DELTAS.size, outcomes.size,
            "the caller was not told about every recording it submitted: $observed",
        )
        assertEquals(
            PartialBatch.VALID, outcomes.count { it is RecordingOutcome.Recorded },
            "a valid recording was discarded because a different one was rejected. " +
                "AttemptRecorder's KDoc has said since T3 that attempts are independent " +
                "events -- see docs/reports/R14. Outcomes: $observed",
        )
        assertEquals(
            listOf(2), outcomes.filterIsInstance<RecordingOutcome.Rejected>().map { it.index },
            "the wrong recording was rejected, so this passed for the wrong reason: $observed",
        )
        assertEquals(
            PartialBatch.VALID.toLong(), observed.attemptRows,
            "the attempt rows and the outcomes disagree: $observed",
        )
        assertEquals(
            BigDecimal("0.400"), observed.score,
            "four recordings of 0.100 should leave the score at 0.400: $observed",
        )
    }
}
