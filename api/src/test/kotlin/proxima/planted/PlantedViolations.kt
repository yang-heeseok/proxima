package proxima.planted

import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.springframework.transaction.annotation.Transactional

/**
 * Violations planted for `TransactionBoundaryRulesSelfTest` to watch the gate refuse.
 *
 * **The package is deliberately outside `net.gseek.proxima`.** Two things must not reach
 * these classes: the production `ArchUnit` import, and Spring Boot's entity scanning, which
 * would otherwise try to map [PlantedDataClassEntity] and fail `ddl-auto=validate` against a
 * table that does not exist. Being in test sources already excludes them from the first;
 * the package puts them out of reach of the second as well, because one guard is a claim.
 *
 * Each class violates exactly one rule, so a self-test failure names the rule that broke.
 */

/**
 * Violates `TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED`.
 *
 * `open` is explicit rather than left to the compiler plugin, so this class violates one
 * rule and not three.
 */
open class PlantedSelfInvocation {

    fun outer() {
        inner()
    }

    @Transactional
    open fun inner() {
        // nothing -- the call site is the violation
    }
}

/** Violates `ENTITIES_ARE_NOT_DATA_CLASSES`. */
@Entity
data class PlantedDataClassEntity(
    @Id
    val id: Long? = null,
    val label: String = "",
)
