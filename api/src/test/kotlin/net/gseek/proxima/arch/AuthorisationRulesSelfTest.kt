package net.gseek.proxima.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Test

/**
 * **The rule has been watched refuse a planted violation.**
 *
 * `AuthorisationRulesTest` runs the same rule object against production code and requires it
 * to pass. That proves the rule does not refuse everything. It does not prove the rule
 * refuses anything, and this repository has been caught by that distinction before: the first
 * time the secret scanner's custom rules were tested by planting a secret, **two of three
 * matched nothing at all**, and the scheduled scan had been green throughout because there
 * was nothing in the tree to find.
 *
 * This rule is newer than that and has never fired in anger. Until this class existed, the
 * only evidence it worked was that it compiled — and `R0` §4 measured what that is worth:
 * of the test classes in this repository that exist to refuse a future edit, **one has ever
 * been paid.**
 */
class AuthorisationRulesSelfTest {

    private val planted = ClassFileImporter().importPackages("proxima.planted")

    @Test
    fun `the planted handler is actually on the classpath`() {
        // Without this, the assertion below passes on an empty import: a rule checked against
        // nothing raises nothing. That is the same shape as the defect the rule is about.
        assertTrue(
            planted.any { it.simpleName == "PlantedUnauthorisedHandler" },
            "the planted handler is missing, so the refusal below would prove nothing. " +
                "Found: ${planted.map { it.simpleName }}",
        )
    }

    @Test
    fun `a handler that takes a path variable and does not authorise is refused`() =
        AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE.expectRefusal(
            "PlantedUnauthorisedHandler",
        )

    /**
     * Fails unless [this] rejects the planted classes **and says which one**.
     *
     * Asserting on the message rather than only on the failure matters: a rule that refused
     * for an unrelated reason would satisfy a bare `assertThrows`, and the self-test would be
     * green over a rule that cannot find what it claims to.
     */
    private fun ArchRule.expectRefusal(mustName: String) {
        val message = try {
            check(planted)
            null
        } catch (e: AssertionError) {
            e.message.orEmpty()
        }

        if (message == null) fail("the rule accepted a planted violation: ${this.description}")
        assertTrue(
            message.contains(mustName),
            "the rule refused, but not for the planted class $mustName. Message: $message",
        )
    }
}
