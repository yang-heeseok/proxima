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
 * `T9`'s second strand, `red` — **an endpoint that authenticates and does not authorise.**
 *
 * ## What makes this different from having no security at all
 *
 * Before `T9`, `GET /api/v1/learners/{learnerId}/recommendations` served anyone. That is not
 * this trap; it is the flat absence underneath it, and nobody who saw it would call it
 * secure.
 *
 * The trap is what this class measures: **a token is required, it is verified, an invalid one
 * is refused — and the endpoint still hands over any learner's data to any caller.** The
 * request is authenticated. The `401`s below prove it. And the answer is somebody else's.
 *
 * That configuration looks defended from every angle a reviewer usually checks. There is a
 * filter. There is a signature. There are refusals. The missing line is a comparison between
 * two numbers the application already has in its hand.
 *
 * ## Why the refusals are in this class and not only in the gate
 *
 * `assertEquals(200, ...)` on a cross-learner request proves the data leaked **only if
 * authentication is actually happening**. If the filter were misconfigured — wrong url
 * pattern, unregistered bean, order wrong — every request would sail through and this test
 * would report a leak while measuring an application with no authentication at all. The two
 * `401` assertions are the control that separates those.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["proxima.security.authorisation=none"],
)
@Import(TestcontainersConfiguration::class)
class CrossLearnerAccessTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var tokens: RequestToken

    @AfterEach
    fun clean() = LearnerFixtures.deleteLearners(jdbc)

    private fun get(learnerId: Long, authorization: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(
            URI.create("http://localhost:$port/api/v1/learners/$learnerId/recommendations?limit=10"),
        ).GET()
        authorization?.let { builder.header("Authorization", it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun tokenFor(learnerId: Long) =
        "Bearer ${tokens.issue(learnerId, Duration.ofMinutes(5))}"

    /**
     * The control. Without these two, the measurement below cannot tell a leak from an
     * application that never checked anything.
     */
    @Test
    fun `the endpoint does refuse callers it cannot identify`() {
        val (learnerId, _) = LearnerFixtures.seedLearner(jdbc, "control")

        val noToken = get(learnerId, null)
        val forged = get(learnerId, tokenFor(learnerId).dropLast(4) + "AAAA")

        println()
        println("T9-AUTHN >>> the filter is doing its job")
        println("  no Authorization header : ${noToken.statusCode()}  ${noToken.body()}")
        println("  tampered signature      : ${forged.statusCode()}  ${forged.body()}")
        println()

        assertEquals(401, noToken.statusCode(), "an unauthenticated request was served")
        assertTrue(noToken.body().contains("missing-token"), "wrong refusal reason: ${noToken.body()}")
        assertEquals(401, forged.statusCode(), "a forged signature was accepted")
        assertTrue(forged.body().contains("bad-signature"), "wrong refusal reason: ${forged.body()}")
    }

    /**
     * The measurement. One authenticated learner, another learner's data.
     *
     * The assertion is on the **item code**, not the status. A `200` carrying `[]` would be
     * indistinguishable from a `200` carrying a leak, and this repository has already shipped
     * one test that proved nothing because it asserted the wrong scope (`R8` §3.1).
     */
    @Test
    fun `an authenticated learner reads another learner's recommendations`() {
        val (alice, aliceItem) = LearnerFixtures.seedLearner(jdbc, "alice")
        val (bob, bobItem) = LearnerFixtures.seedLearner(jdbc, "bob")

        val ownData = get(alice, tokenFor(alice))
        val someoneElsesData = get(bob, tokenFor(alice))

        println()
        println("T9-IDOR >>> proxima.security.authorisation = none")
        println("  alice's token -> alice's learner id : ${ownData.statusCode()}")
        println("  alice's token -> bob's learner id   : ${someoneElsesData.statusCode()}")
        println("  and the body carries bob's item     : ${someoneElsesData.body().contains(bobItem)}")
        println()

        assertTrue(
            ownData.body().contains(aliceItem),
            "the fixture did not produce a recommendation, so nothing below is measurable. " +
                "Body: ${ownData.body()}",
        )
        assertEquals(
            200,
            someoneElsesData.statusCode(),
            "the red arm refused a cross-learner request, so this is no longer the red arm",
        )
        assertTrue(
            someoneElsesData.body().contains(bobItem),
            "alice's request for bob's recommendations returned 200 but not bob's data, so " +
                "what leaked is unproven. Body: ${someoneElsesData.body()}",
        )
    }
}
