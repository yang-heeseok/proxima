package net.gseek.proxima.recommendation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RecommendationView(
    val itemCode: String,
    val conceptName: String,
    val difficulty: Short,
    val renderHint: String,
)

/**
 * `GET /api/v1/learners/{learnerId}/recommendations`
 *
 * **The order of the three statements in this method is the whole of `T1`.**
 *
 * 1. `nextItems` runs inside a transaction and returns entities. The transaction ends here.
 * 2. `it.conceptPrimary.name` touches a lazy association **outside** any transaction.
 * 3. `renderHints` calls something slow that has nothing to do with the database.
 *
 * Nothing about that sequence looks wrong, and every step of it is what a person writes
 * when enriching a result with data from elsewhere: fetch, read what you fetched, then go
 * and get the rest. Reordering 2 and 3 would change the measurement, which is itself the
 * point — a property that depends on the order of two innocuous lines is a property nobody
 * maintains on purpose.
 *
 * Whether step 2 leaves a connection checked out across step 3 **has not been measured on
 * this stack**, and this repository does not assume it. Spring's own warning at startup
 * says the session is bound to the thread for the whole request; it does not say the JDBC
 * connection is, and modern Hibernate releases connections after a transaction completes.
 * The measurement decides it, not the folklore.
 */
@RestController
@RequestMapping("/api/v1/learners")
class RecommendationController(
    private val recommendations: RecommendationService,
    private val content: ContentGateway,
) {

    @GetMapping("/{learnerId}/recommendations")
    fun recommend(
        @PathVariable learnerId: Long,
        @RequestParam(defaultValue = "10") limit: Int,
    ): List<RecommendationView> {
        val items = recommendations.nextItems(learnerId, limit)

        // (2) lazy association, outside the transaction that loaded `items`
        val conceptNames = items.associate { it.id!! to it.conceptPrimary.name }

        // (3) slow, and not the database
        val hints = content.renderHints(items.mapNotNull { it.id })

        return items.map { item ->
            RecommendationView(
                itemCode = item.code,
                conceptName = conceptNames.getValue(item.id!!),
                difficulty = item.difficulty,
                renderHint = hints.getValue(item.id!!),
            )
        }
    }
}
