package net.gseek.proxima.perf

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.boot.test.context.TestComponent

/**
 * Counts the JDBC statements a block of code issues.
 *
 * **`T7`'s whole idea is that a performance property can be held by a test rather than by
 * review.** An N+1 is invisible in a code review of the diff that introduces it — the diff
 * usually adds a field to a response — and invisible in a functional test, because the
 * answer is correct. It is visible in exactly one place: the number of statements.
 *
 * Hibernate's own counter is used rather than a JDBC proxy, because it counts what the ORM
 * decided to do, which is the thing under test. A proxy would also count the statements
 * Testcontainers, Flyway, and the pool's validation issue.
 */
@TestComponent
class StatementCounter(private val entityManagerFactory: EntityManagerFactory) {

    private val statistics
        get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    /**
     * Runs [block] and returns how many statements it prepared, along with its result.
     *
     * **Fails loudly if statistics are off**, rather than returning zero. A counter that
     * silently reports 0 would turn every assertion built on it into a test that passes
     * because it measured nothing — the failure mode this repository has already met twice,
     * in `R5`'s log appender and in its `pg_stat_user_tables` delta.
     */
    fun <T> count(block: () -> T): Counted<T> {
        check(statistics.isStatisticsEnabled) {
            "Hibernate statistics are disabled, so this counter would report 0 for " +
                "everything. Set spring.jpa.properties.hibernate.generate_statistics"
        }
        statistics.clear()
        val before = statistics.prepareStatementCount
        val result = block()
        val after = statistics.prepareStatementCount
        return Counted(result, (after - before).toInt())
    }
}

data class Counted<T>(val result: T, val statements: Int)
