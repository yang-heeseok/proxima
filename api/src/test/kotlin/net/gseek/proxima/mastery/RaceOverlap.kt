package net.gseek.proxima.mastery

/**
 * Whether the calls in a race were ever open at the same instant.
 *
 * **Extracted from [UniquenessRaceTest] so that it can be tested without a database**, which
 * is the whole reason it is its own file. `RaceOverlapTest` is the control: it feeds this
 * intervals whose answer is known by construction, in both directions. Without that, the
 * precondition assertion in `UniquenessRaceTest` would be an instrument nobody has watched
 * refuse anything — and `R0` §4 keeps a count of those.
 */
internal object RaceOverlap {

    /**
     * The largest number of intervals covering any single instant.
     *
     * A sweep: every start is `+1`, every end is `-1`, sorted by time with **the end ordered
     * first on a tie** so that two intervals which merely abut — one ending exactly as the
     * next begins — are not counted as overlapping. That tie rule is the difference between
     * *"they touched"* and *"they were both open"*, and only the second one can produce a
     * race.
     */
    fun peak(startedAt: LongArray, endedAt: LongArray): Int {
        require(startedAt.size == endedAt.size) {
            "a race has one end per start: ${startedAt.size} starts, ${endedAt.size} ends"
        }
        val events = ArrayList<Pair<Long, Int>>(startedAt.size * 2)
        startedAt.forEach { events += it to 1 }
        endedAt.forEach { events += it to -1 }
        events.sortWith(compareBy({ it.first }, { it.second }))

        var open = 0
        var peak = 0
        events.forEach { (_, delta) ->
            open += delta
            if (open > peak) peak = open
        }
        return peak
    }
}
