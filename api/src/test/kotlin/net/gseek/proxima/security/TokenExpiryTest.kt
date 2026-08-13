package net.gseek.proxima.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** A clock the test moves, so that skew is simulated rather than waited for. */
private class FixedAt(private val now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * `T9`'s third strand — **token expiry, and what a clock-skew allowance actually buys and
 * costs.**
 *
 * ## No Spring context, on purpose
 *
 * [RequestToken] takes its policy as constructor arguments, so all three arms can be built in
 * one JVM in milliseconds. Doing this through `@SpringBootTest` would need three application
 * contexts to vary one string, and would measure the same arithmetic more slowly. `R10` §8
 * counts two extra contexts as a real cost; this avoids three.
 *
 * The clock is a parameter for the same reason. A test that asserts expiry by sleeping
 * asserts one point on the curve and takes as long as the token lives.
 *
 * ## The three arms
 *
 * | policy | behaviour |
 * | --- | --- |
 * | `ignored` | signature only. **`red`** |
 * | `strict` | expired and not-yet-valid refused, zero tolerance |
 * | `skewed` | the same, with 30 seconds of leeway **in both directions** |
 *
 * "In both directions" is the part that is easy to skip past, and §3 of `R11` is about what it
 * means: a tolerance added to survive clock drift **also extends the life of every expired
 * token by the same amount.** That is not a bug in the tolerance. It is what a tolerance is,
 * and it should be chosen by someone who knows they are choosing it.
 */
class TokenExpiryTest {

    private val verifierNow = Instant.parse("2026-08-13T12:00:00Z")
    private val clock = FixedAt(verifierNow)

    // Not a credential: this signs tokens that exist inside one test method. It is a literal
    // here rather than a property because this test builds RequestToken directly and never
    // starts Spring -- see the class KDoc.
    private val hmacMaterial = "unit-test-signing-material-not-a-credential"

    private fun verifier(policy: String, skewSeconds: Long = 30) =
        RequestToken(hmacMaterial, clock, policy, skewSeconds)

    /** The issuer's clock may differ from the verifier's; that difference is the subject. */
    private fun tokenIssued(offsetFromVerifier: Duration, validFor: Duration): String =
        verifier("skewed").issue(
            subject = 7L,
            validFor = validFor,
            issuedAt = verifierNow.plus(offsetFromVerifier),
        )

    private fun verdictOf(policy: String, token: String): String =
        when (val v = verifier(policy).verify(token)) {
            is RequestToken.Verdict.Trusted -> "trusted"
            is RequestToken.Verdict.Refused -> v.reason
        }

    @Test
    fun `what each expiry policy does to a token the clocks disagree about`() {
        val scenarios = listOf(
            "fresh, clocks agree" to tokenIssued(Duration.ZERO, Duration.ofMinutes(5)),
            "expired 1 second ago" to tokenIssued(Duration.ofSeconds(-301), Duration.ofMinutes(5)),
            "expired 60 seconds ago" to tokenIssued(Duration.ofSeconds(-360), Duration.ofMinutes(5)),
            "issuer's clock 10s ahead" to tokenIssued(Duration.ofSeconds(10), Duration.ofMinutes(5)),
            "issuer's clock 60s ahead" to tokenIssued(Duration.ofSeconds(60), Duration.ofMinutes(5)),
        )
        val policies = listOf("ignored", "strict", "skewed")

        val results = scenarios.associate { (name, token) ->
            name to policies.associateWith { verdictOf(it, token) }
        }

        println()
        println("T9-EXPIRY >>> verifier clock fixed at $verifierNow, skew tolerance 30s")
        println("  %-26s %-14s %-14s %s".format("scenario", "ignored", "strict", "skewed"))
        results.forEach { (name, byPolicy) ->
            println(
                "  %-26s %-14s %-14s %s".format(
                    name, byPolicy["ignored"], byPolicy["strict"], byPolicy["skewed"],
                ),
            )
        }
        println()

        // The red arm: a signature-only check means the token never stops working. There is
        // no waiting anybody out.
        assertEquals("trusted", results.getValue("expired 60 seconds ago").getValue("ignored"))

        // Zero tolerance refuses a token that is valid, because the machine that minted it
        // is ten seconds ahead. This is the outage nobody plans for: nothing is wrong with
        // the token, the user, or the signature.
        assertEquals("not-yet-valid", results.getValue("issuer's clock 10s ahead").getValue("strict"))
        assertEquals("trusted", results.getValue("issuer's clock 10s ahead").getValue("skewed"))

        // ...and the same tolerance keeps an expired token alive for thirty more seconds.
        // The allowance is symmetric. It cannot be bought in one direction only.
        assertEquals("expired", results.getValue("expired 1 second ago").getValue("strict"))
        assertEquals("trusted", results.getValue("expired 1 second ago").getValue("skewed"))

        // The tolerance is a window, not a hole: past it, the answer is the same as strict's.
        assertEquals("expired", results.getValue("expired 60 seconds ago").getValue("skewed"))
        assertEquals("not-yet-valid", results.getValue("issuer's clock 60s ahead").getValue("skewed"))

        // The control. If every arm refused everything, the table above would look decisive
        // and mean nothing.
        assertTrue(
            results.getValue("fresh, clocks agree").values.all { it == "trusted" },
            "no policy accepted a valid token, so the refusals above are not about expiry",
        )
    }

    /**
     * The tolerance, stated as the number it actually is.
     *
     * Thirty seconds of leeway on a five-minute token is a **10% extension of its lifetime**,
     * and on a thirty-second token it is a doubling. The report needs that as arithmetic
     * rather than as a warning, because the mistake is not choosing a tolerance — it is
     * choosing one without noticing it is a fraction of the token's life.
     */
    @Test
    fun `how much of a token's life a skew allowance adds`() {
        val rows = listOf(
            Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(60),
        ).map { ttl ->
            val tolerance = Duration.ofSeconds(30)
            val extension = tolerance.seconds * 100.0 / ttl.seconds
            "  ttl %-8s + 30s tolerance -> usable for %-8s (%.0f%% longer)".format(
                ttl.toString().removePrefix("PT"),
                ttl.plus(tolerance).toString().removePrefix("PT"),
                extension,
            )
        }

        println()
        println("T9-TOLERANCE >>> what 30 seconds of leeway costs at each token lifetime")
        rows.forEach(::println)
        println()

        // A token that lives 30 seconds and tolerates 30 seconds of skew is accepted for 60.
        val shortLived = tokenIssued(Duration.ofSeconds(-45), Duration.ofSeconds(30))
        assertEquals("expired", verdictOf("strict", shortLived))
        assertEquals(
            "trusted",
            verdictOf("skewed", shortLived),
            "a 30-second token, 45 seconds old, is still accepted under a 30-second " +
                "tolerance. That is the arithmetic, and it is the reason a short token and a " +
                "generous tolerance do not combine into a short-lived credential",
        )
    }
}
