package net.gseek.proxima.mastery

import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import kotlin.test.assertEquals

/**
 * `E5`, once the switch that refuses `NESTED` is turned on.
 *
 * [NestedPropagationTest] establishes that this stack refuses `PROPAGATION_NESTED` outright:
 * `NestedTransactionNotSupportedException`, *"Transaction manager does not allow nested
 * transactions by default"*. That is the finding for the application as it ships, and it is
 * also a dead end — a refusal measures nothing about what a savepoint would have done.
 *
 * So this class flips one boolean in **its own application context** and takes the comparison
 * the refusal was blocking. Nothing here changes the application: the switch is set by a
 * `BeanPostProcessor` declared in a `@TestConfiguration` that only this class imports, so this
 * context is separate from every other test's and `NestedPropagationTest` still measures the
 * shipped default in the same run.
 *
 * ⚠ **The switch is set in `@BeforeEach`, and the first attempt did not work.** It was a
 * `BeanPostProcessor` declared as a `@Bean` in [AllowNested], on the reasoning that the
 * post-processor's package has been stable across Boot majors where the customiser's has not.
 * It compiled, the context started, and the arms still failed with
 * `NestedTransactionNotSupportedException` — **a `@Bean`-declared post-processor is itself an
 * ordinary bean, and the transaction manager was already built by the time it existed.**
 * Measured rather than reasoned about: `bpn0wxpbt`, 2 of 3 arms red with the switch apparently
 * set.
 *
 * So the flag is now set on the live bean before each test and restored after. [AllowNested]
 * stays, and its remaining job is the one it does reliably: **it makes this class's context
 * cache key different from [NestedPropagationTest]'s.** Without it the two classes would share
 * one `ApplicationContext`, and flipping the switch here would silently change what the other
 * class measures — which is the shipped default, the entire point of it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, NestedEnabledPropagationTest.AllowNested::class)
class NestedEnabledPropagationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class AllowNested {
        @Bean
        fun allowNestedTransactions(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (bean is AbstractPlatformTransactionManager) bean.isNestedTransactionAllowed = true
                return bean
            }
        }
    }

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var nested: NestedCounter
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private var rowId = 0L

    @BeforeEach
    fun enableNestedTransactions() {
        val manager = transactionManager as AbstractPlatformTransactionManager
        manager.isNestedTransactionAllowed = true
        check(manager.isNestedTransactionAllowed) { "the switch did not take; every arm below would measure a refusal" }
    }

    @AfterEach
    fun restoreShippedDefault() {
        (transactionManager as AbstractPlatformTransactionManager).isNestedTransactionAllowed = false
    }

    @BeforeEach
    fun setUp() {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-93-nested",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 93', 'G5-6') returning id",
            Long::class.java, "concept-93-a",
        )!!
        rowId = jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-93%')")
        jdbc.execute("delete from learner where external_ref like 'learner-93%'")
        jdbc.execute("delete from concept where code like 'concept-93%'")
    }

    private fun countOf(): Int =
        jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, rowId)!!

    private fun activeConnections(): Int =
        jdbc.dataSource!!.let { (it as com.zaxxer.hikari.HikariDataSource).hikariPoolMXBean.activeConnections }

    /**
     * ⭐ **What `REQUIRES_NEW` buys, and what it costs, in one row value.**
     *
     * The inner work commits independently under `REQUIRES_NEW` and therefore **survives** the
     * outer rolling back. A savepoint has nowhere else to be: rolling the outer transaction back
     * takes the inner work with it.
     *
     * Neither is safer. They answer different questions, and the propagation attribute on the
     * inner method is the only place the answer is written down.
     */
    @Test
    fun `when the outer rolls back, a savepoint goes with it and a new transaction does not`() {
        val requiresNew = runCatching { nested.outerFailsAfterRequiresNew(rowId) }.exceptionOrNull()
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedFailure = runCatching { nested.outerFailsAfterNested(rowId) }.exceptionOrNull()
        val afterNested = countOf()

        println("E5 >>> NESTED ENABLED -- outer rolls back after inner")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  outer=${requiresNew?.let { it::class.java.simpleName }}")
        println("E5 >>>   NESTED        row=$afterNested  outer=${nestedFailure?.let { it::class.java.simpleName }}")

        assertEquals(1, afterRequiresNew, "REQUIRES_NEW committed on its own; the outer rollback cannot reach it")
        assertEquals(0, afterNested, "a savepoint is part of the outer transaction and dies with it")
    }

    /** An inner failure, caught. Both should leave the outer able to finish its own work. */
    @Test
    fun `an inner failure is confined under both, and the savepoint arm actually ran`() {
        val requiresNewInner = nested.outerSurvivesFailedRequiresNew(rowId)
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedInner = nested.outerSurvivesFailedNested(rowId)
        val afterNested = countOf()

        println("E5 >>> NESTED ENABLED -- inner fails, outer catches")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  innerThrew=$requiresNewInner")
        println("E5 >>>   NESTED        row=$afterNested  innerThrew=$nestedInner")

        assertEquals(100, afterRequiresNew, "the inner rolled back, the outer wrote 100")
        assertEquals(100, afterNested, "the savepoint rolled back, the outer wrote 100")
        assertEquals(
            "java.lang.IllegalStateException", nestedInner,
            "the savepoint arm must fail INSIDE its unit of work. If this is a " +
                "NestedTransactionNotSupportedException the switch did not take and the 100 " +
                "above means nothing -- NestedPropagationTest §3.2 is what that looks like",
        )
    }

    /**
     * ⭐ **The number that bites in production and that nobody counts.**
     *
     * `R2` sized this pool and `R24` put three instances against one `max_connections`. Neither
     * varied the propagation, and a `REQUIRES_NEW` called from inside a transaction is a **×2**
     * on every slot held for the duration of the inner call — so a pool of `n` stalls at `n`
     * concurrent callers, every outer half holding a slot and every inner half queueing behind
     * the slots already held.
     */
    @Test
    fun `a savepoint runs on the connection the outer already holds`() {
        val idle = activeConnections()
        val duringRequiresNew = nested.connectionsHeldDuringRequiresNew(rowId) { activeConnections() }
        val duringNested = nested.connectionsHeldDuringNested(rowId) { activeConnections() }

        println("E5 >>> NESTED ENABLED -- active pool connections")
        println("E5 >>>   idle=$idle  duringREQUIRES_NEW=$duringRequiresNew  duringNESTED=$duringNested")

        assertEquals(2, duringRequiresNew, "the outer holds one and the inner takes a second")
        assertEquals(1, duringNested, "a savepoint needs no connection of its own")
    }
}
