package net.gseek.proxima.domain

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
 * What the framework says while paginating in the heap, captured verbatim.
 *
 * The roadmap describes `T2` as *"a warning in the log and no error anywhere"*. That is a
 * claim about this stack, and claims about this stack are measured here rather than
 * inherited — Hibernate 7 may log it, may have changed the wording, or may have stopped.
 *
 * A log assertion is usually a bad test. It is the right one here for two reasons: the
 * warning **is** the entire notification a team gets, so its exact text is the artefact;
 * and if a future upgrade removes it, this repository wants to find out from a red test
 * rather than from production.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CollectionPagingWarningTest {

    @Autowired private lateinit var queries: LearnerPageQueries
    @Autowired private lateinit var jdbc: JdbcTemplate

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    private companion object {
        const val PLANTED = "PROXIMA-APPENDER-CONTROL-EVENT"
    }

    @BeforeEach
    fun attachAppenderAndSeed() {
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)

        // Hibernate is turned down to DEBUG for the duration, so that a notice at ANY level
        // is visible. Asserting an absence at INFO would only establish that the default
        // threshold is INFO.
        (LoggerFactory.getLogger("org.hibernate") as Logger).level = Level.DEBUG

        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values ('concept-870001','Concept 870001','G5-6') returning id",
            Long::class.java,
        )!!
        val itemId = jdbc.queryForObject(
            "insert into item (code, concept_primary_id, difficulty, is_active) values ('item-870001', ?, 5, true) returning id",
            Long::class.java, conceptId,
        )!!
        repeat(5) { l ->
            val learnerId = jdbc.queryForObject(
                "insert into learner (external_ref) values (?) returning id",
                Long::class.java, "learner-87%04d".format(l),
            )!!
            jdbc.update(
                "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                    "values (?, ?, 0.500, 0, 0, now())",
                learnerId, conceptId,
            )
            repeat(4) { a ->
                jdbc.update(
                    "insert into attempt (learner_id, item_id, correct, elapsed_ms, hint_used, attempted_at) " +
                        "values (?, ?, true, 1000, false, ?)",
                    learnerId, itemId,
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(a.toLong()).atOffset(ZoneOffset.UTC),
                )
            }
        }
    }

    @AfterEach
    fun detachAndClear() {
        root.detachAppender(appender)
        jdbc.execute("delete from attempt where learner_id in (select id from learner where external_ref like 'learner-87%')")
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-87%')")
        jdbc.execute("delete from learner where external_ref like 'learner-87%'")
        jdbc.execute("delete from item where code like 'item-87%'")
        jdbc.execute("delete from concept where code like 'concept-87%'")
    }

    @Test
    fun `paginating a collection fetch warns, and does not fail`() {
        LoggerFactory.getLogger("proxima.control").warn(PLANTED)

        val page = queries.pageWithAttempts(PageRequest.of(0, 2))

        assertEquals(2, page.size, "no exception, no error, the right answer")

        val captured = appender.list.map { "${it.level} ${it.loggerName} — ${it.formattedMessage}" }

        // THE CONTROL, and it is a planted event rather than a count.
        //
        // A first attempt asserted merely that SOME event was captured, which conflates
        // "the appender is broken" with "nothing logged anything during this window" --
        // and the second is entirely possible, because a query logs nothing at INFO. So a
        // known event is planted and looked for. Same rule as the secret scanner's
        // self-test: prove the instrument can see a presence before trusting it about an
        // absence.
        assertTrue(
            captured.any { it.contains(PLANTED) },
            "the planted control event was not captured, so this appender is not " +
                "listening and can say nothing about what Hibernate did or did not log. " +
                "Captured ${captured.size} events",
        )

        val aboutTheDefect = captured.filter {
            it.contains("firstResult", ignoreCase = true) ||
                it.contains("maxResults", ignoreCase = true) ||
                it.contains("in memory", ignoreCase = true) ||
                it.contains("HHH90003004") ||
                it.contains("HHH000104")
        }

        val sql = captured.filter { it.contains("org.hibernate.SQL") }
        println("T2-SQL >>> " + sql.joinToString("\n"))
        println("T2-ABOUT-DEFECT >>> " + aboutTheDefect.joinToString(" | ").ifEmpty { "NOTHING" })

        // THE FINDING, asserted as the positive fact rather than as the absence of a
        // warning. Hibernate 7.4.1 wraps the roots in a derived table and applies the page
        // THERE, then joins the collection to that -- so the page reaches the database and
        // there is nothing to warn about.
        assertTrue(
            sql.any { it.contains("offset", ignoreCase = true) && it.contains("from (") },
            "expected Hibernate to push the page into a derived table. If this fails, the " +
                "rewrite is gone and T2's original defect is back. SQL seen:\n" +
                sql.joinToString("\n"),
        )
        assertTrue(
            aboutTheDefect.isEmpty(),
            "Hibernate warned about in-memory pagination, which contradicts the SQL above. " +
                "One of the two observations is wrong and this test cannot say which:\n" +
                aboutTheDefect.joinToString("\n"),
        )
    }

    /**
     * The second strand of `T2`: two collections fetched at once.
     *
     * Historically this is `MultipleBagFetchException`, raised when the query is built
     * rather than when it runs. Whether that is still true on Hibernate 7.4.1 is measured
     * here, not assumed — the first strand turned out to have been fixed by the framework.
     */
    @Test
    fun `fetching two collections at once`() {
        val outcome = runCatching { queries.pageWithAttemptsAndMasteries(PageRequest.of(0, 2)) }

        val description = outcome.fold(
            onSuccess = { rows ->
                "SUCCEEDED with ${rows.size} roots, " +
                    "attempts=${rows.sumOf { r -> r.attempts.size }}, " +
                    "masteries=${rows.sumOf { r -> r.masteries.size }}"
            },
            onFailure = { e ->
                generateSequence(e) { it.cause }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }
            },
        )
        println("T2-TWO-COLLECTIONS >>> $description")

        val sql = appender.list.map { it.formattedMessage }.filter { it.contains("select", true) }
        println("T2-TWO-COLLECTIONS-SQL >>> " + sql.takeLast(1).joinToString())

        // No assertion on which way it goes -- this test exists to record the behaviour of
        // this version. It fails only if it cannot describe what happened.
        assertTrue(description.isNotBlank())
    }
}
