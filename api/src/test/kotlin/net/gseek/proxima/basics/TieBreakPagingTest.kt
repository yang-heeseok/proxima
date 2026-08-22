package net.gseek.proxima.basics

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * `R41` — **an `ORDER BY` with no tie-break does not return the wrong order. It returns the
 * wrong rows.**
 *
 * ## The distinction this report is built on
 *
 * `ORDER BY` on a column whose values are not unique leaves the order of tied rows
 * **undefined**. Paging over that with `LIMIT`/`OFFSET` asks the database the same question
 * once per page and is entitled to a different answer each time. So a row can be returned on
 * two pages, and a row can be returned on none.
 *
 * **The second half is the one that matters, and it is not an ordering complaint.** Nothing
 * throws. No constraint is violated. The application is not told. A caller that walks every
 * page and processes what it receives has silently skipped rows, and the only way to find out
 * is to count. `R41` counts.
 *
 * ## Scope, decided before measuring
 *
 * ⛔ This is **not** about collation. `R25` measured musl-versus-glibc divergence across two
 * containers with five probes and `R26` priced locale-aware collation; `ADR-014` rows `9.1`
 * and `D.8` are closed by them. Nothing here compares collations, and the sort column below is
 * an integer precisely so that it cannot.
 *
 * ## Where it is reachable from — the sweep, re-run at this SHA
 *
 * The table is in `_ROUND3-G-HANDOFF.md` §2. The conclusion: **no reachable paged ordering on
 * a non-unique key exists in `api/src/main`.** So `R41` takes `R26`'s shape rather than `R6`'s
 * — no red commit against application code, an instrument that plants the tie, and the sweep
 * carried as the argument for why that is honest rather than evasive.
 *
 * ⛔ The tie is planted **here**, in a table this test creates and drops. Planting one in a
 * shipped query and calling it reproduced is manufacturing a failure, which the preamble
 * forbids.
 *
 * ## Why one raw connection rather than `JdbcTemplate`
 *
 * Part of the experiment changes the planner's options between pages. `JdbcTemplate` borrows a
 * pooled connection per statement, so a `SET` would land on whichever connection happened to
 * be free and the next page would be planned by a different one. The walk holds **one**
 * connection for its whole length, which is also the more faithful model: two pages of a real
 * result set are two requests, and nothing guarantees they meet the same plan.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TieBreakPagingTest {

    @Autowired
    private lateinit var postgres: PostgreSQLContainer

    private fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    /**
     * 100 rows in 4 groups of 25, paged 10 at a time.
     *
     * The sizes are chosen so that **every page boundary except the last falls inside a group
     * of tied rows**. A test whose pages align with the groups would cross no tie and would
     * report that this defect does not exist — which is exactly why a single-page unit test
     * never catches it.
     */
    private fun seed(c: Connection) {
        c.createStatement().use { st ->
            st.execute("create schema if not exists $SCHEMA")
            st.execute("drop table if exists $SCHEMA.tied")
            st.execute(
                "create table $SCHEMA.tied (id bigint primary key, grp int not null, payload text not null)",
            )
            st.execute(
                "insert into $SCHEMA.tied (id, grp, payload) " +
                    "select g, (g - 1) / $GROUP_SIZE, 'row-' || g from generate_series(1, $ROWS) g",
            )
            st.execute("create index tied_by_grp on $SCHEMA.tied (grp)")
            st.execute("analyze $SCHEMA.tied")
        }
    }

    @AfterAll
    fun dropFixtureSchema() {
        connect().use { c ->
            c.createStatement().use { it.execute("drop schema if exists $SCHEMA cascade") }
        }
    }

    // ------------------------------------------------------------------------------------
    // The walk, and what it counts
    // ------------------------------------------------------------------------------------

    private class Walk(val label: String, val ids: List<Long>) {
        val duplicated: List<Long> =
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        val returned: Set<Long> = ids.toSet()

        fun missing(all: Set<Long>): List<Long> = (all - returned).sorted()

        fun line(all: Set<Long>) =
            "%-46s returned %3d of %d  twice %2d  never %2d"
                .format(label, ids.size, all.size, duplicated.size, missing(all).size)
    }

    /**
     * Walks every page with `LIMIT`/`OFFSET`, optionally changing the planner's options between
     * pages.
     *
     * [shiftPlanBetweenPages] is not a trick to force a failure. It is the honest model of what
     * happens between two pages in production: they are separate requests, and between them
     * autovacuum can run, statistics can move, an index can be created, or the row count can
     * cross a threshold that changes the plan. Toggling `enable_seqscan` makes that
     * **deterministic and reproducible** instead of waiting for it to happen by luck.
     */
    private fun walk(
        c: Connection,
        label: String,
        orderBy: String,
        shiftPlanBetweenPages: Boolean,
    ): Walk {
        val ids = mutableListOf<Long>()
        var offset = 0
        var page = 0
        while (offset < ROWS) {
            if (shiftPlanBetweenPages) {
                val seqScan = if (page % 2 == 0) "on" else "off"
                c.createStatement().use { it.execute("set enable_seqscan = $seqScan") }
            }
            c.prepareStatement(
                "select id from $SCHEMA.tied order by $orderBy limit ? offset ?",
            ).use { ps ->
                ps.setInt(1, PAGE)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs -> while (rs.next()) ids.add(rs.getLong(1)) }
            }
            offset += PAGE
            page += 1
        }
        c.createStatement().use { it.execute("reset enable_seqscan") }
        return Walk(label, ids)
    }

    /**
     * Keyset paging: no `OFFSET` at all. Each page asks for the rows **after** the last one it
     * saw, using the same composite key it sorts by.
     */
    private fun keysetWalk(c: Connection, label: String, shiftPlanBetweenPages: Boolean): Walk {
        val ids = mutableListOf<Long>()
        var lastGrp = Int.MIN_VALUE
        var lastId = Long.MIN_VALUE
        var page = 0
        while (true) {
            if (shiftPlanBetweenPages) {
                val seqScan = if (page % 2 == 0) "on" else "off"
                c.createStatement().use { it.execute("set enable_seqscan = $seqScan") }
            }
            val batch = mutableListOf<Pair<Int, Long>>()
            c.prepareStatement(
                "select grp, id from $SCHEMA.tied where (grp, id) > (?, ?) " +
                    "order by grp, id limit ?",
            ).use { ps ->
                ps.setInt(1, lastGrp)
                ps.setLong(2, lastId)
                ps.setInt(3, PAGE)
                ps.executeQuery().use { rs ->
                    while (rs.next()) batch.add(rs.getInt(1) to rs.getLong(2))
                }
            }
            if (batch.isEmpty()) break
            batch.forEach { ids.add(it.second) }
            lastGrp = batch.last().first
            lastId = batch.last().second
            page += 1
        }
        c.createStatement().use { it.execute("reset enable_seqscan") }
        return Walk(label, ids)
    }

    // ------------------------------------------------------------------------------------
    // 1. The count
    // ------------------------------------------------------------------------------------

    /**
     * **The number `R41` exists to produce: how many rows came back twice, and how many never.**
     *
     * Four walks over the same hundred rows, at page size 10:
     *
     * 1. no tie-break, one plan throughout — the control, and it may well be clean;
     * 2. no tie-break, the plan changing between pages — the defect;
     * 3. a unique tie-break appended, same plan changes — the first remedy;
     * 4. keyset paging on the same composite key — the second remedy.
     *
     * ⭐ **Arms 3 and 4 do not solve the same problem, and `R41` says so rather than
     * recommending both.** A tie-break makes the order total, so the same question gets the
     * same answer twice. Keyset paging additionally stops asking the database to count past
     * rows it will discard, and is stable under **concurrent insertion and deletion**, which a
     * tie-break is not: add a row on page 1 and every subsequent `OFFSET` page shifts by one,
     * tie-break or no tie-break.
     */
    @Test
    fun `how many rows come back twice and how many never come back at all`() {
        connect().use { c ->
            seed(c)
            val all = (1L..ROWS).toSet()

            val stable = walk(c, "order by grp            plan fixed", "grp", false)
            val shifting = walk(c, "order by grp            plan shifts", "grp", true)
            val tieBroken = walk(c, "order by grp, id        plan shifts", "grp, id", true)
            val keyset = keysetWalk(c, "keyset (grp, id) > ..   plan shifts", true)

            println()
            println("R41-PAGING >>> $ROWS rows, $GROUP_SIZE per tied group, page size $PAGE")
            listOf(stable, shifting, tieBroken, keyset).forEach { println("  " + it.line(all)) }
            if (shifting.duplicated.isNotEmpty()) {
                println("  ids returned twice : ${shifting.duplicated}")
                println("  ids never returned : ${shifting.missing(all)}")
            }
            println()

            assertEquals(
                emptyList<Long>(), tieBroken.duplicated,
                "a unique tie-break must make the order total, so no row can repeat",
            )
            assertEquals(
                emptyList<Long>(), tieBroken.missing(all),
                "a unique tie-break must make the order total, so no row can be skipped",
            )
            assertEquals(
                emptyList<Long>(), keyset.duplicated,
                "keyset paging must not repeat a row",
            )
            assertEquals(
                emptyList<Long>(), keyset.missing(all),
                "keyset paging must not skip a row",
            )
            assertEquals(
                ROWS, tieBroken.ids.size,
                "the tie-broken walk must return every row exactly once",
            )

            // The defect arm is REPORTED rather than asserted to be non-empty. If this
            // machine's planner happens to return tied rows identically under both plans, that
            // is a result -- R41 then says it would not shift here and measures what held it
            // stable, which the brief explicitly allows. Asserting a failure would make the
            // test lie on a machine where the defect does not appear.
            assertTrue(
                shifting.ids.size == ROWS,
                "the walk must always fetch ROWS ids in total -- it asks for every offset " +
                    "regardless of what comes back. If this differs, the walk itself is wrong",
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // 2. What the tie-break costs the index
    // ------------------------------------------------------------------------------------

    /**
     * **Plain `EXPLAIN`, and the choice of `EXPLAIN` over `EXPLAIN ANALYZE` is deliberate
     * rather than incidental.**
     *
     * The question is whether the index that serves `order by grp` still serves
     * `order by grp, id`, or whether the second one has to sort. That is a question about the
     * **shape** of the plan — which nodes appear — and plan shape is a logical fact about the
     * query, the schema and the statistics.
     *
     * `EXPLAIN ANALYZE` would answer the same question and would also produce actual execution
     * times. This session does not hold the timing lock, and the right response to that is to
     * choose the form of the query that **cannot** produce a duration, rather than to take one
     * and discard it. Every number below is a node name or a planner estimate, and planner
     * estimates are printed in cost units, not milliseconds.
     */
    @Test
    fun `does the index still serve the order once a tie-break is appended`() {
        connect().use { c ->
            seed(c)

            val withoutTieBreak = explain(c, "select id from $SCHEMA.tied order by grp limit $PAGE")
            val withTieBreak = explain(c, "select id from $SCHEMA.tied order by grp, id limit $PAGE")

            println()
            println("R41-PLAN >>> plain EXPLAIN, no ANALYZE, no duration produced")
            println("  --- order by grp ---")
            withoutTieBreak.forEach { println("    $it") }
            println("  --- order by grp, id ---")
            withTieBreak.forEach { println("    $it") }
            println()

            assertTrue(
                withoutTieBreak.isNotEmpty() && withTieBreak.isNotEmpty(),
                "EXPLAIN returned nothing, so the comparison below would be vacuous",
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // 3. ADR-014 row 44.3, on the real table and the real index
    // ------------------------------------------------------------------------------------

    /**
     * **`ADR-014` row `44.3`, which slice H scoped and declined to guess at.**
     *
     * The row reads: *"plan cost of `recentOutcomesByCount`'s `attempted_at desc, id desc`
     * tie-break — does `V2`'s index still serve it without a sort"*, noted *"the comparison was
     * refused rather than approximated."*
     *
     * ⛔ This does **not** re-measure `recentOutcomesByCount`. `R44` §3 already paid for the
     * specific case and slice H owns it. What is measured here is the one thing `44.3` asks and
     * `R44` left open: **the plan shape**, on the real `attempt` table with `V2`'s real index.
     *
     * The rows are seeded with `generate_series` — the same technique `PopulatedMigrationTest`
     * uses on these tables — because a plan taken against an empty table is a plan for an empty
     * table and would answer a different question. `analyze` runs before the `EXPLAIN` so the
     * planner is deciding on statistics rather than on defaults. Everything is removed
     * afterwards and the statistics are recomputed, because the next test to read this table
     * must not inherit either the rows or the histogram.
     */
    @Test
    fun `does V2's index serve the recency tie-break without a sort`() {
        connect().use { c ->
            try {
                c.createStatement().use { st ->
                    st.execute(
                        "insert into learner (external_ref) " +
                            "select 'learner-g3' || lpad(g::text, 6, '0') " +
                            "from generate_series(1, 20) g",
                    )
                    st.execute(
                        "insert into concept (code, name, grade_band) " +
                            "select 'concept-g3' || lpad(g::text, 4, '0'), 'C' || g, 'G5-6' " +
                            "from generate_series(1, 5) g",
                    )
                    st.execute(
                        "insert into item (code, concept_primary_id, difficulty, is_active) " +
                            "select 'item-g3' || lpad(g::text, 6, '0'), " +
                            "(select min(id) from concept where code like 'concept-g3%'), " +
                            "5, true from generate_series(1, 20) g",
                    )
                    // 20,000 attempts spread over 20 learners -- ADR-007's threshold, and
                    // enough that a sequential scan is not automatically the cheapest plan.
                    //
                    // THE IDS COME FROM TWO ARRAYS AGGREGATED ONCE, not from a correlated
                    // subquery per row. An earlier draft used `join lateral (... limit 1)`
                    // twice, which is 40,000 subquery executions to insert 20,000 rows -- a
                    // measurement fixture that costs more than the thing being measured. It
                    // also avoids assuming the freshly inserted ids are consecutive, which
                    // they are only until another test consumes part of the sequence.
                    st.execute(
                        "insert into attempt (learner_id, item_id, correct, elapsed_ms, hint_used, attempted_at) " +
                            "select ls.a[(g % array_length(ls.a, 1)) + 1], " +
                            "       itm.a[(g % array_length(itm.a, 1)) + 1], " +
                            "       (g % 3 = 0), 4200, false, " +
                            "       timestamptz '2026-08-01 00:00:00Z' + ((g % 500) * interval '1 minute') " +
                            "  from generate_series(1, $ATTEMPTS) g " +
                            "  cross join (select array_agg(id order by id) a from learner " +
                            "               where external_ref like 'learner-g3%') ls " +
                            "  cross join (select array_agg(id order by id) a from item " +
                            "               where code like 'item-g3%') itm",
                    )
                    st.execute("analyze attempt")
                }

                val learnerId = c.createStatement().use { st ->
                    st.executeQuery(
                        "select id from learner where external_ref like 'learner-g3%' order by id limit 1",
                    ).use { rs -> rs.next(); rs.getLong(1) }
                }

                val withTieBreak = explain(
                    c,
                    "select a.correct from attempt a where a.learner_id = $learnerId " +
                        "order by a.attempted_at desc, a.id desc limit 20",
                )
                val withoutTieBreak = explain(
                    c,
                    "select a.correct from attempt a where a.learner_id = $learnerId " +
                        "order by a.attempted_at desc limit 20",
                )

                println()
                println("R41-44.3 >>> the shipped recency read, on the real table and V2's index")
                println("  attempt rows seeded: $ATTEMPTS   plain EXPLAIN, no ANALYZE, no duration")
                println("  --- order by attempted_at desc            (no tie-break) ---")
                withoutTieBreak.forEach { println("    $it") }
                println("  --- order by attempted_at desc, id desc   (as shipped) ---")
                withTieBreak.forEach { println("    $it") }
                println()

                assertTrue(
                    withTieBreak.isNotEmpty(),
                    "EXPLAIN returned nothing; 44.3 cannot be answered from this",
                )
            } finally {
                c.createStatement().use { st ->
                    st.execute("delete from attempt where item_id in (select id from item where code like 'item-g3%')")
                    st.execute("delete from item where code like 'item-g3%'")
                    st.execute("delete from mastery where concept_id in (select id from concept where code like 'concept-g3%')")
                    st.execute("delete from concept where code like 'concept-g3%'")
                    st.execute("delete from learner where external_ref like 'learner-g3%'")
                    // The histogram this test created must not outlive it. A later test
                    // planning against 20,000 phantom rows would be a defect introduced by a
                    // measurement, which is the worst kind.
                    st.execute("analyze attempt")
                }
            }
        }
    }

    private fun explain(c: Connection, sql: String): List<String> =
        c.createStatement().use { st ->
            st.executeQuery("explain $sql").use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out.add(rs.getString(1))
                out
            }
        }

    private companion object {
        const val SCHEMA = "g_paging"
        const val ROWS = 100
        const val GROUP_SIZE = 25
        const val PAGE = 10
        const val ATTEMPTS = 20_000
    }
}
