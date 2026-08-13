package net.gseek.proxima.security

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
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
 * `T9`'s third strand, regression gate — **the shipped application refuses an expired token.**
 *
 * ## Why this exists when `TokenExpiryTest` already measures expiry
 *
 * That test builds [RequestToken] directly and proves the arithmetic. It says nothing about
 * whether the **shipped configuration** selects a policy that uses it. `proxima.security.expiry-policy`
 * has an arm — `ignored` — under which every token is valid forever, and switching to it is a
 * one-word edit that breaks no unit test at all.
 *
 * So this asks the running application, over HTTP, with the configuration that ships. `R4` §7
 * again: assert the effect, because asserting the property proves only that a string in a file
 * is the string in that file.
 *
 * ## No property override
 *
 * Same annotations as `ConnectionHoldingGateTest`, `ManagementSurfaceGateTest` and
 * `AuthorisationGateTest`, so all four share one cached application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class TokenExpiryGateTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tokens: RequestToken

    @AfterEach
    fun clean() = LearnerFixtures.deleteLearners(jdbc)

    private fun get(learnerId: Long, token: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(
                URI.create("http://localhost:$port/api/v1/learners/$learnerId/recommendations?limit=10"),
            )
                .header("Authorization", "Bearer $token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `an expired token is refused, and a current one is not`() {
        val (learnerId, itemCode) = LearnerFixtures.seedLearner(jdbc, "expiry")

        // Expired an hour ago -- far outside any skew tolerance a sane deployment would set.
        val stale = tokens.issue(
            subject = learnerId,
            validFor = Duration.ofSeconds(1),
            issuedAt = Instant.now().minus(Duration.ofHours(1)),
        )
        val current = tokens.issue(learnerId, Duration.ofMinutes(5))

        val refused = get(learnerId, stale)
        val served = get(learnerId, current)

        println()
        println("T9-EXPIRY-GATE >>> the shipped configuration")
        println("  token that expired an hour ago : ${refused.statusCode()}  ${refused.body()}")
        println("  token minted just now          : ${served.statusCode()}")
        println()

        assertEquals(
            401,
            refused.statusCode(),
            "an expired token was served. proxima.security.expiry-policy is supposed to be " +
                "`skewed`; under `ignored` a signature is checked and the timestamps are not, " +
                "which means no token in this system can ever be outlived. See R11 §3 and " +
                "TokenExpiryTest. Body: ${refused.body()}",
        )
        assertTrue(
            refused.body().contains("expired"),
            "the token was refused for the wrong reason, so this gate is passing on an " +
                "accident. Body: ${refused.body()}",
        )

        // The control: without it, an application that refuses everything passes the above.
        assertEquals(
            200,
            served.statusCode(),
            "a current token was also refused, so the assertion above is not about expiry. " +
                "Body: ${served.body()}",
        )
        assertTrue(
            served.body().contains(itemCode),
            "the fixture returned no recommendation, so the 200 proves nothing. " +
                "Body: ${served.body()}",
        )
    }
}
