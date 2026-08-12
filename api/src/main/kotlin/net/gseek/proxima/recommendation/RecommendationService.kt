package net.gseek.proxima.recommendation

import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    fun nextRows(learnerId: Long, limit: Int): List<RecommendationRow> =
        queries.findRecommendations(
            learnerId = learnerId,
            minDifficulty = DIFFICULTY_BAND.first,
            maxDifficulty = DIFFICULTY_BAND.last,
            notAttemptedSince = Instant.now(clock).minus(RECENCY_WINDOW),
            limit = limit,
        )

    @Transactional(readOnly = true)
    fun nextItems(learnerId: Long, limit: Int): List<Item> {
        val ids = queries.findRecommendedItemIds(
            learnerId = learnerId,
            minDifficulty = DIFFICULTY_BAND.first,
            maxDifficulty = DIFFICULTY_BAND.last,
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
         * Step 4 of the documented rule bands difficulty by the learner's recent accuracy.
         * That is a second pass over three million `attempt` rows on a schema with no index
         * for it, so the band is fixed for now. See `RecommendationQueries`.
         */
        val DIFFICULTY_BAND = 3..7

        /** "has not attempted in 30 days", from `domain-model.md`. */
        val RECENCY_WINDOW: Duration = Duration.ofDays(30)
    }
}
