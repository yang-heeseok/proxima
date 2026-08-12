package net.gseek.proxima.mastery

import net.gseek.proxima.domain.Mastery
import net.gseek.proxima.domain.MasteryRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Four ways to add one to a counter, so `T5` can compare them instead of describing them.
 *
 * Each `increment*` method is **one unit of work** — `REQUIRES_NEW`, so that a caller
 * driving many of them concurrently gets many transactions rather than one. That is not a
 * style choice: a lost update is a race between transactions, and a test that ran them all
 * inside one would have nothing to race.
 */
interface MasteryCounterQueries : Repository<Mastery, Long> {

    /** The value, read with no lock at all. */
    @Query("select m.attempts_count from mastery m where m.id = :id", nativeQuery = true)
    fun readCount(@Param("id") id: Long): Int

    /** The value, with the row locked until the transaction ends. */
    @Query("select m.attempts_count from mastery m where m.id = :id for update", nativeQuery = true)
    fun readCountForUpdate(@Param("id") id: Long): Int

    /** Writes an absolute value — whatever the caller computed, whenever it computed it. */
    @Modifying
    @Query("update mastery set attempts_count = :value where id = :id", nativeQuery = true)
    fun writeCount(@Param("id") id: Long, @Param("value") value: Int)

    /** Lets the database do the addition, against the row as it is at write time. */
    @Modifying
    @Query("update mastery set attempts_count = attempts_count + 1 where id = :id", nativeQuery = true)
    fun incrementCount(@Param("id") id: Long)
}

@Service
class MasteryCounter(
    private val queries: MasteryCounterQueries,
    private val masteries: MasteryRepository,
) {

    /**
     * **The defect.** Read, add one, write the result.
     *
     * The most natural way to express "increment", and the reason it is wrong is that
     * nothing connects the read to the write. Two transactions read the same value and both
     * write the same result; one of the two increments is gone and **nothing anywhere
     * reports it**. No exception, no constraint, no log line — the counter is simply lower
     * than it should be, by an amount that depends on timing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementByReadModifyWrite(id: Long) {
        val current = queries.readCount(id)
        queries.writeCount(id, current + 1)
    }

    /**
     * The same shape, through the entity — which carries `@Version`.
     *
     * Whether that changes the outcome is the question, and this repository does not assume
     * it does. `V1` has carried a `version` column since the beginning and `Mastery` maps
     * it, so this is the path an application using JPA normally would take.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementByEntity(id: Long) {
        val mastery = masteries.findById(id).orElseThrow()
        mastery.attemptsCount += 1
        masteries.save(mastery)
    }

    /** Read with the row locked, then write. The lock is held to the end of the transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementByPessimisticLock(id: Long) {
        val current = queries.readCountForUpdate(id)
        queries.writeCount(id, current + 1)
    }

    /** One statement. The database reads and writes the row without anything in between. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementAtomically(id: Long) {
        queries.incrementCount(id)
    }

    /**
     * **The retry in the wrong place.**
     *
     * Optimistic locking turns a lost update into a failed one, so it needs a retry to be
     * usable — and this is where the retry goes if you put it next to the thing that
     * failed. The loop is *inside* the transaction it is retrying.
     *
     * Every attempt after the first runs in the transaction that already failed, against a
     * persistence context that already holds the stale entity. Retrying cannot help,
     * because the thing that has to change between attempts — the transaction — is the one
     * thing this loop does not change.
     *
     * What that actually produces on this stack is the measurement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementWithRetryInside(id: Long, attempts: Int = 5) {
        repeat(attempts) {
            try {
                val mastery = masteries.findById(id).orElseThrow()
                mastery.attemptsCount += 1
                masteries.saveAndFlush(mastery)
                return
            } catch (e: RuntimeException) {
                // Swallowed on purpose: this is the shape being measured, not endorsed.
            }
        }
    }
}

/**
 * The same retry, one level out.
 *
 * **A separate bean, and not a method on [MasteryCounter], for the reason `T3` measured**:
 * a retry that called `this.incrementByEntity(...)` would never cross the proxy, every
 * attempt would run in the caller's transaction, and it would quietly become the broken
 * version above while looking like the fixed one.
 *
 * Here each attempt is a call through a proxy into `REQUIRES_NEW`, so each gets its own
 * transaction, its own persistence context, and its own read of the row.
 */
@Service
class RetryingMasteryCounter(private val counter: MasteryCounter) {

    fun incrementWithRetryOutside(id: Long, attempts: Int = 5) {
        repeat(attempts) {
            try {
                counter.incrementByEntity(id)
                return
            } catch (e: RuntimeException) {
                // next attempt starts a new transaction
            }
        }
        throw IllegalStateException("gave up after $attempts attempts")
    }
}
