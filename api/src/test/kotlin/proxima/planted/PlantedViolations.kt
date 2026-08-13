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

/**
 * Violates `HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE`.
 *
 * **This is what a second endpoint looks like.** It authenticates — the filter runs on every
 * `/api/v1` path regardless of what the handler does — and it never asks whether the caller
 * owns the learner it is about to read. `R11` §3.3 measured what that returns: `200`, with
 * somebody else's data in the body.
 *
 * It is planted rather than imagined because a rule nobody has watched refuse anything is a
 * comment. `R0` §4 counts exactly one gate in this repository that has ever been paid.
 */
@org.springframework.web.bind.annotation.RestController
class PlantedUnauthorisedHandler {

    @org.springframework.web.bind.annotation.GetMapping("/api/v1/learners/{learnerId}/planted")
    fun read(
        @org.springframework.web.bind.annotation.PathVariable learnerId: Long,
    ): String = "learner $learnerId"
}
