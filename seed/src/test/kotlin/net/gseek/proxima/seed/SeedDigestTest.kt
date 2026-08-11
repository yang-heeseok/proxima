package net.gseek.proxima.seed

import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals

/**
 * The dataset is byte-identical **on a different machine**, not merely on this one.
 *
 * ## Why `GeneratorTest` was not enough
 *
 * `GeneratorTest` asserts that two runs produce the same bytes. That is determinism, and it
 * is necessary — but it is satisfied perfectly by a generator that is deterministic *per
 * machine* and different everywhere else. Run it on a second machine and it passes there
 * too, while producing a different dataset.
 *
 * `PUB-7`'s whole argument rests on the stronger property. No rows are published here, so
 * the only thing that lets a reader check a number in `docs/reports/` is that running the
 * generator gives them **the dataset those numbers were taken against**. A benchmark
 * against data you cannot obtain is an anecdote, and a generator that is merely
 * self-consistent does not hand anyone that data.
 *
 * Pinning the digests turns a green CI run into evidence of exactly that: these values were
 * produced on the machine in `docs/explanation/measurement-discipline.md`, and CI is a
 * different machine, a different filesystem, and a different JDK build.
 *
 * ## What a failure here means
 *
 * It means one of two things, and **this test cannot tell you which**:
 *
 * 1. **The generator changed.** Then every number already published in `docs/reports/` was
 *    taken against a dataset that no longer exists. The pins are not simply updated — the
 *    reports are re-measured or re-baselined, per measurement rule 3.
 * 2. **The platform changed the output.** Then `PUB-7`'s reproducibility claim is false and
 *    the cause has to be found: a locale leaking into number formatting, a line separator,
 *    a JDK whose `java.util.Random` no longer matches its specification, a filesystem
 *    encoding.
 *
 * Both are serious. Neither is fixed by editing the expected values until the test passes.
 */
class SeedDigestTest {

    @Test
    fun `the tiny dataset hashes to the pinned values`(@TempDir dir: Path) {
        assertDigests(Scale.TINY, TINY, dir)
    }

    /**
     * The scale every published number is taken at — 3,963,719 rows, ~174 MB written.
     *
     * It is slower than the rest of this module's tests put together and it runs anyway.
     * The cheap version of this test would pin only the tiny scale, which would leave the
     * property that actually matters — that a reader reproduces **the reports' dataset** —
     * resting on the assumption that what holds at 40 concepts holds at 3,000.
     */
    @Test
    fun `the full dataset hashes to the pinned values`(@TempDir dir: Path) {
        assertDigests(Scale.FULL, FULL, dir)
    }

    private fun assertDigests(scale: Scale, expected: Map<String, String>, dir: Path) {
        Generator(scale).generateAll(dir)

        val actual = expected.keys.sorted().associateWith { table ->
            sha256(dir.resolve("$table.tsv"))
        }

        assertEquals(
            expected.toSortedMap(), actual.toSortedMap(),
            "the generated dataset does not match the pinned digests. Either the generator " +
                "changed -- in which case every number in docs/reports/ was taken against a " +
                "dataset that no longer exists -- or this platform produces different bytes, " +
                "in which case PUB-7's reproducibility claim is false. Do not edit these " +
                "values until it passes",
        )
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (stream.read(buffer) != -1) {
                    // DigestInputStream updates the digest as a side effect of reading.
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {

        /**
         * Produced on 2026-08-11 by `seed generate`, on the machine recorded in
         * `docs/explanation/measurement-discipline.md`, at seed value [SEED_VALUE].
         */
        val TINY = mapOf(
            "attempt" to "dc912ef4c3163bc50e141b4e2ea64ce17c0fb94cdbbab38faddf5efc8e09d58e",
            "concept" to "ce032ce0b361877858e59c7d452fb681ec0a8a4ffef10dafa2de7cb01919e078",
            "concept_edge" to "c3f7c3ab35c89ec6f0fe9a1e47eabbc6edb05d47a4a6291fa3a0972a4050586e",
            "item" to "55e7bd9fa1cf9c7b9ab0c8afac7f5afd85fcea0ce09dd633707f597e97f8eded",
            "item_concept" to "8f4b3612d9d8ff2a2961a9d64a80476fab9ada43f9ea3d21e8e51ff665307f44",
            "learner" to "39ad5b7716f9c7bec121f559d75bc482a46a31d883cb9f51d86ddf1dee6b137c",
            "mastery" to "50b8008457d519d55e8fe5acdef93468bb3bbf0c7c144f3035123a1e1de6cd86",
        )

        val FULL = mapOf(
            "attempt" to "7f58cf2424613c86ce3d5452545ff70860605d83bc9b58ea5ee9329d16a6a84f",
            "concept" to "454a5b995bccfafa6cc2f5da5164103cf5b4575d6741435e9341c4f29992e1ab",
            "concept_edge" to "150db5491215f63a2918be8d4d1e77394538bcb703d1ff692a1a1ff3595edd3b",
            "item" to "17fa7c12fc8b83d19321f3c0217e7feea5eb0e13863e74c5c0d9fcf2015c682a",
            "item_concept" to "1d8232f343ddd4e5a4d70bcf885dd6bb74771cb340d3fc224a07c5b86a3ec3e0",
            "learner" to "2ce4c28fee9f74bc3ad4b3b7befb49c7a8a656469ba031e86f50a2fd178b16d7",
            "mastery" to "3b7beefa0850c1e14d17bbd17d295b45eeb964165e5564e106c4e42ce05e331e",
        )
    }
}
