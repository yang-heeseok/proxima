package net.gseek.proxima.basics

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.fixtures.basics.Failure
import net.gseek.fixtures.basics.FailingInner
import net.gseek.fixtures.basics.IsolatedInner
import net.gseek.fixtures.basics.IsolatingOuter
import net.gseek.fixtures.basics.JavaRollbackProbe
import net.gseek.fixtures.basics.KotlinRollbackProbe
import net.gseek.fixtures.basics.SwallowingOuter
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `R40` — **the annotation is applied, the proxy is crossed, and the row is still there.**
 *
 * ## What this is a sibling to
 *
 * `R1` measured a `@Transactional` that was **never applied**: a loop called it through `this`,
 * the call never reached the proxy, and the annotation was inert. The fix was to move the
 * boundary onto a bean the caller has to go through.
 *
 * Every probe here has done that already. The boundary is on its own bean, the call crosses
 * the proxy, the interceptor runs, and a transaction really is open. **And a row written by a
 * unit of work that raised is still committed**, because Spring's default answer to *which
 * exceptions roll back* is a Java language distinction, and half of this repository is written
 * in a language that removed it.
 *
 * ## Why the test is not `@Transactional`
 *
 * `R1` §4 measured the trap: a test annotated `@Transactional` shares one transaction with the
 * code under test, so the code's writes join the test's and get rolled back by the harness
 * whether or not the code had a boundary of its own. **A test that shares a transaction with
 * the code it is testing cannot observe that code's transaction boundaries.** This one runs
 * outside a transaction and counts committed rows with a fresh statement, which is the only
 * vantage point from which the question can be asked. It cleans up after itself in
 * `@AfterEach`, because nothing rolls back for it.
 *
 * ## Every number here is a row count
 *
 * No durations are taken and none are needed. "How many rows survived a failed unit of work"
 * is a logical fact about the transaction rule, not a performance property.
 */
@SpringBootTest
@Import(
    TestcontainersConfiguration::class,
    KotlinRollbackProbe::class,
    JavaRollbackProbe::class,
    FailingInner::class,
    IsolatedInner::class,
    SwallowingOuter::class,
    IsolatingOuter::class,
)
class RollbackRuleTest {

    @Autowired private lateinit var kotlinProbe: KotlinRollbackProbe
    @Autowired private lateinit var javaProbe: JavaRollbackProbe
    @Autowired private lateinit var swallowing: SwallowingOuter
    @Autowired private lateinit var isolating: IsolatingOuter
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val refs = AtomicInteger()

    private fun nextRef() = "learner-g2%06d".format(refs.incrementAndGet())

    @AfterEach
    fun clear() {
        jdbc.execute("delete from learner where external_ref like 'learner-g2%'")
    }

    private fun committed(ref: String): Int =
        jdbc.queryForObject(
            "select count(*) from learner where external_ref = ?", Int::class.java, ref,
        )!!

    /**
     * Runs [call], records what came out of it, and counts what it left behind.
     *
     * `Throwable` rather than `Exception`, because one of the arms raises an `Error` and
     * catching only `Exception` would let it escape and be reported as a test failure instead
     * of as a measurement.
     */
    private fun arm(label: String, ref: String, call: (String) -> Unit): Arm {
        val raised = try {
            call(ref)
            "—"
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }
        return Arm(label, raised, committed(ref))
    }

    private data class Arm(val label: String, val raised: String, val rows: Int)

    private fun printArms(title: String, arms: List<Arm>) {
        println()
        println(title)
        println("  %-52s %-28s %s".format("arm", "raised", "committed rows"))
        arms.forEach { println("  %-52s %-28s %d".format(it.label, it.raised, it.rows)) }
        println()
    }

    // ------------------------------------------------------------------------------------
    // 1. Which exception kinds roll back
    // ------------------------------------------------------------------------------------

