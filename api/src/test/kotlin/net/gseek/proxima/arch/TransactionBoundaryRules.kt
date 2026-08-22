package net.gseek.proxima.arch

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import jakarta.persistence.Entity
import org.springframework.transaction.annotation.Transactional

/**
 * The `T3` regression gate, as rule objects rather than as tests.
 *
 * **They live here so that the rules which run against production code and the rules which
 * are proved to refuse a planted violation are the same objects.** A self-test that
 * re-declares its own copy of a rule proves that the copy works, which is not the claim
 * being made.
 *
 * These are structural rules: they answer *"can this work?"* rather than *"does this
 * work?"*. That is the right question for `T3`, because every trap in it is a **shape** that
 * makes `@Transactional` inert rather than a logic error. A missing boundary changes
 * nothing about a successful request — it changes what a failed one leaves behind, so
 * catching it at run time means writing a deliberately failing unit of work against a real
 * database for every service that ever gains a boundary. These read bytecode and cost a
 * second.
 */
object TransactionBoundaryRules {

    /**
     * The mechanism observed at `21e7162`: a loop called a `@Transactional` method through
     * `this`, the call never reached the proxy, and every write committed on its own.
     *
     * No configuration changes this and nothing warns about it.
     */
    val TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED: ArchRule = methods()
        .that().areAnnotatedWith(Transactional::class.java)
        .should(notBeCalledFromWithinTheirOwnClass())
        .because(
            "a call through `this` does not reach the Spring proxy, so @Transactional on " +
                "the target does nothing -- observed at 21e7162, fixed at 9388743 by " +
                "moving the boundary onto its own bean",
        )

    /**
     * Spring Boot proxies by subclassing, so a `final` class cannot be proxied at all.
     * **Kotlin classes are `final` by default**, which makes this the normal state rather
     * than an unusual mistake; `kotlin("plugin.spring")` is the only reason it is not.
     *
     * So this rule is also the guard on that plugin. Remove it from the build and this
     * fails, instead of every transaction in the application silently ceasing to exist.
     */
    val TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED: ArchRule = classes()
        .that(haveAnyTransactionalMethod())
        .should().notHaveModifier(JavaModifier.FINAL)
        .because(
            "Spring Boot proxies by subclassing and Kotlin classes are final by default " +
                "-- if this fails, kotlin(\"plugin.spring\") has been removed and no " +
                "transaction in this application exists any more",
        )

    /**
     * A proxy advises by overriding, so a method that cannot be overridden cannot be
     * advised. `private` and `final` methods are skipped silently while the surrounding
     * class is proxied normally, which is why the bean looks correct.
     */
    val TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN: ArchRule = methods()
        .that().areAnnotatedWith(Transactional::class.java)
        .should().bePublic()
        .andShould().notHaveModifier(JavaModifier.FINAL)
        .because(
            "a proxy advises by overriding, so a private or final method is skipped while " +
                "the surrounding class is proxied normally",
        )

    /**
     * Hibernate builds a lazy proxy the same way Spring builds an AOP proxy — a runtime
     * subclass — so a `final` entity cannot be lazily loaded and every association becomes
     * eager with no error anywhere.
     *
     * For entities the plugin that prevents this is `kotlin("plugin.jpa")` and **not**
     * `kotlin("plugin.spring")`. That was measured rather than assumed at `0a05991`:
     * removing `plugin.jpa` makes entities `final` and strips their no-arg constructor,
     * while removing `plugin.spring` changes neither.
     */
    val ENTITIES_CAN_BE_SUBCLASSED: ArchRule = classes()
        .that().areAnnotatedWith(Entity::class.java)
        .should().notHaveModifier(JavaModifier.FINAL)
        .because(
            "Hibernate builds lazy proxies by subclassing -- a final entity loads eagerly " +
                "with no error. kotlin(\"plugin.jpa\") is what opens these; see 0a05991",
        )

    /**
     * A Kotlin `data class` generates `equals`/`hashCode` from its constructor properties,
     * read as **fields**. A Hibernate proxy's fields stay empty until a getter runs, so the
     * generated `equals` reports two instances of one row as different; and the generated
     * `hashCode` changes when `id` is assigned, removing the entity from any hash-based
     * collection it was already in.
     */
    val ENTITIES_ARE_NOT_DATA_CLASSES: ArchRule = classes()
        .that().areAnnotatedWith(Entity::class.java)
        .should(notBeAKotlinDataClass())
        .because(
            "a generated equals reads fields and a lazy proxy's fields are empty until a " +
                "getter runs; a generated hashCode changes when the id is assigned. See " +
                "BaseEntity for the equality this repository uses instead",
        )

