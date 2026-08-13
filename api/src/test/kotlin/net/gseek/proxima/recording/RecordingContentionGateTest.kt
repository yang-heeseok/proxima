package net.gseek.proxima.recording

import java.math.BigDecimal
import kotlin.test.assertEquals
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * `R12`, `green` and its regression gate — **the shipped recording path loses nothing under
 * contention.**
 *
 * ## Why the assertions are exact
 *
 * A thousand recordings, each adding `0.001`, on one row. If every one of them lands the
 * score is **exactly `1.000`** and `attempts_count` is **exactly `1000`**. There is no
 * tolerance to argue about and no timing to be flaky about: either the database applied a
 * thousand increments or it did not.
 *
 * That last increment is also the boundary. `0.999 + 0.001 <= 1.000` matches; a
 * thousand-and-first recording would match nothing and be refused. The gate therefore checks
 * the band's edge as a side effect of checking the count, which is worth more than a separate
 * test asserting the edge in isolation would be.
 *
 * ## Why `attempt` rows are counted too
 *
 * `attempts_count` reaching 1000 while only 900 `attempt` rows exist would mean the two
 * halves of a recording had come apart — the thing `AttemptRecordingAtomicityTest` guards for
 * one failing recording, asserted here for a thousand concurrent successful ones.
 *
 * ## No property override, deliberately
 *
 * Same annotations as `AttemptRecordingAtomicityTest`, so Spring's context cache serves both
 * from one application context, and what is asserted is **what production is configured to
 * do** rather than what a test asked for.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class RecordingContentionGateTest {

    @Autowired private lateinit var recorder: AttemptRecorder
    @Autowired private lateinit var fixture: RecordingFixture
    @Autowired private lateinit var masteries: MasteryRepository

    @AfterEach
    fun cleanUp() = fixture.clear()

    @Test
    fun `the shipped recording path applies every concurrent recording exactly once`() {
        val expected = Contention.THREADS * Contention.PER_THREAD
        val outcome = Contention.run(recorder, fixture, masteries)

        println()
        println("R12-GREEN >>> the shipped configuration")
        println("  ${Contention.THREADS} threads x ${Contention.PER_THREAD} recordings, one (learner, concept)")
        println("  $outcome")
        println()

        assertEquals(
            emptyMap(), outcome.failures,
            "the shipped recording path rejected work under contention. R12 measured zero " +
                "rejections; if proxima.recording.mastery-update has been moved back to " +
                "`read-modify-write`, R6 §3 is what that costs. Outcome: $outcome",
        )
        assertEquals(
            expected, outcome.attemptsCount,
            "increments were lost. This is the defect R6 measured at 864 lost out of 1,000 " +
                "with nothing reported anywhere. Outcome: $outcome",
        )
        assertEquals(
            BigDecimal("1.000"), outcome.score,
            "the score did not land on the band's edge, so either an increment was lost or " +
                "the guard is not the one R12 measured. Outcome: $outcome",
        )
        assertEquals(
            expected.toLong(), outcome.attemptRows,
            "the attempt rows and the mastery counter disagree, so the two halves of a " +
                "recording are no longer one unit of work. Outcome: $outcome",
        )
    }
}
