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
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `E1` — one defect, three layers, and the number of instances as the variable.
 *
 * `R6` fixed a lost update at the database and compared six strategies, all of them inside
 * the database's understanding of the work. This asks the question one layer up: the same
 * defect, remedied by a monitor, by an atomic, or by the row.
 *
 * **Every number in this class is arithmetic, exactly as `R6` §1 insisted.** A thousand
 * increments were requested; the counter says something. No arm here reports a duration, so
 * every figure can be taken on a machine that is doing other work — the cost comparison is a
 * separate arm and it is not in this file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LayeredRemedyTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val threads = 10
    private val perThread = 100
    private val expected = threads * perThread

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-90%')")
        jdbc.execute("delete from learner where external_ref like 'learner-90%'")
        jdbc.execute("delete from concept where code like 'concept-90%'")
    }

    private fun freshMastery(tag: String): Long {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-90$tag",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 90', 'G5-6') returning id",
            Long::class.java, "concept-90$tag",
        )!!
        return jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    private fun countOf(id: Long): Int =
        jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, id)!!

    private data class Outcome(
        val arm: String,
        val instances: Int,
        val row: Int,
        val inMemory: List<Int>,
        val failures: Int,
    ) {
        /** What an increment did if it neither landed in the row nor raised. */
        val lostSilently: Int get() = 0
    }

    /**
     * Drives [expected] increments across [instanceCount] instances and reports what survived.
     *
     * The **total work is constant**: ten threads and a hundred increments each, whether that
     * is one instance serving ten threads or two serving five apiece. Changing the instance
     * count without holding the work fixed would compare two different experiments.
     */
    private fun race(
        arm: String,
        id: Long,
        instanceCount: Int,
        flushAtEnd: Boolean = false,
        increment: (LayeredCounter, Long) -> Unit,
    ): Outcome {
        val instances = (1..instanceCount).map { LayeredCounter(jdbc, transactionManager, "i$it") }
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val tasks = (0 until threads).map { t ->
            val instance = instances[t % instanceCount]
            Callable {
                repeat(perThread) {
                    try {
                        increment(instance, id)
                    } catch (e: Exception) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.MINUTES)
        if (flushAtEnd) instances.forEach { it.flushInMemory(id) }

        val outcome = Outcome(arm, instanceCount, countOf(id), instances.map { it.inMemoryValue }, failures.get())
        report(outcome)
        return outcome
    }

    private fun report(o: Outcome) {
        val lost = expected - o.row - o.failures
        println(
            "E1 >>> %-26s instances=%d row=%-6d inMemory=%-14s failures=%-5d rowShortBy=%d"
                .format(o.arm, o.instances, o.row, o.inMemory.toString(), o.failures, lost),
        )
    }

    /**
     * **The premise, and it is measured rather than assumed.**
     *
     * The brief this slice works from states that on one instance all three remedies are
     * correct. That is a claim about this stack and it is checked here before anything is
     * built on it. If an arm is already wrong at one instance, the interesting result is that
     * one — and it would mean the "two instances break it" story is not the whole story.
     */
    @Test
    fun `on ONE instance all three remedies keep every increment`() {
        val rmw = race("read-modify-write", freshMastery("a"), 1) { c, id -> c.incrementByReadModifyWrite(id) }
        val sync = race("(1) synchronized", freshMastery("b"), 1) { c, id -> c.incrementBySynchronized(id) }
        val cas = race("(2) CAS write-through", freshMastery("c"), 1) { c, id -> c.incrementByCas(id) }
        val behind = race("(2) CAS write-behind", freshMastery("d"), 1, flushAtEnd = true) { c, _ -> c.incrementInMemoryOnly() }
        val db = race("(3) database", freshMastery("e"), 1) { c, id -> c.incrementByDatabase(id) }

        assertEquals(
            true, rmw.row < expected,
            "the control must lose updates or this harness is not racing anything: got ${rmw.row}",
        )
        assertEquals(expected, sync.row, "(1) a monitor on the one shared bean serialises every writer")
        assertEquals(expected, cas.row, "(2) every increment took a unique value from the atomic")
        assertEquals(expected, behind.row, "(2) one writer, one flush, nothing to reorder")
        assertEquals(expected, db.row, "(3) the row is the scope and there is one row")
    }

    /**
     * **The same three remedies, the same total work, one more instance.**
     *
     * ⭐ Nothing throws. Nothing is rejected. No constraint is violated and no log line
     * appears. The only way to know is to have counted what should have been — which is `R6`
     * §1's sentence arriving one layer up.
     */
    @Test
    fun `on TWO instances only the database remedy survives, and nothing reports it`() {
        val sync = race("(1) synchronized", freshMastery("f"), 2) { c, id -> c.incrementBySynchronized(id) }
        val cas = race("(2) CAS write-through", freshMastery("g"), 2) { c, id -> c.incrementByCas(id) }
        val behind = race("(2) CAS write-behind", freshMastery("h"), 2, flushAtEnd = true) { c, _ -> c.incrementInMemoryOnly() }
        val db = race("(3) database", freshMastery("i"), 2) { c, id -> c.incrementByDatabase(id) }

        assertEquals(
            0, sync.failures + cas.failures + behind.failures + db.failures,
            "NOTHING FAILED. No exception, no rejection, no constraint, no log line -- that is " +
                "the entire point, and it is why the only way to know is to have counted",
        )

        assertEquals(expected, db.row, "(3) the row is the scope, and every instance shares the row")

        assertTrue(sync.row < expected, "(1) a monitor excludes threads in ONE JVM; got ${sync.row}")
        assertTrue(cas.row < expected, "(2) each instance has its own atomic; got ${cas.row}")
        assertTrue(behind.row < expected, "(2) each instance flushes its own total; got ${behind.row}")

        // The sharpest form of the defect, and the reason (2) is worse than (1) rather than
        // merely equal to it: every instance's in-memory counter is EXACTLY RIGHT about its own
        // work, the totals add to exactly 1,000, and the row holds half. Each process is
        // internally consistent and confidently wrong, which is the state that survives a
        // debugging session.
        assertEquals(
            expected, cas.inMemory.sum(),
            "(2) the instances between them counted every increment: ${cas.inMemory}",
        )
        assertEquals(
            expected, behind.inMemory.sum(),
            "(2) same for the write-behind arm: ${behind.inMemory}",
        )
        assertTrue(
            cas.inMemory.all { it < expected },
            "(2) and no single instance knows the total -- each would answer with its own half",
        )
    }
}
