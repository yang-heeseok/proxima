package net.gseek.proxima.ops

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource

/**
 * **`R31` — what fails to cross an `@Async` boundary, and why `R1` is its sibling.**
 *
 * `R1`'s defect was that the proxy was never applied: a self-invocation never left the object
 * so the interceptor was not in the path, and `@Transactional` did nothing at all. Here the
 * proxy *is* applied, the interceptor *does* run, and the transaction is still not there —
 * because a transaction is bound to a thread and the thread changed. **Same symptom, opposite
 * cause, and no unit test that asserts on a return value can see either.**
 *
 * ## The annotations match `DeploymentBoundaryGateTest`'s on purpose
 *
 * Spring's context cache serves every class with the same annotations from **one** application
 * context; a class that differs by so much as a `@TestPropertySource` gets its own. That is not
 * free — `R9` §3.6's per-class wall times are what a context refresh costs on this machine, and
 * they range from 4 s to 198 s. So the arms below are two classes rather than a property on every
 * method, and the count of extra contexts this file adds is **two**, stated rather than incurred
 * quietly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AsyncBoundaryTest {

    @Autowired private lateinit var caller: TransactionalAsyncCaller
    @Autowired private lateinit var probe: AsyncBoundaryProbe
    @Autowired private lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun removeWhatTheAsyncThreadCommitted() {
        jdbc.update("delete from learner where external_ref like 'r31-%'")
        MDC.clear()
        OpsThreadLocal.VALUE.remove()
    }

    /**
     * `R31` §3.2. **Four contexts, one hop, and how many survive it.**
     *
     * The executor is not asserted by name — the **thread name is read** and printed, because
     * "which executor does an unqualified `@Async` use" is exactly the kind of thing rule 9
     * forbids answering from memory. Boot registers its `ThreadPoolTaskExecutor` under both
     * `applicationTaskExecutor` and the alias Spring's async infrastructure resolves, so the
     * observed name is what settles it.
     */
    @Test
    fun `nothing but the arguments crosses the boundary`() {
        MDC.put(OpsThreadLocal.MDC_KEY, "set-on-the-caller")
        OpsThreadLocal.VALUE.set("set-on-the-caller")

        val (onCaller, onAsync) = caller.observeInsideTransaction()

        println("R31 §3.2 caller = $onCaller")
        println("R31 §3.2 async  = $onAsync")

        // The control. Without this row the async row proves nothing -- it would be equally
        // consistent with "this application has no transactions and sets no MDC".
        assertTrue(onCaller.transactionActive, "the caller really is in a transaction")
        assertEquals("set-on-the-caller", onCaller.mdcValue, "the caller really did set an MDC entry")
        assertEquals("set-on-the-caller", onCaller.threadLocalValue)

        assertNotEquals(
            onCaller.threadName, onAsync.threadName,
            "the work ran on a different thread, which is the whole mechanism. Observed " +
                "executor thread: ${onAsync.threadName} -- read, not assumed",
        )
        assertFalse(
            onAsync.transactionActive,
            "THIS ASSERTION EXPECTS THE DEFECT. The caller is @Transactional and the async " +
                "method is inside its call, and there is no transaction on the async thread. " +
                "R1 got here by never reaching the proxy; this gets here THROUGH the proxy",
        )
        assertNull(onAsync.transactionName, "not a different transaction -- no transaction")
        assertFalse(
            onAsync.requestAttributesPresent,
            "request-scoped beans are unreachable. This repository has no Spring Security, " +
                "so the request attribute is the analogue of the security context here -- " +
                "RecommendationController reads its subject from one. R31 §3.3",
        )
        assertNull(onAsync.mdcValue, "the log line from the async thread carries no correlation id")
        assertNull(onAsync.threadLocalValue, "and a bare ThreadLocal does not travel either")
    }

    /**
     * `R31` §3.4. ⭐ **The caller rolls back and the async write does not.**
     *
     * Both rows are written inside one `@Transactional` method that then throws. A reader who
     * believes `@Async` work participates in the caller's transaction expects to find neither.
     * One is gone and one is not, and **nothing logged a warning about it.**
     */
    @Test
    fun `the caller's rollback does not reach the row the async thread wrote`() {
        assertThrows<DeliberateRollback> { caller.writeBothThenRollBack("r31") }

        val sync = count("r31-sync")
        val async = count("r31-async")
        println("R31 §3.4 after rollback: sync rows=$sync async rows=$async")

        assertEquals(0, sync, "the caller's own write rolled back, as anybody would expect")
        assertEquals(
            1, async,
            "and the async write survived. It was never in that transaction to be rolled " +
                "back -- and no propagation setting was involved, unlike MasteryCounter's " +
                "REQUIRES_NEW where a reader can at least see the word. R31 §3.4",
        )
    }

    /** The red arm's own control: with the decorator off, the MDC does not travel. */
    @Test
    fun `with context propagation off the MDC is absent on the async thread`() {
        MDC.put(OpsThreadLocal.MDC_KEY, "red-arm")
        assertNull(probe.observeAsync().join().mdcValue)
    }

    private fun count(ref: String): Int =
        jdbc.queryForObject("select count(*) from learner where external_ref = ?", Int::class.java, ref)!!
}

/**
 * `R31` §5 — **the green arm, and it is deliberately partial.**
 *
 * One extra Spring context — see the note on the red class above — in exchange for testing the *wiring* and
 * not merely the mechanism: whether Boot's executor actually consumes a `TaskDecorator` bean
 * is a framework behaviour this repository must not assume, so it is asserted through the real
 * `@Async` path rather than by calling the decorator directly.
 *
 * **What it fixes is the MDC. What it does not fix is the transaction**, and that is not an
 * omission — see `R31` §8.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["proxima.ops.async-context=copy-mdc"])
class AsyncBoundaryWithContextCopyTest {

    @Autowired private lateinit var caller: TransactionalAsyncCaller

    @AfterEach
    fun clear() {
        MDC.clear()
    }

    @Test
    fun `the decorator carries the MDC across and still cannot carry the transaction`() {
        MDC.put(OpsThreadLocal.MDC_KEY, "green-arm")

        val (onCaller, onAsync) = caller.observeInsideTransaction()
        println("R31 §5 async = $onAsync")

        assertTrue(onCaller.transactionActive)
        assertEquals(
            "green-arm", onAsync.mdcValue,
            "the TaskDecorator bean was picked up by the executor Boot built -- asserted " +
                "through the real @Async path, not by calling the decorator by hand",
        )
        assertFalse(
            onAsync.transactionActive,
            "AND THE TRANSACTION STILL DOES NOT CROSS. That is not a gap in the decorator: a " +
                "transaction is a JDBC connection bound to one thread, and re-binding it to a " +
                "second concurrent thread would be two threads on one connection, not " +
                "propagation. R31 §8 keeps this as the risk that does not go away",
        )
        assertFalse(
            onAsync.requestAttributesPresent,
            "the decorator deliberately does not copy request attributes: the request they " +
                "belong to may already be complete and recycled. R31 §5",
        )
    }
}
