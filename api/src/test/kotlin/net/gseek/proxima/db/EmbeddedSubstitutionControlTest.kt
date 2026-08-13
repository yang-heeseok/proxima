package net.gseek.proxima.db

import java.sql.DriverManager
import javax.sql.DataSource
import kotlin.test.assertEquals
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * The control for [EmbeddedSubstitutionGateTest]. **It proves the gate is not vacuous.**
 *
 * The gate asserts that `@DataJpaTest` reaches PostgreSQL. That assertion passes for two
 * completely different reasons, and only one of them is worth anything:
 *
 * 1. the substitution mechanism is live, and something is stopping it — the gate is real;
 * 2. there is no embedded database to substitute — the gate would pass over any code at all.
 *
 * Reason 2 is what this repository looked like before `com.h2database:h2` was added, and it
 * is what it will look like again the day someone removes it. So this test asks for the
 * substitution *explicitly* and pins what arrives. If H2 leaves the classpath, this fails
 * and says that the gate beside it has stopped meaning anything — rather than the gate
 * silently becoming a test that checks nothing.
 *
 * `R5`'s log appender captured no events and nearly proved an absence; `R8`'s counter fails
 * rather than reporting zero when statistics are off. This is the same instrument check, for
 * a gate rather than for a measurement.
 *
 * ## What was actually measured here
 *
 * `@AutoConfigureTestDatabase.replace` defaults to **`NON_TEST`** in Spring Boot 4.1.0 —
 * read out of the annotation's `AnnotationDefault` attribute with `javap`, not from memory
 * and not from documentation. Under that default a datasource contributed by a test
 * configuration, which is what `@ServiceConnection` produces, is left alone. Under `ANY`
 * below, it is not: the container still starts and the test still talks to H2.
 */
/*
 * `ddl-auto=none` is set here and nowhere else, and the reason is a measurement.
 *
 * Without it this context does not start at all: Flyway reports `Successfully applied 3
 * migrations` against the substituted H2 and Hibernate then fails with
 * `Schema validation: missing table [attempt]`. Both of those are findings and both are
 * recorded in `R9` §3.4 -- but a context that will not start cannot answer the question this
 * class is asking, which is only *which database arrives*. So schema validation is switched
 * off to separate the two, rather than left on to conflate them.
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(TestcontainersConfiguration::class)
class EmbeddedSubstitutionControlTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var container: PostgreSQLContainer

    private fun appTables(c: java.sql.Connection): List<String> =
        c.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
            buildList { while (rs.next()) add(rs.getString("TABLE_NAME").lowercase()) }
                .filter { it in setOf("attempt", "concept", "item", "learner", "mastery") }
                .sorted()
        }

    @Test
    fun `asking for substitution explicitly does reach an embedded database`() {
        dataSource.connection.use { c ->
            val product = c.metaData.databaseProductName
            println("T8-CONTROL >>> the JPA datasource is $product at ${c.metaData.url}")
            println("T8-CONTROL >>> application tables in it        : ${appTables(c)}")

            assertEquals(
                "H2",
                product,
                "no embedded database was substituted -- EmbeddedSubstitutionGateTest is now " +
                    "asserting something that cannot fail. See docs/reports/R9.",
            )
        }

        // Flyway logged `Successfully applied 3 migrations` while this context was starting,
        // and the H2 above has none of those tables. Somewhere there is a database that does.
        // This looks in the only other one in the room rather than reasoning about which
        // bean Flyway resolved -- the tables are either there or they are not.
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            .use { c ->
                println("T8-CONTROL >>> the container is ${c.metaData.databaseProductName} at ${c.metaData.url}")
                println("T8-CONTROL >>> application tables in it        : ${appTables(c)}")
            }
    }
}
