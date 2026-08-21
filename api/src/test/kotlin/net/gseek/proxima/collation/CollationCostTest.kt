package net.gseek.proxima.collation

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * `R26` — what a locale-aware collation costs, and what it takes away.
 *
 * ## Why this is one image and `R25` is two
 *
 * `R25` had to be two images, because the question was *does the tag decide the ordering*.
 * Its two servers are **16.14 and 16.15**, and the only reason its answer is attributable to
 * collation is a within-image control: told `collate "C"`, the glibc server reproduced the
 * musl server's order exactly.
 *
 * This report asks a different question — *what would a deployment on a locale-aware
 * PostgreSQL pay* — and that one must not be answered across two images, because a duration
 * measured on 16.14-on-musl against one on 16.15-on-glibc is `measurement-discipline.md`
 * rule 3 with extra steps. **Everything below runs on one binary, in one session, and the
 * only thing that varies is the collation named in the statement.**
 *
 * ## What is measured
 *
 * | | |
 * | --- | --- |
 * | §3.2 | the cost of the sort — `C` against `en_US.utf8` against `en-US-x-icu` |
 * | §3.3 | whether a prefix predicate can still use a plain B-tree index |
 * | §3.4 | whether uniqueness changes — the property three constraints in `V1__baseline.sql` depend on |
 *
 * ## The control
 *
 * The instrument is `EXPLAIN (ANALYZE)`'s own `Execution Time`, read verbatim. Its control is
 * that the three arms must not all agree: if a locale-aware collation costs exactly what byte
 * comparison costs, this class is not varying anything — the most likely reason being that
 * the `collate` clause was silently ignored — and the numbers are about nothing. That is the
 * same shape as `R5`'s appender, which captured zero events and nearly proved an absence.
 */
class CollationCostTest {

    companion object {
        private lateinit var pg: PostgreSQLContainer

        /** The glibc image, by digest. `R27` is why it is not a tag. */
        @BeforeAll
        @JvmStatic
        fun start() {
            pg = PostgreSQLContainer(
                DockerImageName.parse(CollationDivergenceTest.GLIBC_DIGEST)
                    .asCompatibleSubstituteFor("postgres"),
            )
            pg.start()
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            if (::pg.isInitialized) pg.stop()
        }

        /**
         * Rows in the probe table.
         *
         * Server-side `generate_series`, so nothing is transferred and the row count is not
         * bounded by how fast this machine's JDBC batch insert is. 200,000 is chosen so the
         * sort is unambiguously the dominant term and still finishes inside a test.
         */
        const val ROWS = 200_000
    }

    private fun connection(): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    /**
     * `EXPLAIN (ANALYZE)`'s reported execution time, in milliseconds.
     *
     * The query is wrapped in `count(*)` so the sort runs to completion and no rows cross the
     * wire; what is being timed is PostgreSQL sorting, not this machine's socket.
     */
    private fun executionMs(c: Connection, sql: String): Double {
        var ms = -1.0
        c.createStatement().use { st ->
            st.executeQuery("explain (analyze, timing off) $sql").use { rs ->
                while (rs.next()) {
                    val line = rs.getString(1)
                    if (line.startsWith("Execution Time:")) {
                        ms = line.removePrefix("Execution Time:").trim().removeSuffix(" ms").toDouble()
                    }
                }
            }
        }
        require(ms >= 0) { "no Execution Time line in the plan for: $sql" }
        return ms
    }

    private fun plan(c: Connection, sql: String): List<String> {
        val out = mutableListOf<String>()
        c.createStatement().use { st ->
            st.executeQuery("explain (analyze, buffers) $sql").use { rs ->
                while (rs.next()) out.add(rs.getString(1))
            }
        }
        return out
    }

    private fun buildProbe(c: Connection) {
        c.createStatement().use { st ->
            st.execute("drop table if exists r26_probe")
            // The generator's own identifier shape -- Generator.kt:309, ref(kind, n).
            st.execute(
                """
                create table r26_probe as
                select 'learner-' || lpad(g::text, 6, '0') as v
                  from generate_series(1, $ROWS) g
                """.trimIndent(),
            )
            st.execute("analyze r26_probe")
        }
    }

    // -------------------------------------------------------------------------------------
    // 3.2  What the sort costs
    // -------------------------------------------------------------------------------------

