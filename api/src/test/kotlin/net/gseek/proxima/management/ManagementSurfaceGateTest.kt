package net.gseek.proxima.management

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * `T9`'s first regression gate — **the shipped management surface stays narrow.**
 *
 * ## Why this is asserted over HTTP and not against the property
 *
 * `application.yml` lists four endpoint ids. Reading that list back in a test would assert
 * that a string in a file is the string in that file. This repository lost six days to
 * exactly that failure — a test-scoped `application.yml` shadowed the main one and nothing
 * noticed, because every gate asserted settings rather than effects (`R4` §7). So this asks
 * the running application over the network what it will answer.
 *
 * ## No properties, deliberately
 *
 * The annotations here are identical to `ConnectionHoldingGateTest`'s, so Spring's context
 * cache serves both from one application context. A `properties = [...]` override would make
 * this a second context and cost a full startup to assert something about the configuration
 * that ships — which is the configuration every other test already has.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class ManagementSurfaceGateTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private fun status(path: String): Int =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode()

    /**
     * The endpoints that leak, one way or another, are unreachable on the shipped
     * configuration.
     *
     * `env` and `configprops` print configuration; `beans`, `mappings` and `conditions`
     * describe the application's shape to anyone who asks; `threaddump` shows what it is
     * doing.
     *
     * `loggers` is different in kind, and [ManagementSurfaceTest] measures it rather than
     * asserting it: a `POST` changes a logger's level at runtime, so an exposed one is not an
     * information leak but a **control surface** — enough to turn on SQL logging and read
     * every statement and bound parameter from then on.
     *
     * `heapdump` is measured separately in [HeapDumpContentTest], where it hands over the
     * datasource password that `/actuator/env` masks. It is listed here because *its access
     * default happening to be closed* is not a reason for this configuration to stop saying
     * so itself.
     */
    @Test
    fun `nothing that leaks is reachable on the shipped configuration`() {
        val shouldBeClosed = listOf(
            "beans", "conditions", "configprops", "env", "flyway",
            "heapdump", "loggers", "mappings", "sbom", "scheduledtasks", "threaddump",
        )

        val open = shouldBeClosed.filter { status("/actuator/$it") == 200 }

        assertTrue(
            open.isEmpty(),
            "these management endpoints answered on the shipped configuration: $open. " +
                "application.yml is supposed to list four ids and nothing else. " +
                "docs/reports/R10 measured what a wide-open surface exposes -- including a " +
                "heap dump containing the datasource password in plain bytes",
        )
    }

    /**
     * The control for the test above, and the reason it is not vacuous.
     *
     * If the management surface were broken, missing, or mapped somewhere else entirely,
     * every path would 404 and the assertion above would pass over an application with no
     * actuator at all. Something the configuration **does** expose has to answer.
     */
    @Test
    fun `the health endpoint answers, so the assertion above is about exposure`() {
        assertEquals(
            200,
            status("/actuator/health"),
            "health is one of the four ids application.yml exposes. If it does not answer, " +
                "the surface is not narrow -- it is absent, and the gate beside this test " +
                "is passing over nothing",
        )
    }

    /**
     * `application.yml` exposes `prometheus`. Whether this build *has* that endpoint is a
     * different question, asked rather than assumed.
     *
     * Recorded rather than asserted: an exposure list naming an endpoint that does not exist
     * is harmless, and finding out is the point.
     */
    @Test
    fun `what the exposure list names and what actually answers`() {
        val exposed = listOf("health", "info", "metrics", "prometheus")
        println()
        println("T9-SHIPPED >>> application.yml exposes: ${exposed.joinToString(", ")}")
        exposed.forEach { println("    %-12s %d".format(it, status("/actuator/$it"))) }
        println()
    }
}
