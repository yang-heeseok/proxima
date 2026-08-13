package net.gseek.proxima.recording

import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **This is the test that proves nothing, and it is green.**
 *
 * It is written the way this kind of test is ordinarily written, and every choice in it is
 * defensible in isolation:
 *
 * - `@Transactional` on the class, so each test rolls back and leaves the database clean.
 *   This is the standard Spring testing idiom and it is recommended everywhere.
 * - It drives the service through its real entry point.
 * - It asserts that the failure surfaces, and it asserts on the state afterwards.
 *
 * It reads like a test of atomicity. It is not one. What it actually asserts is that
 * **code placed after a throw does not run** — which is true of any language and has
 * nothing to do with `@Transactional`.
 *
 * The write that actually leaks is the `attempt` row, and this test never looks at it.
 * Even if it did, the class-level `@Transactional` binds a transaction to the thread for
 * the whole test, so the service's writes join it and are visible to every read this test
 * makes, whether or not the service has a transaction of its own. **A test that shares a
 * transaction with the code it is testing cannot observe that code's transaction
 * boundaries.**
 *
 * The proof that it proves nothing is in `docs/reports/` and is reproducible in one step:
 * delete `@Transactional` from `AttemptRecordingService.record` and run this class again.
 * It stays green. A test that gives the same answer whether or not the thing it is testing
 * exists is not evidence about that thing.
 *
 * See `AttemptRecordingAtomicityTest` for the test that does discriminate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
@Transactional
class AttemptRecordingServiceTest {

    @Autowired private lateinit var service: AttemptRecordingService
    @Autowired private lateinit var masteries: MasteryRepository
    @Autowired private lateinit var fixture: RecordingFixture

    @Test
    fun `a recording that violates the score bound is rejected`() {
        val scene = fixture.scene()

        // Since R14 the batch reports rather than throws. The rejection itself is unchanged
        // -- AttemptRecorder.record still raises; recordAll is what stopped propagating it.
        val outcomes = service.recordAll(
            scene.learnerId,
            listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
        )

        val rejection = outcomes.single()
        assertTrue(rejection is RecordingOutcome.Rejected, "the recording was accepted: $outcomes")
        assertTrue(
            rejection.reason.contains("IllegalArgumentException"),
            "rejected for the wrong reason, so this passes on an accident: ${rejection.reason}",
        )
    }

    /**
     * **This assertion used to read `mastery == null`, and `R12` turned it red without
     * breaking anything.** It is the sharpest demonstration this class has of its own thesis,
     * so it is kept rather than repaired.
     *
     * The old assertion was never evidence about atomicity. It passed because the
     * read-modify-write arm **never wrote a row** on the failure path — the statement that
     * would have written it was the statement that threw. "No trace of a failed unit of work"
     * and "nothing got as far as writing" produce the same `null`, and the test could not tell
     * them apart.
     *
     * The shipped arm creates the row with `on conflict do nothing` and only then declines to
     * move it. From **inside the caller's own transaction** — which is where this class reads,
     * because `@Transactional` on the class binds one — that row is visible. From outside,
     * after the rollback, it is not, and the domain property is exactly as true as it ever
     * was: `AttemptRecordingAtomicityTest` asserts it there.
     *
     * So a change that preserved the property broke the test that claimed to check it. **A
     * test that shares a transaction with the code under test does not merely fail to observe
     * that code's boundaries — it can report their consequences backwards.**
     */
    @Test
    fun `what this test can see after a failed recording, and why that is not the property`() {
        val scene = fixture.scene()

        val outcomes = service.recordAll(
            scene.learnerId,
            listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
        )
        assertTrue(outcomes.single() is RecordingOutcome.Rejected, "the recording was accepted: $outcomes")

        val insideThisTransaction =
            masteries.findByLearnerIdAndConceptId(scene.learnerId, scene.conceptId)

        assertEquals(
            BigDecimal("0.000"), insideThisTransaction?.score,
            "the row exists and has not been moved -- if it had been, the guard did not " +
                "refuse and this recording should not have thrown",
        )
        assertEquals(
            0, insideThisTransaction?.attemptsCount,
            "the counter moved on a recording that failed",
        )
    }
}