    @Test
    fun `the cost of sorting the same column under three collations`() {
        connection().use { c ->
            buildProbe(c)

            val arms = linkedMapOf(
                "C (byte order, what alpine gives)" to "order by v collate \"C\"",
                "en_US.utf8 (glibc, locale-aware)" to "order by v collate \"en_US.utf8\"",
                "en-US-x-icu (ICU)" to "order by v collate \"en-US-x-icu\"",
            )

            // Warm-up, discarded. measurement-discipline.md §"Why the warm-up is discarded":
            // the first execution of a plan pays for parse, plan, and a cold page cache, and
            // no capacity plan cares about it.
            for (order in arms.values) executionMs(c, "select count(*) from (select v from r26_probe $order) s")

            println("\n=== R26 §3.2  sort cost, $ROWS rows, one binary, collation is the only variable ===")
            val medians = linkedMapOf<String, Double>()
            for ((label, order) in arms) {
                val runs = (1..3).map { executionMs(c, "select count(*) from (select v from r26_probe $order) s") }
                val median = runs.sorted()[1]
                medians[label] = median
                println("%-36s runs=%s  median=%.1f ms".format(label, runs.map { "%.1f".format(it) }, median))
            }
            val cBase = medians.values.first()
            for ((label, m) in medians) println("%-36s x%.2f against C".format(label, m / cBase))

            // CONTROL. If all three land on the same number the `collate` clause is doing
            // nothing and none of the above is a measurement of anything.
            assertTrue(
                medians.values.toSet().size == 3,
                "control failed: the three collations produced identical execution times " +
                    "($medians). Either the collate clause was ignored or the sort is not the " +
                    "dominant term, and no ratio above means anything.",
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // 3.3  What a locale-aware collation takes away from an index
    // -------------------------------------------------------------------------------------

    /**
     * The consequence that is not a duration.
     *
     * A B-tree index orders its keys by the column's collation. A prefix predicate —
     * `like 'learner-0001%'` — is only a contiguous range of that ordering when the collation
     * is `C`. On a locale-aware collation PostgreSQL cannot turn the `like` into a range scan
     * on a plain index, and the standard remedy is a second index with `text_pattern_ops`.
     *
     * **On `postgres:16-alpine` that problem is invisible**, because its declared
     * `en_US.utf8` is byte order in practice. So a prefix search written and measured on this
     * repository's own test image would be index-backed, and the same code on a glibc
     * deployment would not be — with no diff, no error, and no warning.
     *
     * There is no such query in this application today. `R26` §3.5 establishes that by sweep
     * rather than by memory, and this probe measures what it would cost if one arrived.
     */
    @Test
    fun `whether a prefix predicate can use a plain index under each collation`() {
        connection().use { c ->
            buildProbe(c)
            c.createStatement().use { st ->
                st.execute("drop table if exists r26_prefix")
                st.execute(
                    """
                    create table r26_prefix (
                        v_default text collate "en_US.utf8" not null,
                        v_c       text collate "C" not null
                    )
                    """.trimIndent(),
                )
                st.execute("insert into r26_prefix select v, v from r26_probe")
                st.execute("create index ix_r26_default on r26_prefix (v_default)")
                st.execute("create index ix_r26_c on r26_prefix (v_c)")
                st.execute("analyze r26_prefix")
            }

            println("\n=== R26 §3.3  a prefix predicate against a plain B-tree ===")
            for ((label, col) in listOf("en_US.utf8 column" to "v_default", "C column" to "v_c")) {
                val p = plan(c, "select count(*) from r26_prefix where $col like 'learner-0001%'")
                val usesIndex = p.any { it.contains("Index") }
                println("--- $label, like 'learner-0001%' -> index used: $usesIndex")
                p.forEach { println("    $it") }
            }

            // And equality, which is the operation the three unique constraints in V1 use.
            for ((label, col) in listOf("en_US.utf8 column" to "v_default", "C column" to "v_c")) {
                val p = plan(c, "select count(*) from r26_prefix where $col = 'learner-000042'")
                println("--- $label, equality -> index used: ${p.any { it.contains("Index") }}")
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // 3.4  Whether the collation changes what is unique
    // -------------------------------------------------------------------------------------

    /**
     * `V1__baseline.sql` has three unique constraints on text — `uk_learner_external_ref`,
     * `uk_concept_code`, `uk_item_code` — and `R7` is an entire report about what a unique
     * constraint on this schema does under concurrency.
     *
     * If a collation changed which values collide, changing the deployment's locale would
     * change which inserts succeed, and every one of those reports would be conditional on
     * the image. This asks whether it does, rather than reasoning from the word
     * *deterministic*.
     */
    @Test
    fun `whether a collation changes which values collide`() {
        connection().use { c ->
            c.createStatement().use { st ->
                st.execute("drop table if exists r26_unique_default")
                st.execute("drop table if exists r26_unique_c")
                st.execute("create table r26_unique_default (v text collate \"en_US.utf8\" not null unique)")
                st.execute("create table r26_unique_c (v text collate \"C\" not null unique)")
            }
            println("\n=== R26 §3.4  does the collation change what collides ===")
            // Two values that ORDER differently under the two collations. If uniqueness were
            // collation-sensitive in the way ordering is, one of these tables would refuse
            // the second insert.
            for (table in listOf("r26_unique_default", "r26_unique_c")) {
                val outcomes = mutableListOf<String>()
                for (v in listOf("Item-000001", "item-000001")) {
                    outcomes += try {
                        c.createStatement().use { st -> st.executeUpdate("insert into $table (v) values ('$v')") }
                        "$v INSERTED"
                    } catch (e: Exception) {
                        "$v REFUSED (${e.message?.lineSequence()?.first()})"
                    }
                }
                val n = c.createStatement().use { st ->
                    st.executeQuery("select count(*) from $table").use { rs -> rs.next(); rs.getInt(1) }
                }
                println("%-22s %s  rows=%d".format(table, outcomes, n))
            }
        }
    }
}
