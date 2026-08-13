package net.gseek.proxima.security

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `T9`'s second strand, `green` and its regression gate — **the path must name the caller.**
 *
 * ## Why this gate needs two assertions and not one
 *
 * "A cross-learner request is refused" passes trivially on an application that refuses
 * everything: a broken filter, a wrong url pattern, a 500 on every request all satisfy it.
 * So the caller's *own* data must come back, in the same configuration, with the same
 * fixture. **The two assertions are one claim** — the endpoint distinguishes, rather than
 * declines.
 *
 * `R10` §7 needed the same shape for the management surface: eleven endpoints closed **and**
 * `health` open, or the gate is passing over an application with no actuator at all.
 *
 * ## No property override, deliberately
 *
 * The annotations match `ConnectionHoldingGateTest`'s and `ManagementSurfaceGateTest`'s, so
 * Spring's context cache serves all three from one application context. The shipped default
 * is `authorisation: owner`, which is exactly what a gate should be asserting: **what
 * production does**, not what a test asked for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class AuthorisationGateTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tokens: RequestToken

    @AfterEach
    fun clean() = LearnerFixtures.deleteLearners(jdbc)

    private fun get(learnerId: Long, asLearner: Long): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(
                URI.create("http://localhost:$port/api/v1/learners/$learnerId/recommendations?limit=10"),
            )
                .header("Authorization", "Bearer ${tokens.issue(asLearner, Duration.ofMinutes(5))}")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the shipped configuration refuses one learner's token for another learner's data`() {
        val (alice, aliceItem) = LearnerFixtures.seedLearner(jdbc, "gate-alice")
        val (bob, _) = LearnerFixtures.seedLearner(jdbc, "gate-bob")

        val own = get(learnerId = alice, asLearner = alice)
        val other = get(learnerId = bob, asLearner = alice)

        assertEquals(
            200,
            own.statusCode(),
            "a learner cannot read their own recommendations, so this gate is asserting " +
                "that the endpoint is broken rather than that it authorises. Body: ${own.body()}",
        )
        assertTrue(
            own.body().contains(aliceItem),
            "the fixture returned no recommendation, so the 200 above proves nothing. " +
                "Body: ${own.body()}",
        )
        assertEquals(
            403,
            other.statusCode(),
            "one learner's token read another learner's recommendations. " +
                "proxima.security.authorisation is supposed to be `owner` -- see " +
                "docs/reports/R11 §3, where the `none` arm returns 200 and the other " +
                "learner's item code in the body. Body: ${other.body()}",
        )
    }
}
