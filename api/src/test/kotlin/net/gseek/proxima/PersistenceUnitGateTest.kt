package net.gseek.proxima

import jakarta.persistence.EntityManagerFactory
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * **The application's persistence unit contains exactly these entities and nothing else.**
 *
 * ## Why an exact set, and why it is worth a test
 *
 * `@SpringBootApplication` sits in `net.gseek.proxima`, so Spring Boot's entity scan roots
 * there. **Test sources are on the same classpath as main sources when tests run.** An
 * `@Entity` written anywhere under that package for any reason — a fixture, a throwaway,
 * something nested inside a test class — silently joins the persistence unit that the whole
 * module's Spring contexts are built from. Hibernate then validates its table against the
 * schema Flyway built, does not find one, and **no context starts at all**.
 *
 * The failure is maximally unhelpful: every `@SpringBootTest` in the module goes red at once,
 * with `SchemaManagementException` stack traces that name the missing table but nothing about
 * where the entity came from or which commit added it.
 *
 * That happened here. Commit `8e5843a` added four measurement entities nested inside
 * `IdentifierGenerationTest`, whose own KDoc had already identified the risk and asserted a
 * mitigation that addressed a different question — how they were *used* rather than how they
 * were *found*. CI reported `Schema validation: missing table [t_open3_identity]` and **34 of
 * 52 tests failed across six classes, none of them the one that changed.**
 *
 * ## Why this is asserted as a set rather than as a count
 *
 * A count moves for a legitimate reason — a new aggregate — and someone updates the number.
 * The set names what arrived, so the diff that adds an entity has to say which one, and the
 * failure message can say where the wrong ones belong instead. This is the same argument
 * `R8` §3.2 makes for exact statement counts over upper bounds: a number that drifts upward
 * one honest commit at a time stops being a claim.
 *
 * Fixtures belong in `net.gseek.fixtures`, outside the scan root. See
 * `net/gseek/fixtures/open3/Open3Entities.kt`.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PersistenceUnitGateTest {

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `the persistence unit holds the application entities and no test fixtures`() {
        val entities = entityManagerFactory.metamodel.entities
            .map { it.javaType.name }
            .sorted()

        assertEquals(
            listOf(
                "net.gseek.proxima.domain.Attempt",
                "net.gseek.proxima.domain.Concept",
                "net.gseek.proxima.domain.Item",
                "net.gseek.proxima.domain.Learner",
                "net.gseek.proxima.domain.Mastery",
            ),
            entities,
            "the persistence unit is not what it should be. If something under " +
                "net.gseek.proxima gained an @Entity for a test, move it to net.gseek.fixtures " +
                "-- Spring Boot's entity scan roots at ProximaApplication's package and test " +
                "sources share the classpath, so it will be found and every @SpringBootTest in " +
                "this module will fail schema validation. See ADR-003 and Open3Entities.kt",
        )
    }
}
