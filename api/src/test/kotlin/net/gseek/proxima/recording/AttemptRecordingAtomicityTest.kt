package net.gseek.proxima.recording

import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same operation, observed from outside a transaction. **This one discriminates.**
 *
 * The single difference from `AttemptRecordingServiceTest` is the absence of
 * `@Transactional` on this class, and that absence is the entire experiment. Without it:
 *
 * - the service's writes are not swept up into a transaction the test opened,
 * - so they commit or roll back on their own terms,
 * - and a read afterwards sees what a *second request* would see, rather than what the
 *   caller's own uncommitted transaction happens to contain.
 *
 * That is the only vantage point from which a transaction boundary is observable at all.
 * The price is that cleanup becomes the test's own job, which is why `RecordingFixture`
 * exists and why it commits.
 *
 * **This test is expected to FAIL at the commit that introduces it.** It is the `red` half
 * of `T3` and it fails by finding one committed `attempt` row belonging to a unit of work
 * that raised. The `green` commit makes it pass without changing a line of this file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class AttemptRecordingAtomicityTest {

    @Autowired private lateinit var service: AttemptRecordingService
    @Autowired private lateinit var fixture: RecordingFixture

    @AfterEach
    fun cleanUp() = fixture.clear()

    @Test
    fun `a recording that fails on its second write leaves no attempt behind`() {
        val scene = fixture.scene()

        // `recordAll` returns outcomes rather than throwing since R14 -- a decision about the
        // BATCH. The unit of work is unchanged and so is what this test asserts: a recording
        // that failed leaves nothing behind. The rejection is checked first, because "no
        // attempt row" is also what a batch that never ran would leave.
        val outcomes = service.recordAll(
            scene.learnerId,
            listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
        )

        assertEquals(
            1, outcomes.size,
            "the batch did not report on the recording it was given: $outcomes",
        )
        assertTrue(
            outcomes.single() is RecordingOutcome.Rejected,
            "the recording was not rejected, so the assertions below are about a unit of " +
                "work that succeeded: $outcomes",
        )

        assertEquals(
            0L, fixture.countAttempts(),
            "an attempt was committed by a unit of work that failed. The attempt and the " +
                "mastery it updates are one unit -- a learner whose history and whose state " +
                "disagree is not reconciled by anything downstream",
        )

        // MOVED HERE FROM AttemptRecordingServiceTest BY R12, BECAUSE ONLY HERE IS IT A CLAIM.
        //
        //   The shipped recording path creates the mastery row with `on conflict do nothing`
        //   before deciding whether to move it. So during a failed unit of work a row exists,
        //   and after it a row does not -- and the difference between those two statements is
        //   a commit boundary, which is precisely what a test sharing a transaction with the
        //   code under test cannot see. That test asserted `null`, passed for years' worth of
        //   the wrong reason, and went red on a change that preserved the property.
        assertEquals(
            null, fixture.masteryOf(scene),
            "a mastery row survived a unit of work that failed. It is created before the " +
                "guarded update decides whether to move it, so this asserts the rollback " +
                "rather than the absence of a write -- R12 §3",
        )
    }
}
