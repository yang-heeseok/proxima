package net.gseek.proxima.recording

import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertEquals

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

        assertThrows<IllegalArgumentException> {
            service.recordAll(
                scene.learnerId,
                listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
            )
        }
    }

    @Test
    fun `mastery is left untouched when the recording fails`() {
        val scene = fixture.scene()

        assertThrows<IllegalArgumentException> {
            service.recordAll(
                scene.learnerId,
                listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
            )
        }

        // Reads as "the failed unit of work left no trace". It is not that. The mastery row
        // was never written because the statement that would have written it is the one
        // that failed -- so this assertion holds with or without a transaction.
        assertEquals(
            null,
            masteries.findByLearnerIdAndConceptId(scene.learnerId, scene.conceptId),
            "mastery must not exist after a failed recording",
        )
    }
}
