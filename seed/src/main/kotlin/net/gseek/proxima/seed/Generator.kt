package net.gseek.proxima.seed

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Writes the dataset as PostgreSQL `COPY` text files, one per table.
 *
 * **Why files and not `INSERT`s.** At three million rows the difference is minutes against
 * tens of minutes. `COPY` is the reason the row count in `domain-model.md` is affordable
 * to regenerate, and regenerating it cheaply is what makes "the seed is code" a real
 * option rather than a stated intention.
 *
 * **Why `java.util.Random` and not `kotlin.random.Random`.** Reproducibility here has to
 * survive a different machine and a different JDK, otherwise a reader cannot check a
 * number. `java.util.Random` has its exact algorithm fixed by its specification, so a
 * given seed yields the same sequence everywhere. `kotlin.random.Random` makes no such
 * guarantee across versions.
 *
 * Each table draws from its own stream, derived from the one seed value. That way adding a
 * column to one table does not shift every row of every other table — which would silently
 * invalidate comparison against numbers already published.
 *
 * **The text format.** `COPY ... FROM` in its default text format is tab-separated, with
 * `\N` for null and backslash escapes. Nothing this generator emits contains a tab, a
 * newline, or a backslash — all values are generated identifiers, numbers, booleans, and
 * timestamps — so no escaping is performed and none is needed. A generator that started
 * emitting free text would have to revisit that.
 */
