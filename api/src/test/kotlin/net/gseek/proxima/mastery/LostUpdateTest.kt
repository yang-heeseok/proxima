package net.gseek.proxima.mastery

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `T5` — how many increments survive, per strategy.
 *
 * **The number that matters is not latency, it is arithmetic.** A thousand increments were
 * requested; the counter says something. The difference is the count of updates that were
 * silently thrown away, and it is exact.
 *
 * Threads are real threads against a real PostgreSQL. A lost update is a race between
 * transactions and cannot be produced by a single-threaded test, however cleverly ordered.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LostUpdateTest {

    @Autowired private lateinit var counter: MasteryCounter
    @Autowired private lateinit var retrying: RetryingMasteryCounter
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val threads = 10
    private val perThread = 100
    private val expected = threads * perThread

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-86%')")
        jdbc.execute("delete from learner where external_ref like 'learner-86%'")
        jdbc.execute("delete from concept where code like 'concept-86%'")
    }

    private fun freshMastery(tag: String): Long {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-86$tag",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 86', 'G5-6') returning id",
            Long::class.java, "concept-86$tag",
        )!!
        return jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    private fun countOf(id: Long): Int =
        jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, id)!!

    /**
     * Drives [increment] from [threads] threads and reports what survived.
     *
     * Failures are counted rather than propagated: a strategy that protects the counter by
     * refusing writes has not lost updates, it has rejected them, and those are different
     * outcomes that a report must not merge.
     */
    private data class Outcome(val finalCount: Int, val failures: Int, val millis: Long) {
        val lost: Int get() = 0
    }

    private fun race(id: Long, increment: (Long) -> Unit): Outcome {
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val start = System.nanoTime()
        val tasks = (1..threads).map {
            Callable {
                repeat(perThread) {
                    try {
                        increment(id)
                    } catch (e: Exception) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
        val millis = (System.nanoTime() - start) / 1_000_000
        return Outcome(countOf(id), failures.get(), millis)
    }

    private fun report(name: String, o: Outcome) {
        val lost = expected - o.finalCount - o.failures
        println(
            "T5 >>> %-22s final=%-6d failures=%-6d lostSilently=%-6d millis=%d"
                .format(name, o.finalCount, o.failures, lost, o.millis),
        )
    }

    @Test
    fun `read-modify-write loses updates, silently`() {
        val id = freshMastery("rmw")
        val o = race(id) { counter.incrementByReadModifyWrite(it) }
        report("read-modify-write", o)

        assertEquals(0, o.failures, "nothing failed -- that is the point")
        assertTrue(
            o.finalCount < expected,
            "expected fewer than $expected increments to survive; got ${o.finalCount}. " +
                "If this passes the race did not happen and the test proved nothing",
        )
    }

    @Test
    fun `the entity path is versioned, and what that turns the loss into`() {
        val id = freshMastery("ent")
        val o = race(id) { counter.incrementByEntity(it) }
        report("entity + @Version", o)

        // No assertion on which way this goes. Mastery has carried @Version since V1 and
        // whether that converts losses into failures on this stack is the measurement.
        assertEquals(
            expected, o.finalCount + o.failures,
            "every increment must be accounted for as either applied or rejected; " +
                "${expected - o.finalCount - o.failures} vanished with no exception",
        )
    }

    /**
     * The retry inside the transaction it is retrying, and the same retry one level out.
     *
     * Optimistic locking rejected 83 % of writes in the test above, so it is unusable
     * without a retry. Where that retry goes is the whole of this test, and the two
     * versions differ by one indirection that is easy to mistake for a refactor.
     */
    @Test
    fun `a retry inside the transaction cannot work, and outside it can`() {
        val inner = freshMastery("rin")
        val innerOutcome = race(inner) { counter.incrementWithRetryInside(it) }
        report("retry INSIDE the tx", innerOutcome)

        val outer = freshMastery("rout")
        val outerOutcome = race(outer) { retrying.incrementWithRetryOutside(it) }
        report("retry OUTSIDE the tx", outerOutcome)

        assertTrue(
            outerOutcome.finalCount > innerOutcome.finalCount,
            "moving the retry outside the transaction must recover increments that the " +
                "inner version cannot: inner=${innerOutcome.finalCount}, " +
                "outer=${outerOutcome.finalCount}",
        )
    }

    @Test
    fun `a pessimistic lock keeps every increment`() {
        val id = freshMastery("pes")
        val o = race(id) { counter.incrementByPessimisticLock(it) }
        report("pessimistic lock", o)

        assertEquals(expected, o.finalCount, "a locked read-modify-write must lose nothing")
        assertEquals(0, o.failures)
    }

    @Test
    fun `a single atomic statement keeps every increment`() {
        val id = freshMastery("atm")
        val o = race(id) { counter.incrementAtomically(it) }
        report("atomic statement", o)

        assertEquals(expected, o.finalCount, "the database did the addition; nothing can be lost")
        assertEquals(0, o.failures)
    }
}
