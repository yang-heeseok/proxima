package net.gseek.proxima.db

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration.Companion.POSTGRES_IMAGE
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * **Migrations, run for the first time against a table that already holds rows.**
 *
 * ## The gap this closes, and why a syntax rule was declined instead
 *
 * `R15` found that `V3`'s deduplication could not run on the seeded database: a correlated
 * subquery, quadratic on a table with no index on `(learner_id, concept_id)` — which is
 * exactly the index `V3` exists to add. Against 600,000 rows it was still going at 32 minutes.
 * **CI had been green for four days**, because every test in this repository applies migrations
 * to an empty schema. Flyway runs at container start, before any fixture inserts anything, so
 * the delete had never deleted anything.
 *
 * `R15` §8 asked whether a check should refuse a migration containing a correlated subquery.
 * `OPEN-7` weighed it and `ADR-007` declined:
 *
 *  - a text rule protects **no migration that exists** — `AGENTS.md` §Scope calls that
 *    *unbanked* — while `MigrationDeduplicationTest` already gates the statement that does;
 *  - the defect is not the syntax. It is that **migrations are only ever tested on empty
 *    tables**, and a correlated subquery is one shape that took. The next could be an
 *    unindexed bulk `UPDATE` or a `NOT IN` over a large table, and a rule spelled for
 *    subqueries passes all of them;
 *  - a rule that fires on correct text is a rule nobody reads, and this repository has paid
 *    that twice — `R7` §3.5 (Kotlin `${'$'}default` bridges) and `R17` §5 (the discarded prose check).
 *
 * So the coverage moved instead of the rule. This class puts rows in the table first.
 *
 * ## Why the second test asserts a PLAN and not a duration
 *
 * The obvious assertion is a time limit, and it is not available: `measurement-discipline.md`
 * rule 9 says **CI asserts nothing that is a duration**, because a shared runner of unstated
 * size produces no comparable number, and `ADR-004` exists because that rule was broken once.
 *
 * A plan is categorical and survives the move to any machine. A statement planned with a
 * `SubPlan` under a scan is evaluated **per row**; one planned as a join or an aggregate is
 * not. That is what separated `R15`'s two statements — cost 9,139,221,232 against 34,214 —
 * and **PostgreSQL decides which side a statement lands on, not a regex.** A correlated
 * subquery the planner flattens into a join passes, correctly.
 */
class PopulatedMigrationTest {

    companion object {
        /** Enough that a sequential scan is the planner's honest choice, few enough to stay quick. */
        const val PAIRS = 20_000
        const val DUPLICATED = 5_000

        /**
         * Concepts carrying prerequisite edges, so `V4`'s `CREATE INDEX` meets rows.
         *
         * Matches the shipped graph's order of magnitude — `domain-model.md` says 3,000
         * concepts and ~9,000 edges — rather than its exact draws, which live in `seed/`.
         */
        const val EDGE_CONCEPTS = 3_000

        private lateinit var pg: PostgreSQLContainer

        @BeforeAll
        @JvmStatic
        fun startPostgres() {
            pg = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            pg.start()
        }

        @AfterAll
        @JvmStatic
        fun stopPostgres() {
            if (::pg.isInitialized) pg.stop()
        }
    }

    private fun connect(): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    private fun flyway(target: String?): Flyway {
        val c = Flyway.configure()
            .dataSource(pg.jdbcUrl, pg.username, pg.password)
            .locations("classpath:db/migration")
            // Both tests below start from an empty database, and they share one container
            // because starting a second costs more than cleaning this one. Without the clean
            // the second test inherits the first's rows and fails on a unique constraint --
            // which is a fixture collision reported as a migration defect, the most expensive
            // kind of red there is.
            .cleanDisabled(false)
        if (target != null) c.target(MigrationVersion.fromVersion(target))
        return c.load()
    }

