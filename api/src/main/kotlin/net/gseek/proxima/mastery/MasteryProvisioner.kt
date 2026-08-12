package net.gseek.proxima.mastery

import java.math.BigDecimal
import java.time.Instant
import net.gseek.proxima.domain.ConceptRepository
import net.gseek.proxima.domain.LearnerRepository
import net.gseek.proxima.domain.Mastery
import net.gseek.proxima.domain.MasteryRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

interface MasteryUpsertQueries : Repository<Mastery, Long> {

    /**
     * Let the database decide. One statement, no window between the check and the write.
     *
     * `on conflict do nothing` needs a unique constraint to conflict *with* — which `V1`
     * does not have, deliberately (`ADR-002`). Until that constraint exists this is just an
     * insert.
     */
    @Modifying
    @Query(
        value = "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
            "values (:learnerId, :conceptId, 0.000, 0, 0, now()) " +
            "on conflict (learner_id, concept_id) do nothing",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("learnerId") learnerId: Long, @Param("conceptId") conceptId: Long)
}

/**
 * "Get the learner's mastery of this concept, creating it if it is not there yet."
 *
 * A sentence everyone writes, and every strategy below is a faithful implementation of it.
 * `T6` is about which of them survive two requests arriving at once.
 *
 * `REQUIRES_NEW` throughout, so a caller driving these concurrently gets one transaction
 * per call rather than one transaction total.
 */
/**
 * The insert, on its own bean, so that `REQUIRES_NEW` actually starts a new transaction.
 *
 * **This began as a method on [MasteryProvisioner] and did not work.** Called through
 * `this`, it never crossed the proxy, the "isolated" insert ran in the caller's transaction,
 * and the constraint violation poisoned it exactly as the unisolated version did — 7
 * failures out of 8, identical to the strategy it was supposed to improve on.
 *
 * The `T3` ArchUnit rule caught it, three reports after `T3` was written. See `R7` §3.4.
 */
@Service
class MasteryInserter(
    private val masteries: MasteryRepository,
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insertInNewTransaction(learnerId: Long, conceptId: Long) {
        masteries.saveAndFlush(
            Mastery(
                learner = learners.getReferenceById(learnerId),
                concept = concepts.getReferenceById(conceptId),
                score = BigDecimal("0.000"),
            ).apply { updatedAt = Instant.EPOCH },
        )
    }
}

@Service
class MasteryProvisioner(
    private val masteries: MasteryRepository,
    private val learners: LearnerRepository,
    private val concepts: ConceptRepository,
    private val upserts: MasteryUpsertQueries,
    private val inserter: MasteryInserter,
) {

    /**
     * **The defect.** Look, then leap.
     *
     * The gap between the existence check and the insert is where the second request lives.
     * Both look, both find nothing, both insert. `V1` carries no unique constraint on
     * `(learner_id, concept_id)` — omitted on purpose so this can be measured — so the
     * database accepts both, and the learner now has two masteries of one concept.
     *
     * **Nothing reports it.** The next read picks one of them, arbitrarily.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateNaive(learnerId: Long, conceptId: Long): Long {
        val existing = masteries.findByLearnerIdAndConceptId(learnerId, conceptId)
        if (existing != null) return existing.id!!

        val created = masteries.save(
            Mastery(
                learner = learners.getReferenceById(learnerId),
                concept = concepts.getReferenceById(conceptId),
                score = BigDecimal("0.000"),
            ).apply { updatedAt = Instant.EPOCH },
        )
        return created.id!!
    }

    /**
     * The same, with the insert failure caught and the row re-read.
     *
     * The obvious repair once a unique constraint exists: *"if someone beat me to it, read
     * theirs."* Whether it works is database-specific and is the second half of `T6` —
     * PostgreSQL aborts the whole transaction on a constraint violation, so the recovery
     * read runs inside a transaction that can no longer do anything.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateCatching(learnerId: Long, conceptId: Long): Long {
        masteries.findByLearnerIdAndConceptId(learnerId, conceptId)?.let { return it.id!! }
        return try {
            masteries.saveAndFlush(
                Mastery(
                    learner = learners.getReferenceById(learnerId),
                    concept = concepts.getReferenceById(conceptId),
                    score = BigDecimal("0.000"),
                ).apply { updatedAt = Instant.EPOCH },
            ).id!!
        } catch (e: RuntimeException) {
            // "Someone else created it -- just read theirs."
            masteries.findByLearnerIdAndConceptId(learnerId, conceptId)?.id
                ?: throw IllegalStateException("lost the race and cannot read the winner", e)
        }
    }

    /**
     * The same repair, with the insert in **its own transaction** so a failure cannot poison
     * the caller's.
     *
     * This is what a savepoint buys, expressed with the tool Spring gives for it. The
     * recovery read then runs in a transaction that never saw the violation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateIsolatingTheInsert(learnerId: Long, conceptId: Long): Long {
        masteries.findByLearnerIdAndConceptId(learnerId, conceptId)?.let { return it.id!! }
        try {
            inserter.insertInNewTransaction(learnerId, conceptId)
        } catch (e: RuntimeException) {
            // the failure was confined to the inner transaction
        }
        return masteries.findByLearnerIdAndConceptId(learnerId, conceptId)?.id
            ?: throw IllegalStateException("no row after insert and recovery")
    }

    /** No check, no race, no exception: one statement that cannot conflict with itself. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateByUpsert(learnerId: Long, conceptId: Long): Long {
        upserts.insertIfAbsent(learnerId, conceptId)
        return masteries.findByLearnerIdAndConceptId(learnerId, conceptId)?.id
            ?: throw IllegalStateException("upsert left no row")
    }
}
