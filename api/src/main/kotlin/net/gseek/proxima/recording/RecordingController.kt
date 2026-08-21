package net.gseek.proxima.recording

import java.math.BigDecimal
import java.time.Instant
import net.gseek.proxima.security.RequestToken
import net.gseek.proxima.security.ResourceAuthorisation
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** One recording as it arrives over HTTP. Deliberately the same six fields as [Recording]. */
data class RecordingRequest(
    val itemId: Long,
    val conceptId: Long,
    val correct: Boolean,
    val elapsedMs: Int,
    val at: Instant,
    val scoreDelta: BigDecimal,
)

/**
 * What became of one recording, on the wire.
 *
 * `RecordingOutcome` is a sealed interface and this is a flat record with a discriminator,
 * because a JSON shape that changes its keys per variant is a shape every client has to
 * branch on before it can read the index. `reason` is `null` on `recorded` and populated on
 * `rejected`.
 */
data class RecordingOutcomeView(
    val index: Int,
    val outcome: String,
    val reason: String?,
)

/**
 * `POST /api/v1/learners/{learnerId}/attempts`
 *
 * **This endpoint exists because `ADR-009` named the measurement that would flip it, and the
 * measurement arrived.** That decision refused a recording endpoint and recorded the cost:
 *
 * > **Every load number in this repository is on the read path.** … The write path's
 * > concurrency was measured by `R6`, `R7` and `R12` with **JVM threads against the service**,
 * > never over HTTP. **The write path under HTTP load is 미측정**, and this decision is what
 * > keeps it that way.
 *
 * and then named its own exit:
 *
 * > **What would flip this:** A load measurement whose question genuinely needs HTTP on the
 * > write path — connection-pool behaviour under concurrent writes is the likeliest.
 *
 * `R24` is that measurement. *What happens to a request that is being processed when the
 * container goes away* has no answer without a request that is being processed, and a JVM
 * thread calling a service method is not one: it has no socket to reset, no Tomcat worker to
 * drain, and no relationship to `server.shutdown` at all. **The trap is about the boundary
 * between the container and the request, and the request has to exist for there to be a
 * boundary.**
 *
 * `ADR-009` is updated rather than deleted — it is the record of a decision and of what
 * overturned it, and the shape it used for that is the one `R14` §8's last bullet already
 * had.
 *
 * ## Why the batch answers `200` and not `207`
 *
 * `R14` §8 recorded that no status had been chosen for *"four of five landed"*, and `ADR-009`
 * closed the question by there being no HTTP. There is now, so it is chosen here.
 *
 * | Option | Why not |
 * | --- | --- |
 * | `207 Multi-Status` | It is a WebDAV status carrying a WebDAV body, and every non-WebDAV use of it is a convention two parties have to agree privately. Nothing here is in a position to agree it |
 * | `4xx` when any item is rejected | **The batch did not fail.** `R14` is the report about treating one rejected recording as a reason to discard the others; answering `400` because item three was out of band re-states that mistake at the transport layer |
 * | `200` with an outcome per item | Chosen. The unit of work is one recording — `AttemptRecorder`'s KDoc, since `T3` — so the batch's own outcome is *"every item was attempted"*, and that is what succeeded |
 *
 * **The rejection is data, not an error**, which is the same move `RecordingQueries` makes one
 * layer down: a recording outside the `0..1` band matches no row rather than aborting a
 * transaction. A caller that wants to treat rejections as failures can; a caller that wants
 * to retry only the rejected indices has what it needs to.
 *
 * ## What this deliberately still does not have
 *
 * No idempotency key — `R14` §5 rejected one as *a contract with an absent party*, and an
 * endpoint that exists to be measured is still an absent party. No pagination of outcomes, no
 * partial-batch retry, no `Location`. Adding those would be building the API `ADR-009` was
 * right to refuse; what changed is that one measurement needs a socket, not that the system
 * acquired consumers.
 */
@RestController
@RequestMapping("/api/v1/learners")
class RecordingController(
    private val recordings: AttemptRecordingService,
    /**
     * `AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE` requires a **direct**
     * call from this handler. That rule was written by `R11` for the endpoint that did not
     * exist yet — this is it, and it is the first time the rule has been paid rather than
     * asserted.
     */
    private val authorisation: ResourceAuthorisation,
) {

    @PostMapping("/{learnerId}/attempts")
    fun record(
        @PathVariable learnerId: Long,
        @RequestBody body: List<RecordingRequest>,
        @RequestAttribute(name = RequestToken.SUBJECT_ATTRIBUTE) subject: Long,
    ): List<RecordingOutcomeView> {
        authorisation.requireOwner(subject, learnerId)

        val outcomes = recordings.recordAll(
            learnerId,
            body.map {
                Recording(
                    itemId = it.itemId,
                    conceptId = it.conceptId,
                    correct = it.correct,
                    elapsedMs = it.elapsedMs,
                    at = it.at,
                    scoreDelta = it.scoreDelta,
                )
            },
        )

        return outcomes.map {
            when (it) {
                is RecordingOutcome.Recorded -> RecordingOutcomeView(it.index, "recorded", null)
                is RecordingOutcome.Rejected -> RecordingOutcomeView(it.index, "rejected", it.reason)
            }
        }
    }
}
