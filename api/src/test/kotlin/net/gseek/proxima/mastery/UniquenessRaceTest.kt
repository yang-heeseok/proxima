package net.gseek.proxima.mastery

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
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
 * `T6` — how many rows exist after N requests all decide to create the same one.
 *
 * **The threads are released from a barrier**, so they run the existence check as
 * simultaneously as this machine allows. A race that is merely *likely* would make the
 * result depend on scheduling luck and the test flaky in the direction of passing, which is
 * the worst direction for a test about a defect.
 *
 * > **The paragraph above was written when this test was, and it is half wrong. `OPEN-12`.**
 * >
 * > **A barrier aligns where the calls *start*, not where their critical sections are.** Once
 * > released, eight racers on an eight-core machine that is also running a Spring context and
 * > several other test classes' containers get descheduled between the existence check and the
 * > insert — and a racer that resumes after the winner has committed finds the row, skips its
 * > insert, and never contends. On the round-two integration merge this test went **red** on a
 * > tree byte-identical to the branch it was green on, and passed on the two full runs after
 * > it.
 * >
 * > So the flakiness is real and its direction is the **opposite** of what was feared here:
 * > this arm asserts *the losers failed*, so an unraced run raises a false alarm rather than
 * > issuing a false clean bill. **The other three arms are the dangerous ones** — they assert
 * > `failures == 0`, which an unraced run satisfies without exercising anything.
 * >
 * > `peakOverlap` now measures whether any two calls were ever open together, and
 * > `assertRaced` requires it in **all four** arms. The instrument's failure and the code's
 * > failure are now two different messages.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class UniquenessRaceTest {

    @Autowired private lateinit var provisioner: MasteryProvisioner
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val racers = 8

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-85%')")
        jdbc.execute("delete from learner where external_ref like 'learner-85%'")
        jdbc.execute("delete from concept where code like 'concept-85%'")
    }

    private fun scene(tag: String): Pair<Long, Long> {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-85$tag",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 85', 'G5-6') returning id",
            Long::class.java, "concept-85$tag",
        )!!
        return learnerId to conceptId
    }

    private fun rowsFor(learnerId: Long, conceptId: Long): Int = jdbc.queryForObject(
        "select count(*) from mastery where learner_id = ? and concept_id = ?",
        Int::class.java, learnerId, conceptId,
    )!!

    private data class Race(val rows: Int, val failures: Int, val peakConcurrency: Int)

    private fun race(learnerId: Long, conceptId: Long, provision: (Long, Long) -> Long): Race {
        val barrier = CyclicBarrier(racers)
        val failures = AtomicInteger()
        val startedAt = LongArray(racers)
        val endedAt = LongArray(racers)
        val pool = Executors.newFixedThreadPool(racers)
        val tasks = (0 until racers).map { i ->
            Callable {
                barrier.await(30, TimeUnit.SECONDS)
                startedAt[i] = System.nanoTime()
                try {
                    provision(learnerId, conceptId)
                } catch (e: Exception) {
                    failures.incrementAndGet()
                } finally {
                    endedAt[i] = System.nanoTime()
                }
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
        return Race(rowsFor(learnerId, conceptId), failures.get(), peakOverlap(startedAt, endedAt))
    }

    /**
     * How many of the calls were ever in flight at the same instant.
     *
     * **This is the precondition every assertion below depends on, and until 2026-08-22
     * nothing measured it.** Each `provision` runs `REQUIRES_NEW`, so a call is a
     * transaction: it opens, reads, writes, commits. Under `READ COMMITTED` a racer sees the
     * winner's row only once the winner has **committed** — so if no two calls were ever open
     * together, every racer read after the previous one committed, nobody found the row
     * absent twice, and **no race happened at all.**
     *
     * A peak of 1 therefore means the harness failed, not the code. That is the distinction
     * `OPEN-12` was opened for: the naive arm's message said *"either the race did not happen
     * or something is swallowing the violation"* and could not tell which.
     *
     * **What this does not prove.** Overlap is necessary for a race and is not sufficient:
     * two calls can be open together and still have the second one's `SELECT` land after the
     * first one's `COMMIT`. So a peak of 2 or more says *the opportunity existed*, not *the
     * race occurred*. The direction that matters is the other one, and it is exact — **a peak
     * of 1 rules a race out.**
     */
    private fun peakOverlap(startedAt: LongArray, endedAt: LongArray): Int =
        RaceOverlap.peak(startedAt, endedAt)

    /**
     * **Every arm below is about what happens when two requests collide, so every arm has to
     * establish that they did.**
     *
     * Three of the four assert `failures == 0`, which is **trivially true of a run in which
     * nothing raced** — the arm would certify a remedy it never exercised. That is the shape
     * `R9` §7 and `R16`'s `rate >= 0.0` threshold are both about, and it was here in three
     * tests. The fourth asserts `failures > 0` and fails loudly instead, which is the safe
     * direction and is how this was found: it went red once on the round-two integration
     * merge, on a tree byte-identical to one it was green on.
     */
    private fun assertRaced(name: String, r: Race) {
        assertTrue(
            r.peakConcurrency >= 2,
            "$name: the harness could not create a race — peak concurrency was " +
                "${r.peakConcurrency} of $racers, so every call ran alone and no assertion " +
                "below is about contention. This is an instrument failure, not a defect: the " +
                "barrier aligns the start of the calls and not their critical sections, so a " +
                "loaded machine can deschedule each racer long enough to serialise them.",
        )
    }

    private fun report(name: String, r: Race) {
        println(
            "T6 >>> %-28s rows=%-4d failures=%-4d peak=%d/%d"
                .format(name, r.rows, r.failures, r.peakConcurrency, racers),
        )
    }

    /**
     * The naive path, now that `V3` enforces the rule.
     *
     * Before `V3` this produced **eight rows and zero failures** (`ad474d8`). The constraint
     * does not make the code correct — it makes the code's incorrectness visible, by turning
     * seven silent duplicates into seven exceptions. That is the trade the report weighs.
     */
    @Test
    fun `the constraint turns silent duplicates into loud failures`() {
        val (learnerId, conceptId) = scene("nai")
        val r = race(learnerId, conceptId) { l, c -> provisioner.findOrCreateNaive(l, c) }
        report("naive check-then-insert", r)

        assertRaced("naive check-then-insert", r)
        assertEquals(1, r.rows, "the constraint must permit exactly one row")
        assertTrue(
            r.failures > 0,
            "the race did happen — peak concurrency ${r.peakConcurrency} of $racers — and " +
                "still nothing failed. Something is swallowing the violation, which is the " +
                "half of the old message this assertion could not previously separate out.",
        )
    }

    /**
     * The repair that reads the winner's row after losing — **in the same transaction**.
     *
     * This is the natural fix and it is where PostgreSQL differs from other databases: a
     * constraint violation aborts the entire transaction, so the recovery read runs inside
     * a transaction that can no longer execute anything. `R1` §9 met this by accident while
     * measuring `T3`; here it is the subject.
     */
    @Test
    fun `catching the violation and reading again, in the same transaction`() {
        val (learnerId, conceptId) = scene("cat")
        val r = race(learnerId, conceptId) { l, c -> provisioner.findOrCreateCatching(l, c) }
        report("catch + re-read (same tx)", r)

        assertRaced("catch + re-read (same tx)", r)
        assertEquals(1, r.rows)
    }

    /** The same repair with the insert in its own transaction, so the failure is confined. */
    @Test
    fun `isolating the insert lets the recovery read succeed`() {
        val (learnerId, conceptId) = scene("iso")
        val r = race(learnerId, conceptId) { l, c ->
            provisioner.findOrCreateIsolatingTheInsert(l, c)
        }
        report("catch + re-read (inner tx)", r)

        assertRaced("catch + re-read (inner tx)", r)
        assertEquals(1, r.rows)
        assertEquals(0, r.failures, "every caller should end up with the winner's row")
    }

    /**
     * **The upsert is not an alternative to the constraint. It requires one.**
     *
     * This was written expecting `on conflict do nothing` to be a way of getting uniqueness
     * without changing the schema — which is how it is often described, and how I thought
     * of it. Against `V1` every one of the eight calls **fails**, because
     * `on conflict (learner_id, concept_id)` needs a unique index on exactly those columns
     * to conflict with, and there is none.
     *
     * So the statement does not degrade to an ordinary insert, and it does not silently do
     * the wrong thing. It refuses. That is the second-best outcome available and it is
     * worth pinning: once `V3` adds the constraint this test changes meaning, and the
     * change is the point.
     */
    @Test
    fun `the upsert works, now that there is something to conflict on`() {
        val (learnerId, conceptId) = scene("ups")
        val r = race(learnerId, conceptId) { l, c -> provisioner.findOrCreateByUpsert(l, c) }
        report("upsert (on conflict)", r)

        assertRaced("upsert (on conflict)", r)
        assertEquals(1, r.rows, "one statement, one row, whoever wins")
        assertEquals(0, r.failures, "and nobody has to lose")
    }
}
