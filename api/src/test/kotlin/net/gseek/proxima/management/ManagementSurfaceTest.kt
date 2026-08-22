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
 * `T9`, first strand — **what `management.endpoints.web.exposure.include: "*"` actually puts
 * on the network.**
 *
 * ## The finding: the roadmap's premise is half wrong on this version
 *
 * `docs/roadmap.md` describes this trap as *management endpoints exposed wholesale, including
 * the one that dumps the heap and everything that was in it.* Measured on Spring Boot 4.1.0,
 * the second clause does not follow from the first. **Exposure and access are two separate
 * gates and `include: "*"` opens only one of them.**
 *
 * From the framework's own `spring-configuration-metadata.json`, not from documentation —
 * and from **all fifteen** endpoints that carry an `access` property, not from a sample of two:
 *
 * ```
 * management.endpoint.heapdump.access       defaultValue: none
 * management.endpoint.shutdown.access       defaultValue: none
 * the other thirteen                        defaultValue: unrestricted
 * ```
 *
 * Thirteen of those fifteen default to `unrestricted` and appear the moment exposure is
 * widened. **`heapdump` and `shutdown` default to `none` and stay 404.** Opening `heapdump`
 * takes a second, separate, deliberate line — which is what [HeapDumpContentTest] supplies, in
 * its own context, so that what the second line is protecting can be measured rather than
 * asserted.
 *
 * > **This KDoc said *"`heapdump` alone defaults to `none`"* until 2026-08-22, and it was wrong
 * > from the day it was written.** `R10` §3.2 withdrew the same sentence on that day, after
 * > reading every `management.endpoint.*.access` default out of the jar rather than the two
 * > lines sampled above: fifteen properties, of which **two** default to `none`. Spring Boot's
 * > own reference for this line names both — *"By default, access to all endpoints except for
 * > `shutdown` and `heapdump` is unrestricted"*.
 * >
 * > **The report was corrected and this comment was not**, so for part of 2026-08-22 the
 * > repository said two different things about itself. That gap is `R43`'s subject one axis
 * > over: CHECK 5 does read comments, but only for index denial, so nothing here could see it.
 *
 * **This class asserts both 404s**, which makes it a trip-wire as much as a measurement: if a
 * future Boot changes either default, this goes red and says so.
 *
 * ## The `red` state is a property, not a commit
 *
 * `application.yml` already restricts the surface to three ids — four when this was written,
 * until `8762453` dropped `prometheus` the same evening. Reverting it to wide-open and
 * pushing that would put a live defect in the history of a public repository for the sake of
 * a strand whose whole content is *what wide-open exposes*. `ManagementSurfaceGateTest` is
 * the `green` half and asserts the shipped surface stays narrow.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.endpoints.web.exposure.include=*"],
)
@Import(TestcontainersConfiguration::class)
class ManagementSurfaceTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /**
     * **`loggers` is the one on this list that is not an information leak.**
     *
     * The others answer questions. This one takes instructions: a `POST` sets a logger's
     * level at runtime. An attacker who can reach it can turn on `DEBUG` for the SQL logger
     * and read every statement and bound parameter the application executes from then on,
     * through whatever ships the logs.
     *
     * That sentence was in this file as a claim before it was a measurement. It is measured
     * here: write a level, read it back, and put it back the way it was.
     */
    private fun measureWhetherLoggersIsWritable() {
        val target = "net.gseek.proxima.t9probe"
        fun levelOf(): String? =
            Regex("\"configuredLevel\"\\s*:\\s*\"?(\\w+)\"?")
                .find(get("/actuator/loggers/$target").body())?.groupValues?.get(1)

        val before = levelOf()
        val write = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/loggers/$target"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"configuredLevel":"TRACE"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val after = levelOf()

        // Put it back, so this measurement does not leave the logger reconfigured.
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/loggers/$target"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"configuredLevel":null}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        println("  loggers is a control surface, not a view:")
        println("    POST /actuator/loggers/$target  -> ${write.statusCode()}")
        println("    configuredLevel before          -> $before")
        println("    configuredLevel after           -> $after")
        println("    restored to                     -> ${levelOf()}")
    }

    /**
     * Everything `include: "*"` puts on the network, listed by asking rather than by
     * recalling what Actuator ships.
     */
    @Test
    fun `what a wide-open management surface actually exposes`() {
        val index = get("/actuator")
        val ids = Regex("\"(\\w+)\":\\{\"href\"").findAll(index.body())
            .map { it.groupValues[1] }
            .filterNot { it == "self" }
            .distinct()
            .sorted()
            .toList()

        val notable = listOf("heapdump", "shutdown", "threaddump", "env", "configprops", "loggers", "mappings", "beans")
        val rows = notable.map { id ->
            val r = get("/actuator/$id")
            id to r.statusCode()
        }

        // `prometheus` is probed because whether this build HAS such an endpoint is a separate
        // question from whether it is exposed, and it is asked here rather than assumed from
        // the fact that micrometer-registry-prometheus is on the classpath.
        //
        // This comment opened "application.yml exposes `prometheus`" until 2026-08-22, and
        // that stopped being true on 2026-08-13 at 20:40 -- 8762453 dropped the id from the
        // exposure list BECAUSE this probe returned 404 with the surface wide open, so the
        // line had never done anything. R10 §3.6. The probe outlived the sentence that
        // justified it by nine days, still returning the finding that removed it.
        val prometheus = get("/actuator/prometheus").statusCode()

        println()
        println("T9-SURFACE >>> management.endpoints.web.exposure.include = *")
        println("  endpoints listed by /actuator (${ids.size}):")
        println("    " + ids.joinToString(", "))
        println("  the ones that matter:")
        rows.forEach { (id, code) ->
            println("    %-12s %d  %s".format(id, code, if (code == 200) "reachable" else "NOT reachable"))
        }
        println("    %-12s %d  %s".format("prometheus", prometheus, if (prometheus == 200) "reachable" else "NOT reachable"))
        println()

        assertTrue(ids.isNotEmpty(), "the index returned nothing, so this test measured nothing")

        measureWhetherLoggersIsWritable()

        // BOTH endpoints whose access defaults to `none`, asserted together.
        //
        // Only `heapdump` was asserted here until 2026-08-22, and the KDoc above called it
        // the only one. `shutdown` is the second, and it was absent from the id list this
        // very test prints -- so the evidence was already in this test's own output and the
        // assertion beside it counted one. R10 §8 records what this 404 does NOT establish:
        // HeapDumpContentTest proves heapdump's 404 is access rather than absence by opening
        // it, and nothing here does the same for shutdown.
        listOf("heapdump", "shutdown").forEach { id ->
            val code = rows.toMap().getValue(id)
            assertEquals(
                404,
                code,
                "the $id endpoint answered $code with only the EXPOSURE widened. It is " +
                    "supposed to need `management.endpoint.$id.access` as well, which is one " +
                    "of exactly two access defaults that are `none` on Spring Boot 4.1.0 -- " +
                    "the other thirteen of fifteen are `unrestricted`. Boot removes an " +
                    "inaccessible endpoint from the application context entirely, which is " +
                    "why this is 404 rather than 403. If that default has changed, T9's " +
                    "first strand changes with it -- see docs/reports/R10 §3.2 and " +
                    "HeapDumpContentTest",
            )
        }
    }
}
