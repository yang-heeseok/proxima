package net.gseek.proxima.mastery

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * `REQUIRES_NEW` and `NESTED` in the same place, so the difference is a diff and not a
 * paragraph.
 *
 * This repository uses `REQUIRES_NEW` everywhere and `NESTED` nowhere — [MasteryCounter],
 * [MasteryProvisioner] and [MasteryInserter] all take the first, and `R7` §3.4 is about what
 * it buys: an inner failure that cannot poison the caller's transaction.
 *
 * They are not two settings of one dial.
 *
 * ```
 *   REQUIRES_NEW : a second transaction, and a second CONNECTION, held while the first waits.
 *                  It commits on its own and survives the outer one rolling back.
 *   NESTED       : a SAVEPOINT inside the first transaction. One connection.
 *                  Rolling back to the savepoint undoes the inner work and leaves the outer
 *                  transaction usable -- but if the OUTER rolls back, the inner work goes
 *                  with it, because it never was anywhere else.
 * ```
 *
 * The connection count is the half that bites in production and the half nobody counts: a
 * `REQUIRES_NEW` called from inside a transaction occupies **two** pool slots for the duration
 * of the inner call, so a pool of `n` deadlocks at `n` concurrent callers — every outer half
 * holding a slot and every inner half queueing behind the ones already held. `R2` sized this
 * pool and `R24` put three instances against one `max_connections`; neither varied the
 * propagation, and this is the multiplier that sits underneath both.
 */
@Service
class NestedCounter(
    private val jdbc: JdbcTemplate,
    private val inner: InnerIncrementer,
) {

    /**
     * An outer transaction that calls an inner one and then fails.
     *
     * What is left in the row afterwards is the entire question, and it has a different answer
     * per propagation with nothing in the calling code to distinguish them.
     */
    @Transactional
    fun outerFailsAfterRequiresNew(id: Long) {
        inner.incrementRequiresNew(id)
        throw IllegalStateException("the outer unit of work failed after the inner one committed")
    }

    /** The same, with the inner call on a savepoint instead of its own transaction. */
    @Transactional
    fun outerFailsAfterNested(id: Long) {
        inner.incrementNested(id)
        throw IllegalStateException("the outer unit of work failed after the inner savepoint")
    }

    /** The outer succeeds; the inner fails and is caught. Which caller is still usable? */
    @Transactional
    fun outerSurvivesFailedRequiresNew(id: Long) {
        runCatching { inner.incrementThenFailRequiresNew(id) }
        jdbc.update("update mastery set attempts_count = attempts_count + 100 where id = ?", id)
    }

    /** The same shape over a savepoint. */
    @Transactional
    fun outerSurvivesFailedNested(id: Long) {
        runCatching { inner.incrementThenFailNested(id) }
        jdbc.update("update mastery set attempts_count = attempts_count + 100 where id = ?", id)
    }

    /** Counts the pool slots held while the inner call is open, under `REQUIRES_NEW`. */
    @Transactional
    fun connectionsHeldDuringRequiresNew(id: Long, probe: () -> Int): Int {
        var seen = 0
        inner.incrementRequiresNew(id) { seen = probe() }
        return seen
    }

    /** The same probe, on a savepoint. */
    @Transactional
    fun connectionsHeldDuringNested(id: Long, probe: () -> Int): Int {
        var seen = 0
        inner.incrementNested(id) { seen = probe() }
        return seen
    }
}

/**
 * The inner half, on its own bean.
 *
 * **Not a private method on [NestedCounter].** `R6` §3.3 and `R7` §3.4 both paid for that
 * lesson and `TransactionBoundaryRules.TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED` now refuses
 * it: a call through `this` never reaches the proxy, so the propagation attribute is read by
 * nobody and every method below would silently run in the caller's transaction — which is
 * exactly the arm this class exists to tell apart from the other one.
 */
@Service
class InnerIncrementer(private val jdbc: JdbcTemplate) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementRequiresNew(id: Long, whileOpen: () -> Unit = {}) {
        bump(id)
        whileOpen()
    }

    @Transactional(propagation = Propagation.NESTED)
    fun incrementNested(id: Long, whileOpen: () -> Unit = {}) {
        bump(id)
        whileOpen()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun incrementThenFailRequiresNew(id: Long) {
        bump(id)
        error("the inner unit of work failed")
    }

    @Transactional(propagation = Propagation.NESTED)
    fun incrementThenFailNested(id: Long) {
        bump(id)
        error("the inner unit of work failed")
    }

    private fun bump(id: Long) {
        jdbc.update("update mastery set attempts_count = attempts_count + 1 where id = ?", id)
    }
}
