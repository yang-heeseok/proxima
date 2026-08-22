package net.gseek.proxima

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The database every test in this module runs against.
 *
 * **PostgreSQL, not H2 — and this now has numbers behind it rather than a good argument.**
 * `docs/reports/R9` ran this repository's own migrations and 23 of its own statements against
 * both. H2 cannot create the first table of `V1`. Eleven statements refuse loudly, and one
 * disagrees **silently**: after a constraint violation, PostgreSQL refuses every statement
 * until rollback and H2 does not — which is the entire mechanism of `R7`. On H2 that report's
 * naive remedy would have passed.
 *
 * **What the image also decides, discovered late.** This image is built against musl, so its
 * declared `en_US.utf8` collation sorts byte-wise: `Apple,Banana,apple,cherry`. Naming a
 * collation explicitly gives `apple,Apple,Banana,cherry`. `R25` measured how far that reaches
 * — 4,461 of 4,465 two-character ASCII pairs re-order under glibc — and also measured that
 * **the set it applies to in this repository is empty**: no `order by` on a `varchar` column
 * exists outside `R9` §3.3's own probe. `R26` prices the alternative.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    companion object {

        /**
         * **Pinned by digest, not by tag, and `OPEN-10` is the decision.**
         *
         * It used to read `postgres:16-alpine`. A tag is a name somebody can move, and this
         * one moved on 2026-08-13 — from a July build of PostgreSQL 16.14 to an August build
         * of 16.15 — while nothing in this tree changed. `R27` found it and measured what it
         * cost: twelve facts compared across the two containers, **three differ and all three
         * are the same fact** (the version string); migrations apply and ordering holds
         * identically on both. What it also found is the part that made this worth fixing:
         * `build.yml` has no image cache, so **every CI runner since that date pulled 16.15
         * while this machine's Docker cache still held July's image.** Local and CI were
         * running different servers and every environment block described the local one.
         *
         * `measurement-discipline.md` already said the digest is *"what makes the row
         * citable"*. It recorded one and nothing in the build ever used it. This line is where
         * the record and the run meet, and a deliberate bump is now a commit.
         *
         * **THIS IS THE INDEX DIGEST, AND SAYING SO IS NOT PEDANTRY.**
         *
         *   `postgres:16-alpine` is multi-architecture: the tag names an OCI **image index**,
         *   and the index lists one manifest per platform. So *"the digest of this image"* has
         *   two true answers — the index (`cf78e766…`, what `docker images --digests` reports)
         *   and the `linux/amd64` manifest inside it (`075f7ba6…`).
         *
         *   `R27` §3 printed both and labelled its own figure `linux/amd64`. The integration
         *   session then read one number out of one handoff and the other out of another,
         *   concluded the tag had moved twice, and published that — **with the answer already
         *   printed in a report it had merged an hour earlier.** `.study` 12장 §6.3 carries
         *   the correction. The index is the right level to pin because it is what the tag
         *   points at and what the recorded July digest was, so the two are comparable.
         *
         * Resolved 2026-08-22 with `docker buildx imagetools inspect postgres:16-alpine`.
         * PostgreSQL 16.15, alpine 3.24.1, musl 1.2.6-r2 — the same alpine and musl as the
         * July image, which is why `R25` and `R26` are unaffected by the move.
         */
        const val POSTGRES_IMAGE =
            "postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"

        /**
         * The tag the digest above was resolved from.
         *
         * Kept as a constant rather than a comment because a check can read a constant. `R27`
         * §5 rejected a drift guard on the ground that it would be **red on arrival** — the tag
         * had already moved away from the recorded digest, and a check nobody can make green
         * gets disabled. That objection is spent the moment the pin above is correct: the two
         * agree today, so a guard comparing them starts green and goes red only when somebody
         * else's registry push moves the tag.
         */
        const val POSTGRES_TAG = "postgres:16-alpine"
    }
}
