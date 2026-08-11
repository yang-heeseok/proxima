package net.gseek.proxima.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Every rule in the gate has been watched refuse a planted violation.**
 *
 * This repository does not accept a guard that has never refused anything. Both CI
 * workflows here carry the same kind of job, and the reason is on record: the first time
 * the secret scanner's rules were tested by planting a secret, **two of the three rules
 * turned out to match nothing at all**, and the repository scan had been passing throughout
 * because there was nothing in the tree to find. Nothing about a passing check
 * distinguishes *"there is nothing to find"* from *"this cannot find anything."*
 *
 * These rules are the `T3` regression gate. They are new, they have never fired in anger,
 * and until this test existed the only evidence they worked was that they compiled.
 *
 * **The negative control is `TransactionBoundaryRulesTest`**, which runs the same rule
 * objects against production code and requires them to pass. A rule set that refuses
 * everything is exactly as useless as one that refuses nothing — findings that are
 * routinely wrong are findings nobody reads, which is the same outcome as not scanning.
 * Both halves are required, and they share the rules rather than each declaring a copy.
 */
class TransactionBoundaryRulesSelfTest {

    /**
     * Note the absence of `DO_NOT_INCLUDE_TESTS`. The planted classes live in test sources
     * — which is also the second reason the production import cannot reach them, the first
     * being that they sit outside the `net.gseek.proxima` package entirely.
     */
    private val planted = ClassFileImporter().importPackages("proxima.planted")

    @Test
    fun `the planted classes are actually on the classpath`() {
        // Without this, every assertion below would pass on an empty import: a rule checked
        // against nothing raises nothing, and the whole self-test would be green and
        // vacuous. That failure mode is the entire subject of T3, so it gets its own check.
        assertTrue(planted.size >= 5, "expected the planted violations, found ${planted.size}")
    }

    @Test
    fun `the self-invocation rule refuses a planted violation`() =
        assertRefuses(TransactionBoundaryRules.TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED)

    @Test
    fun `the final-class rule refuses a planted violation`() =
        assertRefuses(TransactionBoundaryRules.TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED)

    @Test
    fun `the unoverridable-method rule refuses a planted violation`() =
        assertRefuses(TransactionBoundaryRules.TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN)

    @Test
    fun `the final-entity rule refuses a planted violation`() =
        assertRefuses(TransactionBoundaryRules.ENTITIES_CAN_BE_SUBCLASSED)

    @Test
    fun `the data-class-entity rule refuses a planted violation`() =
        assertRefuses(TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES)

    private fun assertRefuses(rule: ArchRule) {
        try {
            rule.check(planted)
        } catch (expected: AssertionError) {
            return
        }
        fail(
            "the rule accepted a planted violation, so it would not catch the real thing: " +
                rule.description,
        )
    }
}
