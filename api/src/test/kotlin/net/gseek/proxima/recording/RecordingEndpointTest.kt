package net.gseek.proxima.recording

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import net.gseek.proxima.security.LearnerFixtures
import net.gseek.proxima.security.RequestToken
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The surface `ADR-009` refused and then named its own exit from — `R24` §2.
 *
 * **This is the gate that stops the endpoint from being wider than the measurement that
 * justified it.** `ADR-009`'s argument was never *an endpoint is bad*; it was that an
 * endpoint with no consumer is an API designed by guessing. One measurement now needs a
 * socket. That buys exactly one `POST`, authorised the same way the read path is, returning
 * the outcome list `R14` established — and nothing else.
 *
 * The three assertions correspond to the three things that would make it more than that:
 *
 * 1. **it carries `R14`'s finding over HTTP** — four of five valid recordings land and each
 *    outcome is named, so the transport did not quietly re-introduce stop-at-first-failure;
 * 2. **a rejection is not an error status** — `200`, with the rejection in the body. A `4xx`
 *    here would be `R14`'s defect restated at the transport layer;
 * 3. **it authorises** — `AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE` says
 *    the call is present in the bytecode; this says it refuses. `R10` §7's shape: the
 *    structural rule and the behavioural one are not substitutes.
 *
 * `LearnerFixtures` is borrowed from `T9` rather than copied. Its rows are committed —
 * a real HTTP request cannot see a test's uncommitted transaction — so cleanup is by prefix
 * in [clean].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class RecordingEndpointTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tokens: RequestToken

    @AfterEach
    fun clean() = LearnerFixtures.deleteLearners(jdbc)

    private fun post(learnerId: Long, asLearner: Long, body: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/learners/$learnerId/attempts"))
                .header("Authorization", "Bearer ${tokens.issue(asLearner, Duration.ofMinutes(5))}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun recording(itemId: Long, conceptId: Long, delta: String) =
        """{"itemId":$itemId,"conceptId":$conceptId,"correct":true,"elapsedMs":1200,""" +
            """"at":"2026-08-10T00:00:00Z","scoreDelta":$delta}"""

    /** The fixture's ids, read back so the batch below references rows that exist. */
    private fun idsFor(externalRef: String): Pair<Long, Long> {
        val itemId = jdbc.queryForObject(
            "select i.id from item i join concept c on c.id = i.concept_primary_id where c.code = ?",
            Long::class.java, externalRef,
        )!!
        val conceptId = jdbc.queryForObject("select id from concept where code = ?", Long::class.java, externalRef)!!
        return itemId to conceptId
    }

    @Test
    fun `a batch with one out-of-band recording lands the other four and names every outcome`() {
        val (learner, _) = LearnerFixtures.seedLearner(jdbc, "post-batch")
        val (itemId, conceptId) = idsFor("${LearnerFixtures.PREFIX}post-batch")

        // The fixture's mastery starts at 0.500. Four increments of 0.100 stay inside the
        // 0..1 band; the third entry asks for 0.900 and would leave it, which is R14's
        // "one invalid recording in a batch of five" over a socket instead of a method call.
        val batch = listOf(
            recording(itemId, conceptId, "0.100"),
            recording(itemId, conceptId, "0.100"),
            recording(itemId, conceptId, "0.900"),
            recording(itemId, conceptId, "0.100"),
            recording(itemId, conceptId, "0.100"),
        ).joinToString(",", "[", "]")

        val response = post(learnerId = learner, asLearner = learner, body = batch)

        assertEquals(
            200,
            response.statusCode(),
            "one rejected recording is not a failed batch -- every recording was attempted, " +
                "which is what succeeded. A 4xx here is R14's defect restated at the " +
                "transport layer. Body: ${response.body()}",
        )
        assertEquals(
            4,
            Regex("\"outcome\":\"recorded\"").findAll(response.body()).count(),
            "four of the five were valid and all four must land. Body: ${response.body()}",
        )
        assertEquals(
            1,
            Regex("\"outcome\":\"rejected\"").findAll(response.body()).count(),
            "the invalid one must be reported as rejected rather than omitted -- a caller " +
                "that cannot tell a rejection from a missing entry is back where R14 started. " +
                "Body: ${response.body()}",
        )
        assertTrue(
            response.body().contains("\"index\":2"),
            "the rejection must name WHICH recording it was, or a retry is a guess. " +
                "Body: ${response.body()}",
        )

        // The rows, not the response. A body that says `recorded` over an empty table would
        // satisfy every assertion above.
        val attempts = jdbc.queryForObject(
            "select count(*) from attempt where learner_id = ?", Long::class.java, learner,
        )
        assertEquals(4L, attempts, "four recordings were reported as landed and this is the table")
    }

    @Test
    fun `one learner's token cannot record against another learner`() {
        val (alice, _) = LearnerFixtures.seedLearner(jdbc, "post-alice")
        val (bob, _) = LearnerFixtures.seedLearner(jdbc, "post-bob")
        val (itemId, conceptId) = idsFor("${LearnerFixtures.PREFIX}post-bob")

        val body = "[${recording(itemId, conceptId, "0.100")}]"

        assertEquals(
            200,
            post(learnerId = bob, asLearner = bob, body = body).statusCode(),
            "bob cannot record against bob, so the refusal below would prove nothing",
        )
        assertEquals(
            403,
            post(learnerId = bob, asLearner = alice, body = body).statusCode(),
            "alice's token wrote to bob's learner. R11 measured this on the READ path and " +
                "the whole point of AuthorisationRules is that the next endpoint does not " +
                "start unauthorised -- this is the next endpoint",
        )
        assertEquals(
            1L,
            jdbc.queryForObject("select count(*) from attempt where learner_id = ?", Long::class.java, bob),
            "the refused request must not have written anything. A 403 issued after the " +
                "write would be a status code, not an authorisation",
        )
    }
}
