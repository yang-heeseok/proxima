package net.gseek.proxima.mastery

import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E2` — a singleton bean holding mutable state.
 *
 * **The first test in this class is the one that matters most, and it passes.** It is the
 * single-threaded test anybody would write against a cache, and it is green against the
 * defective field. Nothing about the declaration is unusual, there is no annotation to forget
 * and no configuration to get wrong; the defect arrives with the second thread and there is
 * no unit test that can be added to catch it single-threaded.
 *
 * No database and no Spring context — the defect is in the heap, and putting a container
 * underneath it would only make it slower to see. Every figure is a count of entries or a
 * count of loader invocations, so nothing here contends with another slice's load.
 */
class SingletonStateTest {

    private val threads = 8
    private val keysPerThread = 250
    private val expectedEntries = threads * keysPerThread

    private fun <T> allAtOnce(count: Int, body: (Int) -> T): List<Result<T>> {
        val gate = CyclicBarrier(count)
        val pool = Executors.newFixedThreadPool(count)
        try {
            val tasks = (0 until count).map { i ->
                Callable {
                    runCatching {
                        gate.await(30, TimeUnit.SECONDS)
                        body(i)
                    }
                }
            }
            return pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    /** **The test that passes.** One thread, one cache, every entry present. */
    @Test
    fun `single-threaded, the plain HashMap cache is perfect`() {
        val cache = SharedScoreCache()
        repeat(expectedEntries) { cache.putPlain(it.toLong(), BigDecimal("0.500")) }

        println("E2 >>> single-threaded plain     entries=${cache.plainSize} expected=$expectedEntries")
        assertEquals(expectedEntries, cache.plainSize, "one thread cannot race itself")
    }

    /**
     * **The same cache, the same number of distinct keys, eight threads.**
     *
     * Every key is written by exactly one thread, so there is no last-writer-wins question
     * here and no ambiguity about the expected size: it is one entry per key.
     */
    @Test
    fun `the plain HashMap loses entries under concurrency, and raises nothing`() {
        val cache = SharedScoreCache()
        val results = allAtOnce(threads) { t ->
            repeat(keysPerThread) { k -> cache.putPlain((t * keysPerThread + k).toLong(), BigDecimal("0.500")) }
        }
        val raised = results.count { it.isFailure }
        println("E2 >>> plain HashMap             entries=${cache.plainSize} expected=$expectedEntries threadsRaised=$raised")
        results.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let {
            println("E2 >>>   verbatim: ${it::class.java.name}: ${it.message}")
        }

        assertTrue(
            cache.plainSize < expectedEntries,
            "every key was written by exactly one thread, so a map that kept them all would " +
                "have $expectedEntries; got ${cache.plainSize}. If this passes, the writes " +
                "did not overlap and the test proved nothing",
        )
        assertEquals(
            0, raised,
            "AND NOTHING RAISED -- that is the finding, not an aside. The entries are simply " +
                "not there, and the only way to know is to have counted what should have been",
        )
    }

    /** The repair everyone reaches for, on the same shape. */
    @Test
    fun `a ConcurrentHashMap keeps every entry under concurrency`() {
        val cache = SharedScoreCache()
        allAtOnce(threads) { t ->
            repeat(keysPerThread) { k -> cache.putConcurrent((t * keysPerThread + k).toLong(), BigDecimal("0.500")) }
        }
        println("E2 >>> ConcurrentHashMap         entries=${cache.concurrentSize} expected=$expectedEntries")

        assertEquals(expectedEntries, cache.concurrentSize, "a concurrent map does not lose entries")
    }

    /**
     * ⭐ **What the concurrent collection does not fix.**
     *
     * Eight threads, **one** key, released together. Both calls the loader path makes are
     * atomic; the pair is not. The cache ends up correct — and the expensive thing it exists
     * to avoid ran more than once, with nothing reporting it.
     *
     * The assertion is the belief that swapping the map fixed the class of problem.
     */
    @Test
    fun `on a thread-safe map, check-then-act loads once per THREAD, not once per key`() {
        val cache = SharedScoreCache()
        allAtOnce(threads) { cache.loadOnceCheckThenAct(1L) { cache.load(BigDecimal("0.750")) } }
        val checkThenAct = cache.loadCount

        val atomic = SharedScoreCache()
        allAtOnce(threads) { atomic.loadOnceAtomically(1L) { atomic.load(BigDecimal("0.750")) } }
        val computeIfAbsent = atomic.loadCount

        println("E2 >>> check-then-act on CHM     loads=$checkThenAct  (threads=$threads, keys=1)")
        println("E2 >>> computeIfAbsent on CHM    loads=$computeIfAbsent  (threads=$threads, keys=1)")

        assertEquals(1, computeIfAbsent, "computeIfAbsent applies the function at most once per key")
        assertTrue(
            checkThenAct > 1,
            "check-then-act must load more than once, or the threads did not overlap and " +
                "this arm compared nothing: got $checkThenAct",
        )
    }

    /**
     * Reading the plain map while another thread writes it.
     *
     * Kept as its own arm because this failure has a **name** and the one above does not. A
     * `ConcurrentModificationException` is the best outcome on this page: it is the only arm
     * of `E2` where the defect announces itself.
     */
    @Test
    fun `iterating the plain map while it is written did NOT raise here`() {
        val cache = SharedScoreCache()
        repeat(1_000) { cache.putPlain(it.toLong(), BigDecimal("0.500")) }

        val writerStop = AtomicInteger(0)
        val results = allAtOnce(2) { role ->
            if (role == 0) {
                repeat(200_000) { cache.putPlain((10_000 + it).toLong(), BigDecimal("0.100")) }
                writerStop.incrementAndGet()
            } else {
                var seen = 0
                while (writerStop.get() == 0) seen = cache.iteratePlain()
                seen
            }
        }
        val raised = results.mapNotNull { it.exceptionOrNull() }
        println("E2 >>> iterate-while-writing     raised=${raised.size}")
        raised.firstOrNull()?.let { println("E2 >>>   verbatim: ${it::class.java.name}: ${it.message}") }

        assertEquals(
            0, raised.size,
            "NOT REPRODUCED, and pinned as such. A ConcurrentModificationException would have " +
                "been the one place in E2 where the defect announced itself; it did not fire. " +
                "This assertion characterises that silence -- it is NOT a claim that iterating " +
                "a racing HashMap is safe. See R35 §8",
        )
    }
}
