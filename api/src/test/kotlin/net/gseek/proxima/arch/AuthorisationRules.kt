package net.gseek.proxima.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import net.gseek.proxima.security.ResourceAuthorisation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping

/**
 * `T9`'s second regression gate, and the one `R11` §8 said was missing.
 *
 * > **One endpoint is authorised.** `RecommendationController` compares subject to path.
 * > Everything added later starts unauthorised by default, and **nothing fails when it
 * > does** — the gate names one path. A structural rule in the `T3` style, asserting that
 * > every handler taking a `learnerId` also authorises, is the thing that would generalise
 * > it. **Not written.**
 *
 * `AuthorisationGateTest` proves the *shipped* endpoint refuses a cross-learner request. It
 * cannot say anything about the second endpoint, because there is no second endpoint — and
 * that is exactly when a hole is cheapest to leave and most expensive to find.
 *
 * ## Why the rule is about path variables rather than about `learnerId`
 *
 * Parameter names are not reliably present in bytecode; annotation values are. A rule keyed
 * on the string `learnerId` would pass on `@PathVariable("learner")` and on a handler that
 * renamed its parameter, which makes it a rule about spelling.
 *
 * **In this application every path variable identifies something that belongs to somebody.**
 * That is a domain claim, and stating it as the rule has a property worth having: a genuinely
 * public path-variable endpoint — `/concepts/{code}`, say — will fail this rule, and whoever
 * adds it has to say out loud that it needs no owner. **The rule makes the decision explicit
 * rather than making it correctly**, which is the most a structural rule can do.
 *
 * ## Why a direct call
 *
 * The condition requires the handler itself to call [ResourceAuthorisation.requireOwner],
 * not to reach it through a chain. Following an arbitrary call graph would make the rule
 * answer *"is it reachable"* rather than *"does it happen"* — and a helper that authorises on
 * some branches is exactly the shape this is meant to refuse.
 */
object AuthorisationRules {

    val HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE: ArchRule = methods()
        .that(areRequestHandlers())
        .and(takeAPathVariable())
        .should(callRequireOwnerDirectly())
        .because(
            "authentication answers who is calling and authorisation answers what they may " +
                "have -- R11 §3.3 measured one learner's token returning another learner's " +
                "data through an endpoint that had a filter, a signature and refusals in its " +
                "logs. A handler that takes a path variable is naming somebody's resource",
        )

    private fun areRequestHandlers() = object : DescribedPredicate<JavaMethod>("are request handlers") {
        private val mappings = listOf(
            RequestMapping::class.java, GetMapping::class.java, PostMapping::class.java,
            PutMapping::class.java, PatchMapping::class.java, DeleteMapping::class.java,
        )

        override fun test(method: JavaMethod): Boolean =
            mappings.any { method.isAnnotatedWith(it) }
    }

    private fun takeAPathVariable() =
        object : DescribedPredicate<JavaMethod>("take a path variable") {
            override fun test(method: JavaMethod): Boolean =
                method.parameterAnnotations.any { annotations ->
                    annotations.any { it.rawType.isEquivalentTo(PathVariable::class.java) }
                }
        }

    private fun callRequireOwnerDirectly() =
        object : ArchCondition<JavaMethod>("call ResourceAuthorisation.requireOwner") {
            override fun check(method: JavaMethod, events: ConditionEvents) {
                val authorises = method.callsFromSelf.any { call ->
                    call.targetOwner.isEquivalentTo(ResourceAuthorisation::class.java) &&
                        call.target.name == "requireOwner"
                }
                events.add(
                    SimpleConditionEvent(
                        method,
                        authorises,
                        "${method.fullName} takes a path variable and does not call " +
                            "ResourceAuthorisation.requireOwner. Either it must, or it is a " +
                            "public resource and the rule needs revisiting deliberately -- " +
                            "see docs/reports/R11 §8",
                    ),
                )
            }
        }
}
