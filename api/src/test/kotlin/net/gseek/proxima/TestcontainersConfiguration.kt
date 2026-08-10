package net.gseek.proxima

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The database every test in this module runs against.
 *
 * **PostgreSQL, not H2, and the tag is pinned.** `T8` in the roadmap is an entire report
 * about what an in-memory database does not tell you -- upsert syntax, types,
 * collation-dependent ordering, reserved words, and identifier generation. A test lane
 * built on H2 would make that report unwritable and would quietly weaken every other one.
 *
 * The tag is `16-alpine` and not `latest` because it appears in the measurement
 * environment block of every report in this repository. `latest` is not a version; it is
 * a promise to change silently, and a number taken against it cannot be reproduced.
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
