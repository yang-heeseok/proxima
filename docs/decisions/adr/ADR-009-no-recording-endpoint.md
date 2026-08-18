# ADR-009 — No recording endpoint, and the gap that leaves is named

> **Created**: 2026-08-18
> **Updated**: 2026-08-18
> **Status**: Accepted
> **Closes**: `OPEN-9`

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
