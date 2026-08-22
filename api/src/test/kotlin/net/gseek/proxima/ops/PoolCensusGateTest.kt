package net.gseek.proxima.ops

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * **`R29`, `R30` and `R32`'s regression gate. Every assertion is a trip-wire on a framework
 * default that one of their findings rests on.**
 *
 * This is `DeploymentBoundaryGateTest`'s argument applied one layer up. That class holds down
 * the defaults `R23` and `R24` depend on; this one holds down the five pool sizes, and for the
 * same reason: **a finding about a default is only as durable as the default**, and a default
 * moves under a version bump nobody reads.
 *
 * ## Why this exists rather than a document
 *
 * Slice D's own discipline says *"every number states the size of all five pools"*. A rule of
 * that shape enforced by a person is `R17`'s subject — three failures in seven days, every one
 * caught by a human. So the five sizes are read from the running JVM by `PoolCensus` and
 * asserted here, and a report quoting a sixth number has something to check itself against.
 *
 * ## Nothing here is a duration
 *
 * `ADR-004` rule 2 forbids a CI assertion that is a duration. Every assertion below is a pool
 * size, a set membership or an inequality between two integers, none of which depends on how
 * fast the machine is. The timings that make these sizes interesting are in `R29` §3 and are
 * not repeated here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class PoolCensusGateTest {

    @Autowired private lateinit var census: PoolCensus

    /**
     * `R29` §3.1. ⭐ **The inequality the whole slice is about.**
     *
     * There are twenty times more web server workers than connections. A worker that cannot
     * get a connection is **alive and doing nothing**, and this repository measured the pool
     * twice — `R2` and `R18` — without ever writing down the number on the other side of it.
     */
    @Test
    fun `there are far more web server workers than there are connections`() {
        val sizes = census.take()
        println("R29 pool census\n${sizes.asReportBlock()}")

        val workers = assertNotNull(sizes.webServerMaxThreads, "the connector must be readable")
        val connections = assertNotNull(sizes.connectionPoolMax, "the pool must be readable")

        assertEquals(
            200, workers,
            "Tomcat's maxThreads default, READ OFF THE RUNNING CONNECTOR rather than off " +
                "application.yml -- which does not mention it, so the Environment cannot " +
                "answer and a reader would have to supply it from memory. If this moved, " +
                "R29's arms are describing a server that no longer exists",
        )
        assertEquals(
            10, connections,
            "HikariCP's default, unchanged since R2. measurement-discipline.md keeps it at " +
                "10 for every measurement unless a report says otherwise",
        )
        assertTrue(
            workers > connections,
            "THIS ASSERTION EXPECTS THE DEFECT, and it is a 20:1 ratio. Every worker beyond " +
                "the tenth can only ever wait. R29 §3 measures which of the three incidents " +
                "the client actually gets: a refusal, a timeout, or silently getting slower",
        )
    }

    /**
     * `R30` §3.1. **A maximum that cannot be reached, in this tree's real configuration.**
     *
     * Nothing sets `spring.task.execution.*` here, so these are the framework's numbers, and
     * the combination is the one `ThreadPoolBoundsTest` shows the mechanism for: an unbounded
     * queue never rejects, so the executor never grows past core and the maximum is decoration.
     */
    @Test
    fun `the task executor's maximum is unreachable because its queue has no bound`() {
        val sizes = census.take()

        val core = assertNotNull(sizes.taskExecutorCore)
        val max = assertNotNull(sizes.taskExecutorMax)
        val queue = assertNotNull(sizes.taskExecutorQueueCapacity)

        assertEquals(8, core, "spring.task.execution.pool.core-size, read from the bean")
        assertEquals(
            Int.MAX_VALUE, max,
            "THIS ASSERTION EXPECTS THE DEFECT. The maximum is 2147483647 -- and it would be " +
                "no more reachable if it read 200, which is the point R30 makes: `max` and " +
                "`queue-capacity` are ONE setting and this tree has never chosen either",
        )
        assertEquals(
            Int.MAX_VALUE, queue,
            "and the queue is unbounded, which is what makes the line above unreachable. " +
                "ThreadPoolExecutor grows past core only when the queue REFUSES a task",
        )
    }

    /**
     * `R32` §3.1. **The fourth pool is sized by the box and named in no file.**
     *
     * `ForkJoinPool.commonPool()` exists in every JVM whether or not anybody uses it. Nothing
     * in this repository configures it, and `R23` already found this JVM taking a number from
     * a cgroup that the host would not have given it — so this size is not stable across the
     * container boundary `R23` and `R24` measured.
     */
    @Test
    fun `the common pool is sized by the processor count and configured nowhere`() {
        val sizes = census.take()

        assertNull(
            sizes.commonPoolParallelismProperty,
            "java.util.concurrent.ForkJoinPool.common.parallelism is unset, so the reading " +
                "below is the JVM's default and not a decision this repository made",
        )
        assertEquals(
            sizes.availableProcessors - 1, sizes.commonPoolParallelism,
            "availableProcessors - 1, because the submitting thread is expected to help -- " +
                "SharedPoolTest measures it doing so. R32 §3.1",
        )
        assertNull(
            sizes.carrierPoolParallelismProperty,
            "and the fifth pool, the virtual-thread carrier scheduler, is unconfigured too. " +
                "R33 §3 measures what turning virtual threads on does to the other four",
        )
    }
}
