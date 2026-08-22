package net.gseek.proxima.recording

import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.assertNull
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
     * **This assertion has now read three different things, and all three were true.** It is the
     * sharpest demonstration this class has of its own thesis, so it is kept and strengthened
     * rather than repaired.
     *
     * | when | the row read | because |
     * | --- | --- | --- |
     * | pre-`R12` | `null` | the read-modify-write arm **never wrote a row at all** — the statement that would have written it was the statement that threw |
     * | `R12` → `022675b` | `0.000` | the shipped arm writes with `on conflict do nothing` and only then declines to move it, and `record` **joined this test's transaction**, so the row was visible from in here |
     * | after `022675b` | `null` | `ADR-020` made `record` `REQUIRES_NEW`, so it owns a transaction that **rolls back**, and the row never becomes visible to this reader |
     *
     * ⛔ **The current `null` is NOT a return to the pre-`R12` state.** It is a third state that
     * happens to print the same way. *"Nothing was ever written"* and *"something was written and
     * then rolled back"* are different facts about the system and **this row cannot tell them
     * apart** — which is exactly what `R12` wrote this class to demonstrate, arriving a second
     * time from a change `R12` never contemplated.
     *
     * ## Why the row is the wrong observable, and what is asserted instead
     *
     * Naming three causes in a comment **documents** the ambiguity; it does not remove it. A
     * fourth mechanism that also produces `null` would pass here silently and this KDoc would go
     * on being true and useless.
     *
     * ⭐ **So the load-bearing assertions below are on what `record` *reported*, not on what the
     * row shows.** The outcome distinguishes what the row cannot: *rejected* is not *never
     * attempted*, and neither is *written and rolled back*. The row is asserted too, but as a
     * consequence — it can no longer be the only evidence.
     *
     * ⭐ **Slice E reached this same move independently, in a different subsystem, on the same
     * afternoon.** `R38` found a nested-transaction test asserting `assertEquals(100, afterNested)`
     * that was green because `NESTED` was refused, the inner work never ran, and the row read
     * `100` **whether a savepoint rolled back or nothing happened at all.** Its fix was to assert
     * on what the inner call threw. **When an observable is reachable by several routes, assert on
     * the route, not the destination.** Neither slice could see the other.
     *
     * **The domain property is unchanged and is not held here.**
     * `AttemptRecordingAtomicityTest` reads from **outside** the transaction and asserts it there.
     */
    @Test
    fun `what this test can see after a failed recording, and why that is not the property`() {
        val scene = fixture.scene()

        val outcomes = service.recordAll(
            scene.learnerId,
            listOf(fixture.recording(scene, scoreDelta = BigDecimal("1.500"))),
        )

        // THE ROUTE. This is the evidence; the row below is only a consequence of it.
        //
        //   A Rejected outcome means the recording WAS attempted and the guard refused it.
        //   That is what separates this run from "the batch stopped before reaching it" and
        //   from "nothing was ever tried" -- neither of which the row can distinguish, because
        //   all three leave it looking the same from in here.
        val rejection = outcomes.single()
        assertTrue(
            rejection is RecordingOutcome.Rejected,
            "the recording was accepted, so nothing below is about a failed unit of work: $outcomes",
        )
        assertTrue(
            rejection.reason.contains("IllegalArgumentException"),
            "rejected, but not by the score guard -- so this test would pass on an accident " +
                "rather than on the mechanism it names: ${rejection.reason}",
        )

        val insideThisTransaction =
            masteries.findByLearnerIdAndConceptId(scene.learnerId, scene.conceptId)

        // THE CONSEQUENCE. Asserted, but it is not what makes the test evidence -- see the KDoc.
        assertNull(
            insideThisTransaction,
            "since ADR-020 made record REQUIRES_NEW, the row it wrote with `on conflict do " +
                "nothing` lives in a transaction that rolled back, so this reader cannot see " +
                "it. If this is non-null, record has stopped isolating itself and R40's green " +
                "has been reverted -- check BatchInsideATransactionTest, which says so with a " +
                "row count instead of with a null",
        )
    }
}
