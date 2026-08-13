package net.gseek.proxima.recording

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.MasteryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Shared by the two arms of `R12`. Ten threads released together onto **one**
 * `(learner, concept)`, one hundred recordings each.
 *
 * The same shape as `R6`'s harness, pointed at `AttemptRecorder.record` instead of at a
 * counter — because `R6` measured the strategies in isolation and `R6` §8 then recorded that
 * **the application was still using the second-worst one.** A measurement of the strategy is
 * not a measurement of the application that chose it.
 */
internal object Contention {
    const val THREADS = 10
    const val PER_THREAD = 100
    val DELTA: BigDecimal = BigDecimal("0.001")

    class Outcome(
        val applied: Int,
        val failures: Map<String, Int>,
        val millis: Long,
        val attemptsCount: Int,
        val score: BigDecimal,
        val attemptRows: Long,
    ) {
        val rejected get() = failures.values.sum()
        override fun toString() =
            "applied %4d  rejected %4d  attempts_count %4d  score %s  attempt rows %4d  %5d ms  %s"
                .format(applied, rejected, attemptsCount, score.toPlainString(), attemptRows, millis, failures)
    }

    fun run(
        recorder: AttemptRecorder,
        fixture: RecordingFixture,
        masteries: MasteryRepository,
    ): Outcome {
        val scene = fixture.scene()
        val applied = AtomicInteger()
        val failures = ConcurrentHashMap<String, AtomicInteger>()
        val barrier = CyclicBarrier(THREADS)
        val pool = Executors.newFixedThreadPool(THREADS)

        val started = System.nanoTime()
        repeat(THREADS) {
            pool.submit {
                barrier.await()
                repeat(PER_THREAD) {
                    try {
                        recorder.record(scene.learnerId, fixture.recording(scene, DELTA))
                        applied.incrementAndGet()
                    } catch (e: Exception) {
                        failures.computeIfAbsent(e.javaClass.simpleName) { AtomicInteger() }
                            .incrementAndGet()
                    }
                }
            }
        }
        pool.shutdown()
        check(pool.awaitTermination(5, TimeUnit.MINUTES)) { "the harness did not finish" }
        val millis = (System.nanoTime() - started) / 1_000_000

        val mastery = masteries.findByLearnerIdAndConceptId(scene.learnerId, scene.conceptId)
        return Outcome(
            applied = applied.get(),
            failures = failures.mapValues { it.value.get() },
            millis = millis,
            attemptsCount = mastery?.attemptsCount ?: 0,
            score = mastery?.score ?: BigDecimal.ZERO,
            attemptRows = fixture.countAttempts(),
        )
    }
}

/**
 * `R12`, `red` — **the arm the application shipped for three days after `R6` measured it.**
 *
 * `R6` §8 named this precisely: *"The application still uses the second-worst option …
 * `AttemptRecorder.record` reads a `Mastery`, mutates `attemptsCount` and `score`, and saves
 * — the `entity + @Version` arm, which rejected 82 % of writes here."* It went unchanged
 * because the fix looked like it needed something an atomic statement cannot express.
 *
 * This measures what that cost, **in the application's own code path** rather than in a
 * strategy comparison. Two defects are live in it at once: `R7`'s check-then-insert
 * (`findBy… ?: Mastery(…)`) and `R6`'s read-modify-write.
 *
 * Nothing here asserts a specific number — thread interleaving is not deterministic. It
 * asserts that the arm **loses or rejects work at all**, so that the green arm beside it is
 * being compared against something real.
 */
@SpringBootTest(properties = ["proxima.recording.mastery-update=read-modify-write"])
@Import(TestcontainersConfiguration::class, RecordingFixture::class)
class RecordingContentionTest {

    @Autowired private lateinit var recorder: AttemptRecorder
    @Autowired private lateinit var fixture: RecordingFixture
    @Autowired private lateinit var masteries: MasteryRepository

    @AfterEach
    fun cleanUp() = fixture.clear()

    @Test
    fun `read-modify-write under contention on one row`() {
        val outcome = Contention.run(recorder, fixture, masteries)

        println()
        println("R12-RED >>> proxima.recording.mastery-update = read-modify-write")
        println("  ${Contention.THREADS} threads x ${Contention.PER_THREAD} recordings, one (learner, concept)")
        println("  $outcome")
        println()

        assertTrue(
            outcome.rejected > 0 || outcome.attemptsCount < Contention.THREADS * Contention.PER_THREAD,
            "the red arm neither rejected nor lost anything, so there is nothing for the " +
                "green arm to be better than. Either contention did not happen or this is " +
                "no longer the red arm: $outcome",
        )
    }
}
