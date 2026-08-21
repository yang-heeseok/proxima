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

        // Each entry names the report that added it. V4 arrived on 2026-08-21 with R20 and
        // it went red here first, exactly as this message says it should -- the failure is
        // the process working, not a test to loosen. It stays an exact list: turning it
        // into `containsAll` would let the next migration in without a report, which is the
        // one thing ADR-002 cannot allow.
        //
        //   1  the naive baseline          ADR-002
        //   2  attempt (learner_id, attempted_at)   R3
        //   3  mastery uniqueness          R7, and its dedup statement R15
        //   4  concept_edge (concept_id)   R20
        assertEquals(
            listOf("1", "2", "3", "4"), applied.map { it["version"] },
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
        //
        // V4 added the second, on 2026-08-21, with R20. Note what it is NOT: the covering
        // pair (concept_id, prerequisite_id), which was measured, produced an Index Only
        // Scan, cost 85% more, and was not faster. R3 rejected an INCLUDE variant on
        // `attempt` for the same reason, and this list is where a future "improvement" that
        // quietly widens the index has to come and say so.
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
            listOf("ix_attempt_learner_attempted_at", "ix_concept_edge_concept"),
            performanceIndexes,
            "an index exists that no report justified, or one a report justified is gone. " +
                "See ADR-002, docs/reports/R3 and docs/reports/R20",
        )
    }

    @Test
    fun `the domain rule mastery has always claimed is now enforced`() {
        // This assertion began life as its own opposite: "mastery must NOT yet have a unique
        // constraint", because T6 needed the race to be reproducible. It was, at ad474d8 --
        // eight concurrent requests, eight rows, no exception -- and V3 closed it in the
        // same commit as the report. The inversion is ADR-002's process completing, and it
        // is left visible rather than rewritten as if the constraint had always been there.
        val masteryUnique = jdbc.queryForList(
            """
            select conname
              from pg_constraint
             where conrelid = 'mastery'::regclass
               and contype = 'u'
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(
            listOf("uk_mastery_learner_concept"), masteryUnique,
            "a learner has exactly one mastery of one concept, and only the database can " +
                "hold that. See ADR-002 and docs/reports/R7",
        )
    }
}
