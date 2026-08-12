package net.gseek.proxima.domain

import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `T2` — what a page does when a collection is fetched beside it.
 *
 * **The assertions here are about rows read, not about milliseconds.** A timing on a small
 * dataset would be noise; the defect is that the number of rows the database returns has
 * nothing to do with the size of the page, and that is exact and checkable at any scale.
 *
 * Data is inserted through `JdbcTemplate` rather than through the entities, so the test
 * builds the situation without going through the mapping it is about to interrogate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CollectionPagingTest {

    @Autowired private lateinit var queries: LearnerPageQueries
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val learners = 20
    private val attemptsEach = 50

    @BeforeEach
    fun seed() {
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values ('concept-880001','Concept 880001','G5-6') returning id",
            Long::class.java,
        )!!
        val itemId = jdbc.queryForObject(
            "insert into item (code, concept_primary_id, difficulty, is_active) values ('item-880001', ?, 5, true) returning id",
            Long::class.java, conceptId,
        )!!
        repeat(learners) { l ->
            val learnerId = jdbc.queryForObject(
                "insert into learner (external_ref) values (?) returning id",
                Long::class.java, "learner-88%04d".format(l),
            )!!
            jdbc.update(
                "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                    "values (?, ?, 0.500, 0, 0, now())",
                learnerId, conceptId,
            )
            repeat(attemptsEach) { a ->
                jdbc.update(
                    "insert into attempt (learner_id, item_id, correct, elapsed_ms, hint_used, attempted_at) " +
                        "values (?, ?, true, 1000, false, ?)",
                    learnerId,
                    itemId,
                    // OffsetDateTime, not Instant: the PostgreSQL driver cannot infer a SQL
                    // type for Instant through a plain setObject.
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(a.toLong())
                        .atOffset(ZoneOffset.UTC),
                )
            }
        }
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from attempt where learner_id in (select id from learner where external_ref like 'learner-88%')")
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-88%')")
        jdbc.execute("delete from learner where external_ref like 'learner-88%'")
        jdbc.execute("delete from item where code like 'item-88%'")
        jdbc.execute("delete from concept where code like 'concept-88%'")
    }

    /**
     * The defect, stated as an equality rather than as a warning.
     *
     * A page of five learners is asked for. Five learners come back — **the answer is
     * correct**, which is the whole problem. What the database was asked for is not five
     * learners; it is every learner joined to every attempt, and the page was applied to
     * that result **after** it arrived in the heap.
     */
    /**
     * A page with a collection fetch returns the right page and the whole collection.
     *
     * **An earlier version of this test asserted that the whole table was read**, measured
     * as a delta over `pg_stat_user_tables`. It passed, and it was wrong twice: those
     * counters are cumulative and PostgreSQL updates them on a delay, so the delta was not
     * the query's; and the premise itself is false on Hibernate 7.4.1, which pushes the page
     * into a derived table. **A test that passes while asserting the opposite of the truth
     * is worse than no test**, and it is recorded here rather than quietly deleted.
     *
     * The claim about where the page is applied now lives in `CollectionPagingWarningTest`,
     * which asserts it against the generated SQL — the only artefact that actually says.
     */
    @Test
    fun `a page with a collection fetch returns the page and the whole collection`() {
        val pageSize = 5
        val page = queries.pageWithAttempts(PageRequest.of(0, pageSize))

        assertEquals(pageSize, page.size)
        assertEquals(
            pageSize * attemptsEach, page.sumOf { it.attempts.size },
            "a paged root must still carry its complete collection, or the page is lying " +
                "about the objects it returns",
        )
    }

    /**
     * The obvious fix, and what it does not fix.
     *
     * `distinct` removes the duplicated roots the join produces. It does not move the page
     * into the database, because it cannot: the page is over roots and the query returns
     * root×collection rows either way.
     */
    @Test
    fun `distinct changes the rows returned and not where the page is applied`() {
        val pageSize = 5
        val plain = queries.pageWithAttempts(PageRequest.of(0, pageSize))
        val distinct = queries.pageWithAttemptsDistinct(PageRequest.of(0, pageSize))

        assertEquals(pageSize, plain.size)
        assertEquals(pageSize, distinct.size)
        assertEquals(
            plain.map { it.id }, distinct.map { it.id },
            "the two return the same page; the difference is elsewhere",
        )
    }

    /**
     * Paging the roots alone, then fetching collections by id.
     *
     * Two statements instead of one, and the page is applied by the database in the first.
     */
    @Test
    fun `paging roots alone lets the database apply the page`() {
        val pageSize = 5
        val roots = queries.pageRootsOnly(PageRequest.of(0, pageSize))
        assertEquals(pageSize, roots.size)

        val withCollections = queries.fetchAttemptsFor(roots.mapNotNull { it.id })
        assertEquals(pageSize, withCollections.distinctBy { it.id }.size)
        assertEquals(
            pageSize * attemptsEach,
            withCollections.distinctBy { it.id }.sumOf { it.attempts.size },
        )
    }
}
