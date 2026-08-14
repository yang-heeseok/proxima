package net.gseek.proxima.security

import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `R16` — **an upper bound on what the token filter can be costing a request.**
 *
 * ## Why this exists instead of a load measurement
 *
 * The filter's cost was the number `R16`'s load work originally set out to isolate, and the
 * harness cannot resolve it: the three measured runs' p99s were 650.0, 743.5 and 891.4 ms —
 * a spread of ~240 ms — so any per-request cost smaller than that is invisible under load on
 * this machine. This measures the dominant term directly instead: one [RequestToken.verify]
 * call, exactly as the filter invokes it, including the `Mac.getInstance` per call that the
 * shipped code performs.
 *
 * ## What it deliberately is not
 *
 * Not a duration assertion — `ADR-004`: CI asserts nothing that is a duration; the number is
 * printed and read into the report. Not a JMH benchmark either: nanosecond-precision is not
 * needed to bound a cost against a 240-millisecond resolution, and a dependency for it would
 * need the network this machine does not currently have.
 *
 * The one assertion is the control: every measured call must return `Trusted`, because a
 * bench that accidentally measured the `malformed` early-exit path would report a flattering
 * number for the wrong code.
 */
class TokenVerifyBenchTest {

    @Test
    fun `what one verify costs, as the filter calls it`() {
        val tokens = RequestToken(
            "bench-signing-material-not-a-credential",
            Clock.systemUTC(),
            "skewed",
            30,
        )
        val token = tokens.issue(subject = 7L, validFor = Duration.ofHours(1))

        // JIT warm-up, discarded -- measurement-discipline's rule, at nanoscale.
        repeat(200_000) { tokens.verify(token) }

        val n = 1_000_000
        var trusted = 0
        val t0 = System.nanoTime()
        repeat(n) {
            if (tokens.verify(token) is RequestToken.Verdict.Trusted) trusted++
        }
        val nsPerOp = (System.nanoTime() - t0).toDouble() / n

        println()
        println(
            "R16-FILTER-BOUND >>> RequestToken.verify(): %.0f ns/op over %,d calls".format(nsPerOp, n),
        )
        println(
            "  as a share of the measured p50 (300.0 ms): %.5f %%".format(nsPerOp / 3_000_000.0),
        )
        println()

        assertEquals(
            n, trusted,
            "not every measured call was a successful verification, so the number above " +
                "includes some cheaper rejection path and is not the filter's cost",
        )
    }
}
