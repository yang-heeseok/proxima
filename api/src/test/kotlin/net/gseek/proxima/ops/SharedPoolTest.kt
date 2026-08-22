package net.gseek.proxima.ops

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * **`R32` — the fourth pool, the one nobody created.**
 *
 * Every assertion here is a **count or a set of thread names**. Nothing is a duration, which
 * is deliberate: these are properties of `ForkJoinPool`, not of how fast this machine is, so
 * they were taken while other work was running on it and would read the same on a quiet one.
 * `R32` §3 says so beside each figure, and it is the reason this trap cost the measurement
 * lock nothing while `R29` cost it an hour.
 *
 * ## How the blocking is done, and why it is not a `CountDownLatch`
 *
 * The tasks park in a poll loop through [SharedPoolWork.blockWithoutTellingThePool] rather
 * than awaiting a latch. **`CountDownLatch.await()` reaches `AbstractQueuedSynchronizer`,
 * and the `j.u.c` synchronisers can hand a `ForkJoinPool` a `ManagedBlocker`** — which is
 * precisely the mechanism that lets the pool compensate by starting another worker. Blocking
 * that way would measure the compensation path; blocking by parking measures what an ordinary
 * JDBC call or socket read does, which is the case the report is about. Stating which of the
 * two was used is the whole difference between the two results.
 */
class SharedPoolTest {

    private val work = SharedPoolWork()

    /**
     * `R32` §3.1. **What determines the pool's size, read rather than recalled.**
     */
    @Test
    fun `the common pool's size comes from the processor count and not from any configuration`() {
        val processors = Runtime.getRuntime().availableProcessors()
        val parallelism = ForkJoinPool.commonPool().parallelism
        val property = System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism")

        println("R32 §3.1 availableProcessors=$processors commonPool.parallelism=$parallelism property=${property ?: "unset"}")

        assertNull(
            property,
            "nothing in this repository sets the common pool's parallelism, so the reading " +
                "below is the JVM's own default and not a decision anybody made",
        )
        assertEquals(
            processors - 1, parallelism,
            "availableProcessors - 1. THE POINT IS THAT NO FILE IN THIS TREE SAYS THIS. The " +
                "pool a parallelStream uses is sized by the box, so the same code has a " +
                "different concurrency inside a two-core container than on this host -- and " +
                "R23 already found this JVM taking its heap from a cgroup rather than from " +
                "anything a report would have written down",
        )
    }

    /**
     * `R32` §3.2. **The caller is a worker, and blocking does not grow the pool.**
     *
     * Peak in flight reaches `parallelism + 1` — the pool's workers **plus the submitting
     * thread**, because `ForkJoinTask.invoke()` executes on the calling thread before it waits.
     * A reader who budgets "seven concurrent" has under-counted by one; a reader who assumes
     * the calling thread is free meanwhile has mis-modelled the request entirely.
     */
    @Test
    @Timeout(120)
    fun `a parallel stream executes on the caller's thread as well as on the pool`() {
        val parallelism = ForkJoinPool.commonPool().parallelism
        val expectedPeak = parallelism + 1
        val released = AtomicBoolean(false)
        val arrived = CountDownLatch(expectedPeak)

        // A plain thread, so its await() cannot be turned into a ManagedBlocker by a pool it
        // is not a member of. It frees the parked tasks the moment the peak has been reached,
        // which makes the peak a fact about the pool rather than about a sleep length.
        val watchdog = Thread({
            arrived.await(60, TimeUnit.SECONDS)
            released.set(true)
        }, "r32-watchdog")
        watchdog.start()

        val observation = try {
            work.observeParallelStream((1..expectedPeak * 2).toList()) {
                arrived.countDown()
                while (!released.get()) work.blockWithoutTellingThePool(20)
            }
        } finally {
            released.set(true)
            watchdog.join(TimeUnit.SECONDS.toMillis(10))
        }

        println("R32 §3.2 $observation")

        assertTrue(
            observation.callerThreadParticipated,
            "the submitting thread ran elements itself. parallelStream() does not hand work " +
                "off and wait -- it joins in, which is why the concurrency below is " +
                "parallelism+1 and not parallelism",
        )
        assertEquals(
            expectedPeak, observation.peakConcurrent,
            "peak in flight is the pool's workers plus the caller, and blocking inside them " +
                "did NOT grow the pool. A ForkJoinPool compensates for a ManagedBlocker and " +
                "for nothing else, so an ordinary blocking call is invisible to it",
        )
        assertEquals(
            parallelism, observation.commonPoolWorkersUsed,
            "and every one of them was a common-pool worker shared with the whole JVM",
        )
    }

    /**
     * `R32` §3.3. ⭐ **The finding. An unrelated caller is starved, silently.**
     *
     * The first caller occupies the common pool. The second shares no code, no bean and no
     * data with it, and its parallel stream collapses to **one thread — its own** — with no
     * exception, no log line, and nothing in any configuration file to explain it.
     *
     * That shape is what makes it an operational incident rather than a slow function:
     * whoever is paged sees the *second* feature degrade, and the cause is in the first.
     */
    @Test
    @Timeout(180)
    fun `one caller holding the common pool serialises an unrelated caller`() {
        val parallelism = ForkJoinPool.commonPool().parallelism
        val released = AtomicBoolean(false)
        val occupied = CountDownLatch(parallelism)

        val hog = Thread({
            work.observeParallelStream((1..parallelism * 4).toList()) {
                occupied.countDown()
                while (!released.get()) work.blockWithoutTellingThePool(20)
            }
        }, "r32-hog")
        hog.start()

        try {
            assertTrue(
                occupied.await(60, TimeUnit.SECONDS),
                "the first caller never took the pool, so this test would have measured " +
                    "nothing. THIS IS THE PRECONDITION, asserted rather than assumed -- " +
                    "ADR-015 is this repository's decision that a race test proves its own",
            )

            val starved = work.observeParallelStream((1..parallelism * 4).toList()) { }
            println("R32 §3.3 starved = $starved")

            assertEquals(
                0, starved.commonPoolWorkersUsed,
                "not one common-pool worker was available to the second caller. Every worker " +
                    "is parked inside the first caller's stream, and a parked ForkJoin worker " +
                    "is not replaced",
            )
            assertEquals(
                1, starved.distinctThreads,
                "the second caller's `parallel` stream ran entirely on its own thread. It is " +
                    "not slower-but-parallel; it is SEQUENTIAL, and nothing said so",
            )

            // The remedy arm, taken while the common pool is STILL held -- a remedy measured
            // after the contention cleared would be a remedy measured against nothing.
            val dedicated = ForkJoinPool(4)
            try {
                val rescued = work.observeOnDedicatedPool(dedicated, (1..32).toList()) { }
                println("R32 §5 dedicated = $rescued")
                assertEquals(
                    0, rescued.commonPoolWorkersUsed,
                    "the remedy's whole claim: this work no longer touches the shared pool",
                )
                assertTrue(
                    rescued.distinctThreads > 1,
                    "and it is still parallel while the common pool is fully occupied, which " +
                        "is exactly what the starved arm above could not manage",
                )
            } finally {
                dedicated.shutdownNow()
            }
        } finally {
            released.set(true)
            hog.join(TimeUnit.SECONDS.toMillis(60))
        }
    }
}
