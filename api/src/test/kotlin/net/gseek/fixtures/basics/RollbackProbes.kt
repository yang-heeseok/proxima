/**
 * The probes `R40` measures. **`@Transactional` is applied here, correctly, on a bean reached
 * through its proxy — and it still does not roll back.**
 *
 * `R1` is the sibling: there the annotation was **never applied**, because a call through
 * `this` never reached the proxy. Everything below crosses a proxy properly. The boundary
 * exists, the interceptor runs, and a row still survives a failed unit of work — because
 * Spring's default rule for *when* to roll back keys on a distinction Kotlin does not make.
 *
 * ## Why these are in `net.gseek.fixtures` and not beside the test
 *
 * The same two reasons as `EqualityShapes.kt`, and the second one matters more here.
 * `TransactionBoundaryRulesTest` imports `net.gseek.proxima` with `DO_NOT_INCLUDE_TESTS`, and
 * the repository's own precedent does **not** rely on that option alone —
 * `TransactionBoundaryRulesSelfTest` says the planted classes are unreachable "first" because
 * they sit outside the package and "second" because they are test sources. Belt and braces,
 * for a rule set whose whole value is that it has never been wrong about production code.
 * These beans carry deliberately unusual transaction shapes and have no business being
 * inspected by a production rule.
 *
 * ## Nothing here is a planted violation
 *
 * Unlike `proxima.planted`, none of these classes breaks an ArchUnit rule. They are ordinary,
 * correct-looking Spring beans of the shape people actually write. That is the point: the
 * defect `R40` measures is invisible to every structural rule in this repository, which is
 * itself one of the report's findings rather than an oversight in the gate.
 */
package net.gseek.fixtures.basics

import java.io.IOException
import net.gseek.proxima.recording.AttemptRecordingService
import net.gseek.proxima.recording.Recording
import net.gseek.proxima.recording.RecordingOutcome
import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Which failure a probe should raise after it has written its row.
 *
 * `CHECKED` is the interesting one, and in Kotlin it is indistinguishable at the call site
 * from the others — see [KotlinRollbackProbe.writeThenThrow].
 */
enum class Failure { NONE, RUNTIME, CHECKED, ERROR }

/**
 * One write, then one failure of a chosen kind, inside a real transaction.
 *
 * **Read the two signatures below and note that they are the same signature.** Kotlin has no
 * checked exceptions, so nothing in the declaration of [writeThenThrow] records that it can
 * raise `IOException`, nothing at the call site has to acknowledge it, and no compiler
 * diagnostic mentions it. Spring's default rollback rule nevertheless treats that exception
 * differently from an `IllegalStateException`, and the difference is a committed row.
 */
@TestComponent
class KotlinRollbackProbe(private val jdbc: JdbcTemplate) {

    /**
     * **The default rule.** `@Transactional` with nothing said about which exceptions matter.
     *
     * Spring rolls back on `RuntimeException` and `Error` and commits on everything else. That
     * rule is stated in terms of a Java language distinction, and this method is written in a
     * language that does not have it.
     */
    @Transactional
    fun writeThenThrow(ref: String, failure: Failure) = writeAndRaise(ref, failure)

    /**
     * **The remedy arm.** The same method, told what to roll back for.
     *
     * `rollbackFor` restores the property most people believe `@Transactional` already has:
     * that a unit of work which did not finish leaves nothing behind, whatever stopped it.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun writeThenThrowRollingBackForAnything(ref: String, failure: Failure) =
        writeAndRaise(ref, failure)

    /**
     * Private, so there is no proxy to miss and no self-invocation to report. The boundary is
     * on the two public methods above, which is where the unit of work is.
     */
    private fun writeAndRaise(ref: String, failure: Failure) {
        jdbc.update("insert into learner (external_ref) values (?)", ref)
        when (failure) {
            Failure.NONE -> Unit
            Failure.RUNTIME -> throw IllegalStateException("unchecked, and the row should go")
            // No `throws` clause. No warning. No call site has to know.
            Failure.CHECKED -> throw IOException("checked, and nothing in Kotlin says so")
            Failure.ERROR -> throw AssertionError("an Error, which Spring does roll back for")
        }
    }
}

/**
 * The inner unit of work in the **swallowed-exception** case: it writes, then rejects.
 *
 * `REQUIRED` — the default — so a caller that already has a transaction gets this work
 * **joined into theirs** rather than isolated from it. That is the whole mechanism of
 * [SwallowingOuter].
 */
