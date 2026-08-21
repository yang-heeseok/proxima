package net.gseek.proxima.ops

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/**
 * **`green`. `R23` §3.1 and §3.2 — the trap that a container-aware JDK holds shut, and the
 * one flag that re-arms it.**
 *
 * ## What the `red` commit asserted, and what it got
 *
 * `docs/explanation/measurement-discipline.md` corrected `-Xmx512m` out of its environment
 * block because nothing in the tree set it, and left the belief underneath untouched: that a
 * container given 512 MB is a JVM with a 512 MB heap. `4e8b117` asserted that belief:
 *
 * ```
 * a 512 MB container was given this much heap. [...] ==> expected: <536870912> but was: <134217728>
 * ```
 *
 * 134217728 is 128 MiB — a quarter of the limit, which is `MaxRAMPercentage` at its default of
 * 25. **The JVM reads the cgroup.** That is the whole reason the classic form of this trap —
 * *the container is killed because the JVM never heard about the limit* — does not reproduce
 * on Temurin 21.0.12+8, and it is why this class asserts the ratio rather than a byte count:
 * the ratio is a property of the JVM, the byte count is a property of the machine.
 *
 * ## The part that is still a trap, and it is one line wide
 *
 * Container-awareness is a **default**, and every heap flag anybody writes overrides it. The
 * second test below measures what that costs, and the answer is not *a slower JVM*:
 *
 * | heap ceiling | who refuses when the workload exceeds it |
 * | --- | --- |
 * | ergonomic — 25 % of the limit | **the JVM.** `OutOfMemoryError: Java heap space`, exit `1`, the container stays up |
 * | `-Xmx` at or above the limit | **the kernel.** `SIGKILL`, exit `137`, no stack trace, no log line, the process is simply gone |
 *
 * **The flag does not change how much memory is available. It changes who tells you.** A JVM
 * that throws can write a heap dump, flush a log, fail a health check and be drained; a JVM
 * that is `SIGKILL`ed does none of those, and the only artefact left is a number in
 * `docker inspect`. `-Xmx512m` in a 512 MB container — the exact pairing the discipline
 * document printed — is on the second row: `R23` §3.2 finds it survives 440 MiB of live data
 * and is killed at 460.
 *
 * ## Why the JVM under test is bind-mounted rather than pulled
 *
 * `eclipse-temurin` on Docker Hub has **no `21.0.12` tag** — queried 2026-08-21, the tag list
 * stops at `21.0.11_10`. Pulling the nearest published image would put a different JVM build
 * inside the container from the one every other number in this repository was taken with, and
 * `measurement-discipline.md` rule 3 is exactly about that. So the container gets
 * `System.getProperty("java.home")` — the toolchain JDK the test itself is running on — read
 * only, over a bind mount.
 *
 * **This requires the Docker daemon to share a filesystem namespace with the test JVM.** It
 * does here: the engine is native inside WSL2 and the whole build lane runs there. On Docker
 * Desktop the bind would resolve against a different root and the container would not start.
 *
 * ## What this asserts in CI, and what it only prints
 *
 * `ADR-004` forbids a CI assertion that is a duration. Nothing here is one. The heap ceiling
 * is a fixed fraction of a limit this test sets itself, and the two exit codes are decided by
 * whether a fixed number of megabytes fits inside a fixed cgroup — both are as true on a
 * runner as on the machine in the environment block. The **byte counts** are printed rather
 * than asserted, because 128 MiB is a fact about a 512 MB container and not about this one.
 */
class ContainerHeapErgonomicsTest {

