package net.gseek.proxima.basics

import jakarta.persistence.EntityManagerFactory
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.gseek.fixtures.basics.DataClassChild
import net.gseek.fixtures.basics.DataClassChildPlainParent
import net.gseek.fixtures.basics.EqParent
import net.gseek.fixtures.basics.IdEqualsChild
import net.gseek.fixtures.basics.PlainParent
import net.gseek.proxima.TestcontainersConfiguration
import org.hibernate.Hibernate
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * `R39` — **what one `==` costs, when the thing being compared is an entity.**
 *
 * ## What this measures and why it is a count
 *
 * Three things go wrong when equality meets JPA, and all three are countable rather than
 * timed:
 *
 * 1. an entity's **hash changes** when its `id` arrives, so a collection that holds it
 *    cannot find it again — counted as *found* or *not found*;
 * 2. a lazy proxy and the loaded instance of one row disagree about their **class**, so an
 *    `equals` written with `javaClass` reports one row as two — counted as a boolean under
 *    three type checks;
 * 3. a generated `equals` that reaches a lazy association makes the proxies answer, so
 *    comparing two objects **issues SQL** — counted with Hibernate's own
 *    `prepareStatementCount`, the instrument `R8` established.
 *
 * None of the three needs a duration and none is reported as one. `R8` §8 already says a
 * statement count is not a duration; this report does not pretend otherwise in either
 * direction.
 *
 * ## Why a standalone SessionFactory, and why it does not start a container
 *
 * The shapes under test cannot live in the application's persistence unit — two of them are
 * `@Entity` `data class`es, which `ENTITIES_ARE_NOT_DATA_CLASSES` refuses on production code
 * and should go on refusing. They are mapped by a `SessionFactory` built here instead.
 *
 * `IdentifierGenerationTest` is the precedent for that, and it starts its own
 * `PostgreSQLContainer`. **This one deliberately does not.** It reuses the container the
 * Spring context already holds, reads its JDBC URL, and creates its tables in a schema of its
 * own. A second PostgreSQL would be a second copy of the same server for no measurement
 * benefit, and this run shares an eight-core machine with other work.
 *
 * The schema is what keeps that safe. `BaselineMigrationTest` asserts that
 * `table_schema = 'public'` holds **exactly** seven base tables; `hbm2ddl` creating five more
 * beside them would turn it red, and the failure would name a table nobody had heard of in a
 * test nobody had changed.
 *
 * ## Everything is printed before anything is asserted
 *
 * Deliberate. Several of the numbers below were **predicted before they were measured**, and a
 * test that dies at the first wrong prediction hands back one fact per run. Printing the whole
 * table first means a single execution yields every figure the report needs, including the
 * ones that refute what was expected. Where a prediction was wrong, `R39` says so rather than
 * quietly adopting the measured value.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityEqualityTest {

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    /**
     * Present only so the Spring context — and therefore Flyway and the container — is fully
     * started before [factory] reads the JDBC URL off it.
     */
    @Autowired
    private lateinit var applicationFactory: EntityManagerFactory

    private lateinit var sf: SessionFactory

    private fun factory(): SessionFactory {
        if (::sf.isInitialized) return sf

        // hbm2ddl will not create the namespace it is told to use unless asked, and asking
        // through `create_namespaces` also makes it DROP the schema on close. Creating it
        // here by hand keeps the teardown in one place -- afterAll below.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { c -> c.createStatement().use { it.execute("create schema if not exists $SCHEMA") } }

        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.url", postgres.jdbcUrl)
            .applySetting("hibernate.connection.username", postgres.username)
            .applySetting("hibernate.connection.password", postgres.password)
            .applySetting("hibernate.default_schema", SCHEMA)
            .applySetting("hibernate.hbm2ddl.auto", "create")
            .applySetting("hibernate.generate_statistics", "true")
            .applySetting("hibernate.show_sql", "false")
            .build()

        val sources = MetadataSources(registry)
        listOf(
            EqParent::class.java,
            PlainParent::class.java,
            IdEqualsChild::class.java,
            DataClassChild::class.java,
            DataClassChildPlainParent::class.java,
        ).forEach { sources.addAnnotatedClass(it) }

        sf = sources.buildMetadata().buildSessionFactory()
        return sf
    }

    /**
     * **The fixture removes its own schema, and that is what makes it a fixture.**
     *
     * These five tables are created by `hbm2ddl` on the factory above and by one
     * `create schema` this class issues over JDBC. **No Flyway migration is involved and none
     * was added** — the migration ceiling is still `V5` and `db/migration` is untouched. The
     * brief's *"no schema changes"* is about the application's schema, which this never
     * touches; the tables live and die inside `g_basics`.
     *
     * Dropping it matters beyond tidiness. The container is shared with every other
     * `@SpringBootTest` in the module through Spring's context cache, so a leaked schema would
     * outlive this class. It would not break `BaselineMigrationTest`, which reads `public`
     * only — but a fixture that relies on another test's `where` clause for its safety is one
     * edit away from causing a failure that reads as a migration defect. It cleans up after
     * itself instead.
     */
    @AfterAll
    fun dropFixtureSchema() {
        if (::sf.isInitialized) sf.close()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            .use { c -> c.createStatement().use { it.execute("drop schema if exists $SCHEMA cascade") } }
    }

    // ------------------------------------------------------------------------------------
    // 1. The hash that moves
    // ------------------------------------------------------------------------------------

    /**
     * **Put it in a `Set`, save it, and it is gone** — for one of the two shapes.
     *
     * The object is still in the set. `size` is 1 and the reference is the same reference.
     * What has changed is the bucket it would be looked for in, so `contains` walks the wrong
     * chain and reports absence. Nothing throws, and no test that does not look for this can
     * see it.
     */
    @Test
    fun `an entity's hash moves when its id arrives, and only one shape survives it`() {
        val sf = factory()
        val rows = mutableListOf<String>()

        var dataFound = true
        var dataHashMoved = false
        var idFound = false
        var idHashMoved = true

        sf.openSession().use { session ->
            val tx = session.beginTransaction()
            val parent = EqParent("parent").also { session.persist(it) }

            val dataChild = DataClassChild(parent = parent, secondary = parent, label = "d")
            val dataSet = HashSet<DataClassChild>()
            dataSet.add(dataChild)
            val dataBefore = dataChild.hashCode()
            session.persist(dataChild)
            val dataAfter = dataChild.hashCode()
            dataFound = dataSet.contains(dataChild)
            dataHashMoved = dataBefore != dataAfter
            rows += "data class      id ${dataChild.id}  hash $dataBefore -> $dataAfter  " +
                "moved=$dataHashMoved  size=${dataSet.size}  " +
                "sameReferenceInSet=${dataSet.first() === dataChild}  contains=$dataFound"

            val idChild = IdEqualsChild(parent = parent, secondary = parent, label = "i")
            val idSet = HashSet<IdEqualsChild>()
            idSet.add(idChild)
            val idBefore = idChild.hashCode()
            session.persist(idChild)
            val idAfter = idChild.hashCode()
            idFound = idSet.contains(idChild)
            idHashMoved = idBefore != idAfter
            rows += "id equality     id ${idChild.id}  hash $idBefore -> $idAfter  " +
                "moved=$idHashMoved  size=${idSet.size}  " +
                "sameReferenceInSet=${idSet.first() === idChild}  contains=$idFound"

            tx.commit()
        }

        println()
        println("R39-HASH >>> one entity, added to a HashSet before persist, looked up after")
        rows.forEach { println("  $it") }
        println()

        assertTrue(dataHashMoved, "the data class hash did not move; the trap did not arm")
        assertFalse(
            dataFound,
            "a data class entity was still findable after persist. If this passes, Kotlin " +
                "stopped generating hashCode over the id, and R39's first number is stale",
        )
        assertFalse(idHashMoved, "BaseEntity's shape is supposed to hash constant per type")
        assertTrue(
            idFound,
            "the shipped shape lost its own entity from a Set. That is the defect R39 says " +
                "this shape does not have",
        )
    }

    // ------------------------------------------------------------------------------------
    // 2. A proxy and the row it stands for
    // ------------------------------------------------------------------------------------

    /**
     * **`getClass()` and `instanceof` do not agree about a lazy proxy, and one of them is
     * wrong about how many rows there are.**
     *
     * A Hibernate proxy is a generated subclass. Its `javaClass` is that subclass, so an
     * `equals` guarded by `javaClass != other.javaClass` returns `false` for two references to
     * one row — which is the single most common way a hand-written entity `equals` is broken.
     * `instanceof` is satisfied by the subclass and `Hibernate.getClass` unwraps it, so both
     * of those agree with the database.
     */
    @Test
    fun `a lazy proxy and the loaded entity of one row, under three type checks`() {
        val sf = factory()
        val parentId = sf.openSession().use { session ->
            val tx = session.beginTransaction()
            val p = EqParent("compared").also { session.persist(it) }
            tx.commit()
            p.id!!
        }

        var byHibernateGetClass = false
        var byJavaClass = true
        var byInstanceOf = false
        var byShippedEquals = false
        var statementsForTypeChecks = 0L
        var proxyClassName = ""

        sf.openSession().use { s1 ->
            sf.openSession().use { s2 ->
                val proxy = s1.getReference(EqParent::class.java, parentId)
                val loaded = s2.find(EqParent::class.java, parentId)

                sf.statistics.clear()
                val before = sf.statistics.prepareStatementCount

                proxyClassName = proxy.javaClass.simpleName
                byHibernateGetClass = Hibernate.getClass(proxy) == Hibernate.getClass(loaded)
                byJavaClass = proxy.javaClass == loaded.javaClass
                byInstanceOf = proxy is EqParent && loaded is EqParent
                statementsForTypeChecks = sf.statistics.prepareStatementCount - before

                byShippedEquals = proxy == loaded
            }
        }

        println()
        println("R39-PROXY >>> one row, one uninitialised proxy, one loaded instance")
        println("  proxy runtime class          : $proxyClassName")
        println("  Hibernate.getClass agrees    : $byHibernateGetClass")
        println("  javaClass agrees             : $byJavaClass")
        println("  instanceof agrees            : $byInstanceOf")
        println("  BaseEntity-shaped equals     : $byShippedEquals")
        println("  statements the type checks cost: $statementsForTypeChecks")
        println()

        assertTrue(byHibernateGetClass, "Hibernate.getClass must unwrap the proxy")
        assertFalse(
            byJavaClass,
            "the proxy and the entity reported the same javaClass. If Hibernate stopped " +
                "proxying by subclassing, R39's second finding no longer applies here",
        )
        assertTrue(byInstanceOf, "a proxy is a subclass, so instanceof must hold")
        assertTrue(byShippedEquals, "one row must equal itself across two sessions")
        assertEquals(
            0, statementsForTypeChecks.toInt(),
            "asking a proxy for its type must not initialise it",
        )
    }

    // ------------------------------------------------------------------------------------
    // 3. The headline: how many queries one equality check costs
    // ------------------------------------------------------------------------------------

    /**
     * **The number `R39` exists to produce.**
     *
     * Two distinct objects standing for **the same row**, each holding its own uninitialised
     * proxies, compared once. That is not a contrived arrangement: it is what happens whenever
     * an entity loaded in one place is compared with the same entity loaded in another, which
     * is what `contains`, `distinct`, and `indexOf` do for a living.
     *
     * The generated `equals` compares constructor properties in declaration order and
     * short-circuits at the first mismatch, so the two rows must genuinely be one row for the
     * association to be reached at all. A test that compared two *different* rows would return
     * `false` at `id` and cost nothing, and would have reported that this trap does not exist.
     */
    @Test
    fun `how many statements one equality check costs, by shape`() {
        val sf = factory()

        val (idChildId, dataChildId, plainChildId) = seedOneOfEach(sf)

        val shipped = compareTwoLoadsOfOneRow(sf, IdEqualsChild::class.java, idChildId)
        val dataClass = compareTwoLoadsOfOneRow(sf, DataClassChild::class.java, dataChildId)
        val dataPlain =
            compareTwoLoadsOfOneRow(sf, DataClassChildPlainParent::class.java, plainChildId)

        println()
        println("R39-EQUALITY >>> two loads of ONE row, compared once")
        println("  %-46s %-8s %s".format("shape", "equal?", "statements"))
        println("  %-46s %-8s %d".format(
            "id equality, 2 lazy associations (SHIPPED)", shipped.first, shipped.second))
        println("  %-46s %-8s %d".format(
            "data class, 2 lazy assoc, parent overrides equals", dataClass.first, dataClass.second))
        println("  %-46s %-8s %d".format(
            "data class, 1 lazy assoc, parent plain equals", dataPlain.first, dataPlain.second))
        println()

        assertTrue(
            shipped.first,
            "the shipped shape must report one row as equal to itself",
        )
        assertEquals(
            0, shipped.second.toInt(),
            "BaseEntity's equals reads id and type only, so it cannot issue SQL. If this is " +
                "not 0, something added an association to the comparison",
        )

        assertNotEquals(
            0, dataClass.second.toInt(),
            "a generated equals over lazy associations is supposed to make the proxies " +
                "answer. Zero here would mean the association was not reached -- check that " +
                "both sides are the SAME row, or the comparison short-circuits at id",
        )

        // The plain-parent arm is the mechanism, not a second copy of the finding: what
        // changes is the class being compared TO, not the class doing the comparing.
        assertTrue(
            dataPlain.second <= dataClass.second,
            "an association whose class does not override equals cannot cost more than one " +
                "that does",
        )
    }

    private fun seedOneOfEach(sf: SessionFactory): Triple<Long, Long, Long> =
        sf.openSession().use { session ->
            val tx = session.beginTransaction()
            val eq = EqParent("eq").also { session.persist(it) }
            val eq2 = EqParent("eq2").also { session.persist(it) }
            val plain = PlainParent("plain").also { session.persist(it) }

            val idChild = IdEqualsChild(parent = eq, secondary = eq2, label = "shipped")
                .also { session.persist(it) }
            val dataChild = DataClassChild(parent = eq, secondary = eq2, label = "data")
                .also { session.persist(it) }
            val plainChild = DataClassChildPlainParent(parent = plain, label = "plain")
                .also { session.persist(it) }
            tx.commit()
            Triple(idChild.id!!, dataChild.id!!, plainChild.id!!)
        }

    /**
     * Loads [id] twice, in two sessions that are both open, and compares the two instances.
     *
     * **Both sessions stay open on purpose.** Detaching one side would make the comparison
     * raise `LazyInitializationException` instead of issuing a query, which is a different
     * finding measured by a different report. Here the question is what the comparison
     * *costs* when it works.
     */
    private fun <T : Any> compareTwoLoadsOfOneRow(
        sf: SessionFactory,
        type: Class<T>,
        id: Long,
    ): Pair<Boolean, Long> =
        sf.openSession().use { s1 ->
            sf.openSession().use { s2 ->
                val a = s1.find(type, id)
                val b = s2.find(type, id)

                check(sf.statistics.isStatisticsEnabled) {
                    "Hibernate statistics are disabled, so this would report 0 for " +
                        "everything -- the failure mode R8 section 3.3 records"
                }
                sf.statistics.clear()
                val before = sf.statistics.prepareStatementCount
                val equal = a == b
                (equal to (sf.statistics.prepareStatementCount - before))
            }
        }

    private companion object {
        /**
         * Not `public`. `BaselineMigrationTest` asserts the exact table list of the public
         * schema, and five fixture tables appearing beside the seven baseline ones would fail
         * it with a message about a table that has nothing to do with migrations.
         */
        const val SCHEMA = "g_basics"
    }
}
