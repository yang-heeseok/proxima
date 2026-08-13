package net.gseek.proxima.db

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration.Companion.POSTGRES_IMAGE
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * `OPEN-3` — **does `IDENTITY` actually prevent batched inserts, and what does a sequence
 * cost instead?**
 *
 * ## Why this exists now
 *
 * `docs/decisions/open.md` has said since 2026-08-10 that the trade is *worth measuring
 * rather than assuming*, and then that **there is nothing to measure until a bulk insert
 * path exists**. That second clause was wrong, and `R9` §8 is the second report in a row to
 * say the question was still open.
 *
 * The claim under test — `IDENTITY` forces a round trip per row, a sequence does not — is a
 * property of the generator and the JDBC driver. It needs three entities and a database. It
 * does not need the application to have a bulk path, and waiting for one meant a provisional
 * choice was hardening into a permanent one by default. **A deadline that can never arrive is
 * not a deadline.**
 *
 * ## What is measured
 *
 * Three arms, identical in everything except the generator, all with
 * `hibernate.jdbc.batch_size = 50` — including the `IDENTITY` arm, because *setting the batch
 * size and having it do nothing* is exactly the failure this decision is about.
 *
 * Both statements and wall time are reported. Statements alone would be misleading: Hibernate
 * can reuse one `PreparedStatement` across unbatched inserts, so a count can agree while the
 * round trips do not. `R8` §8 says a statement count is not a duration; this is the case that
 * proves it.
 *
 * ## A standalone SessionFactory, not the application's
 *
 * These three entities exist to be measured and must not join the application's persistence
 * unit — Hibernate would then validate their tables in every other test in this module. Each
 * arm builds its own `SessionFactory` against the same container, with `hbm2ddl` creating
 * only the table that arm needs.
 */
class IdentifierGenerationTest {

