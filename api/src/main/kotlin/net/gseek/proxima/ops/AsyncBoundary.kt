package net.gseek.proxima.ops

import java.util.concurrent.CompletableFuture
import net.gseek.proxima.domain.Learner
import net.gseek.proxima.domain.LearnerRepository
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder

/**
 * **`R1`'s sibling, one layer over.**
 *
 * In `R1` the annotation did nothing because **the proxy was never applied** — a call through
 * `this` never left the object, so Spring's interceptor was not in the path. Here the proxy is
 * applied and works perfectly, and the work still leaves the transaction behind, because
 * **the thread changed**. The two are the same sentence — *"the annotation is on the method
 * and the behaviour is not"* — reached from opposite directions, and neither is visible to a
 * unit test that asserts on a return value.
 *
 * Read `R1-transaction-annotation-that-does-nothing.md` beside `R31`.
 *
 * ## What is planted here, and what is not
 *
 * Nothing in this file is reachable from a request. This repository serves two endpoints and
 * neither is `@Async`; `ADR-009` is why a third was not added to make this measurable. What is
 * planted is the boundary itself, in the smallest shape carrying every limb of the question:
 * a transaction, a request scope, an MDC entry and a bare `ThreadLocal`.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class OpsAsyncConfiguration {

    /**
     * **The green arm, and it is not the whole remedy.** `R31` §5.
     *
     * A `TaskDecorator` captures the submitting thread's context and re-establishes it on the
     * executing thread. It carries the MDC across, and it could carry the request attributes
     * across — and the second of those is a trap wearing a fix's clothes, because the request
     * they belong to may have completed and been recycled by the time the task runs. This
     * decorator copies the MDC and **deliberately does not copy the request attributes**;
     * `R31` §5 measures both and says why only one of them is offered.
     *
     * **A transaction cannot be copied by anything of this shape, and that is not a
     * limitation of this class.** A transaction here is a JDBC connection bound to a thread by
     * `TransactionSynchronizationManager`; re-binding that connection to a second thread that
     * runs concurrently is not propagation, it is two threads issuing statements down one
     * connection. `R31` §8 keeps that as the risk that does not go away.
     *
     * Off by default, so the red state stays runnable in one binary — `R4` §2's argument.
     */
    @Bean
    fun opsAsyncContextDecorator(
        @Value("\${proxima.ops.async-context:none}") mode: String,
    ): TaskDecorator = TaskDecorator { runnable ->
        if (mode != "copy-mdc") {
            runnable
        } else {
            val captured = MDC.getCopyOfContextMap()
            Runnable {
                val previous = MDC.getCopyOfContextMap()
                if (captured != null) MDC.setContextMap(captured) else MDC.clear()
                try {
                    runnable.run()
                } finally {
                    if (previous != null) MDC.setContextMap(previous) else MDC.clear()
                }
            }
        }
    }
}

/**
 * What survived the hop. Every field is read on the executing thread and nowhere else.
 *
 * `threadName` is here because **"which executor is actually used" must not be answered from
 * memory.** Boot registers its `ThreadPoolTaskExecutor` under both `applicationTaskExecutor`
 * and the alias Spring's async infrastructure looks for, so an unqualified `@Async` lands
 * there and the thread is named `task-N`. Turning on `spring.threads.virtual.enabled`
 * replaces it and the name changes. The test reads the name rather than asserting the wiring,
 * because the name is what the JVM did.
 */
data class BoundaryObservation(
    val threadName: String,
    val virtualThread: Boolean,
    val transactionActive: Boolean,
    val transactionName: String?,
    val requestAttributesPresent: Boolean,
    val mdcValue: String?,
    val threadLocalValue: String?,
)

/** A plain `ThreadLocal`, to separate "Spring did not propagate it" from "nothing propagates". */
object OpsThreadLocal {
    val VALUE: ThreadLocal<String?> = ThreadLocal()
    const val MDC_KEY = "proxima.ops.correlation"
}

@Component
class AsyncBoundaryProbe {

    /** Reads the four contexts on whatever thread Spring chose. */
    @Async
    fun observeAsync(): CompletableFuture<BoundaryObservation> =
        CompletableFuture.completedFuture(observeHere())

    /** The same reading on the caller's thread, so the async row has a control beside it. */
    fun observeHere(): BoundaryObservation {
        val thread = Thread.currentThread()
        return BoundaryObservation(
            threadName = thread.name,
            virtualThread = thread.isVirtual,
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive(),
            transactionName = TransactionSynchronizationManager.getCurrentTransactionName(),
            requestAttributesPresent = RequestContextHolder.getRequestAttributes() != null,
            mdcValue = MDC.get(OpsThreadLocal.MDC_KEY),
            threadLocalValue = OpsThreadLocal.VALUE.get(),
        )
    }
}

/** Raised on purpose to roll the caller back. Never caught inside the application. */
class DeliberateRollback : RuntimeException("rolling the caller back on purpose")

@Service
class AsyncWriter(private val learners: LearnerRepository) {

    /**
     * Writes on the async thread. **No propagation setting is involved — the thread change
     * already made this a separate transaction**, which is the point `R31` §3.4 makes against
     * the vocabulary `R6` and `MasteryCounter` use for `REQUIRES_NEW`.
     *
     * **`@Transactional` is deliberately absent from this method**, and the absence is the
     * cleaner experiment. Putting both annotations here would make the result depend on which
     * advisor is outermost — `@EnableAsync` and `@EnableTransactionManagement` both default to
     * `Ordered.LOWEST_PRECEDENCE`, so the ordering is a tie this repository would be relying on
     * rather than choosing. Without it the transaction is the one `SimpleJpaRepository.save`
     * opens, on the async thread, committed before this method returns — so `join()` observes a
     * committed row and `R31` §3.4's count is not a race.
     */
    @Async
    fun insertLearner(externalRef: String): CompletableFuture<Long> =
        CompletableFuture.completedFuture(learners.save(Learner(externalRef)).id!!)
}

@Service
class TransactionalAsyncCaller(
    private val learners: LearnerRepository,
    private val writer: AsyncWriter,
    private val probe: AsyncBoundaryProbe,
) {

    /**
     * **`R31` §3.4 in one method, and every line of it reads as correct.**
     *
     * The caller is transactional. It writes a row, hands work to a collaborator, waits for
     * it, and then fails. A reader who believes `@Async` work joins the caller's transaction
     * expects both rows to disappear. One of them does.
     *
     * Called from a test and nowhere else. Public, and in its own bean, so that
     * `TransactionBoundaryRules` is satisfied by construction rather than by care.
     */
    @Transactional
    fun writeBothThenRollBack(prefix: String) {
        learners.save(Learner("$prefix-sync"))
        writer.insertLearner("$prefix-async").join()
        throw DeliberateRollback()
    }

    /** The caller's reading, taken inside the transaction, with the async one beside it. */
    @Transactional
    fun observeInsideTransaction(): Pair<BoundaryObservation, BoundaryObservation> =
        probe.observeHere() to probe.observeAsync().join()
}