    private fun seedV1Schema(c: Connection) {
        flyway(null).clean()
        flyway("1").migrate()
        c.createStatement().use { s ->
            s.execute(
                "insert into learner (external_ref, created_at) " +
                    "select 'learner-' || lpad(g::text, 6, '0'), now() from generate_series(1, $PAIRS) g",
            )
            s.execute(
                "insert into concept (code, name, grade_band) " +
                    "select 'C' || lpad(g::text, 5, '0'), 'concept ' || g, 'G' || (1 + g % 6) " +
                    "from generate_series(1, 50) g",
            )
            s.execute(
                "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                    "select l.id, c.id, 0.100, 1, 0, now() " +
                    "from learner l join concept c on c.id = ((l.id - 1) % 50) + 1",
            )
            // concept_edge, so that V4 meets rows too. ADR-002 gives every migration a
            // report and ADR-007 gives every migration a populated table; V4 is DDL, so the
            // plan check below cannot see it and this insert is the only thing that does.
            //
            // Added AFTER the mastery insert and with its own code prefix, so the concepts
            // it needs cannot shift the `(l.id - 1) % 50` mapping the assertions above
            // depend on. A fixture that changes another fixture's arithmetic is the most
            // expensive kind of red there is -- the comment on cleanDisabled says so about
            // the other half of this class.
            s.execute(
                "insert into concept (code, name, grade_band) " +
                    "select 'E' || lpad(g::text, 6, '0'), 'edge concept ' || g, 'G7-9' " +
                    "from generate_series(1, $EDGE_CONCEPTS) g",
            )
            s.execute(
                "insert into concept_edge (prerequisite_id, concept_id, weight) " +
                    "select p.id, c.id, 1.000 " +
                    "  from concept c join concept p on p.id between c.id - 3 and c.id - 1 " +
                    " where c.code like 'E%' and p.code like 'E%'",
            )
        }
    }

