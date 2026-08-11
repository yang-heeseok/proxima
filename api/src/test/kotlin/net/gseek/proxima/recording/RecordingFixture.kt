package net.gseek.proxima.recording

import net.gseek.proxima.domain.AttemptRepository
import net.gseek.proxima.domain.Concept
import net.gseek.proxima.domain.ConceptRepository
import net.gseek.proxima.domain.Item
import net.gseek.proxima.domain.ItemRepository
import net.gseek.proxima.domain.Learner
import net.gseek.proxima.domain.LearnerRepository
import net.gseek.proxima.domain.MasteryRepository
import org.springframework.boot.test.context.TestComponent
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class Scene(val learnerId: Long, val conceptId: Long, val itemId: Long)

/**
 * Builds one learner, one concept, and one item for a test to act on.
 *
 * **`REQUIRES_NEW` is deliberate and is what makes this usable from a test that has no
 * transaction of its own.** `AttemptRecordingAtomicityTest` runs outside a transaction on
 * purpose, so the scene it acts on has to be committed and visible to the connections the
 * service will use. A fixture that joined the caller's transaction would be invisible to
 * them.
 *
 * References are numbered from a counter rather than a fixed string because these rows are
 * committed for real in the non-transactional test and `external_ref` is unique.
 */
@TestComponent
class RecordingFixture(
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
    private val items: ItemRepository,
    private val attempts: AttemptRepository,
    private val masteries: MasteryRepository,
) {

    private val counter = AtomicLong(900_000)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scene(): Scene {
        val n = counter.incrementAndGet()
        val learner = learners.save(Learner(externalRef = "learner-$n"))
        val concept = concepts.save(
            Concept(code = "concept-$n", name = "Concept $n", gradeBand = "G5-6"),
        )
        val item = items.save(
            Item(code = "item-$n", conceptPrimary = concept, difficulty = 5),
        )
        return Scene(learner.id!!, concept.id!!, item.id!!)
    }

    fun recording(scene: Scene, scoreDelta: BigDecimal) = Recording(
        itemId = scene.itemId,
        conceptId = scene.conceptId,
        correct = true,
        elapsedMs = 4_200,
        at = Instant.parse("2026-08-11T00:00:00Z"),
        scoreDelta = scoreDelta,
    )

    /** Used by the non-transactional test, which has no rollback to clean up after it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun clear() {
        attempts.deleteAllInBatch()
        masteries.deleteAllInBatch()
        items.deleteAllInBatch()
        concepts.deleteAllInBatch()
        learners.deleteAllInBatch()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun countAttempts(): Long = attempts.count()
}
