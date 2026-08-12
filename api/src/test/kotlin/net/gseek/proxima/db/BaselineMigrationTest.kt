package net.gseek.proxima.db

import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `V1` applies to a real PostgreSQL and produces exactly the baseline schema.
 *
 * **Why this asserts the whole table set rather than "the migration ran".** Flyway
 * reporting success means it executed the file without raising; it does not mean the
 * schema a reader gets is the schema this repository documents. The set is compared
 * exactly -- an extra table is as much a failure as a missing one, because an extra table
 * means something other than the migration sequence shaped this database, and ADR-002
 * rests on the migration sequence being the only thing that does.
 *
 * **Why it also asserts what is absent.** `V1` deliberately omits an index on
 * `attempt (learner_id, attempted_at)` and the `unique (learner_id, concept_id)` on
 * `mastery`. Those omissions are the starting conditions of the indexing report and the
 * uniqueness-race report. If someone "fixes" the schema without a report, the reports stop
 * being reproducible from history and nothing else in CI would notice -- the build would
 * simply go green with a better schema. So the absence is a test, and the test names the
 * ADR that will justify its own deletion.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BaselineMigrationTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `V1 applies and produces exactly the seven baseline tables`() {
        val tables = jdbc.queryForList(
            """
            select table_name
              from information_schema.tables
             where table_schema = 'public'
               and table_type = 'BASE TABLE'
               and table_name <> 'flyway_schema_history'
             order by table_name
            """.trimIndent(),
            String::class.java,
        )

        assertEquals(
            listOf(
                "attempt",
                "concept",
                "concept_edge",
                "item",
                "item_concept",
                "learner",
                "mastery",
            ),
            tables,
            "the baseline table set changed -- if that was intended, the migration that " +
                "changed it needs a report, see ADR-002",
        )
    }

    @Test
    fun `every migration applied, and all of them succeeded`() {
        val applied = jdbc.queryForList(
            "select version, success from flyway_schema_history order by installed_rank",
        )

        assertEquals(
            listOf("1", "2"), applied.map { it["version"] },
            "the migration sequence changed -- it is the argument this repository makes, " +
                "so a change to it needs a report (ADR-002)",
        )
        assertEquals(
            emptyList(), applied.filterNot { it["success"] == true },
            "a migration is recorded as failed",
        )
    }

    @Test
    fun `the performance indexes are exactly those a report has justified`() {
        // This assertion started as "there must be NO performance index", which was correct
        // for as long as V1 was the whole story. V2 added one, and the test changed in the
        // same commit as the report that measured it -- which is the process ADR-002
        // describes, working. It is still an exact set: an index nobody measured is exactly
        // what this is here to catch.
        val performanceIndexes = jdbc.queryForList(
            """
            select i.indexname
              from pg_indexes i
             where i.schemaname = 'public'
               and i.tablename <> 'flyway_schema_history'
               and i.indexname not in (
                     select conname from pg_constraint where contype in ('p', 'u')
                   )
             order by i.indexname
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(
            listOf("ix_attempt_learner_attempted_at"), performanceIndexes,
            "an index exists that no report justified, or one a report justified is gone. " +
                "See ADR-002 and docs/reports/R3",
        )
    }

    @Test
    fun `the omissions ADR-002 depends on are still omitted`() {
        // The uniqueness race in T6 requires that two concurrent inserts can both land.
        val masteryUnique = jdbc.queryForList(
            """
            select conname
              from pg_constraint
             where conrelid = 'mastery'::regclass
               and contype = 'u'
            """.trimIndent(),
            String::class.java,
        )
        assertTrue(
            masteryUnique.isEmpty(),
            "mastery must NOT yet have a unique constraint -- it arrives in the same " +
                "commit as the failing test and the report, see ADR-002. Found: $masteryUnique",
        )
    }
}