    /**
     * **The table `R40` exists to produce.**
     *
     * Four failure kinds against the default rule, then the two that matter against
     * `rollbackFor`. Each arm writes exactly one row and then fails, so "committed rows" is
     * `0` when the unit of work was honoured and `1` when it was not.
     *
     * How this was checked is the point, and it is worth being explicit because the brief asks:
     * **not by reading Spring's documentation and not from memory.** Each arm executes against
     * a real PostgreSQL through a real proxy, and the row is counted afterwards by a statement
     * issued outside the transaction that wrote it.
     */
    @Test
    fun `which exception kinds roll back, and which commit`() {
        val arms = listOf(
            arm("Kotlin, default rule, no failure", nextRef()) {
                kotlinProbe.writeThenThrow(it, Failure.NONE)
            },
            arm("Kotlin, default rule, RuntimeException", nextRef()) {
                kotlinProbe.writeThenThrow(it, Failure.RUNTIME)
            },
            arm("Kotlin, default rule, checked IOException", nextRef()) {
                kotlinProbe.writeThenThrow(it, Failure.CHECKED)
            },
            arm("Kotlin, default rule, Error", nextRef()) {
                kotlinProbe.writeThenThrow(it, Failure.ERROR)
            },
            arm("Java,   default rule, checked IOException", nextRef()) {
                javaProbe.writeThenThrowChecked(it)
            },
            arm("Java,   default rule, RuntimeException", nextRef()) {
                javaProbe.writeThenThrowRuntime(it)
            },
            arm("Kotlin, rollbackFor = Exception, checked", nextRef()) {
                kotlinProbe.writeThenThrowRollingBackForAnything(it, Failure.CHECKED)
            },
            arm("Java,   rollbackFor = Exception, checked", nextRef()) {
                javaProbe.writeThenThrowCheckedRollingBackForAnything(it)
            },
        )

        printArms("R40-ROLLBACK >>> one write, then one failure, inside a real transaction", arms)

        fun rowsOf(label: String) = arms.single { it.label == label }.rows

        assertEquals(
            1, rowsOf("Kotlin, default rule, no failure"),
            "the control arm must commit, or the whole table is measuring a broken fixture",
        )
        assertEquals(
            0, rowsOf("Kotlin, default rule, RuntimeException"),
            "an unchecked exception is supposed to roll back",
        )
        assertEquals(
            0, rowsOf("Kotlin, default rule, Error"),
            "an Error is supposed to roll back",
        )

        // THE DEFECT, IN ONE NUMBER.
        assertEquals(
            1, rowsOf("Kotlin, default rule, checked IOException"),
            "a checked exception left no row behind. If this is 0, Spring's default rollback " +
                "rule changed and R40's central number is stale -- re-measure before trusting " +
                "anything else in that report",
        )
        assertEquals(
            1, rowsOf("Java,   default rule, checked IOException"),
            "the Java arm must behave identically to the Kotlin one. The divergence R40 " +
                "reports is in what the COMPILER says, not in what the transaction does",
        )

        assertEquals(
            0, rowsOf("Kotlin, rollbackFor = Exception, checked"),
            "rollbackFor is the remedy; if it does not work the remedy table is wrong",
        )
        assertEquals(
            0, rowsOf("Java,   rollbackFor = Exception, checked"),
            "rollbackFor names a runtime type and must not care which language declared it",
        )
    }

    // ------------------------------------------------------------------------------------
    // 2. Where Kotlin and Java diverge, read out of the class files
    // ------------------------------------------------------------------------------------

    /**
     * **The divergence is not in the behaviour. It is in what survives compilation.**
     *
     * Both languages commit the row. What differs is whether anything downstream can find out
     * that the method raises a checked exception:
     *
     * - `javac` writes the `throws` clause into the class file's `Exceptions` attribute, where
     *   `Method.getExceptionTypes()` reads it back — so an ArchUnit rule *could* refuse
     *   `@Transactional` methods that declare checked exceptions;
     * - `kotlinc` writes nothing, because Kotlin has no such clause to write. The same rule is
     *   **structurally unable** to see the Kotlin method.
     *
     * That is why `R40` ships no ArchUnit rule for this. A gate that catches the Java half of a
     * mixed codebase and is blind to the Kotlin half is worse than no gate: it produces a green
     * check that means nothing, which is the exact failure `TransactionBoundaryRulesSelfTest`
     * was written to prevent.
     */
    @Test
    fun `the throws clause survives javac and does not survive kotlinc`() {
        val javaChecked = JavaRollbackProbe::class.java
            .getMethod("writeThenThrowChecked", String::class.java)
            .exceptionTypes.map { it.simpleName }
        val javaRuntime = JavaRollbackProbe::class.java
            .getMethod("writeThenThrowRuntime", String::class.java)
            .exceptionTypes.map { it.simpleName }
        val kotlinChecked = KotlinRollbackProbe::class.java
            .getMethod("writeThenThrow", String::class.java, Failure::class.java)
            .exceptionTypes.map { it.simpleName }

        println()
        println("R40-SIGNATURE >>> declared checked exceptions, read from the class files")
        println("  %-56s %s".format("JavaRollbackProbe.writeThenThrowChecked", javaChecked))
        println("  %-56s %s".format("JavaRollbackProbe.writeThenThrowRuntime", javaRuntime))
        println("  %-56s %s".format("KotlinRollbackProbe.writeThenThrow", kotlinChecked))
        println()

        assertEquals(
            listOf("IOException"), javaChecked,
            "javac must write the throws clause into the Exceptions attribute",
        )
        assertTrue(
            javaRuntime.isEmpty(),
            "an unchecked exception needs no clause, so this is the control: it shows the " +
                "empty list below is not an artefact of how the attribute is read",
        )
        assertTrue(
            kotlinChecked.isEmpty(),
            "the Kotlin method declared a checked exception in its class file. If this ever " +
                "becomes non-empty, a static gate for this defect becomes possible and R40's " +
                "reason for not shipping one has expired",
        )
    }

