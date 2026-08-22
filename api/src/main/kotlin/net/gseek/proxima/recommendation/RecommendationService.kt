package net.gseek.proxima.recommendation

import java.time.Clock
import java.time.Duration
import java.time.Instant
import net.gseek.proxima.recommendation.RecentAccuracy.Basis
import net.gseek.proxima.recommendation.RecentAccuracy.Evidence
import net.gseek.proxima.domain.Item
import net.gseek.proxima.domain.ItemRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Selects the items a learner should see next.
 *
 * **Returns entities, not a projection, and that is the state `T1` measures.** Returning
 * managed entities from a transactional method is extremely common and looks harmless: the
 * transaction ends when this method returns, the caller gets objects, and everything works.
 * What it also does is leave the caller holding objects whose associations have not been
 * loaded — so the caller decides, without knowing it, when the next database round trip
 * happens and what is open at that moment.
 *
 * Whether that costs anything is the question `T1` exists to answer, and it has not been
 * answered yet on this stack. See `docs/reports/` once it has.
 */
@Service
class RecommendationService(
    private val queries: RecommendationQueries,
    private val items: ItemRepository,
    private val clock: Clock,
) {

    /**
     * The remedy: everything the response needs, fetched while the transaction is open.
     *
     * **Same rule, same SQL shape, one difference — the caller is left holding values.**
     * Nothing returned here can trigger a round trip later, so the connection returns to
     * the pool when this method does and is not held across whatever the request does next.
     *
     * It also collapses the two statements [nextItems] issues into one. That is a second
     * improvement riding along with the first and the two **cannot be separated by this
     * measurement** — the report says so rather than attributing the whole difference to
     * the connection hold.
     */
    @Transactional(readOnly = true)
    fun nextRows(learnerId: Long, limit: Int): List<RecommendationRow> {
        val band = RecentAccuracy.bandFor(evidence(learnerId, RECENCY_BASIS))
        return queries.findRecommendations(
            learnerId = learnerId,
            minDifficulty = band.first,
            maxDifficulty = band.last,
            notAttemptedSince = Instant.now(clock).minus(RECENCY_WINDOW),
            limit = limit,
        )
    }

    /**
     * **Step 4 of the documented rule, implemented.** It costs one statement.
     *
     * Before `R44` this was the constant `3..7` for every learner and every request, and the
     * KDoc beside it gave an index that exists as the reason (`R43`). The band now comes from
     * the learner's recent accuracy, which is what `domain-model.md` step 4 always said.
     *
     * **`basis` is a parameter and not a constant, because the two readings are not the same
     * question and `ADR-021` chose one without the other becoming wrong.** A caller that
     * needs the rejected reading can ask for it; nothing here pretends the choice was forced.
     */
    @Transactional(readOnly = true)
    fun difficultyBandFor(learnerId: Long, basis: Basis = RECENCY_BASIS): IntRange =
        RecentAccuracy.bandFor(evidence(learnerId, basis))

    /**
     * The evidence behind a band, exposed because **a band alone cannot be audited.**
     *
     * `R44` §3 compares the two bases learner by learner, and it can only do that if the
     * sample each one used comes back with it.
     *
     * ⚠️ **`LAST_N_DAYS` measures back from `Instant.now(clock)` and is a rolling span, not a
     * day.** It has no midnight in it at all, so *whose* midnight has not had to be answered
     * — and the schema could not answer it anyway: `learner` has no time-zone column and
     * nothing in this repository calls a date-boundary function. **That question is
     * unmeasured rather than handled**, and this comment is not a claim otherwise.
     */
    @Transactional(readOnly = true)
    fun evidenceFor(learnerId: Long, basis: Basis): Evidence = evidence(learnerId, basis)

    /**
     * **The only place the two readings are actually computed, and it carries no annotation
     * on purpose.**
     *
     * `TransactionBoundaryRulesTest` refused the obvious shape of this class, and it was
     * right to: `difficultyBandFor` calling an `@Transactional` `evidenceFor` is a call
     * through `this`, so it never reaches the proxy and the annotation on the target does
     * nothing. That is `R1`'s defect — *observed at `21e7162`, fixed at `9388743`* — and
     * step 4 walked back into it on the first attempt. `R44` §3.5.
     *
     * A private method has no proxy to miss. The three public entry points above each open
     * their own transaction and then call this, which is the boundary being **on** them
     * rather than **under** them.
     */
    private fun evidence(learnerId: Long, basis: Basis): Evidence = when (basis) {
        Basis.LAST_N_ATTEMPTS -> {
            val outcomes = queries.recentOutcomesByCount(learnerId, RECENT_ATTEMPTS)
            Evidence(outcomes.size, outcomes.count { it }, basis)
        }
        Basis.LAST_N_DAYS -> {
            val counts = queries.recentOutcomeCountsSince(
                learnerId, Instant.now(clock).minus(RECENT_DAYS),
            )
            Evidence(counts.attempts.toInt(), counts.correct.toInt(), basis)
        }
    }

    @Transactional(readOnly = true)
    fun nextItems(learnerId: Long, limit: Int): List<Item> {
        val band = RecentAccuracy.bandFor(evidence(learnerId, RECENCY_BASIS))
        val ids = queries.findRecommendedItemIds(
            learnerId = learnerId,
            minDifficulty = band.first,
            maxDifficulty = band.last,
            notAttemptedSince = Instant.now(clock).minus(RECENCY_WINDOW),
            limit = limit,
        )
        // findAllById does not preserve the order of the argument, and the ordering is part
        // of the recommendation -- easiest items first. Restored here rather than relied on.
        val byId = items.findAllById(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private companion object {
        /**
         * Which reading of *recent* the shipped path uses. `ADR-021` chose it and records
         * what the rejected reading did better.
         */
        val RECENCY_BASIS = Basis.LAST_N_DAYS

        /** The sample size when the basis is a count of attempts. `ADR-021`. */
        const val RECENT_ATTEMPTS = 20

        /** The period when the basis is a span of days. `ADR-021`. */
        val RECENT_DAYS: Duration = Duration.ofDays(7)

        /** "has not attempted in 30 days", from `domain-model.md`. */
        val RECENCY_WINDOW: Duration = Duration.ofDays(30)
    }
}
