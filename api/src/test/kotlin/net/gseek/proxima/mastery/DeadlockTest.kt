package net.gseek.proxima.mastery

import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E4` — two transactions, two rows, opposite order.
 *
 * `R6` §8: *"One row, one column, one increment. Multi-row transactions introduce lock
 * ordering and deadlocks, **which this measured nothing about**."* `ADR-014` ledger entry
 * `6.6`, class **a**. This is that measurement.
 *
 * **Every number in this class is a count, a row value, a SQLSTATE or a yes/no.** None of it
 * is a duration, which is why it can be taken on a machine that is doing other things: a
 * deadlock either formed or it did not, and the server either detected it or it did not.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DeadlockTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var locker: RowLocker
    private var rowA = 0L
    private var rowB = 0L

    /** How many opposed pairs are run. A count of trials, not a load level. */
    private val pairs = 10

    @BeforeEach
    fun setUp() {
        locker = RowLocker(jdbc, transactionManager)
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-91-deadlock",
        )!!
        rowA = freshMastery(learnerId, "concept-91-a")
        rowB = freshMastery(learnerId, "concept-91-b")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-91%')")
        jdbc.execute("delete from learner where external_ref like 'learner-91%'")
        jdbc.execute("delete from concept where code like 'concept-91%'")
    }

    private fun freshMastery(learnerId: Long, code: String): Long {
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 91', 'G5-6') returning id",
            Long::class.java, code,
        )!!
        return jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    /** What one side of an opposed pair came back with. */
    private data class Side(
        val failed: Boolean,
        val type: String?,
        val sqlState: String?,
        val message: String?,
        val attempts: Int = 1,
    )

    private data class Pair2(val left: Side, val right: Side, val bothReachedBarrier: Boolean) {
        val casualties: Int get() = listOf(left, right).count { it.failed }
        val retries: Int get() = listOf(left, right).sumOf { it.attempts - 1 }
    }

    /**
     * Runs one opposed pair, with a rendezvous **between** the two locks.
     *
     * Without it the pair is a race that usually does not happen: whichever transaction
     * reaches its second lock first simply takes it and commits. The rendezvous makes the
     * cycle by construction — each side holds its first row and neither asks for its second
     * until the other is also holding — so a run that produces no deadlock is a fact about the
     * server, not about scheduling. `ADR-015` is the same requirement written for
     * `UniquenessRaceTest`: an arm has to prove its own precondition.
     *
     * ⭐ **The rendezvous gives up quietly instead of failing, and that is the instrument, not
     * a leniency.** Once a lock **order** is imposed, the two sides *cannot* both be sitting
     * between their locks — the second one is still queued on the first row. So the barrier
     * being unreachable is not a harness problem to be worked around; it is **the remedy
     * working, observable as a count.** [Pair2.bothReachedBarrier] is that count, and it is
     * what distinguishes *"no deadlock because the order prevented the interleaving"* from
     * *"no deadlock because this run happened not to race"* — the exact confusion `ADR-015`
     * exists to make impossible.
     */
    private fun opposedPair(
        rendezvousSeconds: Long,
        order: (RowLocker, Long, Long, () -> Unit) -> Unit,
    ): Pair2 {
        val gate = CyclicBarrier(2)
        val arrivals = AtomicInteger()
        val meet: () -> Unit = {
            try {
                gate.await(rendezvousSeconds, TimeUnit.SECONDS)
                arrivals.incrementAndGet()
            } catch (e: Exception) {
                // Timed out or found the barrier broken: the two sides were never both
                // between their locks. That is data, and for the ordered arm it is the result.
            }
            Unit
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(
                Callable { attempt { order(locker, rowA, rowB, meet) } },
                Callable { attempt { order(locker, rowB, rowA, meet) } },
            )
            val results = pool.invokeAll(tasks).map { it.get() }
            return Pair2(results[0], results[1], arrivals.get() == 2)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    /**
     * The same opposed pair, with the retry **outside** the transaction that failed.
     *
     * `R6` §3.3 is the standing warning about the other placement: a retry inside the
     * transaction it is retrying recovered nothing and cost time. A deadlock aborts the whole
     * transaction, so there is no version of this that works inside — every attempt here is a
     * fresh call into `RowLocker`, which opens its own `REQUIRES_NEW`.
     *
     * Only the **first** attempt takes the rendezvous. A retry that waited for a partner that
     * has already committed would deadlock the harness rather than the database.
     */
    private fun retryingOpposedPair(maxAttempts: Int): Pair2 {
        val gate = CyclicBarrier(2)
        val arrivals = AtomicInteger()
        val firstOnly: () -> Unit = {
            try {
                gate.await(30, TimeUnit.SECONDS)
                arrivals.incrementAndGet()
            } catch (e: Exception) {
            }
            Unit
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(rowA to rowB, rowB to rowA).map { (first, second) ->
                Callable {
                    var last = Side(failed = true, type = null, sqlState = null, message = "never ran")
                    for (n in 1..maxAttempts) {
                        val hook = if (n == 1) firstOnly else ({ })
                        last = attempt { locker.lockInGivenOrder(first, second, hook) }.copy(attempts = n)
                        if (!last.failed) break
                    }
                    last
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            return Pair2(results[0], results[1], arrivals.get() == 2)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
    }

    private fun attempt(body: () -> Unit): Side = try {
        body()
        Side(failed = false, type = null, sqlState = null, message = null)
    } catch (e: Throwable) {
        Side(
            failed = true,
            type = e::class.java.name,
            sqlState = generateSequence(e as Throwable?) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()?.sqlState,
            message = e.message?.lineSequence()?.firstOrNull(),
        )
    }

    /**
     * **The defect, pinned rather than asserted away.**
     *
     * Both transactions lock two rows they are entitled to lock, with two ordinary statements
     * each. Nothing violates a constraint, nothing is out of range, no row is missing. An
     * application author reading either side alone sees no defect — because there is none in
     * either side. **The defect is the pair**, and no reader of one file can see it.
     *
     * This asserts that the deadlock **does** happen, in the shape `LostUpdateTest`'s first
     * arm uses. If PostgreSQL ever stopped producing `40P01` here, this repository would want
     * to find out, because `R37` §5's whole argument rests on detection being what happens.
     */
    @Test
    fun `opposite order deadlocks, and the server kills exactly one side`() {
        val outcomes = (1..pairs).map { opposedPair(30) { l, first, second, between -> l.lockInGivenOrder(first, second, between) } }
        report("opposite order", outcomes)

        assertEquals(
            pairs, outcomes.count { it.bothReachedBarrier },
            "PRECONDITION: both sides must have been between their locks at once, or the " +
                "cycle never existed and nothing below is about deadlocks",
        )
        assertEquals(
            pairs, outcomes.sumOf { it.casualties },
            "every opposed pair must lose exactly one side",
        )
        assertEquals(
            0, outcomes.count { it.casualties == 2 },
            "the detector breaks the cycle with the MINIMUM number of casualties, and one is " +
                "the minimum -- if both died, something other than deadlock detection ran",
        )
        assertTrue(
            outcomes.flatMap { listOf(it.left, it.right) }.filter { it.failed }.all { it.sqlState == "40P01" },
            "every casualty must carry SQLSTATE 40P01",
        )
    }

    /**
     * ⭐ **The remedy. Take the lower identifier first, always.**
     *
     * Two callers that both sort cannot deadlock on these two rows: a cycle needs one holder
     * waiting on a lower id while another waits on a higher one, and neither ever asks in that
     * direction.
     *
     * ⚠ **`bothReachedBarrier` is expected to be false here and that is the point.** The
     * ordered pair cannot interleave — the second caller is queued on `rowA` while the first
     * is still holding it — so the rendezvous times out. The remedy did not survive the race;
     * **it removed it.**
     */
    /**
     * ⛔ **DO NOT RUN THIS ARM WITHOUT `a retry outside the transaction completes both sides`
     * IN THE SAME INVOCATION. Do not split this file.**
     *
     * This arm's precondition is **unprovable inside this arm**, because the remedy removes the
     * very interleaving the precondition would observe. `bothBetweenLocks=0` paired with
     * `casualties=0` is produced identically by *the order worked* and by *this run never
     * raced* — the two are indistinguishable from these numbers alone, which is exactly the
     * confusion `ADR-015` exists to prevent.
     *
     * What rescues it is the **retry arm**, which runs the unordered pair against the same two
     * rows in the same invocation and reports `bothBetweenLocks=10`. That is what establishes
     * the harness can still build a cycle at all, and therefore that this arm's `0` is the
     * order's doing.
     *
     * **A control that lives in a sibling arm is fragile in a way one inside its own arm is
     * not.** Split this class for tidiness, move either arm to another file, or disable the
     * retry arm, and the evidence for the green result silently evaporates — **and nothing
     * goes red.** `R37` §8 carries the same warning for the reader of the report; this one is
     * for whoever is about to edit the file.
     */
    @Test
    fun `an ascending lock order removes the interleaving rather than surviving it`() {
        val outcomes = (1..pairs).map { opposedPair(2) { l, first, second, between -> l.lockInAscendingIdOrder(first, second, between) } }
        report("ascending id order", outcomes)

        assertEquals(0, outcomes.sumOf { it.casualties }, "a sorted pair cannot form a cycle")
        assertEquals(
            0, outcomes.count { it.bothReachedBarrier },
            "if both sides were ever between their locks at once under an imposed order, the " +
                "order is not doing what this test claims it does",
        )
    }

    /**
     * The other repair: leave the order alone and retry the loser, **outside** the transaction.
     *
     * `R37` §3.3 establishes that Spring types the failure `TransientDataAccessException`, so
     * a generic retry layer would already retry it. Whether that actually recovers the work is
     * a measurement and this is it.
     */
    @Test
    fun `a retry outside the transaction completes both sides of the pair`() {
        val outcomes = (1..pairs).map { retryingOpposedPair(maxAttempts = 3) }
        report("retry OUTSIDE, 3 attempts", outcomes)
        println("E4 >>>   retries=${outcomes.sumOf { it.retries }} over ${outcomes.size} pairs")

        assertEquals(
            0, outcomes.sumOf { it.casualties },
            "the survivor commits and releases, so the cycle is gone by the second attempt",
        )
        assertTrue(
            outcomes.sumOf { it.retries } > 0,
            "PRECONDITION: if nothing ever retried, the first attempts did not deadlock and " +
                "this arm measured a pair that never contended",
        )
    }

    /** What the server itself says it will do, read out of `pg_settings` rather than recalled. */
    @Test
    fun `what the server says about detecting this`() {
        val settings = locker.settings("deadlock_timeout", "lock_timeout", "statement_timeout", "log_lock_waits", "max_locks_per_transaction")
        println("E4 >>> pg_settings")
        settings.forEach { (name, value) -> println("E4 >>>   %-26s %s".format(name, value)) }
        println("E4 >>> server: " + jdbc.queryForObject("select version()", String::class.java))
    }

    private fun report(name: String, outcomes: List<Pair2>) {
        val casualties = outcomes.sumOf { it.casualties }
        val bothDied = outcomes.count { it.casualties == 2 }
        val raced = outcomes.count { it.bothReachedBarrier }
        val states = outcomes.flatMap { listOf(it.left, it.right) }.mapNotNull { it.sqlState }.groupingBy { it }.eachCount()
        val types = outcomes.flatMap { listOf(it.left, it.right) }.mapNotNull { it.type }.groupingBy { it }.eachCount()
        println("E4 >>> %-26s pairs=%d casualties=%d bothDied=%d bothBetweenLocks=%d".format(name, outcomes.size, casualties, bothDied, raced))
        println("E4 >>>   sqlstates=$states")
        println("E4 >>>   exceptions=$types")
        outcomes.flatMap { listOf(it.left, it.right) }.firstOrNull { it.failed }?.let {
            println("E4 >>>   verbatim: ${it.type}: ${it.message}")
        }
    }
}
