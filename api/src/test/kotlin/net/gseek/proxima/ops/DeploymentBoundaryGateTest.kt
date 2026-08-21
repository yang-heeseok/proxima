package net.gseek.proxima.ops

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.Shutdown
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

/**
 * **`R23` and `R24`'s regression gate, and every assertion in it is a trip-wire on a
 * framework default that one of their findings rests on.**
 *
 * Three of this slice's four traps do not reproduce, and in each case what holds them shut is
 * a **default** rather than anything this repository wrote:
 *
 * | Finding | What holds it | Where |
 * | --- | --- | --- |
 * | The container is not OOM-killed | `UseContainerSupport=true`, `MaxRAMPercentage=25` | `ContainerHeapErgonomicsTest` |
 * | A deployment does not cut an in-flight request | `server.shutdown=graceful` | here |
 * | `db` is not in the readiness group | `AvailabilityProbesHealthEndpointGroups` | here |
 * | `pool × instances` fits under `max_connections` | the pool is left at HikariCP's 10 | here |
 *
 * **A finding that a defect is absent is only as durable as the thing making it absent**, and
 * a default can move under a version bump nobody reads. `R5`, `R9` and `R10` are three earlier
 * reports in this repository whose subject is a framework that had already fixed the trap; the
 * roadmap keeps their rows rather than deleting them, and this class is the same argument in
 * executable form. If any of these goes red, the corresponding section of `R23` or `R24` is
 * describing a system that no longer exists.
 *
 * ## Why this is not four classes
 *
 * The annotations match `AuthorisationGateTest`'s and `ConnectionHoldingGateTest`'s, so
 * Spring's context cache serves all of them from **one** application context. Splitting these
 * four assertions across four classes with the same annotations would cost nothing; splitting
 * them across four classes with *different* ones would cost a context each, and `R9` §3.6
 * measured what a context costs here.
 *
 * ## What is asserted, and what is only printed
 *
 * `ADR-004` rule 2 forbids a CI assertion that is a duration. Nothing here is one — every
 * assertion is a default's value, a set membership, or an inequality between two integers
 * neither of which depends on how fast the machine is. The measured **timings** that make
 * these defaults interesting live in `R24` §3.2 and §3.3 and are not repeated here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class DeploymentBoundaryGateTest {

    @Autowired private lateinit var groups: HealthEndpointGroups
    @Autowired private lateinit var environment: Environment
    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * `R24` §3.2. **The split exists and the database is on neither side of it.**
     *
     * This is the assertion most likely to be read as backwards, so it says why in its own
     * message: `db` being absent from `readiness` is the **defect** `R24` measured — readiness
     * answering `200` while every request 500s — and this gate exists to notice if the default
     * changes, not to endorse it. The remedy is a deployment-time property
     * (`management.endpoint.health.group.readiness.include`), measured in `R24` §3.2's boundary
     * arm, and it deliberately does not ship: turning it on changes what `/actuator/health`
     * costs under a probe storm, and `R24` §5 argues why that is a decision for whoever
     * operates the fleet rather than a default for a repository that operates none.
     */
    @Test
    fun `the availability probes exist and neither of them looks at the database`() {
        assertEquals(
            setOf("liveness", "readiness"),
            groups.names,
            "management.endpoint.health.probes.enabled defaults to true on Boot 4.1.0 and " +
                "these are the groups it creates. If the set changed, R24 §3.2's whole " +
                "premise moved",
        )

        val liveness = groups.get("liveness")!!
        val readiness = groups.get("readiness")!!

        assertTrue(liveness.isMember("livenessState"), "liveness must contain the state it is named for")
        assertTrue(readiness.isMember("readinessState"), "readiness must contain the state it is named for")

        assertFalse(
            readiness.isMember("db"),
            "THIS ASSERTION EXPECTS THE DEFECT. R24 §3.2 measured /actuator/health/readiness " +
                "answering 200 in 3ms while every request answered 500, because the readiness " +
                "group holds readinessState and nothing else. If this goes red the default " +
                "moved and that report's boundary arm is describing a fix that shipped itself",
        )
        assertFalse(
            liveness.isMember("db"),
            "a database outage must not make an instance look DEAD, only unable to serve -- " +
                "an orchestrator restarts on a failed liveness probe, so `db` here would turn " +
                "one blip into a fleet-wide restart on top of a fleet-wide drain",
        )

        // The primary group -- what plain /actuator/health aggregates -- DOES see it, and that
        // is the other half of the incident rather than the reassuring half.
        assertTrue(
            groups.primary.isMember("db"),
            "/actuator/health includes db, which is why R24 §3.2 measures it answering 503 " +
                "in 30s and taking every instance out together",
        )
    }

    /**
     * `R24` §3.3, arm A. **The in-flight request survives because of this value and nothing
     * else.**
     *
     * `server.shutdown` was `immediate` before Boot 3, which is what most deployment
     * documentation still assumes and what arm B measures: 2163 of 4000 recordings landed and
     * the client got an empty reply. Arm A landed all 4000 and got its outcome list. **The
     * only difference between them is this enum.**
     */
    @Test
    fun `the web server shuts down gracefully, and the framework waits longer than the platform does`() {
        assertEquals(
            Shutdown.GRACEFUL,
            environment.getProperty("server.shutdown", Shutdown::class.java, Shutdown.GRACEFUL),
            "R24 §3.3 arm A only completes its in-flight request because shutdown is " +
                "graceful. Arm B is the same run with this set to immediate and it loses " +
                "1837 of 4000 recordings",
        )

        val phase = environment.getProperty(
            "spring.lifecycle.timeout-per-shutdown-phase", Duration::class.java, Duration.ofSeconds(30),
        )
        assertEquals(
            Duration.ofSeconds(30),
            phase,
            "the framework's patience. It is not the binding one",
        )
        assertTrue(
            phase > DOCKER_STOP_DEFAULT_GRACE,
            "THE POINT OF THIS ASSERTION IS THAT IT PASSES AND IS BAD NEWS. Spring waits " +
                "$phase for an in-flight request; `docker stop` sends SIGKILL after " +
                "$DOCKER_STOP_DEFAULT_GRACE. Both are defaults and neither knows the other " +
                "exists, so the framework's guarantee is unreachable whenever a request needs " +
                "more than the platform's patience -- R24 §3.3 arm E: 6942 of 8000 landed, " +
                "exit 137 from a `docker stop`, and a `Commencing graceful shutdown` line " +
                "with no completion line after it",
        )
    }

    /**
     * `R24` §3.1. **The arithmetic, checked against the database that is actually running.**
     *
     * `R2` and `R18` both sized this pool on one instance. The number that breaks is
     * `pool × instances`, and no property of the application knows the instance count — so
     * this gate names it. [PLANNED_MAX_INSTANCES] is a **deployment fact this repository
     * records nowhere else**, and stating it here has the property
     * `AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE` was written for: a
     * genuinely larger fleet fails this rule, and whoever wants one has to say so out loud.
     *
     * `superuser_reserved_connections` is subtracted and then measured not to matter, because
     * `R24` §3.1 found the application role **is** a superuser under `postgres:16-alpine` — so
     * the reserve that exists for an operator is spendable by the application, and the honest
     * headroom is the smaller of the two readings.
     */
    @Test
    fun `pool size times the planned instance count fits under the database's ceiling`() {
        val poolSize = jdbc.dataSource!!
            .let { (it as com.zaxxer.hikari.HikariDataSource).maximumPoolSize }
        val maxConnections = jdbc.queryForObject("show max_connections", Int::class.java)!!
        val superuserReserved = jdbc.queryForObject("show superuser_reserved_connections", Int::class.java)!!

        val demand = poolSize * PLANNED_MAX_INSTANCES
        val available = maxConnections - superuserReserved

        println(
            "pool $poolSize x $PLANNED_MAX_INSTANCES instances = $demand against " +
                "max_connections $maxConnections - superuser_reserved $superuserReserved = $available",
        )

        assertTrue(
            demand <= available,
            "$demand connections would be demanded by $PLANNED_MAX_INSTANCES instances at " +
                "pool $poolSize, and the database will hand out $available. R24 §3.1 measured " +
                "what happens past this line and the answer is NOT an application error: the " +
                "DATABASE refuses, 28 times, while every HTTP request still answers 200 and " +
                "no instance logs anything. The only casualty is whoever tries to connect " +
                "next, which is the operator. Either lower the pool or change " +
                "PLANNED_MAX_INSTANCES deliberately -- and if you change it, R24 §3.1's arm D " +
                "is the measurement of the arithmetic that works",
        )
    }

    private companion object {
        /**
         * **A deployment fact, written down here because there is nowhere else.** This
         * repository has no orchestrator, no manifest and no replica count; `R24` ran two and
         * three instances because that is what produces the trap. Three is what this gate
         * checks against, and raising it is a decision somebody has to make in a diff.
         */
        const val PLANNED_MAX_INSTANCES = 3

        /**
         * `docker stop`'s default before it escalates to `SIGKILL`. Ten seconds is the
         * documented default of the Docker CLI's `--time` flag, and it is quoted here as the
         * number `R24` §3.3 arm E actually waited: SIGTERM at +3004 ms, container gone at
         * +13296 ms, so 10292 ms of patience.
         */
        val DOCKER_STOP_DEFAULT_GRACE: Duration = Duration.ofSeconds(10)
    }
}
