package net.gseek.proxima.seed

/**
 * How many rows of each table the generator emits.
 *
 * The full scale is the table in `docs/explanation/domain-model.md`, and the two documents
 * are expected to agree. Three million attempts is not an arbitrary "big number": it is
 * the smallest size at which the difference between a good plan and a bad plan is
 * unambiguous on a developer machine. Below roughly a million rows PostgreSQL will often
 * choose a sequential scan and be right to, which makes every indexing experiment
 * inconclusive.
 *
 * **If this is ever lowered, `domain-model.md` and every report change with it.** A number
 * measured at one scale and quoted next to a row count from another is exactly the kind of
 * silent drift `PUB-4` exists to prevent.
 */
data class Scale(
    val learners: Int,
    val concepts: Int,
    /** Prerequisite edges per concept, capped by how many concepts precede it. */
    val prerequisitesPerConcept: Int,
    val items: Int,
    /**
     * Concepts an item exercises beyond its primary one: between 1 and this many.
     *
     * The lower bound of 1 is load-bearing. Drawing from `0..n` gives an average of 2.0
     * concepts per item and 200,295 rows, against the 250,000 in `domain-model.md` — which
     * is what the first run of the generator produced. The document owns the row counts,
     * so the generator was changed to meet it rather than the other way round.
     */
    val extraConceptsPerItem: Int,
    val attemptsPerLearner: Int,
    val masteryConceptsPerLearner: Int,
) {
    val attempts: Long get() = learners.toLong() * attemptsPerLearner
    val mastery: Long get() = learners.toLong() * masteryConceptsPerLearner

    companion object {
        /** The scale every published number in this repository is taken at. */
        val FULL = Scale(
            learners = 1_000,
            concepts = 3_000,
            prerequisitesPerConcept = 3,
            items = 100_000,
            extraConceptsPerItem = 2,
            attemptsPerLearner = 3_000,
            masteryConceptsPerLearner = 600,
        )

        /**
         * Small enough to generate inside a unit test. Used to check determinism and
         * acyclicity, which are properties of the algorithm and do not depend on scale.
         */
        val TINY = Scale(
            learners = 5,
            concepts = 40,
            prerequisitesPerConcept = 3,
            items = 60,
            extraConceptsPerItem = 2,
            attemptsPerLearner = 20,
            masteryConceptsPerLearner = 10,
        )
    }
}

/**
 * The fixed seed value. Same value in, same bytes out — that is the whole of `PUB-7`'s
 * reproducibility claim, and it is why no dataset is committed.
 */
const val SEED_VALUE: Long = 20260810L
