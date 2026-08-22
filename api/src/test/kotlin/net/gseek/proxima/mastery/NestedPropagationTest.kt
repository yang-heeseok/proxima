package net.gseek.proxima.mastery

import net.gseek.proxima.TestcontainersConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

/**
 * `E5` — `REQUIRES_NEW` against `NESTED`, in the same place.
 *
 * ⛔ **This is the small one of the five and the brief says to say so if it is.** It has no
 * load, no race and no concurrency: two propagations, one row, and the question of what is
 * left behind. It is here because this repository has used `REQUIRES_NEW` in every
 * transactional boundary it owns and has never once compared it with the alternative, and an
 * unexamined default is the shape every other trap in this slice takes.
 *
 * Every figure is a row value, a connection count or an exception type. Nothing here is a
 * duration.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NestedPropagationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var nested: NestedCounter

    private var rowId = 0L

    @BeforeEach
    fun setUp() {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java, "learner-92-nested",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, 'Concept 92', 'G5-6') returning id",
            Long::class.java, "concept-92-a",
        )!!
        rowId = jdbc.queryForObject(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now()) returning id",
            Long::class.java, learnerId, conceptId,
        )!!
    }

    @AfterEach
    fun clear() {
        jdbc.execute("delete from mastery where learner_id in (select id from learner where external_ref like 'learner-92%')")
        jdbc.execute("delete from learner where external_ref like 'learner-92%'")
        jdbc.execute("delete from concept where code like 'concept-92%'")
    }

    private fun countOf(): Int =
        jdbc.queryForObject("select attempts_count from mastery where id = ?", Int::class.java, rowId)!!

    /** Pool slots currently checked out, read from Hikari's own gauge rather than counted by hand. */
    private fun activeConnections(): Int =
        jdbc.dataSource!!.let { (it as com.zaxxer.hikari.HikariDataSource).hikariPoolMXBean.activeConnections }

    private fun outcomeOf(body: () -> Unit): String = try {
        body()
        "completed"
    } catch (e: Throwable) {
        "${e::class.java.name}: ${e.message?.lineSequence()?.firstOrNull()}"
    }

    /**
     * **Is `NESTED` even available here?**
     *
     * `AbstractPlatformTransactionManager` carries a `nestedTransactionAllowed` switch and
     * this repository has never set it. Whether Spring Boot's auto-configured transaction
     * manager arrives with savepoints enabled is a fact about this stack, and it is measured
     * rather than recalled — if it refuses, **the refusal is the finding** and there is
     * nothing to dress up.
     */
    @Test
    fun `what NESTED does when the outer transaction rolls back`() {
        val requiresNew = outcomeOf { nested.outerFailsAfterRequiresNew(rowId) }
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedOutcome = outcomeOf { nested.outerFailsAfterNested(rowId) }
        val afterNested = countOf()

        println("E5 >>> outer rolls back after inner")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  outer=$requiresNew")
        println("E5 >>>   NESTED        row=$afterNested  outer=$nestedOutcome")

        assertEquals(1, afterRequiresNew, "REQUIRES_NEW committed on its own; the outer rollback cannot reach it")
        assertEquals(
            1, afterNested,
            "a savepoint is not a separate transaction: if the OUTER rolls back the inner " +
                "work goes with it, because it never was anywhere else",
        )
    }

    /** The other direction: the inner fails and is caught. Which caller is still usable? */
    @Test
    fun `what an inner failure leaves the outer transaction able to do`() {
        val requiresNew = outcomeOf { nested.outerSurvivesFailedRequiresNew(rowId) }
        val afterRequiresNew = countOf()

        jdbc.update("update mastery set attempts_count = 0 where id = ?", rowId)

        val nestedOutcome = outcomeOf { nested.outerSurvivesFailedNested(rowId) }
        val afterNested = countOf()

        println("E5 >>> inner fails, outer catches")
        println("E5 >>>   REQUIRES_NEW  row=$afterRequiresNew  outer=$requiresNew")
        println("E5 >>>   NESTED        row=$afterNested  outer=$nestedOutcome")

        assertEquals(100, afterRequiresNew, "R7 §3.4: the inner failure was confined and the outer wrote 100")
        assertEquals(100, afterNested, "rolling back to a savepoint leaves the outer transaction usable")
    }

    /**
     * ⭐ **The number that bites in production and that nobody counts.**
     *
     * `R2` sized this pool and `R24` put three instances against one `max_connections`.
     * Neither varied the propagation, and a `REQUIRES_NEW` inside a transaction is a **×2** on
     * every slot held for the duration of the inner call.
     */
    @Test
    fun `how many pool slots each propagation holds while the inner call is open`() {
        val idle = activeConnections()
        val duringRequiresNew = nested.connectionsHeldDuringRequiresNew(rowId) { activeConnections() }
        val duringNested = nested.connectionsHeldDuringNested(rowId) { activeConnections() }

        println("E5 >>> active pool connections")
        println("E5 >>>   idle=$idle  duringREQUIRES_NEW=$duringRequiresNew  duringNESTED=$duringNested")

        assertEquals(2, duringRequiresNew, "the outer holds one and the inner takes a second")
        assertEquals(1, duringNested, "a savepoint runs on the connection the outer already has")
    }
}
