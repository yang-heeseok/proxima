package net.gseek.proxima.collation

import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * `R27` — the digest that makes a row citable, and the tag that does the pulling.
 *
 * ## What `measurement-discipline.md` says, and what it does
 *
 * The environment block records the image twice:
 *
 * ```
 *   PostgreSQL     : postgres:16-alpine — server 16.14
 *                    sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
 * ```
 *
 * and the document explains why both lines are there:
 *
 * > The image digest is recorded alongside the tag because `16-alpine` is a moving tag. Two
 * > people running `postgres:16-alpine` a month apart are not necessarily running the same
 * > server, and the digest is what makes the row citable.
 *
 * **`TestcontainersConfiguration.kt` pins the tag.** The digest appears in eleven environment
 * blocks and in no artefact. So the document predicted the hazard, named the remedy, and the
 * remedy was never wired to anything — which is `PUB-4`'s failure shape and `R17`'s subject,
 * met in the document that owns the rule.
 *
 * ## What this class measures
 *
 * Two containers, both `postgres:16-alpine`, both pinned by digest:
 *
 * | arm | digest | what it is |
 * | --- | --- | --- |
 * | **RECORDED** | `sha256:57c72fd2…` | the image every number in this repository was taken on |
 * | **TODAY** | `sha256:075f7ba6…` | what `postgres:16-alpine` resolves to on 2026-08-21 |
 *
 * Nothing is asserted. Every difference between the two is a finding, and the absence of a
 * difference would be one too — it would mean the moving tag has so far moved harmlessly,
 * which is a fact about eleven days and not about the mechanism.
 *
 * ## Why there is no gate here
 *
 * The gate is four lines and it belongs in `TestcontainersConfiguration.kt`, which this slice
 * may not edit. `R27` §7 says so rather than writing a gate somewhere it does not belong.
 */
class ImageTagDriftTest {

    companion object {

        /** The digest `measurement-discipline.md` records. Also `R25`'s arm A. */
        const val RECORDED = CollationDivergenceTest.MUSL_DIGEST

        /**
         * What `postgres:16-alpine` resolved to on 2026-08-21, read from the registry:
         *
         * ```
         * $ docker buildx imagetools inspect postgres:16-alpine
         * Name:      docker.io/library/postgres:16-alpine
         * MediaType: application/vnd.oci.image.index.v1+json
         * Digest:    sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
         * Manifests:
         *   Name:        docker.io/library/postgres:16-alpine@sha256:075f7ba6…
         *   Platform:    linux/amd64
         * ```
         */
        const val TODAY = "postgres@sha256:075f7ba66bc9b3ce7d6b8b635208ff61cd7cf1a67d71ec530eec5d7ae0cbe571"

        private lateinit var recorded: PostgreSQLContainer
        private lateinit var today: PostgreSQLContainer

        @BeforeAll
        @JvmStatic
        fun start() {
            recorded = container(RECORDED)
            today = container(TODAY)
            recorded.start()
            today.start()
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            if (::recorded.isInitialized) recorded.stop()
            if (::today.isInitialized) today.stop()
        }

        private fun container(ref: String) =
            PostgreSQLContainer(DockerImageName.parse(ref).asCompatibleSubstituteFor("postgres"))
    }

    private enum class Arm(val label: String) { RECORDED("RECORDED"), TODAY("TODAY   ") }

    private fun connection(arm: Arm): Connection {
        val c = when (arm) { Arm.RECORDED -> recorded; Arm.TODAY -> today }
        return DriverManager.getConnection(c.jdbcUrl, c.username, c.password)
    }

    @Test
    fun `what the two images the same tag has named differ in`() {
        println("\n=== R27 §3  postgres:16-alpine, eleven days apart ===")
        val facts = linkedMapOf(
            "version()" to "select version()",
            "server_version" to "show server_version",
            "server_version_num" to "show server_version_num",
            "datcollate" to "select datcollate from pg_database where datname = current_database()",
            "datlocprovider" to "select datlocprovider from pg_database where datname = current_database()",
            "icu collations" to "select count(*)::text from pg_collation where collprovider = 'i'",
            "libc collations" to "select count(*)::text from pg_collation where collprovider = 'c'",
            // `lc_collate` is deliberately absent: it stopped being a GUC in PostgreSQL 16
            // (`unrecognized configuration parameter`), which is itself a small reminder that
            // "the same server, a patch level apart" is a claim and not a default.
            "shared_buffers" to "show shared_buffers",
            "work_mem" to "show work_mem",
            "max_connections" to "show max_connections",
            "random_page_cost" to "show random_page_cost",
            "max_parallel_workers_per_gather" to "show max_parallel_workers_per_gather",
        )
        val read = mutableMapOf<Arm, MutableMap<String, String>>()
        for (arm in Arm.entries) {
            val m = mutableMapOf<String, String>()
            connection(arm).use { c ->
                for ((k, sql) in facts) {
                    m[k] = c.createStatement().use { st ->
                        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else "?" }
                    }
                }
            }
            read[arm] = m
        }
        var differing = 0
        for (k in facts.keys) {
            val a = read[Arm.RECORDED]!![k]
            val b = read[Arm.TODAY]!![k]
            val mark = if (a == b) "   " else ">>>"
            if (a != b) differing++
            println("$mark %-32s RECORDED=%s".format(k, a))
            if (a != b) println("    %-32s TODAY   =%s".format("", b))
        }
        println("facts compared                      : ${facts.size}")
        println("facts that differ                   : $differing")

        // And the thing every report in this repository actually depends on: do the
        // migrations still apply, and does the ordering behaviour still hold?
        println("\n--- the migrations, and R9 §3.3's four strings, on both ---")
        for (arm in Arm.entries) {
            val c = when (arm) { Arm.RECORDED -> recorded; Arm.TODAY -> today }
            val result = runCatching {
                Flyway.configure()
                    .dataSource(c.jdbcUrl, c.username, c.password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                    .migrationsExecuted
            }
            println("${arm.label} | migrations applied: ${result.getOrElse { "FAILED: ${it.message?.lineSequence()?.first()}" }}")
            connection(arm).use { conn ->
                conn.createStatement().use { st ->
                    st.execute("drop table if exists r27_probe")
                    st.execute("create table r27_probe (v varchar(64) not null)")
                    st.execute("insert into r27_probe (v) values ('cherry'),('Apple'),('apple'),('Banana')")
                    val out = mutableListOf<String>()
                    st.executeQuery("select v from r27_probe order by v").use { rs ->
                        while (rs.next()) out.add(rs.getString(1))
                    }
                    println("${arm.label} | order by v        : ${out.joinToString(",")}")
                }
            }
        }
    }
}
