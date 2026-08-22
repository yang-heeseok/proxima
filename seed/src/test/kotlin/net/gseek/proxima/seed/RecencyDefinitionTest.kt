package net.gseek.proxima.seed

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * **Step 4 of the documented rule needs a definition of *recent*, and this measures whether
 * the shipped dataset can tell two candidate definitions apart.**
 *
 * `domain-model.md` step 4: *filtered to a difficulty band matched to their recent accuracy*.
 * Two readings are in ordinary use and they are not the same question:
 *
 * | | what it fixes | what it lets vary |
 * | --- | --- | --- |
 * | last `N` attempts | the sample size | the period it spans |
 * | last `D` days | the period | the sample size |
 *
 * The argument for them diverging is a population with different cadences: a learner doing a
 * hundred a day and a learner doing three a week have wildly different "last 20". **This test
 * does not assume that population exists here. It measures whether it does**, because the
 * answer decides whether `R44`'s headline number means anything.
 *
 * The generator is called rather than reimplemented. A test that recomputes the seed's
 * arithmetic measures its own copy of the algorithm, and the copy is what drifts.
 */
class RecencyDefinitionTest {

    /**
     * Accuracy thresholds are `ADR-021`'s, restated here rather than imported: `seed` does
     * not depend on `api`, and a number that must agree across a module boundary is worth
     * stating twice with a test that says so — the shape `ADR-006` chose for the score band.
     */
    private fun band(accuracy: Double?): String = when {
        accuracy == null -> "none"
        accuracy < 0.50 -> "1..5"
        accuracy < 0.80 -> "3..7"
        else -> "5..9"
    }

    private class Learner {
        val correct = ArrayList<Boolean>(3_000)
        val at = ArrayList<Long>(3_000)
    }

