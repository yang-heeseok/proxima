package net.gseek.proxima.ops

import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.ForkJoinPool
import javax.sql.DataSource
import org.apache.coyote.AbstractProtocol
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.tomcat.TomcatWebServer
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

/**
 * **The five pools, counted — because a number that names one of them is not reproducible.**
 *
 * `R2` and `R18` both measured the connection pool, and both looked at it from the database
 * side. Neither could have said how many web server worker threads existed while it did, and
 * neither knew that three further pools were in the process the whole time. This class is the
 * answer to *"the size of all five pools"*, produced from the running JVM rather than from
 * `application.yml` — the two are not the same thing and the difference is `R30`'s subject.
 *
 * ```
 *   [web server workers]  →  [connection pool]  →  DB
 *            ↘ @Async               → [applicationTaskExecutor]
 *            ↘ parallelStream       → [ForkJoinPool.commonPool]
 *            ↘ virtual threads      → [the carrier pool underneath]
 * ```
 *
 * ## Every field is nullable and `null` is a finding, not a gap
 *
 * `null` means **this pool is not observable in this configuration**, and that state is the
 * one `R33` is about: switching the web server to virtual threads does not make the worker
 * pool bigger, it makes it *stop existing* — and with it every gauge that was watching it.
 * A census that reported `0` there would be claiming a measurement it did not take, so it
 * reports `null` and the report writes `미측정` beside it.
 *
 * ## Not wired into any request path
 *
 * Nothing calls this while serving traffic. It is a measurement fixture in the sense
 * `RequestToken`'s KDoc uses the phrase, and `ADR-009` is why it is not an endpoint: the
 * numbers it produces belong to a test and to a report, and exposing them over HTTP would be
 * a control surface this repository decided not to grow.
 */
@Component
class PoolCensus(
    private val context: ApplicationContext,
    private val dataSource: ObjectProvider<DataSource>,
    private val taskExecutor: ObjectProvider<ThreadPoolTaskExecutor>,
) {

    fun take(): PoolSizes {
        val hikari = dataSource.ifAvailable as? HikariDataSource
        val executor = taskExecutor.ifAvailable

        return PoolSizes(
            webServerMaxThreads = webServerMaxThreads(),
            webServerMinSpareThreads = webServerMinSpareThreads(),
            connectionPoolMax = hikari?.maximumPoolSize,
            connectionPoolMin = hikari?.minimumIdle,
            taskExecutorCore = executor?.corePoolSize,
            taskExecutorMax = executor?.maxPoolSize,
            taskExecutorQueueCapacity = executor?.queueCapacity,
            commonPoolParallelism = ForkJoinPool.commonPool().parallelism,
            commonPoolParallelismProperty =
                System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            carrierPoolParallelismProperty =
                System.getProperty("jdk.virtualThreadScheduler.parallelism"),
        )
    }

    /**
     * Read off the running connector, not off the `Environment`.
     *
     * **The `Environment` cannot answer this and answering from it would be the defect.**
     * `server.tomcat.threads.max` is unset in this repository, so `getProperty` returns
     * `null` and a reader would have to supply Tomcat's default from memory — which rule 9
     * of the shared preamble forbids and which `R30` is partly about. The connector knows the
     * number it is actually running with.
     */
    private fun webServerMaxThreads(): Int? = protocolHandler()?.maxThreads

    private fun webServerMinSpareThreads(): Int? = protocolHandler()?.minSpareThreads

    private fun protocolHandler(): AbstractProtocol<*>? {
        val server = (context as? WebServerApplicationContext)?.webServer as? TomcatWebServer
        return server?.tomcat?.connector?.protocolHandler as? AbstractProtocol<*>
    }
}

/**
 * One census. Every field that a report quotes has to come from here rather than from a
 * document, because `application.yml` states three of these eleven numbers and the JVM
 * decides the rest.
 */
data class PoolSizes(
    val webServerMaxThreads: Int?,
    val webServerMinSpareThreads: Int?,
    val connectionPoolMax: Int?,
    val connectionPoolMin: Int?,
    val taskExecutorCore: Int?,
    val taskExecutorMax: Int?,
    val taskExecutorQueueCapacity: Int?,
    val commonPoolParallelism: Int,
    val commonPoolParallelismProperty: String?,
    val availableProcessors: Int,
    val carrierPoolParallelismProperty: String?,
) {

    /** The block a report pastes. One line per pool, `미측정` where the pool is not observable. */
    fun asReportBlock(): String = buildString {
        appendLine("  1 web server workers   : max=${n(webServerMaxThreads)} minSpare=${n(webServerMinSpareThreads)}")
        appendLine("  2 connection pool      : max=${n(connectionPoolMax)} minIdle=${n(connectionPoolMin)}")
        appendLine("  3 applicationTaskExecutor: core=${n(taskExecutorCore)} max=${n(taskExecutorMax)} queueCapacity=${n(taskExecutorQueueCapacity)}")
        appendLine("  4 ForkJoinPool.commonPool: parallelism=$commonPoolParallelism (availableProcessors=$availableProcessors, property=${s(commonPoolParallelismProperty)})")
        append("  5 virtual thread carriers : property=${s(carrierPoolParallelismProperty)}")
    }

    private fun n(v: Int?): String = v?.toString() ?: "미측정"

    private fun s(v: String?): String = v ?: "unset"
}
