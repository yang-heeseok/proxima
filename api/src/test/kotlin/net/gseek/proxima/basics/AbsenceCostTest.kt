package net.gseek.proxima.basics

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.domain.Mastery
import net.gseek.proxima.domain.MasteryRepository
import net.gseek.proxima.perf.StatementCounter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `R42` — **`orElse` evaluates its argument. `orElseGet` evaluates its argument only when it
 * is needed. They look the same and one of them queries the database for nothing.**
 *
 * ## ⛔ This trap is smaller than the other three, and saying so is part of the report
 *
 * `R39` measures an equality check that costs SQL; `R40` measures committed rows surviving a
 * failed unit of work; `R41` measures rows silently disappearing from a paged result. This one
 * measures **one wasted statement per call**. It is real, it is countable, and it is not in the
 * same class of harm. `R42` says that in its own §1 rather than presenting four findings of
 * equal weight.
 *
 * It earns its place for one reason: `orElse` and `orElseGet` are the pair everybody has read
 * about and nobody has measured, and the measurement is two lines of instrument away given
 * `R8`'s counter already exists.
 *
 * ## Two instruments, deliberately
 *
 * A statement count from `StatementCounter` **and** a plain call counter incremented inside the
 * fallback. They measure the same claim by different routes, and a disagreement between them
 * would mean the instrument is wrong rather than the finding. `R8` §3.3 records why this
 * repository does not trust a single counter that can silently report zero.
 *
 * ## Why the fallback goes through the repository and not through `JdbcTemplate`
 *
 * `StatementCounter` reads Hibernate's `prepareStatementCount`, which counts what the ORM
 * decided to do. A `JdbcTemplate` query in the fallback would be invisible to it, and the test
 * would report that `orElse` costs nothing — a false negative produced by pointing the
 * instrument at the wrong layer. The fallback calls `masteries.count()` so that the statement
 * it issues is one Hibernate can see.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, StatementCounter::class)
class AbsenceCostTest {

    @Autowired private lateinit var masteries: MasteryRepository
    @Autowired private lateinit var counter: StatementCounter
    @Autowired private lateinit var jdbc: JdbcTemplate

    private var learnerId = 0L
    private var conceptId = 0L
    private var masteryId = 0L

    /**
     * The instance the fallback hands back, fetched **before** any measurement so that
     * building it costs nothing inside a counted block.
     */
    private lateinit var spare: Mastery

    private val fallbackCalls = AtomicInteger()

    @BeforeEach
    fun seed() {
        learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values ('learner-g4-0001') returning id",
            Long::class.java,
        )!!
        conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) " +
                "values ('concept-g4-01', 'Concept G4', 'G5-6') returning id",
            Long::class.java,
        )!!
        masteryId = jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
        spare = masteries.findById(masteryId).orElseThrow()
        fallbackCalls.set(0)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-g4%')")
        jdbc.execute("delete from concept where code like 'concept-g4%'")
        jdbc.execute("delete from learner where external_ref like 'learner-g4%'")
    }

    /**
     * One statement, and one increment, every time it is called — whether or not it was needed.
     *
     * This stands for whatever the real fallback is: a default fetched from another table, a
     * configuration lookup, an object graph built to be discarded. It is a query here because a
     * query is what `R8`'s counter can see.
     */
    private fun expensiveFallback(): Mastery {
        fallbackCalls.incrementAndGet()
        // EXACTLY ONE statement, so FALLBACK_STATEMENTS below is a number that cannot drift.
        // An earlier draft of this method built a fresh Mastery by loading its associations,
        // which cost three statements and made the constant depend on Hibernate's caching
        // within a transaction -- a fragile expectation measuring the wrong thing.
        masteries.count()
        return spare
    }

    private class Arm(val label: String, val statements: Int, val calls: Int)

    private fun arm(label: String, block: () -> Any?): Arm {
        fallbackCalls.set(0)
        val counted = counter.count(block)
        return Arm(label, counted.statements, fallbackCalls.get())
    }

    /**
     * **The number: what `orElse` costs when the value is present.**
     *
     * Four arms. The two `present` rows are the finding — the value was found, so the fallback
     * was not needed, and one of the two spellings ran it anyway.
     *
     * The `absent` rows are the control. They must agree with each other: when the fallback
     * genuinely is needed, the two spellings cost the same, which is what shows the difference
     * above is laziness and not overhead.
     */
    @Test
    fun `what orElse costs when the value is present`() {
        val presentOrElse = arm("present, orElse(fallback())") {
            masteries.findById(masteryId).orElse(expensiveFallback())
        }
        val presentOrElseGet = arm("present, orElseGet { fallback() }") {
            masteries.findById(masteryId).orElseGet { expensiveFallback() }
        }
        val absentOrElse = arm("absent,  orElse(fallback())") {
            masteries.findById(MISSING).orElse(expensiveFallback())
        }
        val absentOrElseGet = arm("absent,  orElseGet { fallback() }") {
            masteries.findById(MISSING).orElseGet { expensiveFallback() }
        }

        // Kotlin's own idiom, for comparison. The elvis operator is lazy by construction --
        // its right-hand side is not an argument, so there is nothing to evaluate early.
        val presentElvis = arm("present, Kotlin ?: fallback()") {
            masteries.findByLearnerIdAndConceptId(learnerId, conceptId) ?: expensiveFallback()
        }

        val arms = listOf(
            presentOrElse, presentOrElseGet, absentOrElse, absentOrElseGet, presentElvis,
        )

        println()
        println("R42-ABSENCE >>> one lookup, one fallback that issues a statement")
        println("  %-38s %-12s %s".format("arm", "statements", "fallback calls"))
        arms.forEach { println("  %-38s %-12d %d".format(it.label, it.statements, it.calls)) }
        println()

        assertEquals(
            1, presentOrElse.calls,
            "orElse must have evaluated its argument even though the value was present -- " +
                "that is the whole trap. If this is 0, Optional.orElse stopped being eager",
        )
        assertEquals(
            0, presentOrElseGet.calls,
            "orElseGet must not evaluate its supplier when the value is present",
        )
        assertEquals(
            0, presentElvis.calls,
            "Kotlin's elvis operator is lazy; its right-hand side is not an argument",
        )
        assertEquals(
            1, absentOrElse.calls,
            "when the value is absent the fallback is genuinely needed",
        )
        assertEquals(
            1, absentOrElseGet.calls,
            "when the value is absent both spellings must do the same work -- this is the " +
                "control that shows the difference above is laziness, not overhead",
        )

        // The two instruments must agree. A statement count that did not move while the call
        // counter did would mean StatementCounter is pointed at the wrong layer.
        assertEquals(
            presentOrElseGet.statements + FALLBACK_STATEMENTS, presentOrElse.statements,
            "the statement counter and the call counter disagree about what orElse cost. " +
                "One of the two instruments is wrong, and R8 section 3.3 says which failure " +
                "mode that is",
        )
    }

    private companion object {
        /** An id no row has. */
        const val MISSING = -1L

        /**
         * What one `expensiveFallback()` costs in Hibernate statements. The fallback issues
         * `masteries.count()` and nothing else, so this is 1.
         *
         * A constant rather than a literal in the assertion, so that changing what the
         * fallback does changes one number in one place instead of silently invalidating the
         * arithmetic that checks the two instruments against each other.
         */
        const val FALLBACK_STATEMENTS = 1
    }
}