class Generator(
    private val scale: Scale,
    private val seed: Long = SEED_VALUE,
    /**
     * **Edges deliberately emitted backwards, breaking the DAG property. Zero everywhere
     * except in a test that is about cycles.**
     *
     * ## Why this exists at all
     *
     * `V1__baseline.sql` says acyclicity cannot be a `CHECK` constraint and is asserted by
     * a test instead. That test asserts the generator does not produce a cycle. **Nothing
     * anywhere asserts what happens when something else does** — a curriculum author, an
     * import, a migration from another system — and *nothing* is a bad answer for a
     * traversal, because the three shapes a recursive read can take die three different
     * deaths on a cyclic graph. `docs/reports/R21` measures them, and it needs a cyclic
     * graph that is reproducible rather than hand-built.
     *
     * ## Why it is a parameter and not a `Scale` field, and not a CLI flag
     *
     * A `Scale` field would put it in `Scale.FULL`'s and `Scale.TINY`'s constructor calls,
     * where somebody would eventually set it. A CLI flag would let a cyclic dataset be
     * loaded into a real database, and the whole point of `PUB-7` is that the seed is the
     * thing a reader reproduces — a flag that quietly produces a different dataset from the
     * published digests is a trap, not a feature. `Main.kt` does not expose this.
     *
     * ## What guarantees `Scale.FULL` is unaffected
     *
     * At the default of `0` the injection loop below does not execute and nothing else in
     * this class reads this value, so the emitted bytes cannot differ. That is an argument;
     * `SeedDigestTest` is the measurement, and it pins SHA-256 for both scales.
     */
    private val backEdges: Int = 0,
) {

    /** One stream per table, so tables are independent of each other's evolution. */
    private fun rng(table: Int) = Random(seed + table * 1_000_003L)

    /**
     * Item attributes, decided once and shared.
     *
     * The first draft recovered these by replaying the item stream from the same seed
     * inside the `item_concept` writer, and separately invented a difficulty for the
     * attempt writer out of an arithmetic expression on the item id. Both were replaced:
     * the replay broke silently if the item writer ever drew a different number of values,
     * and the invented difficulty meant a learner's success rate had no relationship to
     * the difficulty actually stored on the row they attempted. Holding the arrays costs
     * under a megabyte at full scale.
     */
    private class Items(val primaryConcept: IntArray, val difficulty: IntArray, val active: BooleanArray)

    fun generateAll(outDir: Path): Map<String, Path> {
        Files.createDirectories(outDir)
        val items = drawItems()
        return linkedMapOf(
            "learner" to write(outDir, "learner") { writeLearners(it) },
            "concept" to write(outDir, "concept") { writeConcepts(it) },
            "concept_edge" to write(outDir, "concept_edge") { writeConceptEdges(it) },
            "item" to write(outDir, "item") { writeItems(it, items) },
            "item_concept" to write(outDir, "item_concept") { writeItemConcepts(it, items) },
            "attempt" to write(outDir, "attempt") { writeAttempts(it, items) },
            "mastery" to write(outDir, "mastery") { writeMastery(it) },
        )
    }

    private fun write(outDir: Path, table: String, body: (BufferedWriter) -> Unit): Path {
        val path = outDir.resolve("$table.tsv")
        // A large buffer matters here: at three million rows the default is a measurable
        // fraction of generation time.
        Files.newBufferedWriter(path).use { raw ->
            val w = BufferedWriter(raw, 1 shl 20)
            body(w)
            w.flush()
        }
        return path
    }

    // -----------------------------------------------------------------------------------
    // learner
    // -----------------------------------------------------------------------------------
    //
    // The reference is shaped so it cannot be mistaken for a real identifier -- not a name,
    // not an email, not a phone number. PUB-7 requires the shape; the CI guard checks the
    // shapes a machine can check, and this is the other half of that.

    private fun writeLearners(w: BufferedWriter) {
        val created = ts(BASE_INSTANT.epochSecond)
        for (id in 1..scale.learners) {
            w.append(id.toString()).append('\t')
                .append(ref("learner", id)).append('\t')
                .append(created).append('\n')
        }
    }

    // -----------------------------------------------------------------------------------
    // concept / concept_edge
    // -----------------------------------------------------------------------------------

    private fun writeConcepts(w: BufferedWriter) {
        val r = rng(2)
        val created = ts(BASE_INSTANT.epochSecond)
        for (id in 1..scale.concepts) {
            val band = GRADE_BANDS[r.nextInt(GRADE_BANDS.size)]
            w.append(id.toString()).append('\t')
                .append(ref("concept", id)).append('\t')
                .append("Concept ").append(pad(id)).append('\t')
                .append(band).append('\t')
                .append(created).append('\n')
        }
    }

    /**
     * **Acyclicity is guaranteed by construction, not by checking afterwards.**
     *
     * Every edge runs from a lower concept id to a higher one. A cycle would require an
     * edge that runs backwards, and there is no code path that emits one. That is a
     * stronger guarantee than generating freely and rejecting cycles, and it is cheap.
     *
     * It cannot be expressed as a `CHECK` constraint — a constraint sees one row, and a
     * cycle is a property of the whole graph. `V1__baseline.sql` records that gap in a
     * comment rather than hiding it, and `GeneratorTest` asserts the property by
     * topological sort, so the guarantee is observed rather than asserted in prose.
     */
    private fun writeConceptEdges(w: BufferedWriter) {
        val r = rng(3)
        var edgeId = 1
        // Only allocated when a cycle has been asked for. At the default it stays null and
        // every line below that touches it is skipped, which is why the emitted bytes
        // cannot move -- see the `backEdges` parameter, and SeedDigestTest.
        val firstPrerequisite = if (backEdges > 0) IntArray(scale.concepts + 1) else null
        for (conceptId in 2..scale.concepts) {
            val available = conceptId - 1
            val wanted = minOf(scale.prerequisitesPerConcept, available)
            val chosen = HashSet<Int>(wanted * 2)
            var guard = 0
            while (chosen.size < wanted && guard < wanted * 20) {
                guard++
                // Prerequisites are drawn from nearby earlier concepts rather than
                // uniformly: a curriculum's prerequisite graph is local, and a uniform
                // draw would produce a graph with an unrealistically short diameter.
                val window = minOf(available, 60)
                val candidate = available - r.nextInt(window)
                if (candidate in 1 until conceptId) chosen.add(candidate)
            }
            for (prereq in chosen.sorted()) {
                val weight = 0.400 + r.nextInt(601) / 1000.0
                w.append(edgeId.toString()).append('\t')
                    .append(prereq.toString()).append('\t')
                    .append(conceptId.toString()).append('\t')
                    .append(dec3(weight)).append('\n')
                edgeId++
                if (firstPrerequisite != null && firstPrerequisite[conceptId] == 0) {
                    firstPrerequisite[conceptId] = prereq
                }
            }
        }
        if (firstPrerequisite != null) writeBackEdges(w, firstPrerequisite, edgeId)
    }

    /**
     * Closes [backEdges] cycles, each of a different length, by adding one edge that runs
     * **upwards** — `prerequisite_id > concept_id`.
     *
     * Every forward edge runs from a lower concept id to a higher one, so a walk over
     * prerequisites strictly decreases and cannot revisit anything. One upward edge from a
     * concept `s` to a concept `t` that is already below it on a prerequisite chain closes
     * a cycle whose length is the number of hops taken to get there, plus one.
     *
     * The chain is followed through `firstPrerequisite`, which is the lowest-numbered
     * prerequisite of each concept and therefore a real edge rather than an invented one.
     * **The cycles this produces are edges the database will accept**: `uk_concept_edge`
     * sees a pair no forward edge can produce, and `ck_concept_edge_no_self` is satisfied
     * because at least one hop was taken. That is the point — a cycle is not something the
     * schema can refuse.
     */
    private fun writeBackEdges(w: BufferedWriter, firstPrerequisite: IntArray, startId: Int) {
        var edgeId = startId
        for (i in 0 until backEdges) {
            // Spread across the id range rather than clustered, so a traversal starting
            // anywhere near the top can reach one. The stride is a fraction of the range
            // rather than a constant: the first version used 97, which is fine at 3,000
            // concepts and puts every injection after the first below zero at TINY's 40 --
            // so the test asking for three cycles got one, and said so.
            var node = scale.concepts - i * (scale.concepts / (backEdges + 1))
            if (node < 2) continue
            val from = node
            var hops = 0
            val wanted = 2 + i
            while (hops < wanted) {
                val next = firstPrerequisite[node]
                if (next == 0) break
                node = next
                hops++
            }
            if (hops == 0) continue
            w.append(edgeId.toString()).append('\t')
                .append(from.toString()).append('\t')  // prerequisite_id -- the HIGH one
                .append(node.toString()).append('\t')  // concept_id     -- the LOW one
                .append("1.000").append('\n')
            edgeId++
        }
    }

    // -----------------------------------------------------------------------------------
    // item / item_concept
    // -----------------------------------------------------------------------------------

    private fun drawItems(): Items {
        val r = rng(4)
        val primary = IntArray(scale.items)
        val difficulty = IntArray(scale.items)
        val active = BooleanArray(scale.items)
        for (i in 0 until scale.items) {
            primary[i] = 1 + r.nextInt(scale.concepts)
            difficulty[i] = 1 + r.nextInt(10)
            // A small share of items are retired. is_active exists so that a query which
            // forgets to filter it returns rows it should not -- a difference that only
            // shows up against data which actually contains inactive rows.
            active[i] = r.nextInt(100) >= 5
        }
        return Items(primary, difficulty, active)
    }

    private fun writeItems(w: BufferedWriter, items: Items) {
        val created = ts(BASE_INSTANT.epochSecond)
        for (i in 0 until scale.items) {
            val id = i + 1
            w.append(id.toString()).append('\t')
                .append(ref("item", id)).append('\t')
                .append(items.primaryConcept[i].toString()).append('\t')
                .append(items.difficulty[i].toString()).append('\t')
                .append(if (items.active[i]) "t" else "f").append('\t')
                .append(created).append('\n')
        }
    }

    private fun writeItemConcepts(w: BufferedWriter, items: Items) {
        val r = rng(5)
        for (i in 0 until scale.items) {
            val id = i + 1
            // The primary key is (item_id, concept_id), so the set must be distinct.
            val concepts = LinkedHashSet<Int>()
            concepts.add(items.primaryConcept[i])
            // At least one further concept, up to `extraConceptsPerItem`. Drawing from
            // 0..n instead put the average at 2.0 concepts per item and produced 200,295
            // rows against the 250,000 in domain-model.md -- see that field's comment.
            val extra = 1 + r.nextInt(scale.extraConceptsPerItem)
            var guard = 0
            while (concepts.size < 1 + extra && guard < 20) {
                guard++
                concepts.add(1 + r.nextInt(scale.concepts))
            }
            for ((index, conceptId) in concepts.withIndex()) {
                // The primary concept carries full weight; the rest carry less.
                val weight = if (index == 0) 1.000 else 0.200 + r.nextInt(401) / 1000.0
                w.append(id.toString()).append('\t')
                    .append(conceptId.toString()).append('\t')
                    .append(dec3(weight)).append('\n')
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // attempt -- the hot table
    // -----------------------------------------------------------------------------------

    private fun writeAttempts(w: BufferedWriter, items: Items) {
        val r = rng(6)
        val sb = StringBuilder(96)
        var id = 1L
        val windowSeconds = 18L * 30 * 24 * 3600 // ~18 months
        val start = BASE_INSTANT.epochSecond - windowSeconds

        for (learnerId in 1..scale.learners) {
            // A learner's ability is stable across their history, and correctness is drawn
            // against it and the item's real difficulty. Uniformly random correctness would
            // make the recommendation query in domain-model.md meaningless -- every learner
            // would look identical and step 2's WHERE clause would select nothing
            // interesting.
            val ability = -1.5 + r.nextInt(3001) / 1000.0
            for (n in 0 until scale.attemptsPerLearner) {
                val itemIndex = r.nextInt(scale.items)
                val difficulty = items.difficulty[itemIndex]
                val p = 1.0 / (1.0 + exp(-(ability - (difficulty - 5.5) * 0.4)))
                val correct = r.nextInt(1000) < (p * 1000).roundToInt()
                val hint = r.nextInt(100) < 18
                val base = if (correct) 4_000 else 9_000
                val elapsed = base + r.nextInt(26_000)
                // Attempts advance through the window so that "the last 30 days" and
                // ordering by time are meaningful. The jitter keeps them off a grid.
                val at = start + (windowSeconds * n) / scale.attemptsPerLearner + r.nextInt(3600)

                sb.setLength(0)
                sb.append(id).append('\t')
                    .append(learnerId).append('\t')
                    .append(itemIndex + 1).append('\t')
                    .append(if (correct) 't' else 'f').append('\t')
                    .append(elapsed).append('\t')
                    .append(if (hint) 't' else 'f').append('\t')
                    .append(ts(at)).append('\n')
                w.append(sb)
                id++
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // mastery
    // -----------------------------------------------------------------------------------
    //
    // The (learner_id, concept_id) pairs are distinct even though V1 carries no unique
    // constraint to make them so. That constraint's absence is deliberate (ADR-002) and it
    // is what T6 reproduces -- but T6 is about a race between two concurrent requests, not
    // about the seed shipping duplicates. A dataset that already contained them would make
    // that report's before-and-after uninterpretable.

    private fun writeMastery(w: BufferedWriter) {
        val r = rng(7)
        var id = 1L
        val updated = ts(BASE_INSTANT.epochSecond)
        val pool = IntArray(scale.concepts) { it + 1 }

        for (learnerId in 1..scale.learners) {
            // Partial Fisher-Yates: draw distinct concepts without allocating a set per
            // learner. The pool stays shuffled between learners, which is still a
            // deterministic function of the seed.
            val take = minOf(scale.masteryConceptsPerLearner, scale.concepts)
            for (i in 0 until take) {
                val j = i + r.nextInt(scale.concepts - i)
                val tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp

                val score = r.nextInt(1001) / 1000.0
                val attempts = r.nextInt(40)
                w.append(id.toString()).append('\t')
                    .append(learnerId.toString()).append('\t')
                    .append(pool[i].toString()).append('\t')
                    .append(dec3(score)).append('\t')
                    .append(attempts.toString()).append('\t')
                    .append('0').append('\t')
                    .append(updated).append('\n')
                id++
            }
        }
    }

    // -----------------------------------------------------------------------------------

    private companion object {
        /** Fixed, so the dataset does not shift with the calendar. */
        val BASE_INSTANT: Instant = Instant.parse("2026-08-10T00:00:00Z")

        val GRADE_BANDS = arrayOf("G1-2", "G3-4", "G5-6", "G7-9", "G10-12")

        fun pad(n: Int): String = n.toString().padStart(6, '0')

        /** `learner-000001` — a shape no real identifier has. */
        fun ref(kind: String, n: Int): String = "$kind-${pad(n)}"

        /** numeric(4,3), rendered without locale or floating-point surprises. */
        fun dec3(v: Double): String {
            val scaled = (v * 1000).roundToInt().coerceIn(0, 1000)
            return if (scaled == 1000) "1.000" else "0." + scaled.toString().padStart(3, '0')
        }

        fun ts(epochSecond: Long): String = Instant.ofEpochSecond(epochSecond).toString()
    }
}
