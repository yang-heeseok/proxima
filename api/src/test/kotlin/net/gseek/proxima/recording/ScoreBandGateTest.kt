package net.gseek.proxima.recording

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * `OPEN-6`'s gate — **the predicate may be stricter than the constraint. It may never be
 * laxer.**
 *
 * ## The two places the rule lives, and why that is allowed
 *
 * `mastery.score` must stay inside `0..1`. That rule is written twice:
 *
 * - `ck_mastery_score` in `V1` — **authoritative.** It is the last line of defence against
 *   any writer at all, including `psql`, and it must stay.
 * - `and score + :delta between 0 and 1.000` in `RecordingQueries.applyRecording` — what the
 *   application actually consults. It exists so that a refusal is **zero rows rather than an
 *   aborted transaction**, which is the whole finding of `R12` §3.4.
 *
 * Neither can be deleted in favour of the other. `ADR-006` accepts the duplication and gates
 * the **relationship** instead of the text, because the two are not supposed to be equal —
 * they are supposed to be ordered.
 *
 * ## Only one direction is dangerous
 *
 * | | what happens |
 * | --- | --- |
 * | predicate **stricter** than the constraint | some valid recordings are refused. Wrong, visible, and harmless to the data |
 * | predicate **laxer** than the constraint | a recording passes the predicate, reaches the row, and violates `ck_mastery_score` — **`R1` §9's aborted transaction**, from the exact code path `R12` built to avoid it |
 *
 * So the assertion is not *"the two agree"*. It is **"no recording ever reaches the
 * constraint"**: every refusal must arrive as `IllegalArgumentException` from the guard, and
 * a `DataIntegrityViolationException` anywhere in this test means the predicate has been
 * widened, the constraint tightened, or one of them moved.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class ScoreBandGateTest {

    @Autowired private lateinit var recorder: AttemptRecorder
    @Autowired private lateinit var fixture: RecordingFixture

    @AfterEach
    fun cleanUp() = fixture.clear()

    /**
     * @param delta applied to a fresh row whose score is `0.000`
     * @param acceptable whether `0.000 + delta` is inside the band
     */
    private data class Probe(val delta: String, val acceptable: Boolean)

    @Test
    fun `every refusal comes from the guard and none from the constraint`() {
        val probes = listOf(
            Probe("0.000", acceptable = true),
            Probe("0.500", acceptable = true),
            // The upper edge, which `between 0 and 1.000` includes and a `<` would not.
            Probe("1.000", acceptable = true),
            Probe("1.001", acceptable = false),
            Probe("9.999", acceptable = false),
            // The lower edge. `require(updated <= 1)` never checked this end at all; before
            // R12 a negative delta went to ck_mastery_score and aborted the transaction.
            Probe("-0.001", acceptable = false),
            Probe("-1.000", acceptable = false),
        )

        val results = probes.map { probe ->
            val scene = fixture.scene()
            val outcome = try {
                recorder.record(scene.learnerId, fixture.recording(scene, BigDecimal(probe.delta)))
                "applied, score=" + fixture.masteryOf(scene)?.score?.toPlainString()
            } catch (e: Exception) {
                "refused by ${e.javaClass.simpleName}"
            }
            Triple(probe, outcome, fixture.masteryOf(scene))
        }

        println()
        println("OPEN6-BAND >>> ck_mastery_score (V1) against the predicate in RecordingQueries")
        results.forEach { (probe, outcome, _) ->
            println("  delta %-7s expected %-10s -> %s".format(
                probe.delta, if (probe.acceptable) "applied" else "refused", outcome,
            ))
        }
        println()

        results.forEach { (probe, outcome, row) ->
            if (probe.acceptable) {
                assertTrue(
                    outcome.startsWith("applied"),
                    "delta ${probe.delta} is inside the band and was refused. The predicate " +
                        "is stricter than ck_mastery_score -- see ADR-006. Got: $outcome",
                )
            } else {
                assertTrue(
                    outcome.contains("IllegalArgumentException"),
                    "delta ${probe.delta} leaves the band and was not refused by the guard. " +
                        "A DataIntegrityViolationException here means the predicate is now " +
                        "LAXER than ck_mastery_score, so a recording reached the constraint " +
                        "and aborted its transaction -- R1 §9, from the code path R12 built " +
                        "to avoid exactly that. See ADR-006. Got: $outcome",
                )
                assertEquals(
                    null, row,
                    "a refused recording left a mastery row behind: $outcome",
                )
            }
        }
    }
}
