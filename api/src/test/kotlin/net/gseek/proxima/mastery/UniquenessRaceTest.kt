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

    private data class Race(val rows: Int, val failures: Int)

    private fun race(learnerId: Long, conceptId: Long, provision: (Long, Long) -> Long): Race {
        val barrier = CyclicBarrier(racers)
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(racers)
        val tasks = (1..racers).map {
            Callable {
                barrier.await(30, TimeUnit.SECONDS)
                try {
                    provision(learnerId, conceptId)
                } catch (e: Exception) {
                    failures.incrementAndGet()
                }
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
        return Race(rowsFor(learnerId, conceptId), failures.get())
    }

    private fun report(name: String, r: Race) {
        println("T6 >>> %-28s rows=%-4d failures=%-4d".format(name, r.rows, r.failures))
    }

    /**
     * **The red state.** Eight requests, one concept, and the database is happy to store
     * eight masteries of it.
     *
     * `V1` omits `unique (learner_id, concept_id)` on purpose — `ADR-002` — so this is what
     * the domain rule is worth when only the application enforces it.
     */
    @Test
    fun `an application-level existence check does not make anything unique`() {
        val (learnerId, conceptId) = scene("nai")
        val r = race(learnerId, conceptId) { l, c -> provisioner.findOrCreateNaive(l, c) }
        report("naive check-then-insert", r)

        assertEquals(0, r.failures, "nobody failed, which is why nobody notices")
        assertTrue(
            r.rows > 1,
            "expected duplicate mastery rows and found ${r.rows}. If this is 1, either the " +
                "race did not happen or something is now enforcing uniqueness -- and if it " +
                "is a constraint, this test should be measuring the constraint instead",
        )
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
    fun `the upsert cannot even run without the constraint it conflicts on`() {
        val (learnerId, conceptId) = scene("ups")
        val r = race(learnerId, conceptId) { l, c -> provisioner.findOrCreateByUpsert(l, c) }
        report("upsert (on conflict)", r)

        assertEquals(racers, r.failures, "expected every upsert to be rejected outright")
        assertEquals(0, r.rows, "and to have created nothing")
    }
}
