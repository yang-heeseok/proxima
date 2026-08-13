package net.gseek.proxima.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * `T9`'s structural gate, applied to production code.
 *
 * Tests are excluded from the import for the reason `TransactionBoundaryRulesTest` gives:
 * the planted violation that proves this rule can refuse lives in test sources, outside the
 * `net.gseek.proxima` package, so neither this import nor Spring's component scanning
 * reaches it.
 *
 * See `AuthorisationRulesSelfTest` for the half that proves the rule does anything.
 */
class AuthorisationRulesTest {

    private val production = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("net.gseek.proxima")

    /**
     * The control. This rule selects handlers that take a path variable, and if it ever
     * selected none it would pass over an application with no authorisation at all — the
     * failure mode `TransactionBoundaryRulesSelfTest`'s KDoc describes as *nothing about a
     * passing check distinguishes "there is nothing to find" from "this cannot find
     * anything."*
     */
    @Test
    fun `the rule has something to check`() {
        val handlers = production.filter { klass ->
            klass.methods.any { m -> m.parameterAnnotations.any { it.isNotEmpty() } }
        }
        assertTrue(
            handlers.isNotEmpty(),
            "no production class carries an annotated method parameter, so the rule below " +
                "would pass over an empty selection",
        )
    }

    @Test
    fun `every handler taking a path variable authorises the caller`() =
        AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE.check(production)
}
