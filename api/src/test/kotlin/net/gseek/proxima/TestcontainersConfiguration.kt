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
 * The tag is `16-alpine` and not `latest` because it appears in the measurement
 * environment block of every report in this repository. `latest` is not a version; it is
 * a promise to change silently, and a number taken against it cannot be reproduced.
 *
 * **What the tag also decides, discovered late.** This image is built against musl, so its
 * declared `en_US.utf8` collation sorts byte-wise: `Apple,Banana,apple,cherry`. Naming a
 * collation explicitly gives `apple,Apple,Banana,cherry`. Every ordering-dependent number in
 * this repository was taken under the first behaviour. `R9` §3.3 and §8.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    companion object {
        const val POSTGRES_IMAGE = "postgres:16-alpine"
    }
}
