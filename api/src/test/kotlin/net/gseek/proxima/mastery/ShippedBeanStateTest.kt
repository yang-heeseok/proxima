package net.gseek.proxima.mastery

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals

/**
 * `E2`'s missing half: **does any bean this application actually ships hold mutable state?**
 *
 * `R35` reproduced the defect and proved a class of failure is reachable in a Spring bean on
 * this stack. It proved nothing whatever about `proxima` — `SharedScoreCache` was built to be
 * measured and has no production caller. Without this sweep `R35` is a demonstration, and
 * `ADR-014` entry `35.5` is what that gap was priced at.
 *
 * ⭐ **This is done by reflection over the running `ApplicationContext`, not by grep, and the
 * difference is not fussiness.** A text search for `var` misses every field that is mutable
 * without saying so in source: inherited fields, `lateinit`, delegated properties, anything
 * Kotlin lowers into a backing field, and — the one that matters most here — a `val` holding a
 * `MutableList`, which is a final reference to a mutable object and is exactly as shared as a
 * `var`. It also cannot see through an AOP proxy to the target class.
 *
 * **A clean negative is a real result.** `R5` is the precedent in this repository: the defect
 * the framework already fixed. If nothing is found, `R35` gets to say *the class is reachable
 * in this stack and no shipped bean here is in it*, which is a stronger sentence than either
 * half on its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ShippedBeanStateTest {

    @Autowired private lateinit var context: ApplicationContext

    /** What was found, in a form that names the field rather than counting it. */
    private data class Finding(val bean: String, val declaringClass: String, val field: String, val type: String, val why: String) {
        override fun toString() = "$bean :: $declaringClass.$field : $type  [$why]"
    }

    /**
     * Types whose instances are mutable even when the reference holding them is `final`.
     *
     * Deliberately broad. A false positive here costs a sentence in a report; a false negative
     * is the entire defect going unseen, which is what `35.5` was opened about.
     */
    private fun isMutableContainer(type: Class<*>): Boolean =
        type.isArray ||
            MutableCollection::class.java.isAssignableFrom(type) ||
            MutableMap::class.java.isAssignableFrom(type) ||
            java.util.Collection::class.java.isAssignableFrom(type) ||
            java.util.Map::class.java.isAssignableFrom(type) ||
            java.util.concurrent.atomic.AtomicInteger::class.java.isAssignableFrom(type) ||
            java.util.concurrent.atomic.AtomicLong::class.java.isAssignableFrom(type) ||
            java.util.concurrent.atomic.AtomicReference::class.java.isAssignableFrom(type)

    private fun fieldsOf(type: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            out += c.declaredFields
            c = c.superclass
        }
        return out
    }

    @Test
    fun `no bean this application ships holds mutable state`() {
        val findings = mutableListOf<Finding>()
        var beansExamined = 0
        var fieldsExamined = 0

        for (name in context.beanDefinitionNames) {
            val bean = runCatching { context.getBean(name) }.getOrNull() ?: continue
            // Through the proxy to the class that actually declares the state.
            val target = AopProxyUtils.ultimateTargetClass(bean)
            if (!target.name.startsWith("net.gseek.proxima")) continue
            // The instruments this slice built are not shipped code and are not the subject.
            if (target.name.endsWith("SharedScoreCache") || target.name.endsWith("VisibilityFlag")) continue
            beansExamined++

            for (f in fieldsOf(target)) {
                if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
                fieldsExamined++
                val mutableRef = !Modifier.isFinal(f.modifiers)
                val mutableTarget = isMutableContainer(f.type)
                if (mutableRef) {
                    findings += Finding(name, target.simpleName, f.name, f.type.name, "non-final field")
                } else if (mutableTarget) {
                    findings += Finding(name, target.simpleName, f.name, f.type.name, "final reference to a mutable type")
                }
            }
        }

        println("E2 >>> shipped-bean sweep   beans=$beansExamined fields=$fieldsExamined findings=${findings.size}")
        findings.forEach { println("E2 >>>   $it") }

        // The instrument must be shown to have looked at something. A sweep that examined zero
        // beans would report zero findings and mean nothing -- the vacuous-pass shape ADR-015
        // was written about, and R43 §3.5 is this round's instance of it.
        assertEquals(true, beansExamined >= 5, "the sweep examined $beansExamined beans; it is not looking at the application")
        assertEquals(true, fieldsExamined >= 5, "the sweep examined $fieldsExamined fields; it is not reading them")

        assertEquals(
            emptyList<Finding>(), findings,
            "a bean this application ships holds mutable state, which every request shares",
        )
    }
}
