package net.gseek.proxima.arch

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test

/**
 * The `T3` gate, applied to production code.
 *
 * Tests are excluded from the import on purpose: the planted violations that prove these
 * rules can refuse live in test sources, and a gate that failed on its own controls would
 * be useless. They are outside the `net.gseek.proxima` package as well, so neither this
 * import nor Spring's entity scanning can reach them.
 *
 * See `TransactionBoundaryRulesSelfTest` for the half that proves these rules do anything.
 */
class TransactionBoundaryRulesTest {

    private val production = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("net.gseek.proxima")

    @Test
    fun `a transactional method is never called from inside its own class`() =
        TransactionBoundaryRules.TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED.check(production)

    @Test
    fun `a class holding a transactional method can be subclassed`() =
        TransactionBoundaryRules.TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED.check(production)

    @Test
    fun `a transactional method can be overridden by a proxy`() =
        TransactionBoundaryRules.TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN.check(production)

    @Test
    fun `an entity can be subclassed, so Hibernate can proxy it`() =
        TransactionBoundaryRules.ENTITIES_CAN_BE_SUBCLASSED.check(production)

    @Test
    fun `no entity is a Kotlin data class`() =
        TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES.check(production)
}
