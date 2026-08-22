package net.gseek.proxima.ops

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * **`R30` §3.1 — when is a thread pool's maximum size ever used.**
 *
 * The answer is *"only when the queue is full"*, and the consequence is that
 * **`max-size` and `queue-capacity` are one setting, not two.** A configuration reading
 * `core 8, max 200, queue unbounded` is a pool that runs at 8 forever and has never once
 * been near 200 — and it reads, to anyone scanning it, like a pool that can reach 200.
 *
 * `ThreadPoolExecutor.execute` is documented as three cases in order: below core, start a
 * thread; otherwise try to enqueue; **only if enqueueing fails**, start a thread up to
 * maximum; and only if that fails, reject. An unbounded queue never fails to accept, so the
 * third case is unreachable and so is the fourth.
 *
 * ## Why this is a unit test and not a load run
 *
 * Nothing here is a duration. Every number below is a **count** — pool size, queue depth,
 * rejections — and `ThreadPoolExecutor` creates its threads *synchronously inside*
 * `execute()`, so by the time the submission loop returns, the pool size it reached is final.
 * There is no sampling, no sleeping and no race, which is why this could be taken while the
 * machine was busy and `R29`'s figures could not.
 *
 * ## The real bean's shape is asserted elsewhere
 *
 * `PoolCensusGateTest` reads this application's actual `applicationTaskExecutor` and finds
 * `core=8, max=2147483647, queueCapacity=2147483647`. This class is the mechanism behind why
 * that combination means what it means.
 */
class ThreadPoolBoundsTest {

    /**
     * `R30` §3.1, arm A — **red**. The generous-looking maximum, never reached.
     *
     * `max=8` is written down and `2` is what runs. Eighteen of twenty tasks sit in a queue
     * that has no limit, so nothing is refused and nothing is logged: the only symptom
     * available to anybody is that work takes longer than it should.
     */
    @Test
    fun `an unbounded queue makes the maximum unreachable`() {
        val arm = submit(core = 2, max = 8, queue = Int.MAX_VALUE, tasks = 20)

        assertEquals(
            2, arm.peakPoolSize,
            "core is 2 and max is 8, and the pool never left core. THIS ASSERTION EXPECTS " +
                "THE DEFECT: with an unbounded queue, ThreadPoolExecutor's third case -- " +
                "grow towards maximum -- is unreachable, so `max=8` is a number that " +
                "documents nothing. R30 §3.1",
        )
        assertEquals(18, arm.queued, "the other eighteen went to the queue, not to a thread")
        assertEquals(
            0, arm.rejected,
            "nothing is refused, and that is the whole problem: an unbounded queue converts " +
                "overload from a refusal the caller can see into latency and heap it cannot",
        )
    }

    /**
     * `R30` §3.1, arm B — **green, and it buys a new failure mode rather than removing one.**
     *
     * Bounding the queue at 2 makes the maximum reachable: the pool grows to 8, which is what
     * the configuration always claimed. **Ten of the twenty tasks are now rejected outright.**
     *
     * That is not a regression. It is the same overload, delivered as a refusal at submission
     * time instead of as unbounded queueing — and `R30` §5 argues that a caller who is told
     * `no` has options a caller who is told `later` does not. It is stated here rather than in
     * the report alone because the number is the argument.
     */
    @Test
    fun `bounding the queue reaches the maximum and starts refusing`() {
        val arm = submit(core = 2, max = 8, queue = 2, tasks = 20)

        assertEquals(
            8, arm.peakPoolSize,
            "with a queue of 2 the pool grows to max. The setting that changed is the QUEUE",
        )
        assertEquals(2, arm.queued, "the bounded queue is full and stays full")
        assertEquals(
            10, arm.rejected,
            "8 running + 2 queued = 10 accepted, and the remaining 10 are refused with " +
                "TaskRejectedException. R30 §5: this is the cost of making `max` mean " +
                "something, and it is a cost worth naming before choosing it",
        )
    }

    /**
     * The negative half. **A bound that is never approached refuses nothing**, so this arm
     * shows the same bounded configuration behaving identically to the unbounded one when the
     * load fits — which is why the defect survives every test anybody writes for it.
     */
    @Test
    fun `the same bounded pool is indistinguishable from the unbounded one under small load`() {
        val bounded = submit(core = 2, max = 8, queue = 2, tasks = 2)
        val unbounded = submit(core = 2, max = 8, queue = Int.MAX_VALUE, tasks = 2)

        assertEquals(bounded.peakPoolSize, unbounded.peakPoolSize)
        assertEquals(0, bounded.rejected)
        assertEquals(0, unbounded.rejected)
        assertTrue(
            bounded.peakPoolSize <= 2,
            "two tasks, two core threads, and no way for any test at this size to tell the " +
                "two configurations apart. R30 §4",
        )
    }

    private data class Arm(val peakPoolSize: Int, val queued: Int, val rejected: Int)

    /**
     * Submits [tasks] tasks that park until released, then reads the pool's counters.
     *
     * The read is taken **after every submission has returned** and is exact rather than
     * sampled: `ThreadPoolExecutor` adds workers on the submitting thread, so no thread can
     * appear afterwards. The latch only exists so the test does not leave parked threads
     * behind; no assertion depends on it.
     */
    private fun submit(core: Int, max: Int, queue: Int, tasks: Int): Arm {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = core
        executor.maxPoolSize = max
        executor.queueCapacity = queue
        executor.setThreadNamePrefix("r30-")
        executor.initialize()

        val release = CountDownLatch(1)
        val accepted = AtomicInteger()
        val rejected = AtomicInteger()
        try {
            repeat(tasks) {
                try {
                    executor.execute {
                        accepted.incrementAndGet()
                        release.await()
                    }
                } catch (_: TaskRejectedException) {
                    rejected.incrementAndGet()
                }
            }
            val pool = executor.threadPoolExecutor
            return Arm(
                peakPoolSize = pool.poolSize,
                queued = pool.queue.size,
                rejected = rejected.get(),
            )
        } finally {
            release.countDown()
            executor.shutdown()
            executor.threadPoolExecutor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
