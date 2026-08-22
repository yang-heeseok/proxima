package net.gseek.proxima.ops

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import org.springframework.stereotype.Component

/**
 * **The fourth pool: the one nobody created.**
 *
 * A search on 2026-08-22, **before this file existed**, found **zero** uses of `parallelStream()`
 * in `api/src/main` and `seed/src/main`. So the trap is planted rather than found — it only exists
 * once somebody writes the call, and the value of writing it here is that the shape *"a slice
 * titled three pools turned out to have five"* is what `R32` reports.
 *
 * ⚠️ **The count is now one, and it is this file.** Stated in the present tense rather than left
 * as a sentence that was true when it was written and false the moment it was saved — which is the
 * failure `R27` is about, and which this round has now collected four times.
 *
 * `parallelStream()` does not create a pool. It submits to `ForkJoinPool.commonPool()`, which
 * is **one pool per JVM**, shared by every caller in the process — including any library that
 * ever calls a parallel stream, and including `CompletableFuture`'s no-executor overloads.
 * Nothing in a stack trace says so and nothing in configuration mentions it.
 *
 * ## Two things this file exists to measure, both of them counts
 *
 * 1. **What determines that pool's size.** Read at run time from
 *    `ForkJoinPool.commonPool().parallelism` and from the system property that overrides it,
 *    never written down from memory. `PoolCensus` takes the reading.
 * 2. **What blocking inside it does to an unrelated caller.** A `ForkJoinPool` grows a
 *    compensation thread when a task blocks *through a `ManagedBlocker`*. Ordinary blocking —
 *    a JDBC call, a sleep, a socket read — is invisible to it, so the pool does not grow and
 *    the workers are simply gone. The observation is the **peak number of elements in flight**
 *    and the **set of threads that ran them**, which are counts and therefore contend with
 *    nothing; no duration is published from this class.
 */
@Component
class SharedPoolWork {

    /**
     * Runs [work] over [items] on `parallelStream()` and reports what ran where.
     *
     * **The caller's own thread is in the answer and that surprises people.** A parallel
     * stream does not hand the work off and wait; the submitting thread joins in and executes
     * elements too. So a request thread calling this is not merely *waiting* on the common
     * pool, it is *inside* it.
     */
    fun <T> observeParallelStream(items: List<T>, work: (T) -> Unit): SharedPoolObservation {
        val caller = Thread.currentThread().name
        val recorder = Recorder(caller)
        items.parallelStream().forEach { recorder.run(it, work) }
        return recorder.observation(items.size, dedicated = false)
    }

    /**
     * The same work, on a pool this code owns. `R32` §5's remedy arm.
     *
     * **`ForkJoinPool.submit` then `join` is what makes a parallel stream run somewhere
     * else** — the stream API has no executor parameter, so the only way to move it is to run
     * the terminal operation from inside another `ForkJoinPool`. That is a real limitation of
     * the API rather than a style choice, and it is why the remedy `R32` reaches is usually
     * "do not use a parallel stream for this" rather than "use a different one".
     */
    fun <T> observeOnDedicatedPool(
        pool: ForkJoinPool,
        items: List<T>,
        work: (T) -> Unit,
    ): SharedPoolObservation {
        val caller = Thread.currentThread().name
        val recorder = Recorder(caller)
        pool.submit { items.parallelStream().forEach { recorder.run(it, work) } }.join()
        return recorder.observation(items.size, dedicated = true)
    }

    /**
     * Blocks the current thread the way a JDBC call would, with no `ManagedBlocker` around it.
     *
     * Used as the [work] argument by the tests. It is `LockSupport.parkNanos` rather than
     * `Thread.sleep` for one reason: `Thread.sleep` on a **virtual** thread unmounts it, which
     * would silently change what the same experiment measures under `R33`'s configuration.
     * Parking does too, so neither is neutral — the tests state which they used.
     */
    fun blockWithoutTellingThePool(millis: Long) {
        LockSupport.parkNanos(millis * 1_000_000)
    }

    private class Recorder(private val callerThread: String) {
        private val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val inFlight = AtomicInteger()
        private val peak = AtomicInteger()

        fun <T> run(item: T, work: (T) -> Unit) {
            threads.add(Thread.currentThread().name)
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                work(item)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        fun observation(elements: Int, dedicated: Boolean) = SharedPoolObservation(
            elements = elements,
            threads = threads.toSortedSet(),
            peakConcurrent = peak.get(),
            callerThreadParticipated = threads.contains(callerThread),
            commonPoolParallelism = ForkJoinPool.commonPool().parallelism,
            commonPoolSizeAfter = ForkJoinPool.commonPool().poolSize,
            ranOnDedicatedPool = dedicated,
        )
    }
}

/**
 * One run's worth of counts. **No duration is in here on purpose** — a duration would need
 * the machine to be quiet and these facts do not, so they were taken while it was not.
 */
data class SharedPoolObservation(
    val elements: Int,
    val threads: Set<String>,
    val peakConcurrent: Int,
    val callerThreadParticipated: Boolean,
    val commonPoolParallelism: Int,
    val commonPoolSizeAfter: Int,
    val ranOnDedicatedPool: Boolean,
) {
    val distinctThreads: Int get() = threads.size

    /** How many common-pool workers touched this run. The caller's thread is not one. */
    val commonPoolWorkersUsed: Int
        get() = threads.count { it.startsWith("ForkJoinPool.commonPool-") }
}
