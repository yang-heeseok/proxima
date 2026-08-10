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
    fun `flyway records V1 as the only applied migration, and it succeeded`() {
        val applied = jdbc.queryForList(
            "select version, success from flyway_schema_history order by installed_rank",
        )

        assertEquals(1, applied.size, "expected exactly one applied migration at baseline")
        assertEquals("1", applied[0]["version"])
        assertEquals(true, applied[0]["success"])
    }

    @Test
    fun `the omissions ADR-002 depends on are still omitted`() {
        // The indexing report's first EXPLAIN is supposed to show a sequential scan.
        // PostgreSQL creates an index for a primary key and for each unique constraint, so
        // this counts only indexes that are neither -- which at baseline is none.
        //
        // flyway_schema_history is excluded because Flyway owns it and indexes it itself
        // (flyway_schema_history_s_idx). This test found that index on its first run, which
        // is a fair illustration of why the assertion is written as an exact set: a check
        // that merely counted "some indexes exist" would have said nothing either way.
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
            emptyList(), performanceIndexes,
            "V1 must carry no performance index -- an index added here erases the " +
                "measurement it was supposed to justify, see ADR-002",
        )

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
