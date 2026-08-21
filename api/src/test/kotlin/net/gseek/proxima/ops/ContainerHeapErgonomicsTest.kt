package net.gseek.proxima.ops

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * **`red`.** The belief this repository's own measurement discipline printed as fact.
 *
 * `docs/explanation/measurement-discipline.md` carries this correction, made before the
 * first measurement:
 *
 * > **It said `-Xmx512m`.** Nothing sets that. The heap flag is 미측정 as a property of these
 * > runs, and the field is left out until a report actually pins it — a stated JVM flag that
 * > no run used is worse than no flag, because it looks checkable and is not.
 *
 * The correction removed the flag and left the belief underneath it untouched: that a
 * container given 512 MB is a JVM with a 512 MB heap, so writing `-Xmx512m` beside a 512 MB
 * limit is the harmless act of writing down what was going to happen anyway.
 *
 * **This test asserts that belief so that the number which refutes it is in the history**,
 * rather than being asserted in a report nobody can re-run. It is expected to fail. The
 * green commit replaces the assertion with what was measured.
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
 */
class ContainerHeapErgonomicsTest {

    /**
     * A container with a hard memory ceiling and nothing running in it, so that every
     * measurement below is an `exec` into a known cgroup rather than a fresh container whose
     * start cost would sit inside the number.
     *
     * **`withMemorySwap` is set equal to `withMemory` on purpose.** Docker's default when only
     * `--memory` is given is to allow swap up to twice the limit, and a JVM that swaps is a
     * JVM that does not die — the limit would be enforced by nothing observable and the
     * measurement would say the trap does not exist when what happened is that it was paid for
     * in page faults.
     */
    private fun memoryLimited(bytes: Long): GenericContainer<*> =
        MemoryLimitedContainer(DockerImageName.parse(BASE_IMAGE))
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig!!.withMemory(bytes).withMemorySwap(bytes)
            }
            .withFileSystemBind(System.getProperty("java.home"), "/jdk", BindMode.READ_ONLY)
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

    @Test
    fun `a container limited to 512 MB runs a JVM whose heap is 512 MB`() {
        memoryLimited(LIMIT_512MB).use { jvm ->
            jvm.start()

            // The cgroup's own view, read from inside, so that the limit under test is the
            // one the kernel is enforcing rather than the one this test asked for.
            val cgroupMax = jvm.execInContainer("cat", "/sys/fs/cgroup/memory.max").stdout.trim()
            assertEquals(LIMIT_512MB.toString(), cgroupMax, "the container did not get the limit")

            assertEquals(
                LIMIT_512MB,
                jvm.maxHeapBytes(),
                "a 512 MB container was given this much heap. If this fails, the sentence " +
                    "`-Xmx512m` in measurement-discipline.md was not merely unsourced -- it " +
                    "was describing a JVM that does not exist, and the report has to say " +
                    "what does",
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
    }
}
