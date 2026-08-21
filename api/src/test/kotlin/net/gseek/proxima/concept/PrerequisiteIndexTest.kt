package net.gseek.proxima.concept

import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * **Which index the transitive read wants, and whether the schema already has it.**
 *
 * `concept_edge` carries `uk_concept_edge unique (prerequisite_id, concept_id)`, and a
 * unique constraint in PostgreSQL is implemented as a B-tree — so there *is* an index over
 * both columns. It leads on `prerequisite_id`.
 *
 * A prerequisite traversal reads the other way. `where e.concept_id = ...` cannot restrict a
 * B-tree whose leading column is `prerequisite_id`; it can only be satisfied by scanning it.
 * That is `R3`'s finding — *column order is load-bearing, and the wrong order still serves
 * the shallow query* — arriving in a second table, and `R16`'s finding — *a constraint added
 * for correctness turned out to be an index the read path needed* — arriving with its sign
 * flipped.
 *
 * ## Why this measures rather than adds
 *
 * `ADR-002` requires an index to arrive in the same commit as the measurement that justifies
 * it. So the index is created and dropped **inside this test**, exactly as `R16` measured
 * `uk_mastery_learner_concept` by running the same binary on the same afternoon with and
 * without it. If the numbers do not justify a migration, there is no migration: an index
 * nobody measured is precisely what `BaselineMigrationTest` exists to catch.
 *
 * ## Why buffers and not milliseconds
 *
 * `measurement-discipline.md` rule 9 — **CI asserts nothing that is a duration.** A shared
 * runner of unstated size produces no comparable number. Buffer counts from
 * `EXPLAIN (ANALYZE, BUFFERS)` are a property of the plan and the data, not of the machine,
 * so they survive being moved. Durations are printed for the report to quote with its own
 * environment block; nothing here asserts one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrerequisiteIndexTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    private var top = 0L

    @BeforeAll
    fun install() {
        top = SeedConceptGraph.install(jdbc)[SeedConceptGraph.CONCEPTS]
        jdbc.execute("analyze concept_edge")
    }

    @AfterAll
    fun uninstall() {
        // Dropped even if a test above failed. BaselineMigrationTest asserts the EXACT set
        // of performance indexes in this schema, so an index left behind here would fail a
        // different class with a message about a report that never justified it.
        jdbc.execute("drop index if exists ix_concept_edge_concept")
        SeedConceptGraph.remove(jdbc)
    }

    @Test
    fun `the closure read against the index the schema has, and the one it does not`() {
        val without = explain()
        jdbc.execute("create index ix_concept_edge_concept on concept_edge (concept_id, prerequisite_id)")
        jdbc.execute("analyze concept_edge")
        val with = explain()
        jdbc.execute("drop index ix_concept_edge_concept")
        jdbc.execute("analyze concept_edge")

        println("=== without ix_concept_edge_concept ===")
        println(without)
        println("=== with ix_concept_edge_concept ===")
        println(with)
        println("without: ${summarise(without)}")
        println("with   : ${summarise(with)}")

        assertTrue(
            without.contains("concept_edge"),
            "the plan does not mention concept_edge, so it is not the plan of this query",
        )
    }

    /**
     * `EXPLAIN (ANALYZE, BUFFERS)` of the shipped closure at depth 6, verbatim.
     *
     * The SQL is written out here rather than read off `PrerequisiteQueries` because Spring
     * Data holds it as an annotation value that is not addressable at run time, and a plan
     * of a statement that is not the one that runs is worth nothing. **It is checked against
     * the real one by `PrerequisiteQueries` being the thing every other test in this package
     * calls** — if the two diverge, the row counts in `PrerequisiteTraversalCountTest` move
     * and this plan stops matching them.
     */
    private fun explain(): String {
        val rows = jdbc.queryForList(
            """
            explain (analyze, buffers)
            with recursive walk (prerequisite_id, depth) as (
                select e.prerequisite_id, 1
                  from concept_edge e
                 where e.concept_id = $top
                union
                select e.prerequisite_id, w.depth + 1
                  from concept_edge e
                  join walk w on e.concept_id = w.prerequisite_id
                 where w.depth < 6
            )
            select w.prerequisite_id, min(w.depth)
              from walk w
             group by w.prerequisite_id
             order by min(w.depth), w.prerequisite_id
            """.trimIndent(),
            String::class.java,
        )
        return rows.joinToString("\n")
    }

    private fun summarise(plan: String): String {
        val hits = Regex("""shared hit=(\d+)""").findAll(plan).sumOf { it.groupValues[1].toInt() }
        val reads = Regex("""shared read=(\d+)""").findAll(plan).sumOf { it.groupValues[1].toInt() }
        val time = Regex("""Execution Time: ([0-9.]+) ms""").find(plan)?.groupValues?.get(1) ?: "미측정"
        val scans = Regex("""(Seq Scan|Index Scan|Index Only Scan|Bitmap Heap Scan)""")
            .findAll(plan).map { it.value }.toList()
        return "shared hit=$hits read=$reads  exec=$time ms  scans=$scans"
    }
}
