package net.gseek.proxima.perf

import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.recommendation.RecommendationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `T7` — the statement count of the recommendation read, asserted as a number.
 *
 * **This is the gate `R4` could not write.** `R4` chose the projection over returning
 * entities and measured what that was worth under load; nothing then stopped someone
 * changing it back, because both versions return the same JSON and every functional test
 * passes either way. The difference is one number, and this is where it lives.
 *
 * The counts are exact rather than upper bounds. A `<=` assertion drifts: each person who
 * adds a statement raises the bound by one and the test keeps passing forever. An exact
 * count forces the person who changes it to say so.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, StatementCounter::class)
class QueryCountTest {

    @Autowired private lateinit var recommendations: RecommendationService
    @Autowired private lateinit var counter: StatementCounter
    @Autowired private lateinit var jdbc: JdbcTemplate

    private var learnerId = 0L
    private val items = 5

    @BeforeEach
    fun seed() {
        learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values ('learner-840001') returning id",
            Long::class.java,
        )!!
        repeat(items) { i ->
            val conceptId = jdbc.queryForObject(
                "insert into concept (code, name, grade_band) values (?, ?, 'G5-6') returning id",
                Long::class.java, "concept-84%04d".format(i), "Concept 84%04d".format(i),
            )!!
            val itemId = jdbc.queryForObject(
                "insert into item (code, concept_primary_id, difficulty, is_active) " +
                    "values (?, ?, 5, true) returning id",
                Long::class.java, "item-84%04d".format(i), conceptId,
            )!!
            jdbc.update(
                "insert into item_concept (item_id, concept_id, weight) values (?, ?, 1.000)",
                itemId, conceptId,
            )
            jdbc.update(
                "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                    "values (?, ?, 0.500, 0, 0, now())",
                learnerId, conceptId,
            )
        }
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-84%')")
        jdbc.execute("delete from item_concept where item_id in (select id from item where code like 'item-84%')")
        jdbc.execute("delete from item where code like 'item-84%'")
        jdbc.execute("delete from concept where code like 'concept-84%'")
        jdbc.execute("delete from learner where external_ref like 'learner-84%'")
    }

    /**
     * The shipped path. One statement, whatever the page size.
     */
    @Test
    fun `the recommendation read issues exactly one statement`() {
        val counted = counter.count { recommendations.nextRows(learnerId, 10) }

        assertTrue(counted.result.isNotEmpty(), "an empty result would assert nothing")
        assertEquals(
            1, counted.statements,
            "the recommendation read must be one statement. It returned " +
                "${counted.result.size} rows in ${counted.statements}. If this rose, " +
                "something reintroduced a per-row fetch -- see R4, which chose the " +
                "projection over returning entities for exactly this reason",
        )
    }

    /**
     * **The service that returns entities is not where the N+1 is**, and finding that out
     * was worth the test.
     *
     * Counted on its own, `nextItems` issues **two** statements no matter how many rows it
     * returns — the id query and the entity load. It looks efficient. It is efficient.
     *
     * The cost is created by whoever touches the object graph afterwards, which in `R4` was
     * the controller reading `conceptPrimary.name`. **A service can hand out an N+1 without
     * containing one**, and a statement-count test scoped to the service would certify it
     * as clean. That is why the assertion below counts the caller's access too.
     */
    @Test
    fun `the entity path is cheap until someone reads what it returned`() {
        val service = counter.count { recommendations.nextItems(learnerId, 10) }
        assertTrue(service.result.isNotEmpty())
        assertEquals(
            2, service.statements,
            "the service alone should be the id query plus the entity load",
        )
    }

    /**
     * The same path with the association read, which is what the response needs.
     *
     * `@Transactional` on the test so the session is open — with `open-in-view: false` the
     * shipped configuration would raise `LazyInitializationException` here instead, which
     * `R4` §3.1 already measured. **This test is counting the cost the entity path has when
     * it works at all.**
     */
    @Test
    @Transactional
    fun `reading the association costs one statement per row`() {
        val counted = counter.count {
            val items = recommendations.nextItems(learnerId, 10)
            items.forEach { it.conceptPrimary.name }
            items
        }

        assertTrue(counted.result.isNotEmpty())
        assertEquals(
            2 + counted.result.size, counted.statements,
            "expected the id query, the entity load, and one lazy concept fetch per row -- " +
                "${counted.result.size} rows cost ${counted.statements} statements",
        )
    }

    /**
     * The property that actually matters, stated so it cannot be satisfied by luck: the
     * shipped read does not get more expensive as the answer gets bigger.
     */
    @Test
    fun `the statement count does not grow with the number of rows`() {
        val small = counter.count { recommendations.nextRows(learnerId, 1) }
        val large = counter.count { recommendations.nextRows(learnerId, 10) }

        assertTrue(large.result.size > small.result.size, "the two calls must differ in size")
        assertEquals(
            small.statements, large.statements,
            "statements grew from ${small.statements} to ${large.statements} while rows " +
                "grew from ${small.result.size} to ${large.result.size} -- that is an N+1",
        )
    }
}
