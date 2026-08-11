package net.gseek.proxima.domain

import jakarta.persistence.EntityManager
import net.gseek.proxima.TestcontainersConfiguration
import org.hibernate.Hibernate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The mappings agree with `V1`, and lazy associations are actually lazy.
 *
 * The first is largely established by the application context starting at all:
 * `spring.jpa.hibernate.ddl-auto=validate` compares every mapped entity against the live
 * schema and refuses to start on a mismatch. That is a real check and it is why the value
 * is `validate` rather than `none`.
 *
 * The second is not established by anything else, and is the reason this file exists.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class EntityMappingTest {

    @Autowired
    private lateinit var em: EntityManager

    private fun persistLearnerWithAttempt(): Long {
        val learner = Learner(externalRef = "learner-900001")
        val concept = Concept(code = "concept-900001", name = "Concept 900001", gradeBand = "G5-6")
        em.persist(learner)
        em.persist(concept)
        val item = Item(
            code = "item-900001",
            conceptPrimary = concept,
            difficulty = 5,
        )
        em.persist(item)
        val attempt = Attempt(
            learner = learner,
            item = item,
            correct = true,
            elapsedMs = 4_200,
            attemptedAt = Instant.parse("2026-08-11T00:00:00Z"),
        )
        em.persist(attempt)
        em.flush()
        em.clear()
        return attempt.id!!
    }

    @Test
    fun `a lazy many-to-one is a proxy that is not initialised until it is used`() {
        val attemptId = persistLearnerWithAttempt()

        val attempt = em.find(Attempt::class.java, attemptId)

        // This is the assertion that a final entity class cannot satisfy: Hibernate builds
        // a lazy proxy by generating a SUBCLASS of the entity at run time, exactly as
        // Spring builds an AOP proxy by subclassing a bean. A class that cannot be
        // subclassed cannot be proxied, and the association is then loaded eagerly --
        // silently, with no error and no warning, turning one SELECT into several.
        assertFalse(
            Hibernate.isInitialized(attempt.item),
            "attempt.item was already loaded -- the association is not lazy. If Item cannot " +
                "be subclassed, Hibernate cannot build a proxy for it",
        )
        assertFalse(
            Hibernate.isInitialized(attempt.learner),
            "attempt.learner was already loaded -- the association is not lazy",
        )

        // Touching it initialises it, and only then.
        assertEquals("item-900001", attempt.item.code)
        assertTrue(Hibernate.isInitialized(attempt.item))
    }

    @Test
    fun `equals survives meeting a proxy of the same row`() {
        val attemptId = persistLearnerWithAttempt()

        val attempt = em.find(Attempt::class.java, attemptId)
        val proxied: Item = attempt.item                       // proxy, uninitialised
        val loaded: Item = em.find(Item::class.java, proxied.id)

        // The point of BaseEntity. A generated `equals` reading fields would say false
        // here, because a proxy's fields are empty until a getter runs.
        assertEquals(proxied, loaded, "a proxy and a loaded instance of the same row must be equal")
        assertEquals(loaded, proxied, "and equality must be symmetric")
        assertEquals(proxied.hashCode(), loaded.hashCode(), "equal objects must hash equally")

        assertTrue(hashSetOf(proxied).contains(loaded), "a Set must not hold both")
        assertEquals(1, hashSetOf(proxied, loaded).size)
    }

    @Test
    fun `hashCode does not change when an id is assigned`() {
        // The contract a data class cannot keep: an entity put into a hash-based collection
        // before persist must still be findable in it afterwards.
        val learner = Learner(externalRef = "learner-900002")
        val before = learner.hashCode()
        val set = hashSetOf(learner)

        em.persist(learner)
        em.flush()

        assertEquals(before, learner.hashCode(), "hashCode changed when the id was assigned")
        assertTrue(set.contains(learner), "the entity vanished from the set it was put in")
    }

    @Test
    fun `two unsaved entities are not equal to each other`() {
        // The other half of id-based equality: without the null guard, every new entity
        // would be equal to every other new entity of the same type.
        val a = Learner(externalRef = "learner-900003")
        val b = Learner(externalRef = "learner-900004")

        assertNotEquals(a, b)
        assertEquals(a, a)
        assertEquals(2, hashSetOf(a, b).size)
    }
}