    // ------------------------------------------------------------------------------------
    // 3. The quieter half: catch it, log it, lose everything
    // ------------------------------------------------------------------------------------

    /**
     * **What comes out when a transaction marked rollback-only is asked to commit.**
     *
     * `docs/roadmap.md` has carried this as *"not done at all"* since `T3`. The mechanism:
     *
     * 1. the outer method opens a transaction and writes;
     * 2. it calls an inner `@Transactional` bean whose propagation is `REQUIRED`, so the inner
     *    **joins** the outer transaction rather than getting one of its own;
     * 3. the inner raises. Its interceptor cannot roll back a transaction it does not own, so
     *    it does the only safe thing available and **marks the shared transaction
     *    rollback-only**;
     * 4. the outer catches the exception, logs it, and returns a value — successfully, as far
     *    as its own code is concerned;
     * 5. the outer's interceptor then tries to commit a transaction that has been marked, and
     *    that is where the failure finally appears.
     *
     * The exception the caller sees names none of the code that caused it, arrives from a
     * method that returned normally, and takes the outer's own write with it.
     */
    @Test
    fun `swallowing an inner failure loses the outer work and fails at the commit`() {
        val outerRef = nextRef()
        val innerRef = nextRef()

        var returned: String? = null
        val raised = try {
            returned = swallowing.writeThenSwallowInnerFailure(outerRef, innerRef)
            "—"
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }

        val outerRows = committed(outerRef)
        val innerRows = committed(innerRef)

        println()
        println("R40-SWALLOW >>> outer writes, inner fails, outer catches and carries on")
        println("  what the outer method computed for itself : ${returned ?: "never returned"}")
        println("  what the caller actually received         : $raised")
        println("  committed rows, outer's own write         : $outerRows")
        println("  committed rows, inner's write             : $innerRows")
        println()

        assertEquals(
            "UnexpectedRollbackException", raised,
            "the commit was expected to refuse a transaction marked rollback-only. If nothing " +
                "was raised, the inner did not join the outer transaction and this test is " +
                "measuring two transactions rather than one",
        )
        assertEquals(
            0, outerRows,
            "the outer's own write was lost too, which is the part that makes this expensive " +
                "-- the caught exception was about the inner and it took the outer with it",
        )
        assertEquals(0, innerRows, "the inner write must be gone")
    }

    /**
     * The same orchestration against an inner that owns its transaction.
     *
     * **The call site is character-for-character the same.** The difference is one propagation
     * setting on a different class, and it changes the outcome from "everything is lost and the
     * caller gets an exception it cannot explain" to "the failed work is gone and the rest
     * committed".
     *
     * `R40` §5 prices what that costs rather than recommending it flatly: `REQUIRES_NEW` takes
     * a second connection while the first is still held, and this repository already has a
     * formula that cares — `R2`'s pool sizing, where it is the `Cm` term.
     */
    @Test
    fun `isolating the inner transaction keeps the outer work`() {
        val outerRef = nextRef()
        val innerRef = nextRef()

        var returned: String? = null
        val raised = try {
            returned = isolating.writeThenSwallowInnerFailure(outerRef, innerRef)
            "—"
        } catch (t: Throwable) {
            t.javaClass.simpleName
        }

        val outerRows = committed(outerRef)
        val innerRows = committed(innerRef)

        println()
        println("R40-ISOLATED >>> the same code, against an inner that owns its transaction")
        println("  what the outer method computed for itself : ${returned ?: "never returned"}")
        println("  what the caller actually received         : $raised")
        println("  committed rows, outer's own write         : $outerRows")
        println("  committed rows, inner's write             : $innerRows")
        println()

        assertEquals("—", raised, "nothing should have been raised to the caller")
        assertEquals(
            1, outerRows,
            "the outer's write must survive a failure that was confined to the inner",
        )
        assertEquals(0, innerRows, "the inner's own write must still be rolled back")
    }
}
