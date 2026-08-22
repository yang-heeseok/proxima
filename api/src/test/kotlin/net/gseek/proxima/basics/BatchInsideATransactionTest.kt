package net.gseek.proxima.basics

import java.math.BigDecimal
import kotlin.test.assertEquals
import net.gseek.fixtures.basics.TransactionalBatchCaller
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.recording.AttemptRecordingService
import net.gseek.proxima.recording.RecordingFixture
import net.gseek.proxima.recording.RecordingOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * `R40` §4 — **the shipped batch path, called from a caller that has a transaction.**
 *
 * ## Why this test exists separately from the probes
 *
 * `RollbackRuleTest` measures the rollback rule on beans written to measure it. That is
 * necessary and it is not sufficient: a rule demonstrated on a purpose-built probe is a fact
 * about Spring, not a claim about this application. This class makes the claim about this
 * application, using **only shipped code plus one caller**.
 *
 * ## What is shipped, and what is one annotation away from being false
 *
 * `AttemptRecordingService.recordAll` under `per-item-outcomes` attempts every recording and
 * returns an outcome for each. `R14` measured it: of five recordings with the third invalid,
 * **four land**. That is the repository's stated domain rule — *attempts are independent
 * events, and one learner's invalid submission is not a reason to discard the valid ones
 * recorded beside it.*
 *
 * It is true today because `recordAll` holds no transaction, so each `AttemptRecorder.record`
 * opens its own and a rejection rolls back alone. **`AttemptRecordingService`'s KDoc says the
 * class holds no `@Transactional` deliberately — but the reason it gives is `R1`'s, about
 * where a boundary belongs, not this one.** That the batch survives at all is a second
 * property riding on the same absence, and nothing in the tree states it or checks it.
 *
 * ## Why a caller rather than an edit
 *
 * The obvious way to show this would be to add `@Transactional` to `recordAll`. That would be
 * **manufacturing the failure**, which the preamble forbids, and it would prove only that
 * editing shipped code can break it.
 *
 * A caller proves something stronger and is what actually happens: the second service that
 * needs to record attempts alongside its own writes will be `@Transactional`, because almost
 * every service is, and it will call `recordAll` because that is the API. No production file
 * is modified here. The defect is reached the way it would be reached in production.
 *
 * ## The numbers are row counts
 *
 * How many of four valid recordings survived. Nothing is timed.
 */
@SpringBootTest
@Import(
    TestcontainersConfiguration::class,
    RecordingFixture::class,
    TransactionalBatchCaller::class,
)
class BatchInsideATransactionTest {

    @Autowired private lateinit var service: AttemptRecordingService
    @Autowired private lateinit var caller: TransactionalBatchCaller
    @Autowired private lateinit var fixture: RecordingFixture

    @AfterEach
    fun cleanUp() = fixture.clear()

    /**
     * The control. **This is `R14`'s number, re-measured on this base rather than quoted.**
     *
     * `R14` is on `main` and this branch descends from `round3/recency`, which changed the
     * recommendation path. Nothing it changed touches recording — but "nothing I changed
     * touches it" is exactly the reasoning `R17` exists to distrust, so the figure is taken
     * again here rather than carried across.
     */
    @Test
    fun `called with no transaction of its own, the batch keeps every valid recording`() {
        val scene = fixture.scene()
        val recordings = DELTAS.map { fixture.recording(scene, BigDecimal(it)) }

        val outcomes = service.recordAll(scene.learnerId, recordings)
        val rows = fixture.countAttempts()

        println()
        println("R40-CONTROL >>> the shipped call path, no outer transaction")
        println("  outcomes    : ${outcomes.summary()}")
        println("  attempt rows: $rows")
        println()

        assertEquals(
            VALID.toLong(), rows,
            "the control must reproduce R14's shipped behaviour. If it does not, this base " +
                "changed something about recording and the comparison below is not the one " +
                "R40 claims to be making",
        )
    }

    /**
     * **The same call, from inside a caller's transaction. Nothing else differs.**
     *
     * What is asserted is the property the application says it has, stated as `R14` states it:
     * a rejected recording does not discard the valid ones recorded beside it.
     *
     * ⚠️ **This assertion FAILED on the red commit `94fe9ee`, and that failure is the
     * measurement.** Verbatim:
     *
     * ```
     * 4 valid recordings were attempted and 0 survived. [...] Raised: UnexpectedRollbackException
     *   ==> expected: <4> but was: <0>
     * ```
     *
     * The mechanism is `RollbackRuleTest`'s third case reached through shipped code:
     * `AttemptRecorder.record` was `REQUIRED`, so inside a caller's transaction it joined
     * rather than isolating; the invalid recording's interceptor marked the shared transaction
     * rollback-only; `recordAll` caught the rejection and reported it as one outcome among
     * five, exactly as designed; and then the caller's commit refused.
     *
     * ⭐ **It passes on the green commit**, where `AttemptRecorder.record` is `REQUIRES_NEW`
     * and the unit of work is one recording regardless of the caller. `ADR-020`.
     *
     * **This is now the gate.** Reverting that propagation turns this red with the number in
     * the message, rather than leaving a batch path that silently loses everything the first
     * time a transactional caller is written.
     *
     * The caller receives `UnexpectedRollbackException`. The list of per-item outcomes that
     * `R14` was written to provide **is computed and then thrown away with the transaction**,
     * so the caller is not merely told less than it was promised — it is told nothing, by an
     * exception naming a transaction it never marked.
     */
    @Test
    fun `called from inside a caller's transaction, the valid recordings are lost too`() {
        val scene = fixture.scene()
        val recordings = DELTAS.map { fixture.recording(scene, BigDecimal(it)) }

        var outcomes: List<RecordingOutcome>? = null
        val raised = try {
            outcomes = caller.recordAllInsideMyTransaction(scene.learnerId, recordings)
            "—"
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }
        val rows = fixture.countAttempts()

        println()
        println("R40-RED >>> the same shipped call, from a caller that has a transaction")
        println("  5 recordings, deltas $DELTAS, the third one invalid")
        println("  outcomes the caller received : ${outcomes?.summary() ?: "none -- $raised"}")
        println("  raised to the caller         : $raised")
        println("  attempt rows committed       : $rows  (of $VALID valid recordings)")
        println()

        assertEquals(
            VALID.toLong(), rows,
            "$VALID valid recordings were attempted and $rows survived. AttemptRecorder's own " +
                "KDoc says the unit of work is one recording and that one learner's invalid " +
                "submission is not a reason to discard the valid ones beside it. Inside a " +
                "caller's transaction that is not what happens: the rejected recording marks " +
                "the shared transaction rollback-only, and the caller's commit takes " +
                "everything. Raised: $raised",
        )
    }

    private fun List<RecordingOutcome>.summary() =
        joinToString { if (it is RecordingOutcome.Recorded) "ok" else "rejected" }

    private companion object {
        /** `PartialBatchTest`'s deltas, so the two reports describe the same batch. */
        val DELTAS = listOf("0.100", "0.100", "1.500", "0.100", "0.100")
        const val VALID = 4
    }
}
