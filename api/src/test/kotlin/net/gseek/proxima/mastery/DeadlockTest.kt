package net.gseek.proxima.mastery

import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private data class Side(val failed: Boolean, val type: String?, val sqlState: String?, val message: String?)

    private data class Pair2(val left: Side, val right: Side) {
        val casualties: Int get() = listOf(left, right).count { it.failed }
    }

    /**
     * Runs one opposed pair, with a barrier **between** the two locks.
     *
     * Without the barrier the pair is a race that usually does not happen: whichever
     * transaction reaches its second lock first simply takes it and commits. The barrier makes
     * the cycle by construction — each side holds its first row and neither asks for its
     * second until the other is also holding — so a run that produces no deadlock is a fact
     * about the server, not about scheduling. `ADR-015` is the same requirement written for
     * `UniquenessRaceTest`: an arm has to prove its own precondition.
     */
    private fun opposedPair(order: (RowLocker, Long, Long, () -> Unit) -> Unit): Pair2 {
        val gate = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(
                Callable { attempt { order(locker, rowA, rowB) { gate.await(30, TimeUnit.SECONDS) } } },
                Callable { attempt { order(locker, rowB, rowA) { gate.await(30, TimeUnit.SECONDS) } } },
            )
            val results = pool.invokeAll(tasks).map { it.get() }
            return Pair2(results[0], results[1])
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(30, TimeUnit.SECONDS)
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
     * **The naive belief, and the red assertion.**
     *
     * Both transactions lock two rows they are entitled to lock, with two ordinary statements
     * each. Nothing violates a constraint, nothing is out of range, no row is missing. An
     * application author reading either side alone sees no defect — because there is none in
     * either side. **The defect is the pair**, and no reader of one file can see it.
     */
    @Test
    fun `two transactions taking the same two rows in opposite order both complete`() {
        val outcomes = (1..pairs).map { opposedPair { l, first, second, between -> l.lockInGivenOrder(first, second, between) } }
        report("opposite order", outcomes)

        assertEquals(
            0, outcomes.sumOf { it.casualties },
            "each side locks two rows it is entitled to lock; nothing here violates anything",
        )
    }

    /** What the server itself says it will do, read out of `pg_settings` rather than recalled. */
    @Test
    fun `what the server says about detecting this`() {
        val settings = locker.settings("deadlock_timeout", "lock_timeout", "statement_timeout", "log_lock_waits", "max_locks_per_transaction")
        println("E4 >>> pg_settings")
        settings.forEach { (name, value) -> println("E4 >>>   %-26s %s".format(name, value)) }
    }

    private fun report(name: String, outcomes: List<Pair2>) {
        val casualties = outcomes.sumOf { it.casualties }
        val bothDied = outcomes.count { it.casualties == 2 }
        val states = outcomes.flatMap { listOf(it.left, it.right) }.mapNotNull { it.sqlState }.groupingBy { it }.eachCount()
        val types = outcomes.flatMap { listOf(it.left, it.right) }.mapNotNull { it.type }.groupingBy { it }.eachCount()
        println("E4 >>> %-16s pairs=%d casualties=%d bothDied=%d".format(name, outcomes.size, casualties, bothDied))
        println("E4 >>>   sqlstates=$states")
        println("E4 >>>   exceptions=$types")
        outcomes.flatMap { listOf(it.left, it.right) }.firstOrNull { it.failed }?.let {
            println("E4 >>>   verbatim: ${it.type}: ${it.message}")
        }
    }
}