    val ALL: List<ArchRule> = listOf(
        TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED,
        TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED,
        TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN,
        ENTITIES_CAN_BE_SUBCLASSED,
        ENTITIES_ARE_NOT_DATA_CLASSES,
    )
}

/**
 * Kotlin compiles a function with default arguments into the function plus a synthetic
 * static bridge named `foo$default`, and the bridge calls the real method. ArchUnit sees
 * that as a self-invocation and it is not one: the bridge receives the **proxy** as its
 * receiver argument and dispatches through it, so the advice applies normally.
 *
 * Established by measurement rather than by reading — `R6` §3.3 exercised such a method
 * under concurrency and it behaved transactionally throughout. Without this exclusion the
 * rule reports every `@Transactional` method that has a default argument, and **a rule that
 * is routinely wrong is a rule nobody reads**, which is the same outcome as not having one.
 */
private fun JavaMethod.isKotlinDefaultArgumentBridge(): Boolean = name.endsWith("\$default")

/**
 * **The exclusion above was too wide, and `R45` is the report with the three arms that show it.**
 *
 * Dropping every access whose origin is a `$default` bridge also drops the real defect, because
 * a same-class caller that OMITS the default argument never touches the annotated method
 * directly — the compiler routes it `caller -> target${'$'}default -> target`, and the only access
 * to the annotated method has the bridge as its origin. Measured, same defect, one difference:
 *
 *   nextRows calls difficultyBandFor(learnerId)                 -> rule PASSED   (blind)
 *   nextRows calls difficultyBandFor(learnerId, RECENCY_BASIS)  -> rule FAILED   (caught)
 *
 * **Looking through the bridge is what distinguishes them, and the receiver is why it is sound.**
 * `${'$'}default` is a static method taking the receiver as its first argument. Called from another
 * class the receiver is the injected **proxy**, so the forwarded call is advised and there is no
 * defect. Called from inside the owning class the receiver is `this`, so the forwarded call is
 * not advised and the annotation does nothing. So a bridge access is a violation exactly when
 * the bridge itself is called from within the owning class — which is what this now checks,
 * rather than exempting the bridge outright.
 *
 * The original exclusion's reason still holds and is not undone: the bridge's own call to its
 * target is compiler plumbing and is still never reported.
 */
private fun notBeCalledFromWithinTheirOwnClass() =
    object : ArchCondition<JavaMethod>("not be called from within their own class") {
        override fun check(method: JavaMethod, events: ConditionEvents) {
            method.accessesToSelf
                .filter { it.originOwner == method.owner }
                .flatMap { access ->
                    val origin = access.origin as? JavaMethod
                    if (origin?.isKotlinDefaultArgumentBridge() != true) {
                        listOf(access.origin.fullName)
                    } else {
                        // Look through the bridge to whoever called it. Same class -> the
                        // receiver was `this` and the proxy was missed. Anywhere else -> the
                        // receiver was the proxy and nothing is wrong.
                        origin.accessesToSelf
                            .filter { it.originOwner == method.owner }
                            .map { "${it.origin.fullName} (through ${origin.name})" }
                    }
                }
                .forEach { originName ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            "${method.fullName} is called from $originName, " +
                                "inside its own class, so the call does not reach the " +
                                "proxy and @Transactional has no effect",
                        ),
                    )
                }
        }
    }

private fun notBeAKotlinDataClass() =
    object : ArchCondition<JavaClass>("not be a Kotlin data class") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            val names = item.methods.map { it.name }
            if ("copy" in names && names.any { it.startsWith("component") }) {
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        "${item.name} generates copy() and componentN(), so it is a data " +
                            "class -- its equals reads fields and its hashCode changes " +
                            "when the id is assigned",
                    ),
                )
            }
        }
    }

private fun haveAnyTransactionalMethod() =
    object : DescribedPredicate<JavaClass>("hold a method annotated with @Transactional") {
        override fun test(input: JavaClass): Boolean =
            input.methods.any { it.isAnnotatedWith(Transactional::class.java) }
    }
