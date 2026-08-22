package net.gseek.proxima.mastery

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Two rows, two transactions, and the order they are taken in.
 *
 * `R6` measured six ways to add one to a counter and said so in its own §8: *"one row, one
 * column, one increment. Multi-row transactions introduce lock ordering and deadlocks,
 * **which this measured nothing about**."* `ADR-014` priced that sentence as ledger entry
 * `6.6` — class **a**, measurable here, not done. This class is the instrument that takes it.
 *
 * **Why this is not a `@Transactional` service.** The whole subject is *which transaction
 * takes which lock, and when*, so the transaction boundary has to be visible in the code
 * rather than woven onto it, and the caller has to be able to stand **between** the two
 * locks. A `TransactionTemplate` gives both; an annotation gives neither, and would also
 * make the object unusable unless it were fetched from the context as a proxy — which
 * [LayeredCounter] explains is exactly what this slice must be able to duplicate.
 *
 * A row is locked with `select … for update`, which holds until the transaction ends. That
 * is `R6`'s `readCountForUpdate` with the read thrown away: this class is about the lock,
 * not the value.
 */
class RowLocker(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {

    private val transactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /**
     * **The defect, when two callers disagree about the order.**
     *
     * Take `firstId`, run [betweenLocks], take `secondId`, commit. Nothing here is wrong.
     * Each statement is ordinary, each lock is one this transaction is entitled to, and a
     * single caller doing this a million times will never see a problem.
     *
     * The defect is not in this method. **It is in the set of callers**, and no caller can
     * see the set. That is the whole difficulty with lock ordering and it is why the remedy
     * below is a convention rather than a mechanism.
     */
    fun lockInGivenOrder(firstId: Long, secondId: Long, betweenLocks: () -> Unit = {}) {
        transactions.executeWithoutResult {
            lockRow(firstId)
            betweenLocks()
            lockRow(secondId)
        }
    }

    /**
     * **The remedy: take the lower identifier first, always.**
     *
     * Two callers that both do this cannot deadlock on these two rows, because a cycle needs
     * one holder waiting on a lower id while another waits on a higher one, and neither ever
     * asks in that direction.
     *
     * ⚠ **This is an application convention and the database does not enforce it.**
     * PostgreSQL has no notion that `id` orders locks; it will grant them in whatever order
     * it is asked. Nothing in the schema, no constraint, no `GRANT`, and no configuration
     * setting makes the sorted call correct and the unsorted one an error — they are the same
     * two statements. The only thing standing between this repository and `40P01` is that
     * every future caller remembers to call this method instead of the one above, and
     * `DeadlockTest` measures the remedy rather than the remembering.
     *
     * Compare `V3`: *"there is no version of 'look, then leap' that closes the gap … only the
     * database can be inside it."* Uniqueness could be moved into the database. **Lock order
     * cannot**, and that asymmetry is the finding.
     */
    fun lockInAscendingIdOrder(a: Long, b: Long, betweenLocks: () -> Unit = {}) {
        lockInGivenOrder(minOf(a, b), maxOf(a, b), betweenLocks)
    }

    private fun lockRow(id: Long) {
        jdbc.queryForObject(
            "select attempts_count from mastery where id = ? for update",
            Int::class.java,
            id,
        )
    }

    /**
     * What PostgreSQL says it will do, read from the running server rather than remembered.
     *
     * `deadlock_timeout` is **not** a timeout that kills anything. It is how long a backend
     * waits on a lock before it stops waiting and runs the cycle check — so it is the cost of
     * *looking*, and it is set to a value large enough that ordinary lock waits never pay it.
     * A deadlock is therefore detected, not timed out, and the difference decides whether a
     * retry is a repair or a way of failing more slowly.
     */
    fun settings(vararg names: String): Map<String, String> =
        names.associateWith { name ->
            jdbc.queryForObject(
                "select setting || coalesce(unit, '') || ' (source=' || source || ', boot=' || " +
                    "boot_val || coalesce(unit, '') || ')' from pg_settings where name = ?",
                String::class.java,
                name,
            ) ?: "ABSENT"
        }
}
