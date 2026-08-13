package net.gseek.proxima.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * A signed request token, and the verifier that decides whether to believe it.
 *
 * # THIS IS A MEASUREMENT FIXTURE. DO NOT COPY IT INTO A REAL SYSTEM.
 *
 * `T9` needs two things that only exist once a request can be attributed to somebody: an
 * endpoint that **authenticates and does not authorise**, and **token expiry with clock
 * skew**. Both are properties of the gap between verifying a caller and deciding what that
 * caller may reach — not of any particular library — so the smallest thing that produces the
 * gap is built here rather than pulling in a framework to produce it.
 *
 * What a real system needs and this does not have: key rotation, an audience and issuer,
 * revocation, refresh, anything resembling a login, and a review by somebody who does this
 * for a living. **The point of this class is the defects measured around it**, and a reader
 * who takes the token design instead of the measurements has taken the wrong thing.
 *
 * ## Format
 *
 * ```
 * <subject>.<issuedAt>.<expiresAt>.<signature>
 * ```
 *
 * Epoch seconds, and an HMAC-SHA256 over the first three joined by `.`, base64url without
 * padding. Deliberately not JWT: a hand-written parser for a three-field format has no
 * algorithm field to confuse, which removes a class of defect this report is not about.
 *
 * ## The clock is injected
 *
 * [clock] is a bean, not `Instant.now()`. Clock skew cannot be measured by a test that
 * cannot move the clock, and a verifier that reads the system clock directly is a verifier
 * whose expiry behaviour is asserted by waiting. `R6` needed real threads because the defect
 * was in real concurrency; this defect is in arithmetic on two timestamps, and simulating it
 * is more faithful than sleeping.
 */
@Component
class RequestToken(
    @Value("\${proxima.security.token-secret}") secret: String,
    private val clock: Clock,
    /**
     * **Which of `T9`'s expiry arms is in force.** All of them live in one binary for the
     * reason `R4` §2 gives: two builds means two JIT histories and two configurations beside
     * the difference being measured.
     *
     * | value | behaviour |
     * | --- | --- |
     * | `ignored` | the signature is checked and the timestamps are not. **`red`** |
     * | `strict` | expired is rejected, and so is not-yet-valid, with zero tolerance |
     * | `skewed` | the same, with [skewTolerance] of leeway in both directions |
     */
    @Value("\${proxima.security.expiry-policy:skewed}") private val expiryPolicy: String,
    @Value("\${proxima.security.skew-tolerance-seconds:30}") skewSeconds: Long,
) {

    private val key = SecretKeySpec(secret.toByteArray(), MAC_ALGORITHM)
    private val skewTolerance: Duration = Duration.ofSeconds(skewSeconds)

    /** What a verifier concluded, and why. The reason is the finding in `T9`'s third strand. */
    sealed interface Verdict {
        data class Trusted(val subject: Long) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun issue(subject: Long, validFor: Duration, issuedAt: Instant = clock.instant()): String {
        val body = "$subject.${issuedAt.epochSecond}.${issuedAt.plus(validFor).epochSecond}"
        return "$body.${sign(body)}"
    }

    /**
     * Verifies [token], in the order the checks must happen: **shape, then signature, then
     * time.**
     *
     * Reading the timestamps before the signature would mean trusting numbers an attacker
     * supplied, which is a different defect from the one being measured and not one worth
     * shipping in a fixture.
     */
    fun verify(token: String): Verdict {
        val parts = token.split('.')
        if (parts.size != 4) return Verdict.Refused("malformed")

        val body = parts.take(3).joinToString(".")
        val expected = sign(body)
        // Constant time. A verifier whose comparison short-circuits leaks the signature one
        // byte at a time, which is not this report's subject but is not worth introducing.
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[3].toByteArray())) {
            return Verdict.Refused("bad-signature")
        }

        val subject = parts[0].toLongOrNull() ?: return Verdict.Refused("malformed")
        val issuedAt = parts[1].toLongOrNull() ?: return Verdict.Refused("malformed")
        val expiresAt = parts[2].toLongOrNull() ?: return Verdict.Refused("malformed")

        val now = clock.instant().epochSecond
        val leeway = when (expiryPolicy) {
            // The red arm. The signature was valid, so the token is accepted -- which is
            // exactly the reasoning that produces a token nobody can ever revoke by waiting.
            "ignored" -> return Verdict.Trusted(subject)
            "strict" -> 0L
            "skewed" -> skewTolerance.seconds
            else -> error("unknown proxima.security.expiry-policy: $expiryPolicy")
        }

        if (now > expiresAt + leeway) return Verdict.Refused("expired")
        // Not-yet-valid is the direction people forget. A token minted by a machine whose
        // clock is ahead of this one arrives from the future and is indistinguishable from a
        // forgery to a verifier with no tolerance.
        if (now < issuedAt - leeway) return Verdict.Refused("not-yet-valid")

        return Verdict.Trusted(subject)
    }

    private fun sign(body: String): String =
        Mac.getInstance(MAC_ALGORITHM).run {
            init(key)
            Base64.getUrlEncoder().withoutPadding().encodeToString(doFinal(body.toByteArray()))
        }

    companion object {
        private const val MAC_ALGORITHM = "HmacSHA256"

        /** Where the filter leaves the verified subject for the controller to find. */
        const val SUBJECT_ATTRIBUTE = "proxima.subject"
    }
}
