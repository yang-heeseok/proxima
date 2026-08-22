package net.gseek.proxima.mastery

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The control for [RaceOverlap], and the reason it is worth having is `R0` §4.
 *
 * `UniquenessRaceTest` now refuses to assert anything about contention unless [RaceOverlap]
 * says two calls were open together. **That makes the whole class depend on this function
 * being right** — and an instrument nobody has watched refuse anything proves nothing about
 * the runs it passed. This repository has shipped six of those; `study-consistency.yml`'s `S3`
 * was the most recent, and it reported `OK` over a planted violation for as long as it
 * existed.
 *
 * So both halves are here: intervals that **do** overlap must be counted, and intervals that
 * **do not** must not be. Needs no database, so it costs milliseconds and runs on every push.
 */
class RaceOverlapTest {

    /**
     * The negative half. **This is the case the round-two integration merge actually hit**:
     * eight calls, each finishing before the next began, on a machine too busy to run them
     * together. The old assertion could not see it and reported *"either the race did not
     * happen or something is swallowing the violation"*.
     */
    @Test
    fun `calls that ran one after another peak at one`() {
        val starts = longArrayOf(0, 10, 20, 30)
        val ends = longArrayOf(9, 19, 29, 39)

        assertEquals(
            1, RaceOverlap.peak(starts, ends),
            "four calls that never coincided must peak at 1 — this is the state in which no " +
                "assertion about contention means anything",
        )
    }

    /** Abutting exactly is still not overlapping, and the tie rule is what decides it. */
    @Test
    fun `a call ending exactly as the next begins is not an overlap`() {
        assertEquals(
            1, RaceOverlap.peak(longArrayOf(0, 10), longArrayOf(10, 20)),
            "touching is not contending: the first transaction committed at the instant the " +
                "second opened, so the second read a row that was already there",
        )
    }

    /** The positive half — without it a function returning 1 forever would pass the test above. */
    @Test
    fun `calls open together are counted`() {
        assertEquals(
            4, RaceOverlap.peak(longArrayOf(0, 1, 2, 3), longArrayOf(10, 11, 12, 13)),
            "four calls all open at instant 3 must peak at 4",
        )
    }

    /** Peak is the maximum at any instant, not the count of calls that overlapped something. */
    @Test
    fun `peak is the maximum at an instant, not a total`() {
        // two pairs, each pair overlapping internally and separated from the other pair
        assertEquals(
            2, RaceOverlap.peak(longArrayOf(0, 1, 100, 101), longArrayOf(10, 11, 110, 111)),
            "two disjoint pairs of two peak at 2, not 4",
        )
    }

    /** A race of one cannot be a race, and the function must say so rather than divide by it. */
    @Test
    fun `a single call peaks at one`() {
        assertEquals(1, RaceOverlap.peak(longArrayOf(5), longArrayOf(6)))
    }
}