@TestComponent
class FailingInner(private val jdbc: JdbcTemplate) {

    @Transactional
    fun writeThenFail(ref: String) {
        jdbc.update("insert into learner (external_ref) values (?)", ref)
        throw IllegalStateException("the inner unit of work rejected this")
    }
}

/**
 * The same inner work, in **its own transaction**, so a failure cannot reach the caller's.
 *
 * This is the remedy `MasteryProvisioner.findOrCreateIsolatingTheInsert` already uses in
 * shipped code, with the same sentence attached: *"so a failure cannot poison the caller's."*
 * `R40` prices it rather than assuming it, because it is not free — it takes a second
 * connection while the first is still held, which is `Cm = 2` in `R2`'s pool-sizing formula.
 */
@TestComponent
class IsolatedInner(private val jdbc: JdbcTemplate) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun writeThenFail(ref: String) {
        jdbc.update("insert into learner (external_ref) values (?)", ref)
        throw IllegalStateException("the inner unit of work rejected this")
    }
}

/**
 * **The quieter half of `R40`, and the one nothing in this repository has measured.**
 *
 * `docs/roadmap.md` has said since `T3` that the *"swallowed exception / rollback-only case is
 * **not done at all**"*. This is it, in the shape people write it: do some work, call
 * something that might fail, catch the failure, note it, carry on. Every line of that is
 * defensible in isolation.
 *
 * What it produces is not an error at the `catch`. It is an error **at the commit**, thrown
 * out of a method that already returned successfully to itself, naming a transaction the
 * caller never marked.
 */
@TestComponent
class SwallowingOuter(
    private val inner: FailingInner,
    private val jdbc: JdbcTemplate,
) {

    @Transactional
    fun writeThenSwallowInnerFailure(outerRef: String, innerRef: String): String {
        jdbc.update("insert into learner (external_ref) values (?)", outerRef)
        return try {
            inner.writeThenFail(innerRef)
            "inner returned normally"
        } catch (e: RuntimeException) {
            // The log line that makes this look handled. It is the shape being measured,
            // not endorsed -- MasteryCounter.incrementWithRetryInside carries the same note.
            "swallowed ${e.javaClass.simpleName}"
        }
    }
}

/**
 * The same orchestration, calling the isolated inner instead.
 *
 * Identical code at the call site. The only difference is one word on a different class, which
 * is exactly why this defect survives review.
 */
@TestComponent
class IsolatingOuter(
    private val inner: IsolatedInner,
    private val jdbc: JdbcTemplate,
) {

    @Transactional
    fun writeThenSwallowInnerFailure(outerRef: String, innerRef: String): String {
        jdbc.update("insert into learner (external_ref) values (?)", outerRef)
        return try {
            inner.writeThenFail(innerRef)
            "inner returned normally"
        } catch (e: RuntimeException) {
            "swallowed ${e.javaClass.simpleName}"
        }
    }
}

/**
 * **A caller with a transaction of its own, calling the shipped batch path.**
 *
 * This is the class that turns `R40`'s probe finding into a claim about *this application*.
 * Everything above measures the rule on purpose-built beans; this measures what the rule does
 * to code that already ships.
 *
 * `AttemptRecordingService.recordAll` in its `per-item-outcomes` arm attempts every recording
 * and returns an outcome for each, catching rejections as it goes. `R14` measured that: **four
 * of five valid recordings land.** That behaviour is correct today for one reason and one only
 * — `recordAll` has no transaction, so every `AttemptRecorder.record` gets its own, and a
 * rejected recording rolls back alone.
 *
 * **Nothing states that as a requirement and nothing checks it.** `AttemptRecordingService`'s
 * KDoc does say the class holds no `@Transactional` *deliberately*, but the reason it gives is
 * `R1`'s proxy argument — that the boundary belongs on the unit of work — not this one. The
 * property that the batch survives at all is load-bearing, undocumented, and one annotation
 * away from being false.
 *
 * This bean is that one annotation, placed where anyone would place it: not on the shipped
 * service, but on **a caller**, which is the ordinary way a second service acquires a
 * transaction. No production file is modified to produce the measurement, which is the
 * difference between reproducing a defect and manufacturing one.
 */
@TestComponent
class TransactionalBatchCaller(private val service: AttemptRecordingService) {

    @Transactional
    fun recordAllInsideMyTransaction(
        learnerId: Long,
        recordings: List<Recording>,
    ): List<RecordingOutcome> = service.recordAll(learnerId, recordings)
}
