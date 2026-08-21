# ADR-013 — One endpoint, bought by one measurement

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Status**: Accepted
> **Supersedes**: `ADR-009`, on the condition `ADR-009` named itself

## Context

`ADR-009` refused a recording endpoint on 2026-08-18. Its reasoning was that an endpoint adds
surface and no defect class, that `R14` had reproduced, measured and fixed a real batch defect
through the service layer without one, and that designing an API with no consumer is designing
by guessing. It recorded the cost rather than implying it:

> **Every load number in this repository is on the read path.** … The write path's concurrency
> was measured by `R6`, `R7` and `R12` with **JVM threads against the service**, never over
> HTTP. **The write path under HTTP load is 미측정**, and this decision is what keeps it that
> way.

And it named its own exit:

> **What would flip this:** A load measurement whose question genuinely needs HTTP on the write
> path — connection-pool behaviour under concurrent writes is the likeliest.

`R24` §3.3 is that measurement. *What happens to a request that is being processed when the
container goes away* has no answer a JVM thread can give: a thread has no socket to reset, no
Tomcat worker to drain, and no relationship to `server.shutdown` — which on Boot 4.1.0 defaults
to `graceful`, read out of `spring-boot-web-server-4.1.0.jar`'s own configuration metadata. **The
trap is the boundary between the container and the request, so the request has to exist for
there to be a boundary.**

`R24` §3.1 arm E is the second half of the same need: eighty concurrent writes at one contended
mastery row, which is `ADR-009`'s own *"connection-pool behaviour under concurrent writes"*.

## Decision

**One `POST`, and its scope is the measurement rather than an API.**

`POST /api/v1/learners/{learnerId}/attempts`, in `RecordingController`. It calls
`ResourceAuthorisation.requireOwner` directly — the same call the read path makes, which is the
first time `AuthorisationRules.HANDLERS_TAKING_A_PATH_VARIABLE_AUTHORISE` has been **paid**
rather than asserted, since `R11` wrote it for an endpoint that did not exist. It takes a list
of recordings and returns `R14`'s outcome list.

`ADR-009` is **updated and not deleted.** Its reasoning was not refuted; the condition it named
arrived. That is the shape `R14` §8's last bullet already used and the shape `ADR-003` requires
of a decision that is replaced rather than amended.

**The status question `R14` §8 left open is answered here:** the batch responds `200`, with a
per-item outcome.

| Option | Why not |
| --- | --- |
| `207 Multi-Status` | A WebDAV status carrying a WebDAV body. Every non-WebDAV use is a convention two parties agree privately, and there is no second party |
| `4xx` when any item is rejected | **The batch did not fail.** `R14` is the report about treating one rejected recording as a reason to discard the others; answering `400` because item three left the `0..1` band restates that at the transport layer |
| `200` with an outcome per item | **Chosen.** The unit of work is one recording — `AttemptRecorder`'s KDoc, since `T3` — so the batch's outcome is *every item was attempted*, and that is what succeeded |

**A rejection is data, not an error**, which is the move `RecordingQueries` already makes one
layer down: a recording outside the band matches no row rather than aborting a transaction.

## What this deliberately still does not have

No idempotency key — `R14` §5 rejected one as *a contract with an absent party*, and an endpoint
that exists to be measured is still an absent party. No partial-batch retry protocol, no
`Location`, no pagination of outcomes, no `GET`. **What changed is that one measurement needs a
socket, not that the system acquired consumers.**

## The cost, stated as plainly as `ADR-009` stated its own

**This repository declined twice to add surface for the sake of a measurement, and this is the
first time it did.** `R10` §5 refused to commit a wide-open actuator surface because a public
repository carrying one in its history is a worked example for the wrong reader; `R11` §5 refused
to commit a working IDOR for the same reason.

**This is not that, and the difference is worth being precise about.** Those two would have
committed a **live defect**. This commits a **working endpoint**: it authorises, and
`RecordingEndpointTest` asserts that a cross-learner write is refused `403` **before** the write
happens, with the row count checked afterwards to prove the refusal was not issued after the
fact. What it does share with them is that the surface is permanent and the measurement was one
afternoon. **A reader arriving in a year finds an endpoint whose only caller is still a test.**

`ADR-009`'s 미측정 narrows rather than closing:

> **Write-path *shutdown* behaviour under HTTP is now measured** — `R24` §3.3, five arms.
> **Write-path latency under HTTP load remains 미측정**, and so does connection-pool behaviour
> under concurrent writes at a concurrency where the pool binds: `R24` §3.1 arm E found the
> contended row's lock binding first, at 80 concurrent writers against a pool of 17.

## What would flip this

**Deleting it.** If no measurement needs the socket again, this endpoint is surface with a test
for a caller, and removing it is a smaller act than adding it was. `ADR-009`'s reasoning is
preserved intact above and in that document precisely so that the argument for removal does not
have to be rediscovered.

A second consumer arriving for its own reasons would flip it the other way, and at that point
the three things listed under *What this deliberately still does not have* stop being deferrals
and become requirements.

## What was not measured

- **What the endpoint costs when nobody is calling it.** One more handler, one more mapping,
  one more filter path. `R16` priced the token filter at 911 ns/op and ruled it out of
  everything; nothing equivalent was measured for this.
- **Whether `200` is the right status in practice.** It is argued, not measured, because
  measuring it needs a client that acts on the outcome list — and there is none. That is the
  same absent party `R14` §5 named.
- **The maximum batch size.** `R24` §3.3 sent 8000 recordings in one 832001-byte request and it
  worked. Nothing limits it, nothing rejects an oversized one, and **what happens at ten times
  that is 미측정** — a request body limit is exactly the kind of thing an endpoint with a real
  consumer would need and this one has not been given.