    @Test
    fun `the two definitions of recent, measured against the dataset every published number uses`(
        @TempDir tmp: File,
    ) {
        val scale = Scale.FULL
        val files = Generator(scale).generateAll(tmp.toPath())
        val attempts = files.getValue("attempt").toFile()

        // Rows are emitted learner by learner, so one learner is buffered at a time rather
        // than three million rows.
        val perLearner = LinkedHashMap<Long, Learner>()
        var maxAt = Long.MIN_VALUE
        var rows = 0L
        attempts.bufferedReader().useLines { lines ->
            for (line in lines) {
                val f = line.split('\t')
                val learnerId = f[1].toLong()
                val ok = f[3] == "t"
                val at = Instant.parse(f[6]).epochSecond
                perLearner.getOrPut(learnerId) { Learner() }.let { it.correct += ok; it.at += at }
                if (at > maxAt) maxAt = at
                rows++
            }
        }
        assertEquals(scale.attempts, rows, "the generator did not emit the scale's row count")
        assertEquals(scale.learners, perLearner.size, "not every learner produced attempts")

        val n = 20
        val days = 7L
        val since = maxAt - Duration.ofDays(days).seconds

        var disagree = 0
        var emptyWindow = 0
        var minSpanH = Long.MAX_VALUE
        var maxSpanH = Long.MIN_VALUE
        var minInWindow = Int.MAX_VALUE
        var maxInWindow = Int.MIN_VALUE

        // H4 -- the window slides, so the same request answers differently over time.
        //
        // A rolling window advancing past one attempt drops that attempt out. So "the same
        // request one second later" is exactly: the same band computed over one fewer row,
        // at the instant the oldest in-window attempt falls off the back. `wobbleNext`
        // counts the learners for whom the VERY NEXT such crossing changes the band;
        // `wobbleRotation` counts, over a full rotation of the window, how many of the
        // crossings change it -- which is the rate rather than the instant.
        var wobbleNext = 0
        var wobbleRotation = 0L
        var crossings = 0L

        for ((_, l) in perLearner) {
            val size = l.at.size
            // last N attempts
            val from = size - n
            val byCount = (from until size).count { l.correct[it] }.toDouble() / n
            val spanH = (l.at[size - 1] - l.at[from]) / 3600

            // last D days, measured from the dataset's own newest attempt
            val idx = l.at.indexOfFirst { it >= since }
            val inWindow = if (idx < 0) 0 else size - idx
            val byDays = if (inWindow == 0) null
            else (size - inWindow until size).count { l.correct[it] }.toDouble() / inWindow

            if (inWindow == 0) emptyWindow++
            if (band(byCount) != band(byDays)) disagree++
            minSpanH = minOf(minSpanH, spanH); maxSpanH = maxOf(maxSpanH, spanH)
            minInWindow = minOf(minInWindow, inWindow); maxInWindow = maxOf(maxInWindow, inWindow)

            // H4: walk the window forward one attempt at a time over a full rotation.
            // At step k the window holds the newest (inWindow - k) attempts.
            if (inWindow > 1) {
                var previous = band(byDays)
                for (k in 1 until inWindow) {
                    val held = inWindow - k
                    val acc = (size - held until size).count { l.correct[it] }.toDouble() / held
                    val next = band(acc)
                    if (k == 1 && next != previous) wobbleNext++
                    if (next != previous) wobbleRotation++
                    crossings++
                    previous = next
                }
            }
        }

        // THE SAME WINDOW, MEASURED THE WAY THE APPLICATION ACTUALLY MEASURES IT.
        //
        // Everything above is relative to the dataset's OWN newest attempt, which is the
        // only reference point that makes the two definitions comparable. The shipped code
        // does not have that reference point: `RecommendationService` computes
        // `Instant.now(clock).minus(RECENT_DAYS)`, and the seed's window ends at a fixed
        // instant that recedes further into the past every day this repository is not
        // regenerated. When the gap exceeds the window, step 4 has no evidence for anybody.
        val now = Instant.now().epochSecond
        val sinceNow = now - Duration.ofDays(days).seconds
        val emptyByWallClock = perLearner.count { (_, l) -> l.at.last() < sinceNow }
        val datasetAgeDays = (now - maxAt) / 86_400.0

        val pct = disagree * 100.0 / perLearner.size
        println(
            """
            |
            |=== R44: what the two definitions of "recent" do on the shipped seed ===
            |  scale                       ${scale.learners} learners x ${scale.attemptsPerLearner} attempts = ${scale.attempts} rows
            |  newest attempt in dataset   ${Instant.ofEpochSecond(maxAt)}
            |  definitions                 last $n attempts   vs   last $days days
            |
            |  SPAN of the last $n attempts, across all ${perLearner.size} learners
            |    min ${minSpanH} h    max ${maxSpanH} h    spread ${maxSpanH - minSpanH} h
            |  COUNT of attempts in the last $days days, across all ${perLearner.size} learners
            |    min ${minInWindow}      max ${maxInWindow}      spread ${maxInWindow - minInWindow}
            |
            |  learners with an EMPTY $days-day window   $emptyWindow
            |  learners whose BAND DISAGREES            $disagree of ${perLearner.size}  (${"%.1f".format(pct)}%)
            |
            |  --- and the same definition against the wall clock, which is what ships ---
            |  clock now                   ${Instant.ofEpochSecond(now)}
            |  dataset is older by         ${"%.1f".format(datasetAgeDays)} days
            |  learners with an EMPTY $days-day window measured from now
            |                              $emptyByWallClock of ${perLearner.size}
            |
            |  --- H4: the window slides, so the same request answers differently ---
            |  learners whose band flips at the VERY NEXT boundary crossing
            |                              $wobbleNext of ${perLearner.size}  (${"%.1f".format(wobbleNext * 100.0 / perLearner.size)}%)
            |  boundary crossings over one full window rotation
            |                              $crossings
            |  of those, crossings that CHANGE the band
            |                              $wobbleRotation  (${"%.1f".format(wobbleRotation * 100.0 / crossings)}%)
            |
            """.trimMargin(),
        )

        // THE ASSERTION IS THE FINDING, NOT A THRESHOLD THAT WAS TUNED.
        //
        // Generator.kt writes
        //     at = start + (windowSeconds * n) / scale.attemptsPerLearner + rng(0..3599)
        // and there is no learner-dependent term in it. Every learner therefore attempts on
        // the same cadence and the two definitions are a constant apart for all of them. If
        // this assertion ever fails, the generator gained a per-learner cadence and R44's
        // conclusion is void -- which is the only reason it is an assertion rather than a
        // printed number.
        assertTrue(
            maxSpanH - minSpanH <= 2,
            "the last-$n-attempts span now differs by ${maxSpanH - minSpanH} h across learners. " +
                "R44 concluded the shipped seed has no cadence variation; it now has some.",
        )
        assertEquals(
            0, emptyWindow,
            "some learner has no attempt in the last $days days of the dataset's own window",
        )
    }
}
