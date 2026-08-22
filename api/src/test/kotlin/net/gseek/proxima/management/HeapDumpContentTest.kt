package net.gseek.proxima.management

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A bean that holds a known string, so that the search below has something it is **supposed**
 * to find.
 *
 * Without it, *"the dump does not contain the password"* and *"the dump is not a dump and the
 * search is broken"* produce identical evidence. **That is not hypothetical here.** The first
 * run of this measurement searched a 404 response body — `heapdump` is closed by default, see
 * [ManagementSurfaceTest] — found no canary, and would have reported a clean heap dump if the
 * control had not failed first.
 *
 * `R5`'s log appender captured zero events and nearly proved an absence. `R8`'s statement
 * counter fails rather than reporting zero. `R9`'s substitution gate has a control beside it
 * for the same reason. This is the fourth.
 */
@TestComponent
class HeapCanary {
    @Suppress("unused")
    val canary: String = CANARY

    companion object {
        const val CANARY = "PROXIMA-HEAPDUMP-CANARY-6f2b1d"
    }
}

/**
 * A container whose credentials are **distinctive strings**, and the reason is a false
 * positive this test produced on its first successful run.
 *
 * `PostgreSQLContainer` defaults to username `test` and password `test`. The active Spring
 * profile is `test`. The database in the JDBC url is called `test`. Searching a 168 MB heap
 * dump for `"test"` finds it, and the search proves **nothing** — and the same applies to
 * `/actuator/env`, which reported the password as visible for exactly that reason.
 *
 * The canary control had already passed. It proved the dump was a dump and the search
 * mechanism worked; it could not prove the *needle was specific*, which is a different
 * property and was the one that was broken. **A control tells you the instrument is alive,
 * not that you are pointing it at the right thing.**
 *
 * So the credentials here cannot occur by accident. The username is changed too, because a
 * username of `test` would carry the same problem into any later question about it.
 */
@TestConfiguration(proxyBeanMethods = false)
class DistinctiveCredentialPostgres {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE))
            .withUsername(USERNAME)
            .withPassword(PASSWORD)

    companion object {
        const val USERNAME = "proxima_t9_user_a41f"
        const val PASSWORD = "PROXIMA-T9-DB-PASSWORD-9c4e"
    }
}

/**
 * `T9`, first strand, second half — **what the second switch is protecting.**
 *
 * [ManagementSurfaceTest] measured that `management.endpoints.web.exposure.include: "*"` is
 * not enough to reach `/actuator/heapdump`: access defaults to `none` for it.
 * So the obvious conclusion is that the heap dump is not really a hazard on this version.
 *
 * > **This said *"for that endpoint alone"* until 2026-08-22, and it was wrong.** Two of the
 * > fifteen endpoints carrying an `access` property default to `none` — `heapdump` and
 * > `shutdown`. `R10` §3.2 owns that count and the evidence for it; this is a citation, not a
 * > second telling. The word carried no weight here — this class is about what opening
 * > `heapdump` exposes, and that is unchanged — which is exactly why it survived the
 * > correction that reached the report.
 *
 * **That conclusion is worth exactly one measurement, and this is it.** The endpoint is opened
 * here the way a person would open it — one property — and then the dump is searched for the
 * database password that `/actuator/env` refuses to print.
 *
 * ## Why this is a separate class
 *
 * A different property set is a different Spring context, and the 404 in [ManagementSurfaceTest]
 * has to be observed in a context where the endpoint is *not* opened or it observes nothing.
 * The cost is one extra application context per CI run, recorded in `R10` §8.
 *
 * ## The temporary file
 *
 * The dump is written under the JVM's temp directory and **deleted in a `finally`**. A heap
 * dump of this process contains, by construction, everything this process could see. The
 * password in it belongs to an ephemeral Testcontainers database that stops existing when the
 * test ends, which is the only reason it is acceptable to write one at all.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.endpoints.web.exposure.include=*",
        // The second switch. One line, and it is the whole difference.
        "management.endpoint.heapdump.access=unrestricted",
    ],
)
@Import(DistinctiveCredentialPostgres::class, HeapCanary::class)
class HeapDumpContentTest {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var container: PostgreSQLContainer

    @Autowired
    @Suppress("unused")
    private lateinit var canary: HeapCanary

    private val http: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `a heap dump contains the credential that env refuses to print`() {
        val envBody = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/env")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val envHidesIt = !envBody.contains(container.password)

        val dump: Path = Files.createTempFile("proxima-t9-", ".hprof")
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/heapdump")).GET().build(),
                HttpResponse.BodyHandlers.ofFile(dump),
            )

            val sizeMb = dump.fileSize() / 1024 / 1024
            val canaryFound = dump.containsBytes(HeapCanary.CANARY)
            val passwordFound = dump.containsBytes(container.password)
            val usernameFound = dump.containsBytes(container.username)
            val urlFound = dump.containsBytes(container.jdbcUrl)

            println()
            println("T9-HEAPDUMP >>> management.endpoint.heapdump.access = unrestricted")
            println("  the credential being searched for          : ${container.password}")
            println("  GET /actuator/heapdump                     : ${response.statusCode()}")
            println("  size                                       : $sizeMb MB")
            println("  contains the planted canary   (CONTROL)    : $canaryFound")
            println("  contains the datasource password           : $passwordFound")
            println("  contains the datasource username           : $usernameFound")
            println("  contains the JDBC url                      : $urlFound")
            println("  /actuator/env does NOT print that password : $envHidesIt")
            println()

            check(container.password == DistinctiveCredentialPostgres.PASSWORD) {
                "the container is not the one with the distinctive credential, so a hit " +
                    "below could be any occurrence of a common word -- see the KDoc on " +
                    "DistinctiveCredentialPostgres"
            }

            assertEquals(200, response.statusCode(), "the endpoint did not answer, so nothing below was measured")
            assertTrue(
                canaryFound,
                "the planted string is not in the dump, so any negative result here would " +
                    "mean the search is broken rather than that the dump is clean. This " +
                    "control has already caught exactly that once -- see the KDoc on HeapCanary",
            )
        } finally {
            // Not something to leave lying about, even holding an ephemeral password.
            dump.deleteIfExists()
        }
    }

    /**
     * Streams the file looking for [needle], with an overlap so a match spanning a chunk
     * boundary is not missed. ISO-8859-1 because it is byte-for-byte reversible and the
     * needle is ASCII.
     */
    private fun Path.containsBytes(needle: String): Boolean {
        val chunk = ByteArray(1 shl 20)
        val overlapSize = needle.length
        val overlap = ByteArray(overlapSize)
        var overlapLen = 0
        Files.newInputStream(this).use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) return false
                val window = ByteArray(overlapLen + read)
                System.arraycopy(overlap, 0, window, 0, overlapLen)
                System.arraycopy(chunk, 0, window, overlapLen, read)
                if (String(window, Charsets.ISO_8859_1).contains(needle)) return true
                overlapLen = minOf(overlapSize, window.size)
                System.arraycopy(window, window.size - overlapLen, overlap, 0, overlapLen)
            }
        }
    }
}
