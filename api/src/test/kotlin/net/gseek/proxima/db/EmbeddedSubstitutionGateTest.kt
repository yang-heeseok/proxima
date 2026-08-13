package net.gseek.proxima.db

import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

/**
 * `T8`'s regression gate — **the test lane connects to PostgreSQL, and says so out loud.**
 *
 * ## Why this test exists at the moment H2 arrives
 *
 * `@DataJpaTest` carries `@AutoConfigureTestDatabase(replace = ANY)`. That means: *if an
 * embedded database is on the classpath, use it instead of the configured datasource.*
 * Until this repository added `com.h2database:h2` to measure it, there was no embedded
 * database to substitute and the annotation had nothing to do.
 *
 * Adding H2 changed what existing tests connect to, without changing a line of their code.
 * A container still starts — `@Import(TestcontainersConfiguration)` is right there — and the
 * test still talks to H2. Nothing fails, nothing warns, and every assertion in the affected
 * test keeps passing, against a database that [H2DivergenceTest] measured as disagreeing
 * with PostgreSQL on the exact mechanism `R7` is about.
 *
 * So the dependency that makes `T8` measurable is itself the defect `T8` is about. That is
 * not irony; it is why the gate lands in the same commit.
 *
 * The root fix is `spring.test.database.replace: NONE` in `application-test.yml`, which is
 * global — a per-test annotation would fix this test and leave the next one exposed. This
 * asserts the effect, not the property, for the reason `R4` §7 gives: a gate that asserts a
 * setting passes when the setting is read from a file nobody loads.
 */
@DataJpaTest
@Import(TestcontainersConfiguration::class)
class EmbeddedSubstitutionGateTest {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `the test lane runs on PostgreSQL and not on an embedded substitute`() {
        dataSource.connection.use { c ->
            val product = c.metaData.databaseProductName
            val url = c.metaData.url

            println("T8-GATE >>> @DataJpaTest connected to $product at $url")

            assertEquals(
                "PostgreSQL",
                product,
                "an embedded database was substituted for the container -- see docs/reports/R9",
            )
            assertTrue(
                url.startsWith("jdbc:postgresql:"),
                "the JDBC url is not PostgreSQL's: $url",
            )
        }
    }
}
