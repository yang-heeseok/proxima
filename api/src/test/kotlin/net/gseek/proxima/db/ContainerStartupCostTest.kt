package net.gseek.proxima.db

import java.io.File
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration.Companion.POSTGRES_IMAGE
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.TestcontainersConfiguration

/**
 * `T8`, second strand — **what a real database costs to start, and what reuse takes off it.**
 *
 * The argument for H2 is not correctness, because nobody claims H2 is more correct. It is
 * speed: a container is slow, an in-memory database is instant, and the price of the truth
 * measured in [H2DivergenceTest] is paid on every run. So the price gets measured too, and
 * the trade is stated with both numbers in it rather than with one.
 *
 * **What is measured here is one container start, not a CI run.** The roadmap asked for "CI
 * time before and after", and the honest answer needs a distinction that phrasing hides:
 * Testcontainers reuse keeps a container alive *between JVM runs on the same machine*. A CI
 * job starts on a fresh runner with no containers on it, so there is nothing to reuse and
 * the second number would be the first. That is a finding about the feature, and it is
 * recorded in the report rather than being demonstrated by pushing a commit to watch a
 * number not move.
 *
 * The user's `~/.testcontainers.properties` is written to and **restored**, because reuse is
 * a machine-level opt-in and this measurement is not entitled to leave it switched on.
 */
class ContainerStartupCostTest {

    private fun startOnce(reuse: Boolean): Long {
        val c = PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)).withReuse(reuse)
        val t0 = System.nanoTime()
        c.start()
        val ms = (System.nanoTime() - t0) / 1_000_000
        // A reused container must stay alive; that is the whole mechanism.
        if (!reuse) c.stop()
        return ms
    }

    @Test
    fun `what a container start costs, and what reuse takes off it`() {
        val cold = (1..3).map { startOnce(reuse = false) }

        val props = File(System.getProperty("user.home"), ".testcontainers.properties")
        val original: String? = if (props.exists()) props.readText() else null

        val reused: List<Long>
        try {
            TestcontainersConfiguration.getInstance()
                .updateUserConfig("testcontainers.reuse.enable", "true")

            // The first of these pays for a container; every one after it should find the
            // one already running. If they are all the same, reuse is not working and the
            // number below is not a measurement of reuse.
            val first = startOnce(reuse = true)
            reused = listOf(first) + (1..2).map { startOnce(reuse = true) }
        } finally {
            TestcontainersConfiguration.getInstance()
                .updateUserConfig("testcontainers.reuse.enable", "false")
            if (original == null) props.delete() else props.writeText(original)
        }

        fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

        println()
        println("T8-STARTUP >>>")
        println("  image                        : $POSTGRES_IMAGE (already pulled; no download in these numbers)")
        println("  cold start, 3 runs (ms)      : $cold   median ${median(cold)}")
        println("  with reuse, 3 runs (ms)      : $reused   median ${median(reused.drop(1))} after the first")
        println("  NOTE: the first reuse run still creates the container. Only runs 2..3 are the saving.")
        println()

        assertTrue(cold.all { it > 0 }, "a start that took no time was not measured")
    }
}
