package net.gseek.proxima.concept

import kotlin.test.assertEquals
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
 * **"The next 20" stops meaning anything once the graph expands.**
 *
 * At depth 1 a page over the recommendation is unremarkable: the set of concepts in scope is
 * fixed by the learner's mastery, and `limit 20` takes twenty of them. Nothing here is
 * visible from there.
 *
 * Open depth `d` and two things break, and they break for different reasons.
 *
 * 1. **Where the `limit` goes.** Bounding the recursion's output — an entirely reasonable
 *    thing to do to a query that can carry 797,160 rows — cuts the *concepts* rather than the
 *    *page*, and the twenty items that come back are the twenty best items of an arbitrary
 *    subset. §3.1.
 * 2. **What the page is ordered by.** `order by difficulty, id` is the domain's answer —
 *    easiest first. It is also unstable under a change of `maxDepth`, because a concept
 *    found one level deeper brings items of every difficulty and they interleave everywhere.
 *    The ordering that *is* stable puts `depth` first, and `depth` is an artefact of the
 *    traversal rather than anything a learner cares about. §3.2.
 *
 * ## What this deliberately does not do
 *
 * **Order on text.** `R9` §8 records a standing risk against every `order by` on text in this
 * repository: `postgres:16-alpine` is musl-built and its declared `en_US.utf8` does not
 * collate locale-aware, so every ordering number here was taken byte-wise. `concept.name` and
 * `concept.grade_band` are the obvious keys for "which concept next" and neither is used.
 * Every key below is numeric. §8 says what that leaves unanswered rather than implying it
 * answered it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpandedPagingTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    private var top = 0L

    @BeforeAll
    fun install() {
        val ids = SeedConceptGraph.install(jdbc)
        SeedConceptGraph.installItems(jdbc, ids)
        top = ids[SeedConceptGraph.CONCEPTS]
        jdbc.execute("analyze")
    }

    @AfterAll
    fun uninstall() = SeedConceptGraph.remove(jdbc)

    /**
     * **The wrong twenty, shown.**
     *
     * Both queries say `limit 20`, both return twenty rows, both are ordered by difficulty
     * then id, and both are correct SQL. One of them answers *the twenty easiest problems a
     * learner should see next*; the other answers *the twenty easiest problems among the
     * first twenty concepts the recursion happened to emit*, and there is nothing in its
     * result to say which it is.
     */
    @Test
    fun `cutting before the expansion and sorting after it return different pages`() {
        val cutFirst = items(cutBeforeExpansion(DEPTH, limit = 20, offset = 0))
        val sortAfter = items(sortAfterExpansion(DEPTH, limit = 20, offset = 0))

        val shared = cutFirst.intersect(sortAfter.toSet())
        println("depth $DEPTH, page 1")
        println("  cut before expansion : $cutFirst")
        println("  sort after expansion : $sortAfter")
        println("  in common            : ${shared.size} of 20")

        assertEquals(20, cutFirst.size, "the arm being criticised must still return a full page")
        assertEquals(20, sortAfter.size)
        assertEquals(
            1, shared.size,
            "the two pages shared ${shared.size} of 20 items. That number is the finding; " +
                "if it has moved, the fixture's difficulty distribution has moved with it",
        )
    }

    /**
     * **And the pages do not add up to the answer**, which is the part a reviewer cannot see.
     *
     * Paging is supposed to partition a result: walk every page and you have walked the
     * whole set, once each. With the `limit` inside the recursion, the pages partition a
     * *different* set — one that is a strict subset of the answer — and no page is missing,
     * duplicated, or malformed. There is no symptom.
     */
    @Test
    fun `every page is well formed and the pages together are not the answer`() {
        val pages = (0 until 5).map { items(cutBeforeExpansion(DEPTH, 20, it * 20)) }
        val walked = pages.flatten()
        val whole = items(sortAfterExpansion(DEPTH, limit = 10_000, offset = 0))

        println("five pages of 20, cut before expansion")
        println("  rows returned    : ${walked.size}")
        println("  distinct rows    : ${walked.toSet().size}")
        println("  the whole answer : ${whole.size} items")
        println("  walked but not in the answer : ${(walked.toSet() - whole.toSet()).size}")
        println("  in the answer and never paged: ${(whole.toSet() - walked.toSet()).size}")

        assertEquals(
            walked.size, walked.toSet().size,
            "a page repeated a row, which is a defect anybody would notice. This one is " +
                "not that -- every page here is internally perfect",
        )
        assertTrue(
            (walked.toSet() - whole.toSet()).isEmpty(),
            "paging returned items that are not in the answer at all, which would be a " +
                "different and much louder bug than the one this test is about",
        )
        assertEquals(
            182, (whole.toSet() - walked.toSet()).size,
            "182 of the answer's 202 items are unreachable by paging -- 90% of it. If this " +
                "number has moved, the closure or the fixture has",
        )
        assertTrue(
            pages.drop(1).all { it.isEmpty() },
            "pages 2..5 returned ${pages.drop(1).map { it.size }}. They are supposed to be " +
                "empty, and THAT is what a caller sees: page 1 is full, page 2 is empty, " +
                "and the result looks like a set of exactly 20 items rather than 202",
        )
    }

    /**
     * **The page moves when the depth bound moves, and the fix costs the domain its ordering.**
     *
     * `order by i.difficulty, i.id` is what the domain asks for. A concept found one level
     * deeper brings an item of some arbitrary difficulty, which lands wherever its difficulty
     * puts it — including on page 1. So *the next 20* at `maxDepth = 6` and at `maxDepth = 7`
     * are different pages, and the learner did nothing.
     *
     * `order by t.depth, i.difficulty, i.id` is stable, because a concept found at depth 7
     * sorts after every concept found at depth 6 and cannot displace anything. It is also
     * no longer *easiest first*: it is *nearest first*, and near is a property of the graph
     * walk rather than of the learner.
     */
    @Test
    fun `the natural ordering is not stable under the depth bound and the stable one is not natural`() {
        val byDifficulty6 = items(sortAfterExpansion(6, 20, 0))
        val byDifficulty7 = items(sortAfterExpansion(7, 20, 0))
        val byDepth6 = items(sortAfterExpansionDepthFirst(6, 20, 0))
        val byDepth7 = items(sortAfterExpansionDepthFirst(7, 20, 0))

        val difficultyHeld = byDifficulty6.intersect(byDifficulty7.toSet()).size
        val depthHeld = byDepth6.intersect(byDepth7.toSet()).size
        println("page 1 survivors, depth 6 -> depth 7")
        println("  order by difficulty, id       : $difficultyHeld of 20")
        println("  order by depth, difficulty, id: $depthHeld of 20")

        assertTrue(
            difficultyHeld < 20,
            "page 1 was unchanged by opening the graph one level further. Then the natural " +
                "ordering IS stable and §3.2 has no subject -- check that depth 7 actually " +
                "reaches concepts depth 6 does not",
        )
        assertEquals(
            20, depthHeld,
            "ordering by depth first lost $depthHeld of 20 rows off page 1 when the bound " +
                "moved. It is supposed to lose none: a concept found deeper sorts after " +
                "everything already there. If it does not, the depth column is not min-depth",
        )
    }

    // -----------------------------------------------------------------------------------

    private fun items(sql: String): List<Long> =
        jdbc.queryForList(sql, Long::class.java).map { requireNotNull(it) { "item.id is not null" } }

    /** The recursion, bounded, grouped to shortest depth. Shared by every arm below. */
    private fun closure(depth: Int) = """
        with recursive walk (prerequisite_id, depth) as (
            select e.prerequisite_id, 1 from concept_edge e where e.concept_id = $top
            union
            select e.prerequisite_id, w.depth + 1
              from concept_edge e join walk w on e.concept_id = w.prerequisite_id
             where w.depth < $depth
        )
        select w.prerequisite_id as concept_id, min(w.depth) as depth
          from walk w group by w.prerequisite_id
    """.trimIndent()

    /**
     * **The reasonable-looking mistake.** `limit 20` on the concepts, before any item is
     * looked at — which is what somebody writes to stop a recursion that can carry 797,160
     * rows from carrying them.
     */
    private fun cutBeforeExpansion(depth: Int, limit: Int, offset: Int) = """
        with target as (
            ${closure(depth)}
            order by 2, 1
            limit 20
        )
        select i.id from item i
          join item_concept ic on ic.item_id = i.id
         where ic.concept_id in (select concept_id from target)
           and i.is_active
         order by i.difficulty, i.id
         limit $limit offset $offset
    """.trimIndent()

    /** The page taken over the whole expansion. */
    private fun sortAfterExpansion(depth: Int, limit: Int, offset: Int) = """
        with target as (${closure(depth)})
        select i.id from item i
          join item_concept ic on ic.item_id = i.id
         where ic.concept_id in (select concept_id from target)
           and i.is_active
         order by i.difficulty, i.id
         limit $limit offset $offset
    """.trimIndent()

    /** The same, ordered so that a deeper concept can never displace a shallower one. */
    private fun sortAfterExpansionDepthFirst(depth: Int, limit: Int, offset: Int) = """
        with target as (${closure(depth)})
        select i.id from item i
          join item_concept ic on ic.item_id = i.id
          join target t on t.concept_id = ic.concept_id
         where i.is_active
         order by t.depth, i.difficulty, i.id
         limit $limit offset $offset
    """.trimIndent()

    private companion object {
        /**
         * Deep enough that the closure is much larger than a page — 202 concepts — and
         * shallow enough that the `union all` arm `R20` measured is still affordable beside
         * it. Nothing here depends on the value except the numbers, which are pinned.
         */
        const val DEPTH = 6
    }
}