    /**
     * A container with a hard memory ceiling and nothing running in it, so that every
     * measurement below is an `exec` into a known cgroup rather than a fresh container whose
     * start cost would sit inside the number.
     *
     * **`withMemorySwap` is set equal to `withMemory` on purpose.** Docker's default when only
     * `--memory` is given is to allow swap up to twice the limit, and a JVM that swaps is a
     * JVM that does not die — the limit would be enforced by nothing observable and this test
     * would report that the trap does not exist when what actually happened is that it was
     * paid for in page faults. `/sys/fs/cgroup/memory.swap.max` reads `0` under this setting
     * and the tests below check it, so the control is measured rather than trusted.
     */
    private fun memoryLimited(bytes: Long): GenericContainer<*> =
        MemoryLimitedContainer(DockerImageName.parse(BASE_IMAGE))
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!.withMemory(bytes).withMemorySwap(bytes)
            }
            .withFileSystemBind(System.getProperty("java.home"), "/jdk", BindMode.READ_ONLY)
            .withCopyToContainer(Transferable.of(HOLD_SOURCE), "/src/Hold.java")
            .withCommand("sleep", "infinity")

    /** `-XX:+PrintFlagsFinal` is the JVM stating its own ergonomics; nothing here derives it. */
    private fun GenericContainer<*>.maxHeapBytes(vararg flags: String): Long {
        val out = execInContainer(
            "/jdk/bin/java", *flags, "-XX:+PrintFlagsFinal", "-version",
        ).stdout
        val line = out.lineSequence().first { it.contains(" MaxHeapSize ") }
        // "   size_t MaxHeapSize   = 134217728   {product} {ergonomic}" -- the value is the
        // first token after the `=`. Taken by position rather than by pattern, so that this
        // line reads next to the verbatim output it parses.
        return line.substringAfter("=").trim().substringBefore(" ").toLong()
    }

    private fun GenericContainer<*>.cgroup(file: String): String =
        execInContainer("cat", "/sys/fs/cgroup/$file").stdout.trim()

    /** The kernel's own count of how many processes it has killed in this cgroup. */
    private fun GenericContainer<*>.oomKills(): Int =
        cgroup("memory.events").lineSequence()
            .first { it.startsWith("oom_kill ") }
            .substringAfter(' ').trim().toInt()

    @Test
    fun `the JVM reads the cgroup, so the heap ceiling is a fraction of the container limit`() {
        for (limit in listOf(LIMIT_512MB, LIMIT_1GB, LIMIT_2GB)) {
            memoryLimited(limit).use { jvm ->
                jvm.start()

                // The cgroup's own view, read from inside, so the limit under test is the one
                // the kernel is enforcing rather than the one this test asked for.
                assertEquals(limit.toString(), jvm.cgroup("memory.max"), "container limit")
                assertEquals("0", jvm.cgroup("memory.swap.max"), "swap must be off or the limit is soft")

                val ergonomic = jvm.maxHeapBytes()
                println("limit ${limit shr 20} MiB -> MaxHeapSize $ergonomic (${ergonomic shr 20} MiB)")

                assertEquals(
                    limit / 4,
                    ergonomic,
                    "MaxRAMPercentage defaults to 25 and UseContainerSupport defaults to true, " +
                        "so the heap ceiling is a quarter of the CONTAINER limit. If this fails, " +
                        "either a default moved or the JVM stopped reading the cgroup -- and " +
                        "every container-limit number in R23 is taken under the other behaviour",
                )

                // The control. Without it, "the heap tracks the limit" would also be satisfied
                // by a JVM that ignores the cgroup on a machine whose RAM happens to be 4x the
                // limit -- and 512 MB x 4 is 2 GiB, which is a plausible laptop.
                val unaware = jvm.maxHeapBytes("-XX:-UseContainerSupport")
                assertTrue(
                    unaware > limit,
                    "with container support off the ceiling must come from the HOST, and on " +
                        "this machine that is larger than any limit here. Got $unaware against " +
                        "a limit of $limit -- if these are equal the control proves nothing",
                )
                println("limit ${limit shr 20} MiB -> MaxHeapSize with -XX:-UseContainerSupport $unaware (${unaware shr 20} MiB)")
            }
        }
    }

    @Test
    fun `who refuses first when the workload outgrows the container`() {
        memoryLimited(LIMIT_512MB).use { jvm ->
            jvm.start()
            val killsBefore = jvm.oomKills()

            // (a) The shipped default. 400 MiB of live data against a 128 MiB ceiling.
            val ergonomic = jvm.execInContainer("/jdk/bin/java", "/src/Hold.java", "400")
            println("ergonomic heap, 400 MiB live -> exit ${ergonomic.exitCode}")
            assertEquals(
                1,
                ergonomic.exitCode,
                "the JVM should refuse this itself. Exit 137 here would mean the kernel got " +
                    "there first, which is the failure this default exists to prevent",
            )
            assertTrue(
                (ergonomic.stdout + ergonomic.stderr).contains("java.lang.OutOfMemoryError: Java heap space"),
                "an exit of 1 with no OutOfMemoryError is a different failure and must not " +
                    "be read as this one",
            )
            assertEquals(
                killsBefore,
                jvm.oomKills(),
                "the kernel must not have killed anything. This is the assertion that " +
                    "separates 'the JVM refused' from 'the JVM died and something logged a " +
                    "plausible-looking exception on the way out'",
            )

            // (b) The one line that re-arms it: a heap flag at the container's own size, which
            // is the pairing measurement-discipline.md printed. 480 MiB of live data now fits
            // inside the heap and does not fit inside the container.
            val flagged = jvm.execInContainer("/jdk/bin/java", "-Xmx512m", "/src/Hold.java", "480")
            println("-Xmx512m, 480 MiB live -> exit ${flagged.exitCode}")
            assertEquals(
                137,
                flagged.exitCode,
                "128 + 9: SIGKILL. The kernel refuses, not the JVM, and 137 is the whole " +
                    "artefact -- there is no stack trace and no log line to find later",
            )
            assertTrue(
                !(flagged.stdout + flagged.stderr).contains("OutOfMemoryError"),
                "a SIGKILLed JVM cannot report anything, and if it did, this test would be " +
                    "measuring something other than an out-of-memory kill",
            )
            assertEquals(
                killsBefore + 1,
                jvm.oomKills(),
                "the kernel's own counter is the evidence. An exit code can be produced by " +
                    "anything; /sys/fs/cgroup/memory.events is the kernel saying it did this",
            )
        }
    }

    private class MemoryLimitedContainer(image: DockerImageName) :
        GenericContainer<MemoryLimitedContainer>(image)

    private companion object {
        /**
         * Plain glibc userland and nothing else. The image is a place to put a cgroup and a
         * bind mount; it contributes no JVM, no libc surprise of the kind `R9` §3.3 found in
         * `postgres:16-alpine`, and no init system that could reap the process under test.
         */
        const val BASE_IMAGE = "ubuntu:24.04"

        const val LIMIT_512MB = 512L * 1024 * 1024
        const val LIMIT_1GB = 1024L * 1024 * 1024
        const val LIMIT_2GB = 2048L * 1024 * 1024

        /**
         * Holds N MiB and does not let go, so the JVM cannot collect its way out.
         *
         * **Both ends of every array are written.** A `new byte[1MiB]` is zeroed by the JVM on
         * allocation so the pages are already resident, but touching them explicitly is what
         * makes that a property of this fixture rather than of an implementation detail — a
         * runtime that ever hands out lazily-zeroed arrays would turn this measurement into a
         * measurement of virtual address space, which no cgroup limits.
         *
         * Run through `java /src/Hold.java` — JEP 330 single-file source launch — so there is
         * no compile step inside the container and nothing to keep in sync with a build.
         */
        val HOLD_SOURCE = """
            import java.util.ArrayList;
            import java.util.List;

            public class Hold {
                public static void main(String[] args) {
                    long mib = Long.parseLong(args[0]);
                    List<byte[]> held = new ArrayList<>();
                    for (long i = 0; i < mib; i++) {
                        byte[] block = new byte[1024 * 1024];
                        block[0] = 1;
                        block[block.length - 1] = 1;
                        held.add(block);
                    }
                    System.out.println("SURVIVED holding " + mib + " MiB, maxHeap="
                        + (Runtime.getRuntime().maxMemory() >> 20) + " MiB");
                }
            }
        """.trimIndent()
    }
}
