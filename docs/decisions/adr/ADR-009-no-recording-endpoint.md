# ADR-009 — No recording endpoint, and the gap that leaves is named

> **Created**: 2026-08-18
> **Updated**: 2026-08-21
> **Status**: **Superseded 2026-08-21 by `ADR-013`** — on the condition this document named
> itself. Accepted from 2026-08-18 to 2026-08-21.
> **Closes**: `OPEN-9`

> ## The flip, 2026-08-21
>
> **`R24` is the measurement *What would flip this* describes, and the endpoint exists.**
> Everything below stands as written; nothing in the reasoning turned out to be wrong, and
> that is the point of leaving it. What arrived is the condition, not a counter-argument.
>
> `R24` asks what happens to a request that is **being processed** when the container goes
> away, on the write path. There is no version of that question a JVM thread calling
> `recordAll` can answer: the thread has no socket to reset, no Tomcat worker to drain, and no
> relationship to `server.shutdown` — which on Spring Boot 4.1.0 defaults to `graceful`, read
> out of `spring-boot-web-server-4.1.0.jar`'s own configuration metadata. **The trap is the
> boundary between the container and the request, so the request has to exist for there to be
> a boundary.**
>
> What that bought is deliberately one `POST` — `RecordingController`, authorised by the same
> `ResourceAuthorisation.requireOwner` call the read path makes, returning `R14`'s outcome
> list. The three things this document said an endpoint would have to guess at are still not
> guessed: no idempotency key (`R14` §5's *contract with an absent party* is unchanged), no
> retry protocol, no `Location`. **One status was chosen and it is argued rather than
> assumed** — `200` with a per-item outcome, in `RecordingController`'s KDoc, with `207` and
> `4xx` named and rejected.
>
> **What this cost, stated as plainly as the paragraph it replaces.** The public surface of
> this application grew by one write endpoint, in a repository whose `R10` and `R11` both
> declined to commit a live defect for a reader to find. This one is not a defect — it
> authorises, and `RecordingEndpointTest` asserts that it refuses a cross-learner write
> before the write happens. But it is surface, and surface is what this document was
> protecting.
>
> The bullet below that says the write path under HTTP load is 미측정 is **no longer true for
> shutdown behaviour and still true for everything else**: `R24` drives batches through the
> socket to measure what a deployment cuts. **Write-path *latency* under HTTP load, and the
> connection pool under concurrent writes, remain 미측정** — `R24` §8.

## Context

`AttemptRecorder.recordAll` has no caller outside tests. `R14` measured a real defect in it —
of four valid recordings in a batch of five, **two landed**, and the ones after the invalid
entry were never attempted rather than rejected — and fixed it so every recording is attempted
and every outcome is returned.

`R14` §8 then recorded three things that all wait on the same absent thing:

> - **There is still no endpoint.** `recordAll` has no caller outside tests, so this decides
>   the shape of an API with no consumer.
> - **The outcomes are returned, not acted on.** Nothing retries a rejection, nothing reports
>   it to a learner, and no HTTP status has been chosen for *"four of five landed"*. **That is
>   the next decision and it needs the endpoint that does not exist.**
> - **Order is not part of the contract.**

That last shape — *a decision conditioned on something that may never arrive* — is the one
`ADR-003` condemned when it closed `OPEN-3`: **a deadline that cannot arrive is not a
deadline**, and a provisional choice nobody can schedule is a permanent choice with a
disclaimer on it. `R19` moved it to `OPEN-9`, one level down, where the question is decidable:
*does the endpoint exist at all?*

Checked first, because `R14`'s bullet assumes an answer that is written nowhere:
`docs/roadmap.md` *Deferred, deliberately* does not name a recording endpoint, and
`docs/explanation/domain-model.md` places **recommendation policy** out of scope while saying
the layer underneath it is *not* out of scope. **So it was undecided, not closed.**

## Decision

**No recording endpoint. `recordAll` stays a service method, and the three `R14` §8 bullets
that wait on a consumer are settled by there never being one.**

And the cost is recorded rather than left implicit:

> **Every load number in this repository is on the read path.** `R2`, `R4`, `R16` and `R18`
> all measure `GET /api/v1/learners/{id}/recommendations`. The write path's concurrency was
> measured by `R6`, `R7` and `R12` with **JVM threads against the service**, never over HTTP.
> **The write path under HTTP load is 미측정**, and this decision is what keeps it that way.

## Why

**An endpoint adds surface, not a defect class.** *Features can be invented, failures cannot* —
and `R14` reproduced, measured and fixed its failure through the service layer without one. An
endpoint would not have changed a single number in that report.

**The three bullets it was blocking are not blocked by a missing endpoint; they are answered by
its absence.** No HTTP status has to be chosen for *"four of five landed"* if there is no HTTP.
`RecordingOutcome` is returned to a caller in the same process, which can branch on it. `R14`
§5 rejected idempotency keys on the same ground — *a contract with an absent party*.

**Deciding it now is what `open.md`'s own rule requires.** The row could name no honest
deadline, and a row that cannot name one *"should be decided now instead"*.

## The one real argument on the other side, and why it loses here

An endpoint has exactly one use this repository would feel: **it would let k6 exercise the
write path.** That is a genuine gap — the concurrency reports drive threads at a service, which
is a faithful model of contention and not of a request queue, a connection pool under writes,
or a transaction held across a serialisation boundary.

It loses on scope and on order:

- The gap is about **the load harness**, not about an API. If putting writes under HTTP load
  ever becomes worth measuring, the thing to build is a load scenario and whatever surface it
  minimally needs — decided by that measurement's requirements, not by guessing an API now.
- Building an endpoint *first*, to enable a measurement nobody has scheduled, is how the
  unbanked guard in `ADR-007` gets built with a different name on it.

`load/README.md` already lists `attempts-concurrent.js` as *(to come)* — the marker
`docs-consistency.yml` check 1 requires for an artefact that does not exist. **The gap has a
place to live, and it is not this decision.**

## What would flip this

A load measurement whose question genuinely needs HTTP on the write path — connection-pool
behaviour under concurrent writes is the likeliest — or a second consumer of `recordAll`
arriving for its own reasons. Either replaces this decision rather than amending it, which is
the shape `R14` §8's last bullet already uses for batch atomicity.