    /**
     * `V1`, then rows, then the rest — the order a real deployment met them in, and the one no
     * test here had ever used.
     */
    @Test
    fun `every migration applies to a table that already holds rows`() {
        connect().use { c ->
            seedV1Schema(c)

            // A second copy of some pairs, carrying a DIFFERENT score, so that "which row
            // survived" is answerable and not merely "how many". V3's comment claims it keeps
            // the earliest row and deliberately does not merge; this makes that testable.
            c.createStatement().use { s ->
                s.execute(
                    "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                        "select m.learner_id, m.concept_id, 0.900, 9, 0, now() " +
                        "from mastery m order by m.id limit $DUPLICATED",
                )
                s.execute("analyze")
            }

            flyway(null).migrate()

            c.createStatement().use { s ->
                s.executeQuery("select count(*) from mastery").use { rs ->
                    rs.next()
                    assertEquals(
                        PAIRS,
                        rs.getInt(1),
                        "V3 must leave exactly one row per (learner_id, concept_id); " +
                            "$DUPLICATED duplicates were planted",
                    )
                }
                s.executeQuery("select count(*) from mastery where score = 0.900").use { rs ->
                    rs.next()
                    assertEquals(
                        0,
                        rs.getInt(1),
                        "the later duplicate survived. V3 keeps the lowest id, so every row " +
                            "planted as a second copy must be gone",
                    )
                }
                s.executeQuery(
                    "select count(*) from pg_constraint where conrelid = 'mastery'::regclass " +
                        "and conname = 'uk_mastery_learner_concept'",
                ).use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1), "V3 did not reach its constraint")
                }
            }
        }
    }

    /**
     * The check `OPEN-7` was opened to consider, in the shape `ADR-007` chose: PostgreSQL
     * decides, on a populated table, and the verdict is a plan rather than a clock.
     */
    @Test
    fun `no migration statement is planned to run per row`() {
        connect().use { c ->
            seedV1Schema(c)
            c.createStatement().use { it.execute("analyze") }

            // THE LIST BELOW IS HAND-MAINTAINED, SO SOMETHING HAS TO NOTICE WHEN IT STOPS
            // BEING COMPLETE. It did stop, once: V4 landed on 2026-08-21 and this list did
            // not move with it, which would have left a migration outside the only check
            // ADR-007 built. Flyway already enumerates the files; comparing against it costs
            // nothing and closes the hole permanently.
            assertEquals(
                MIGRATIONS,
                flyway(null).info().all().map { it.script },
                "the migration files on the classpath are not the ones this test reads. A " +
                    "migration missing from MIGRATIONS is a migration whose statements are " +
                    "never planned against rows, which is exactly what R15 cost four days",
            )

            val statements = dmlStatementsInMigrations()
            assertTrue(
                statements.isNotEmpty(),
                "no DML was found in any migration. Either the migrations changed shape or this " +
                    "test stopped reading them, and either way it now asserts nothing",
            )

            statements.forEach { (file, sql) ->
                val plan = StringBuilder()
                c.createStatement().use { s ->
                    s.executeQuery("explain $sql").use { rs ->
                        while (rs.next()) plan.appendLine(rs.getString(1))
                    }
                }
                assertTrue(
                    !plan.contains("SubPlan"),
                    "$file plans a SubPlan, which PostgreSQL evaluates once per row. That is the " +
                        "shape R15 measured at cost 9,139,221,232 against 34,214 for the same " +
                        "intent written as a join.\n\n$plan",
                )
            }
        }
    }

    /**
     * **The plan check, watched refusing.**
     *
     * The test above is green, and green on its own establishes nothing: it would look the same
     * if `EXPLAIN` returned an empty plan, if `SubPlan` were spelled differently by this
     * PostgreSQL, or if the statement list were quietly empty. `R0` §4 counts nine gates here
     * written to refuse a future edit and one that has ever been paid; `R9` §7 is about a gate
     * that passes both when something protects it and when there is nothing to substitute.
     *
     * So the statement `R15` removed is planted and required to be caught. It is quoted from
     * `R15` §2 — the form that was in `V3` from `T6` until 2026-08-14 — and if PostgreSQL ever
     * flattens it into a join, **this test goes red and says the check has stopped being able
     * to see the thing it was built for.**
     */
    @Test
    fun `the check refuses the statement R15 removed`() {
        connect().use { c ->
            seedV1Schema(c)
            c.createStatement().use { it.execute("analyze") }

            val plan = StringBuilder()
            c.createStatement().use { s ->
                s.executeQuery(
                    "explain delete from mastery m where m.id > (" +
                        "select min(k.id) from mastery k " +
                        "where k.learner_id = m.learner_id and k.concept_id = m.concept_id)",
                ).use { rs ->
                    while (rs.next()) plan.appendLine(rs.getString(1))
                }
            }

            assertTrue(
                plan.contains("SubPlan"),
                "the planted correlated statement was NOT planned with a SubPlan, so the check " +
                    "in the test above cannot distinguish it from the aggregate form and is " +
                    "passing for the wrong reason.\n\n$plan",
            )
        }
    }

    /**
     * Statements are read out of the migrations on the classpath rather than copied, for
     * `MigrationDeduplicationTest`'s reason: a copy drifts, and a drifted copy passes while the
     * migration is wrong. Comment lines go first — `V3`'s commentary quotes the **old**
     * statement as a counter-example, and a naive read finds that one.
     */
    private fun dmlStatementsInMigrations(): List<Pair<String, String>> =
        MIGRATIONS.flatMap { name ->
            val body = javaClass.getResource("/db/migration/$name")?.readText().orEmpty()
                .lines()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
            body.split(";")
                .map { it.trim() }
                .filter { s -> s.isNotEmpty() && DML.any { s.lowercase().startsWith(it) } }
                .map { name to it }
        }

    private val MIGRATIONS = listOf(
        "V1__baseline.sql",
        "V2__attempt_learner_time_index.sql",
        "V3__mastery_unique_learner_concept.sql",
        "V4__concept_edge_by_concept.sql",
    )

    private val DML = listOf("insert", "update", "delete", "select", "with")
}
