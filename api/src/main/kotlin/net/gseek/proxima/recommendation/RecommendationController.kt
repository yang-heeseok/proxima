package net.gseek.proxima.recommendation

import net.gseek.proxima.security.RequestToken
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

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
    /**
     * **Both arms of `T1`'s comparison live in one binary on purpose.** Measuring them from
     * two builds would mean two JIT histories, two page-cache states and two startup
     * sequences sitting alongside the difference being measured. One process, one flag, and
     * the only thing that changes between runs is the code path.
     *
     * The default is now `projection` — `R4` chose it, and `application.yml` turns
     * `open-in-view` off beside it because **neither half works alone**.
     *
     * `entities` is kept so the `red` state stays runnable, and it **requires
     * `--spring.jpa.open-in-view=true`**. With the shipped configuration it raises
     * `LazyInitializationException`, which is not a bug in this class: it is the finding
     * from `R2` §3.1 that the code and the setting are one decision, not two.
     */
    @Value("\${proxima.recommendation.strategy:projection}")
    private val strategy: String,
    /**
     * **`T9`'s second strand: the difference between knowing who is calling and deciding what
     * they may have.**
     *
     * `TokenAuthenticationFilter` has already run by the time this method is entered. The
     * request carries a verified subject. Every caller that reaches here is authenticated,
     * and `authorisation = none` is what it looks like to stop there.
     *
     * | value | behaviour |
     * | --- | --- |
     * | `none` | the learner id comes from the **path** and the verified subject is ignored. **`red`** |
     * | `owner` | the path must name the caller, or 403 |
     *
     * The `red` arm is not a strawman and it is not rare. It is what this endpoint did before
     * authentication existed at all, kept working unchanged after authentication was added,
     * and passed every functional test in this repository both times — because every one of
     * those tests asks for its own learner's data.
     */
    @Value("\${proxima.security.authorisation:owner}")
    private val authorisation: String,
) {

    @GetMapping("/{learnerId}/recommendations")
    fun recommend(
        @PathVariable learnerId: Long,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestAttribute(name = RequestToken.SUBJECT_ATTRIBUTE) subject: Long,
    ): List<RecommendationView> {
        authorise(subject, learnerId)
        return when (strategy) {
            "entities" -> viaEntities(learnerId, limit)
            "projection" -> viaProjection(learnerId, limit)
            else -> error("unknown proxima.recommendation.strategy: $strategy")
        }
    }

    /**
     * The one comparison that separates the two arms.
     *
     * **403 and not 404.** Hiding existence behind a not-found is a real technique and a
     * different decision with its own cost — it makes an authorisation failure and a typo
     * indistinguishable in the logs. It is not taken here, and saying so is the point: an
     * unexamined 404 would look like a considered choice.
     */
    private fun authorise(subject: Long, learnerId: Long) {
        when (authorisation) {
            "none" -> Unit
            "owner" ->
                if (subject != learnerId) {
                    throw ResponseStatusException(HttpStatus.FORBIDDEN, "not your learner")
                }
            else -> error("unknown proxima.security.authorisation: $authorisation")
        }
    }

    /** `red`. The connection is held from (2) until the response is written. */
    private fun viaEntities(learnerId: Long, limit: Int): List<RecommendationView> {
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

    /** `green`. Nothing after the transaction can reach the database. */
    private fun viaProjection(learnerId: Long, limit: Int): List<RecommendationView> {
        val rows = recommendations.nextRows(learnerId, limit)

        val hints = content.renderHints(rows.map { it.itemId })

        return rows.map { row ->
            RecommendationView(
                itemCode = row.itemCode,
                conceptName = row.conceptName,
                difficulty = row.difficulty,
                renderHint = hints.getValue(row.itemId),
            )
        }
    }
}