    companion object {
        private const val ROWS = 1_000
        private const val BATCH_SIZE = 50

        private lateinit var pg: PostgreSQLContainer

        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            pg.start()
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            if (::pg.isInitialized) pg.stop()
        }
    }

    // -----------------------------------------------------------------------------------
    // The three arms, differing only in how an id is obtained
    // -----------------------------------------------------------------------------------

    @Entity
    @Table(name = "t_open3_identity")
    class IdentityRow(var payload: String = "") {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        var id: Long? = null
    }

    @Entity
    @Table(name = "t_open3_seq_1")
    class SequenceRowAllocate1(var payload: String = "") {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "g1")
        @SequenceGenerator(name = "g1", sequenceName = "seq_open3_1", allocationSize = 1)
        var id: Long? = null
    }

    @Entity
    @Table(name = "t_open3_seq_50")
    class SequenceRowAllocate50(var payload: String = "") {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "g50")
        @SequenceGenerator(name = "g50", sequenceName = "seq_open3_50", allocationSize = 50)
        var id: Long? = null
    }

    /** The seeded-database hazard `open.md` recorded but never reproduced. */
    @Entity
    @Table(name = "t_open3_seeded")
    class SeededRow(var payload: String = "") {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gs")
        @SequenceGenerator(name = "gs", sequenceName = "seq_open3_seeded", allocationSize = 1)
        var id: Long? = null
    }

    // -----------------------------------------------------------------------------------

    private fun sessionFactory(vararg entities: Class<*>): SessionFactory {
        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", pg.jdbcUrl)
            .applySetting("hibernate.connection.username", pg.username)
            .applySetting("hibernate.connection.password", pg.password)
            .applySetting("hibernate.hbm2ddl.auto", "create")
            .applySetting("hibernate.jdbc.batch_size", BATCH_SIZE.toString())
            .applySetting("hibernate.order_inserts", "true")
            .applySetting("hibernate.generate_statistics", "true")
            .applySetting("hibernate.show_sql", "false")
            .build()
        val sources = MetadataSources(registry)
        entities.forEach { sources.addAnnotatedClass(it) }
        return sources.buildMetadata().buildSessionFactory()
    }

    private class Run(val statements: Long, val millis: Long)

    /**
     * One measured insert of [ROWS] rows in one transaction.
     *
     * The statistics are cleared inside, so the count is this transaction's and not the
     * factory's lifetime total. Statistics being enabled is asserted rather than assumed,
     * for the reason `StatementCounter` gives: a counter that silently reports zero turns
     * every assertion built on it into a test that measured nothing.
     */
    private fun insertRows(sf: SessionFactory, make: (Int) -> Any): Run {
        check(sf.statistics.isStatisticsEnabled) { "statistics are off; this would count 0" }
        sf.statistics.clear()
        val before = sf.statistics.prepareStatementCount
        val t0 = System.nanoTime()
        sf.openSession().use { session ->
            val tx = session.beginTransaction()
            repeat(ROWS) { i ->
                session.persist(make(i))
                if (i % BATCH_SIZE == BATCH_SIZE - 1) {
                    session.flush()
                    session.clear()
                }
            }
            tx.commit()
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        return Run(sf.statistics.prepareStatementCount - before, millis)
    }

    private fun truncate(table: String) {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
            c.createStatement().use { it.execute("truncate table $table") }
        }
    }

    private class Arm(val label: String, val statements: Long, val times: List<Long>) {
        val median get() = times[1]
        val spread get() = (times.last() - times.first()) * 100 / maxOf(times.last(), 1)
        override fun toString() =
            "%-38s statements %-5d  ms %-16s median %4d  spread %d%%"
                .format(label, statements, times.toString(), median, spread)
    }

    private fun measure(label: String, table: String, sf: SessionFactory, make: (Int) -> Any): Arm {
        // One discarded warm-up. The JIT has not seen this path and the pool has not opened
        // a connection; including that in the first number would make arm order matter.
        insertRows(sf, make)
        truncate(table)

        val runs = (1..3).map {
            val r = insertRows(sf, make)
            truncate(table)
            r
        }
        val statements = runs.map { it.statements }.distinct()
        check(statements.size == 1) {
            "the statement count varied between runs ($statements); it is supposed to be " +
                "deterministic, so something other than the generator is being measured"
        }
        return Arm(label, statements.single(), runs.map { it.millis }.sorted())
    }

    /**
     * **This test is `ADR-003`'s trip-wire, which is why the counts are asserted exactly.**
     *
     * The decision to keep `IDENTITY` rests on one fact: Hibernate cannot batch inserts
     * whose keys the database assigns, so it must execute each one to read the key back.
     * That is a property of a version, not a law — PostgreSQL can return generated keys for
     * a multi-row insert, and a future Hibernate could use it. **The day it does, the
     * arithmetic behind `ADR-003` changes and this test goes red to say so.**
     *
     * Durations are printed and deliberately **not** asserted. They move with the machine;
     * the counts do not.
     */
    @Test
    fun `what each identifier strategy costs for a thousand inserts`() {
        val identity = sessionFactory(IdentityRow::class.java).use {
            measure("IDENTITY  (what ships today)", "t_open3_identity", it) { i -> IdentityRow("row-$i") }
        }
        val sequence1 = sessionFactory(SequenceRowAllocate1::class.java).use {
            measure("SEQUENCE  allocationSize = 1", "t_open3_seq_1", it) { i -> SequenceRowAllocate1("row-$i") }
        }
        val sequence50 = sessionFactory(SequenceRowAllocate50::class.java).use {
            measure("SEQUENCE  allocationSize = 50", "t_open3_seq_50", it) { i -> SequenceRowAllocate50("row-$i") }
        }

        println()
        println("OPEN3-INSERTS >>> $ROWS rows per run, hibernate.jdbc.batch_size = $BATCH_SIZE")
        listOf(identity, sequence1, sequence50).forEach { println("  $it") }
        println("  IDENTITY / SEQUENCE(50) on the median: %.1fx"
            .format(identity.median.toDouble() / sequence50.median))
        println()

        val batches = ROWS / BATCH_SIZE

        assertEquals(
            ROWS.toLong(),
            identity.statements,
            "IDENTITY batched something. It could not before, and if it can now, ADR-003's " +
                "premise is gone -- re-read it before changing this number.",
        )
        assertEquals(
            (ROWS + batches).toLong(),
            sequence1.statements,
            "a sequence with allocationSize = 1 is supposed to cost one nextval per row plus " +
                "one execution per batch",
        )
        assertEquals(
            (ROWS / 50 + batches).toLong(),
            sequence50.statements,
            "a sequence with allocationSize = 50 is supposed to cost one nextval per 50 rows " +
                "plus one execution per batch",
        )
    }

    /**
     * The hazard `open.md` recorded on 2026-08-10 and never reproduced: a sequence generator
     * that is **not** the sequence the seed loader realigns.
     *
     * The loader `COPY`s rows with explicit ids and then calls `setval`. If a later decision
     * introduces a sequence and that step is missed — or realigns a different sequence — the
     * generator starts at 1 and hands out ids that already exist. **Against an empty test
     * database this never happens.**
     */
    @Test
    fun `a sequence that was not realigned collides with seeded rows`() {
        sessionFactory(SeededRow::class.java).use { sf ->
            // Stand in for the seed loader's COPY: explicit ids, sequence untouched.
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().use { st ->
                    st.execute("truncate table t_open3_seeded")
                    st.execute(
                        "insert into t_open3_seeded (id, payload) values " +
                            (1..5).joinToString(", ") { "($it, 'seeded-$it')" },
                    )
                }
            }

            val beforeRealign = try {
                sf.openSession().use { s ->
                    val tx = s.beginTransaction()
                    s.persist(SeededRow("application-row"))
                    tx.commit()
                }
                "INSERTED -- no collision"
            } catch (e: Exception) {
                val root = generateSequence(e as Throwable) { it.cause }.last()
                "FAILED -- " + (root.message?.lineSequence()?.firstOrNull()?.take(80))
            }

            // What the loader is supposed to do, and the whole content of the hazard.
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().use {
                    it.execute("select setval('seq_open3_seeded', (select max(id) from t_open3_seeded))")
                }
            }

            val afterRealign = try {
                sf.openSession().use { s ->
                    val tx = s.beginTransaction()
                    s.persist(SeededRow("application-row"))
                    tx.commit()
                }
                "INSERTED"
            } catch (e: Exception) {
                val root = generateSequence(e as Throwable) { it.cause }.last()
                "FAILED -- " + (root.message?.lineSequence()?.firstOrNull()?.take(80))
            }

            println()
            println("OPEN3-SEEDED >>> 5 rows loaded with explicit ids 1..5, sequence left at its start")
            println("  first application insert, sequence NOT realigned : $beforeRealign")
            println("  same insert after setval to max(id)              : $afterRealign")
            println()

            assertTrue(afterRealign == "INSERTED", "realigning the sequence did not fix it: $afterRealign")
        }
    }
}
