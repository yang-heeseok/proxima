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
import kotlin.test.assertTrue

/**
 * `E5`'s extra arm: **setting the switch the error message names does not enable savepoints
 * here, and after two attempts this class stops trying and records that instead.**
 *
 * [NestedPropagationTest] establishes the shipped behaviour: `PROPAGATION_NESTED` is refused
 * with `NestedTransactionNotSupportedException`, whose message says *"specify
 * 'nestedTransactionAllowed' property with value 'true'"*. This class was written to take that
 * instruction and measure what a savepoint actually does.
 *
 * **It has been tried twice and the refusal did not move.**
 *
 * 1. A `BeanPostProcessor` declared as a `@Bean`. It did not work, and the reason is
 *    established: such a post-processor is itself an ordinary bean and cannot process a
 *    transaction manager that was built before it existed. `R38` §4.1.
 * 2. Setting the flag directly on the injected [PlatformTransactionManager] in `@BeforeEach`,
 *    with a `check` that the setter took. **The check passes — the flag reads `true` — and
 *    `@Transactional(NESTED)` still raises the same exception.**
 *
 * ⛔ **The mechanism for the second failure is `미측정` and no guess is recorded as one.** The
 * obvious candidates — a different manager instance behind the annotation, or a `JpaDialect`
 * that cannot supply savepoints — are distinguishable by measurement, and that measurement was
 * not taken. `E5` is the smallest of this slice's five traps and its brief says to say so and
 * stop rather than manufacture a result, so this class characterises the refusal it can observe
 * and does not chase the one it cannot.
 *
 * **What it therefore pins** is narrow and genuinely useful: *following the exception's own
 * instruction, in the most direct way available, does not make this work.* Anyone who reads
 * that message and expects one line to fix it should meet this test first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, NestedEnabledPropagationTest.AllowNested::class)
class NestedEnabledPropagationTest {

    /**
     * Kept for one reason only: it makes this class's context cache key differ from
     * [NestedPropagationTest]'s. Without it the two would share one `ApplicationContext` and
     * the flag flipped here would change what that class measures — which is the shipped
     * default, the whole point of it.
     */
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
    private var flagAfterSetting = false

    @BeforeEach
    fun enableNestedTransactionsAndRecordWhetherItTook() {
        val manager = transactionManager as AbstractPlatformTransactionManager
        manager.isNestedTransactionAllowed = true
        flagAfterSetting = manager.isNestedTransactionAllowed
        println("E5 >>> nestedTransactionAllowed after setting it: $flagAfterSetting on ${manager::class.java.name}")
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

    private val refusal = "org.springframework.transaction.NestedTransactionNotSupportedException"

    /**
     * ⭐ **The flag is set, the flag reads back set, and the refusal is unchanged.**
     *
     * The `REQUIRES_NEW` arm beside it is the control: it proves the harness reaches a
     * transaction at all, so *"nothing ran"* cannot be confused with *"nothing was attempted"*.
     */
    @Test
    fun `setting nestedTransactionAllowed does not lift the refusal`() {
        assertTrue(flagAfterSetting, "PRECONDITION: the setter must have taken, or this measures nothing")

        val requiresNew = runCatching { nested.outerFailsAfterRequiresNew(rowId) }.exceptionOrNull()
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedFailure = runCatching { nested.outerFailsAfterNested(rowId) }.exceptionOrNull()
        val afterNested = countOf()

        println("E5 >>> SWITCH SET -- outer rolls back after inner")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  outer=${requiresNew?.let { it::class.java.simpleName }}")
        println("E5 >>>   NESTED        row=$afterNested  outer=${nestedFailure?.let { it::class.java.name }}")

        assertEquals(1, afterRequiresNew, "the control: REQUIRES_NEW committed on its own and survived the rollback")
        assertEquals(0, afterNested, "nothing ran under NESTED, so nothing was written")
        assertEquals(refusal, nestedFailure!!::class.java.name, "the refusal is unchanged with the flag set")
    }

    /** The same, on the path where an inner failure is caught. */
    @Test
    fun `an inner failure under NESTED is still a refusal, not a savepoint rollback`() {
        val requiresNewInner = nested.outerSurvivesFailedRequiresNew(rowId)
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedInner = nested.outerSurvivesFailedNested(rowId)
        val afterNested = countOf()

        println("E5 >>> SWITCH SET -- inner fails, outer catches")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  innerThrew=$requiresNewInner")
        println("E5 >>>   NESTED        row=$afterNested  innerThrew=$nestedInner")

        assertEquals(100, afterRequiresNew, "the inner rolled back, the outer wrote 100")
        assertEquals("java.lang.IllegalStateException", requiresNewInner, "the control failed INSIDE its unit of work")
        assertEquals(
            100, afterNested,
            "the row is 100 here too -- AND MEANS NOTHING, which is R38 §3.2's whole subject",
        )
        assertEquals(refusal, nestedInner, "what the row cannot tell you: the savepoint arm never ran")
    }

    /** Connection counts: the `REQUIRES_NEW` number is real, the `NESTED` one does not exist. */
    @Test
    fun `REQUIRES_NEW holds two pool slots and NESTED never takes one`() {
        val idle = activeConnections()
        val duringRequiresNew = nested.connectionsHeldDuringRequiresNew(rowId) { activeConnections() }
        val duringNested = runCatching { nested.connectionsHeldDuringNested(rowId) { activeConnections() } }
            .fold(onSuccess = { it }, onFailure = { -1 })

        println("E5 >>> SWITCH SET -- active pool connections")
        println("E5 >>>   idle=$idle  duringREQUIRES_NEW=$duringRequiresNew  duringNESTED=$duringNested")

        assertEquals(2, duringRequiresNew, "the outer holds one and the inner takes a second")
        assertEquals(
            -1, duringNested,
            "not a connection count: NESTED is refused before a connection is taken, so there " +
                "is no savepoint figure to compare against the 2 above",
        )
    }
}
