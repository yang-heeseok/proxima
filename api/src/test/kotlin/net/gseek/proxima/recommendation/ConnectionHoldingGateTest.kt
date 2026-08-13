package net.gseek.proxima.recommendation

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.security.RequestToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `T1` regression gate. `R4` §7 called its absence the most valuable thing that report
 * left undone; this is it.
 *
 * **The fix `R4` measured is two edits that only work together**, and each is easy to undo
 * on its own for a plausible-sounding reason:
 *
 * - `spring.jpa.open-in-view: false` looks like a line someone added to quieten a warning
 * - `proxima.recommendation.strategy=projection` looks like an implementation detail
 *
 * Remove either and the application still starts, and every other test still passes. Remove
 * the first and the pool silently goes back to holding ten connections to do the work of
 * two — measured in `R4` §3.4. Remove the second and requests fail with
 * `LazyInitializationException`, which at least announces itself, but only under traffic
 * that reaches a lazy association.
 *
 * So this class asserts both halves, and asserts them **as effects rather than as
 * settings**: reading back the property you just set proves the property was set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ConnectionHoldingGateTest {

    @Autowired private lateinit var context: ApplicationContext
    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * The JDK's own client rather than a Spring test client: Boot 4 ships neither
     * `TestRestTemplate` nor `RestTestClient` on this classpath, and adding a dependency to
     * make an assertion is a worse trade than four lines of `java.net.http`.
     */
    @Value("\${local.server.port}")
    private var port: Int = 0

    /**
     * **This gate acquired a dependency on authentication, and that is worth knowing.**
     *
     * `T9` put a token filter in front of everything under `/api/v1`. This test was the only
     * one in the repository that called that path, and it began failing with
     * `401 {"error":"missing-token"}` — measured, one failure out of 56, `R11` §3.1.
     *
     * It now signs a token for the learner it created. The cost is that a broken verifier
     * makes **this** test red as well as `T9`'s, so a failure here no longer localises to the
     * connection-holding question on its own. The assertion message below prints the response
     * body precisely so that the distinction survives — which is how the 401 above was
     * diagnosed in one read rather than by bisecting.
     */
    @Autowired
    private lateinit var tokens: RequestToken

    private fun get(path: String, asLearner: Long): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer ${tokens.issue(asLearner, Duration.ofMinutes(5))}")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @AfterEach
    fun clear() {
        // Committed on purpose -- see below -- so cleanup is this test's own job.
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-99%')")
        jdbc.execute("delete from item_concept where item_id in (select id from item where code like 'item-99%')")
        jdbc.execute("delete from item where code like 'item-99%'")
        jdbc.execute("delete from concept where code like 'concept-99%'")
        jdbc.execute("delete from learner where external_ref like 'learner-99%'")
    }

    /**
     * Half one, asserted through the machinery rather than the property.
     *
     * Spring Boot registers an [OpenEntityManagerInViewInterceptor] **only** when
     * `spring.jpa.open-in-view` resolves true. Asserting the bean is absent therefore
     * asserts the behaviour — the interceptor that binds an `EntityManager` to the request,
     * and with it a connection, is not installed. Asserting the property instead would
     * check that a string in a file is the string in that file.
     */
    @Test
    fun `nothing binds an EntityManager to the request`() {
        val bound = context.getBeanNamesForType(OpenEntityManagerInViewInterceptor::class.java).toList()

        assertTrue(
            bound.isEmpty(),
            "open-in-view is back on: found $bound. R4 measured what that costs -- ten " +
                "connections held to keep two busy, p99 5919 ms -> 9064 ms at 200 VU. " +
                "It is not a warning to silence, and setting it explicitly to `true` " +
                "silences the warning while keeping the behaviour",
        )
    }

    /**
     * Half two, asserted by making a real request over HTTP.
     *
     * `RANDOM_PORT` and no `@Transactional` on purpose: the defect lives in the request
     * lifecycle, and a test sharing a transaction with the code under test cannot observe
     * that lifecycle — the same reason `AttemptRecordingAtomicityTest` is built this way.
     *
     * The row this sets up is the minimum that makes the recommendation return something:
     * a concept the learner has not mastered, with no unmet prerequisite, carrying one
     * active item in the difficulty band that the learner has not attempted. **If the
     * response is empty this test proves nothing**, so it asserts on the content.
     */
    @Test
    fun `the shipped configuration answers a request that touches a concept`() {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values ('learner-990001') returning id",
            Long::class.java,
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) " +
                "values ('concept-990001', 'Concept 990001', 'G5-6') returning id",
            Long::class.java,
        )!!
        val itemId = jdbc.queryForObject(
            "insert into item (code, concept_primary_id, difficulty, is_active) " +
                "values ('item-990001', ?, 5, true) returning id",
            Long::class.java,
            conceptId,
        )!!
        jdbc.update(
            "insert into item_concept (item_id, concept_id, weight) values (?, ?, 1.000)",
            itemId, conceptId,
        )
        jdbc.update(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now())",
            learnerId, conceptId,
        )

        val response = get("/api/v1/learners/$learnerId/recommendations?limit=10", asLearner = learnerId)

        assertEquals(
            200, response.statusCode(),
            "the shipped configuration cannot serve this request. Read the body before " +
                "assuming which control failed: `LazyInitializationException` means " +
                "open-in-view was turned off without moving the fetch inside the transaction " +
                "(R4 §3.2 -- the two are one decision); an `error` field means the request " +
                "never reached the controller and this is T9's filter, not T1's. " +
                "Body: ${response.body()}",
        )
        val body = response.body().orEmpty()
        assertTrue(
            body.contains("item-990001"),
            "the recommendation returned nothing, so this test asserted nothing. Body: $body",
        )
        assertTrue(
            body.contains("Concept 990001"),
            "the concept name is missing, which is the field that requires the join the " +
                "projection does and the entity path lazy-loads. Body: $body",
        )
    }
}
