package net.gseek.proxima.mastery

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertEquals

/**
 * `E1`'s second half: **what each layer costs, and where the ranking inverts.**
 *
 * `LayeredRemedyTest` established the boundary — ① and ② are correct only while there is one
 * instance. That is a correctness result and it says nothing about why anyone reaches for ①
 * or ② in the first place, which is that they are cheap.
 *
 * ⛔ **This class must not be used to conclude that CAS is faster than locking.** That claim
 * is contention-dependent and inverts; the measurement worth taking is **where** it inverts,
 * which is why every arm is swept across thread counts rather than run at one.
 *
 * ⭐ **THIS IS THE ONLY CLASS IN SLICE E THAT PUBLISHES DURATIONS**, and it therefore requires
 * an exclusive machine. Every other figure in `R34`–`R38` is a count, a row value, a SQLSTATE
 * or a boolean, and was deliberately taken while other slices were running. This one may not
 * be. See `R34` §3.4's environment block for the verified floor it ran against.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LayeredCostTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    /** Total increments, held constant across every thread count so the work is comparable. */
    private val inMemoryTotal = 2_000_000
    private val databaseTotal = 1_000

    /** The contention sweep. One thread is the uncontended floor; 32 is four per core. */
    private val threadCounts = listOf(1, 2, 4, 8, 16, 32)

    private val repetitions = 3

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-94%')")
        jdbc.execute("delete from learner where external_ref like 'learner-94%'")
        jdbc.execute("delete from concept where code like 'concept-94%'")
    }

    private fun freshMastery(tag: String): Long {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-94$tag",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 94', 'G5-6') returning id",
            Long::class.java, "concept-94$tag",
        )!!
        return jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    /**
     * Runs [total] operations across [threads], released together, and returns milliseconds.
     *
     * The barrier matters for a cost measurement as much as for a race one: without it the
     * first threads finish before the last start, and a "32-thread" figure is really a
     * staggered mixture of contention levels.
     */
    private fun timeOnce(threads: Int, total: Int, op: (Int) -> Unit): Long {
        val per = total / threads
        val gate = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val tasks = (0 until threads).map {
                Callable {
                    gate.await(60, TimeUnit.SECONDS)
                    repeat(per) { op(it) }
                }
            }
            val start = System.nanoTime()
            pool.invokeAll(tasks)
            return (System.nanoTime() - start) / 1_000_000
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }
    }

    private data class Timing(val median: Long, val low: Long, val high: Long) {
        /** Spread as a percentage of the median, so a quiet run can be told from a noisy one. */
        val spreadPct: Long get() = if (median == 0L) 0 else (high - low) * 100 / median
        override fun toString() = "%6d ms (%d-%d, spread %d%%)".format(median, low, high, spreadPct)
    }

    private fun measure(threads: Int, total: Int, newOp: () -> (Int) -> Unit): Timing {
        // One discarded warm-up per arm per thread count. R6 §8 records a 76% spread on its
        // fastest arm; an un-warmed JIT is the first thing that produces one.
        timeOnce(threads, total / 4, newOp())
        val runs = (1..repetitions).map { timeOnce(threads, total, newOp()) }.sorted()
        return Timing(runs[runs.size / 2], runs.first(), runs.last())
    }

    /**
     * ⭐ **The inversion, with no database anywhere in it.**
     *
     * A monitor against a CAS loop, same work, same threads, same instant. Under low
     * contention CAS should win — no blocking, no monitor bookkeeping. As contention rises the
     * CAS loop retries more and burns CPU while a monitor parks a loser instead of spinning
     * it, and the ranking is expected to cross. **Where it crosses is the result.**
     */
    @Test
    fun `where a monitor overtakes a CAS loop, with no database in the way`() {
        println("E1 >>> IN-MEMORY COST -- $inMemoryTotal increments, median of $repetitions, warm-up discarded")
        println("E1 >>> %-8s %-34s %-34s %s".format("threads", "(1) synchronized", "(2) AtomicInteger", "ranking"))

        val crossings = mutableListOf<String>()
        for (threads in threadCounts) {
            val counter = LayeredCounter(jdbc, transactionManager)
            val sync = measure(threads, inMemoryTotal) { { counter.incrementInMemorySynchronized() } }
            val cas = measure(threads, inMemoryTotal) { { counter.incrementInMemoryOnly() } }
            val winner = if (sync.median < cas.median) "(1) synchronized" else "(2) CAS"
            crossings += "$threads:$winner"
            println("E1 >>> %-8d %-34s %-34s %s".format(threads, sync, cas, winner))
        }
        println("E1 >>> ranking by thread count: $crossings")
    }

    /**
     * The same three layers with the database in place, at the contention `R6` §3 used.
     *
     * This is the comparison that explains why anyone writes ② at all: the write-behind form
     * touches the database **once**, and the other two touch it once or twice per increment.
     * The gap is not subtle and it is the entire reason the defect `R34` measures gets written.
     */
    @Test
    fun `what a round trip costs, against a primitive that does not take one`() {
        val threads = 10
        println("E1 >>> DATABASE COST -- $databaseTotal increments, $threads threads, median of $repetitions")

        val syncId = freshMastery("s")
        val casId = freshMastery("c")
        val dbId = freshMastery("d")

        val sync = measure(threads, databaseTotal) {
            val c = LayeredCounter(jdbc, transactionManager); { c.incrementBySynchronized(syncId) }
        }
        val cas = measure(threads, databaseTotal) {
            val c = LayeredCounter(jdbc, transactionManager); { c.incrementByCas(casId) }
        }
        val db = measure(threads, databaseTotal) {
            val c = LayeredCounter(jdbc, transactionManager); { c.incrementByDatabase(dbId) }
        }
        val behind = measure(threads, databaseTotal) {
            val c = LayeredCounter(jdbc, transactionManager); { c.incrementInMemoryOnly() }
        }

        println("E1 >>> (1) synchronized + read-modify-write   $sync")
        println("E1 >>> (2) CAS + write-through                $cas")
        println("E1 >>> (3) one atomic statement               $db")
        println("E1 >>> (2) CAS in memory, no round trip       $behind")

        // Correctness is asserted elsewhere; this arm exists for the durations. What it must
        // assert is that the database arms ACTUALLY REACHED THE DATABASE, because the failure
        // mode of a cost measurement is a fast number produced by nothing happening.
        //
        // The expected value is the warm-up plus every repetition, because `measure` runs the
        // arm once to warm it and then `repetitions` times, all against the same row.
        val expectedApplied = databaseTotal / 4 + repetitions * databaseTotal
        assertEquals(
            expectedApplied,
            jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, dbId),
            "the (3) arm must have applied every increment of every run, or its duration is " +
                "measuring nothing",
        )
        assertEquals(
            expectedApplied,
            jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, syncId),
            "the (1) arm is correct at one instance, so it too must have applied every increment",
        )
    }
}
