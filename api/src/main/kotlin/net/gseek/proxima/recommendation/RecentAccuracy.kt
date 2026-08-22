package net.gseek.proxima.recommendation

/**
 * **Step 4 of the documented rule: the difficulty band, computed from the learner's recent
 * accuracy instead of fixed.**
 *
 * `domain-model.md` step 4 reads *filtered to a difficulty band matched to their recent
 * accuracy*. Until `R44` the band was the constant `3..7` and the KDoc on
 * [RecommendationQueries] gave a cost as the reason. That cost stopped existing when `V2`
 * indexed `(learner_id, attempted_at)` on 2026-08-12, and `R43` is the report about the
 * sentence outliving it.
 *
 * **What was actually missing was not an index. It was a definition of *recent*.**
 *
 * Two readings are in ordinary use and they are different questions:
 *
 * | | fixes | lets vary |
 * | --- | --- | --- |
 * | [Basis.LAST_N_ATTEMPTS] | the sample size | the period it spans |
 * | [Basis.LAST_N_DAYS] | the period | the sample size |
 *
 * Neither is correct in general. A learner working a hundred problems a day and one working
 * three a week have completely different "last 20"; give them both "last 7 days" instead and
 * one band comes off three attempts while the other comes off three hundred. **`ADR-021`
 * records which was chosen, and what the rejected one did better** — the second half is the
 * part that is usually lost.
 *
 * ⚠️ **On the shipped seed the two agree, and that is a property of the dataset rather than
 * of the definitions.** `Generator.writeAttempts` spaces every learner's attempts identically,
 * so this repository's own data cannot tell them apart. `RecencyDefinitionTest` measures that
 * and `R44` §3 is the number. **Do not read agreement here as evidence that the choice does
 * not matter.**
 */
object RecentAccuracy {

    /** Which question *recent* is being asked as. */
    enum class Basis { LAST_N_ATTEMPTS, LAST_N_DAYS }

    /**
     * The evidence a band is computed from, kept separate from the band so that
     * **[attempts] travels with the answer**.
     *
     * A band computed from three attempts and one computed from three hundred are not
     * interchangeable, and every caller that has ever flattened them to a single number has
     * lost the only thing that says which. [accuracy] is `null` when there is no evidence at
     * all rather than `0.0`, because *never tried* and *tried and got everything wrong* are
     * opposite facts that a zero would merge.
     */
    data class Evidence(val attempts: Int, val correct: Int, val basis: Basis) {
        init {
            require(attempts >= 0) { "attempts cannot be negative: $attempts" }
            require(correct in 0..attempts) { "correct $correct is not within 0..$attempts" }
        }

        val accuracy: Double? get() = if (attempts == 0) null else correct.toDouble() / attempts
    }

    /**
     * Accuracy to difficulty band.
     *
     * The thresholds are `ADR-021`'s and are **restated in `RecencyDefinitionTest`** in the
     * `seed` module, which cannot depend on this one. That duplication is deliberate and has
     * the shape `ADR-006` chose for the score band: two definitions of one rule, with
     * something that fails when they stop agreeing, rather than one definition in a place
     * neither side can reach.
     *
     * [NO_EVIDENCE] is returned when there is nothing to compute from. It is the band the
     * repository shipped as a constant for the whole of its life before `R44`, which makes
     * it the honest default: it is not a guess about the learner, it is the absence of one.
     */
    fun bandFor(evidence: Evidence): IntRange {
        val a = evidence.accuracy ?: return NO_EVIDENCE
        return when {
            a < 0.50 -> 1..5
            a < 0.80 -> 3..7
            else -> 5..9
        }
    }

    /** What `DIFFICULTY_BAND` was for every request this repository ever served before `R44`. */
    val NO_EVIDENCE: IntRange = 3..7
}
