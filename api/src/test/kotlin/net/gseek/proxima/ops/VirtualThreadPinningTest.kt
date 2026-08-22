package net.gseek.proxima.ops

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * **`R33` §3.3 — the conditions under which a virtual thread is pinned to its carrier, measured
 * on this JDK rather than recalled.**
 *
 * ⚠️ The whole point of this class is that **the list of pinning conditions is a thing nobody
 * should write from memory.** It moves between JDK releases — that is the entire subject of the
 * work that eventually removed the `synchronized` case — so a report naming the conditions has to
 * name the JDK it read them on. `R33`'s environment block says **Temurin 21.0.12+8**, and these
 * assertions are what that line is worth.
 *
 * ## Why the instrument is JFR and not a stopwatch
 *
 * The intuitive demonstration is a timing: block N virtual threads and see whether the batch
 * takes one unit or two. That is a **duration**, `ADR-004` rule 2 forbids CI asserting one, and it
 * would have needed the machine's measurement lock that `R29` was holding.
 *
 * `jdk.VirtualThreadPinned` is a JFR event the JVM emits when a virtual thread parks **while
 * pinned to its carrier**. Counting those events is a count. It is exact, it does not care how
 * fast this machine is, and it names the condition directly instead of inferring it from how long
 * something took.
 *
 * The event carries a default threshold in JFR's shipped settings, so it is enabled here with
 * `withThreshold(Duration.ZERO)` — a threshold would silently drop short pins and turn this into
 * a test that passes because it saw nothing.
 */
class VirtualThreadPinningTest {

    /**
     * `R33` §3.3. **Blocking inside `synchronized` pins; blocking inside a `ReentrantLock` does
     * not.**
     *
     * Both arms do the same thing — take a mutual-exclusion primitive, block while holding it,
     * release it. The only difference is which primitive, and that difference is the whole of
     * what `R33` had to go and look for in this repository's code.
     */
    @Test
    @Timeout(180)
    fun `blocking inside synchronized pins the carrier and blocking inside a ReentrantLock does not`() {
        val monitorPins = pinnedEventsWhile { threads -> blockInsideMonitor(threads) }
        val lockPins = pinnedEventsWhile { threads -> blockInsideReentrantLock(threads) }

        println("R33 §3.3 jdk.VirtualThreadPinned events: synchronized=$monitorPins ReentrantLock=$lockPins")

        assertTrue(
            monitorPins > 0,
            "blocking inside a `synchronized` block did not emit a single " +
                "jdk.VirtualThreadPinned event on ${System.getProperty("java.version")}. Either " +
                "this JDK no longer pins there -- in which case R33 §3.3 is describing a runtime " +
                "that no longer exists and its conclusion about the JDBC driver has to be retaken " +
                "-- or the recording is not capturing the event, which would make the negative " +
                "arm below vacuous",
        )
        assertEquals(
            0, lockPins,
            "a ReentrantLock is virtual-thread aware: the thread unmounts and the carrier is " +
                "released. THIS IS THE NEGATIVE HALF and it is what stops the assertion above " +
                "from being satisfied by an instrument that reports pinning for everything",
        )
    }

    /**
     * The consequence, as a count rather than as a duration.
     *
     * With one monitor per thread — so they do not exclude each other — the number of virtual
     * threads that can be **simultaneously inside** their blocking region is what a pinned carrier
     * costs. This is printed rather than asserted against a fixed number, because the scheduler is
     * permitted to grow extra carriers and **how far it grows is not something this repository has
     * established**; asserting a number here would be writing a JDK internal from memory, which is
     * the mistake the whole class exists to avoid.
     */
    @Test
    @Timeout(180)
    fun `how many pinned virtual threads can be inside their blocking region at once`() {
        val carriers = Runtime.getRuntime().availableProcessors()
        val attempted = carriers * 2

        val monitorInside = peakInside(attempted) { blockInsideMonitor(it) }
        val lockInside = peakInside(attempted) { blockInsideReentrantLock(it) }

        println(
            "R33 §3.3 availableProcessors=$carriers attempted=$attempted " +
                "peak simultaneously inside: synchronized=$monitorInside ReentrantLock=$lockInside",
        )

        assertEquals(
            attempted, lockInside,
            "every virtual thread blocking inside a ReentrantLock unmounted, so all of them are " +
                "inside at once regardless of how many carriers exist. That is the number the " +
                "`synchronized` arm is compared against",
        )
        assertTrue(
            monitorInside <= attempted,
            "sanity: cannot have more inside than were started",
        )
    }

    // ---------------------------------------------------------------- the two arms

    private fun blockInsideMonitor(gate: Gate) {
        val monitor = Any()
        synchronized(monitor) { gate.holdHere() }
    }

    private fun blockInsideReentrantLock(gate: Gate) {
        val lock = ReentrantLock()
        lock.lock()
        try {
            gate.holdHere()
        } finally {
            lock.unlock()
        }
    }

    // ---------------------------------------------------------------- the harness

    /**
     * A gate the blocked threads sit on. It parks rather than awaiting a latch, for the reason
     * `R32` §2.1 gives: a `java.util.concurrent` synchroniser can hand the scheduler a
     * `ManagedBlocker`, and blocking that way would measure the compensation path instead of the
     * pinning one.
     */
    private class Gate(count: Int) {
        val arrived = CountDownLatch(count)
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()

        @Volatile var released = false

        fun holdHere() {
            val now = inside.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            arrived.countDown()
            try {
                while (!released) LockSupport.parkNanos(20_000_000)
            } finally {
                inside.decrementAndGet()
            }
        }
    }

    private fun runArm(threads: Int, arm: (Gate) -> Unit): Gate {
        val gate = Gate(threads)
        val started = (1..threads).map {
            Thread.ofVirtual().name("r33-probe-$it").unstarted { arm(gate) }
        }
        started.forEach { it.start() }
        // Bounded wait: if fewer arrive than were started, that IS the observation and the peak
        // records it. Nothing is asserted about how long this took.
        gate.arrived.await(20, TimeUnit.SECONDS)
        gate.released = true
        started.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        return gate
    }

    private fun peakInside(threads: Int, arm: (Gate) -> Unit): Int = runArm(threads, arm).peak.get()

    /** Counts `jdk.VirtualThreadPinned` events emitted while [body] runs. */
    private fun pinnedEventsWhile(body: (Gate) -> Unit): Int {
        val file = createTempFile("r33-pinning", ".jfr")
        return try {
            Recording().use { recording ->
                recording.enable(PINNED_EVENT).withThreshold(Duration.ZERO).withStackTrace()
                recording.destination = file
                recording.start()
                runArm(Runtime.getRuntime().availableProcessors() * 2, body)
                recording.stop()
            }
            RecordingFile(file).use { rf ->
                var n = 0
                while (rf.hasMoreEvents()) {
                    if (rf.readEvent().eventType.name == PINNED_EVENT) n++
                }
                n
            }
        } finally {
            file.deleteIfExists()
        }
    }

    private companion object {
        const val PINNED_EVENT = "jdk.VirtualThreadPinned"
    }
}
