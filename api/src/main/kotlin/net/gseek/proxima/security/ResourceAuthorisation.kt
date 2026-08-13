package net.gseek.proxima.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * **The one comparison that separates authentication from authorisation.**
 *
 * `TokenAuthenticationFilter` establishes *who is calling*. This decides *whether they may
 * have the thing they asked for*. `R11` measured what it costs to answer only the first
 * question: alice's token returned bob's recommendations with a `200` and bob's item code in
 * the body, through an endpoint with a filter, a signature, and refusals in its logs.
 *
 * ## Why this is a bean and not four lines in the controller
 *
 * It was four lines in the controller, and `R11` §8 recorded the hole that left:
 *
 * > **One endpoint is authorised.** … Everything added later starts unauthorised by default,
 * > and **nothing fails when it does** — the gate names one path.
 *
 * A structural rule cannot require *"the handler compares two numbers"*. It can require
 * **"the handler calls this method"**, and that is only expressible if the method has one
 * home. `AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE` is the rule; this
 * class is what makes the rule writable.
 *
 * That is the same move `T3` forced twice: the fix for self-invocation was not a call syntax
 * but **giving the boundary its own bean**, and both `R6` and `R7` found their remedies
 * needed it too.
 */
@Component
class ResourceAuthorisation(
    /**
     * Which arm of `R11`'s second strand is in force.
     *
     * | value | behaviour |
     * | --- | --- |
     * | `none` | the verified subject is ignored. **`red`** — every caller may reach every learner |
     * | `owner` | the resource must belong to the caller, or 403 |
     */
    @Value("\${proxima.security.authorisation:owner}")
    private val policy: String,
) {

    /**
     * Refuses unless [learnerId] is the caller.
     *
     * **403 and not 404.** Hiding existence behind a not-found is a real technique with its
     * own cost — it makes an authorisation failure and a typo indistinguishable in the logs.
     * It is not taken here, and saying so is the point: an unexamined 404 would look like a
     * considered choice.
     */
    fun requireOwner(subject: Long, learnerId: Long) {
        when (policy) {
            "none" -> Unit
            "owner" ->
                if (subject != learnerId) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "not your learner")
                }
            else -> error("unknown proxima.security.authorisation: $policy")
        }
    }
}
