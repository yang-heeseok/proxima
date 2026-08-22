package net.gseek.proxima.mastery

import java.util.concurrent.atomic.AtomicInteger
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * One defect — a lost update — with the remedy placed at three different layers.
 *
 * `R6` fixed it at the third. It compared six *strategies* and every one of them lived inside
 * the database's understanding of the work: an absolute write, a version column, a row lock,
 * one atomic statement. It never asked the question one layer up, which is the one an
 * application actually asks first:
 *
 * ```
 *   ① synchronized  — mutual exclusion inside one JVM
 *   ② CAS / Atomic  — an atomic update with no lock at all
 *   ③ the database  — a row lock, or a statement the database cannot split
 * ```
 *
 * **On one instance all three keep every increment.** ① and ② are also far cheaper than ③,
 * and cheap is not the same as wrong — it is **narrower**. Where each one stops being correct
 * is the subject, and the answer is not "use the database": it is that ① and ② are scoped to
 * an *object*, ③ is scoped to a *row*, and the moment a second process exists only one of
 * those scopes still contains every writer.
 *
 * **What "instance" means here, exactly, and what it does not.** An instance of this class is
 * one application instance's copy of the bean: its own monitor, its own [AtomicInteger]. The
 * test constructs one, then two, against **one database**. That is deliberately *not* a
 * second JVM — and the direction of the difference is what makes it usable. Two objects in
 * one heap share a JIT, a garbage collector, and a cache-coherent view of memory; two JVMs on
 * two machines share none of it. So this construction is **strictly more favourable to ① and
 * ②** than a real fleet is. Everything it shows them losing, they lose worse in production.
 * What it therefore cannot support is the opposite claim — nothing here measures ① or ② over
 * a network, and no cost figure taken this way describes a real second instance.
 *
 * A plain class rather than a `@Service` for the same reason: the instance count is the
 * variable, and a singleton bean cannot be instantiated twice by the thing measuring it.
 * A `TransactionTemplate` rather than `@Transactional` because a directly constructed object
 * has no proxy — which is `R6` §3.3's own finding arriving as a constraint on the harness.
 */
class LayeredCounter(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
    /** Which application instance this object stands for. Only ever used in messages. */
    val instance: String = "i1",
) {

    private val transactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /**
     * ②'s state, and the reason ② is the interesting arm: **it is state.**
     *
     * ① holds a lock and owns nothing; ③ holds nothing and owns nothing. ② keeps the count
     * in the process, which is what makes it fast and what makes a second process a second
     * answer rather than a slower one.
     */
    private val inMemory = AtomicInteger(0)

    /** What this instance would report if asked right now, without touching the database. */
    val inMemoryValue: Int get() = inMemory.get()

    /**
     * ① Mutual exclusion inside one JVM.
     *
     * `@Synchronized` puts the monitor on **this object**. Every thread in this process that
     * calls this method on this bean serialises, so the read and the write cannot interleave
     * and the read-modify-write that `R6` measured losing 864 of 1,000 becomes exact.
     *
     * It is exact for one reason only: every writer went through this object. Nothing in the
     * language, the framework or the database checks that, and nothing reports it when it
     * stops being true.
     */
    @Synchronized
    fun incrementBySynchronized(id: Long) {
        transactions.executeWithoutResult {
            val current = readCount(id)
            writeCount(id, current + 1)
        }
    }

    /**
     * ② An atomic update with no lock, written through to the database.
     *
     * `incrementAndGet` is a CAS loop: it cannot lose an increment and it cannot block. The
     * number it returns is unique to this call, so on one instance the sequence of values
     * written is exactly `1 … n`.
     *
     * **What is not obvious is that a unique value is not an ordered write.** Two threads
     * can be handed 998 and 999 and reach the database in the other order, and the row then
     * holds 998 with nothing wrong anywhere. Whether that actually happens on this stack is
     * a measurement and not an argument, so [inMemoryValue] is recorded beside the row: the
     * two are different answers to the same question and the report must not merge them.
     */
    fun incrementByCas(id: Long) {
        val next = inMemory.incrementAndGet()
        transactions.executeWithoutResult { writeCount(id, next) }
    }

    /**
     * ② in its honest form: the counter lives in memory and the database is written **once**.
     *
     * The write-behind counter — a shape common enough to be unremarkable, and correct on one
     * instance in a way the per-increment variant above is not, because there is exactly one
     * write and nothing can reorder it.
     *
     * On two instances each process flushes its own total and the second flush overwrites the
     * first. No exception, no constraint, no log line: the counter is simply half.
     */
    fun incrementInMemoryOnly() {
        inMemory.incrementAndGet()
    }

    /**
     * ① with no database in it at all — a monitor around a plain `Int`.
     *
     * **This is the arm that makes the cost comparison mean something.** Every other ① and ②
     * here pays a round trip per increment, and a round trip is so much more expensive than
     * either primitive that it hides the difference the brief asks about. With the database
     * removed, `synchronized` against `AtomicInteger` is a straight contest between a monitor
     * and a CAS loop — which is where the two are known to **invert** as contention rises, and
     * finding that point is the measurement.
     */
    @Synchronized
    fun incrementInMemorySynchronized() {
        guarded++
    }

    /** The monitor's counter. A plain `Int` on purpose: an atomic here would measure nothing. */
    private var guarded = 0

    /** Read under the same monitor, so the read cannot race the last write. */
    val guardedValue: Int get() = synchronized(this) { guarded }

    /** The flush that makes [incrementInMemoryOnly] visible to anyone else. */
    fun flushInMemory(id: Long) {
        transactions.executeWithoutResult { writeCount(id, inMemory.get()) }
    }

    /**
     * ③ The database. One statement, and the row is the scope.
     *
     * `R6` §5 chose this. What this class adds is *why* the choice survives a second
     * instance when ① and ② do not: the exclusion is attached to the row, and the row is the
     * one thing every instance already shares.
     */
    fun incrementByDatabase(id: Long) {
        transactions.executeWithoutResult {
            jdbc.update("update mastery set attempts_count = attempts_count + 1 where id = ?", id)
        }
    }

    /** The unguarded shape, kept so that every arm can be read against the defect itself. */
    fun incrementByReadModifyWrite(id: Long) {
        transactions.executeWithoutResult {
            val current = readCount(id)
            writeCount(id, current + 1)
        }
    }

    private fun readCount(id: Long): Int =
        jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, id)!!

    private fun writeCount(id: Long, value: Int) {
        jdbc.update("update mastery set attempts_count = ? where id = ?", value, id)
    }
}
