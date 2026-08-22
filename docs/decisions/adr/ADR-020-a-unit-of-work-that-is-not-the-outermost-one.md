# ADR-020 — A unit of work stays a unit of work when it is not the outermost one

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: PENDING — Proposed. Accepted once `R40`'s measurement window has run.

## Context

`AttemptRecorder.record` is annotated `@Transactional`, and its KDoc has said since `T3` that

> the unit of work is one recording, **not the batch: attempts are independent events, and one
> learner's invalid submission is not a reason to discard the valid ones recorded beside it.**

`R1` chose that boundary and `R14` measured what it buys — of five recordings with the third
invalid, **four land**, and the caller is told which. `AttemptRecordingService.recordAll` exists
to deliver exactly that.

**The sentence is unconditional and the behaviour is not.**

`@Transactional` defaults to `REQUIRED`, which means *use the caller's transaction if there is
one*. There is no caller with a transaction today — `RecordingController.record` carries no
annotation — so the promise holds. The moment one exists, `record` stops being a unit of work at
all: it becomes a fragment of the caller's. A rejected recording then marks the caller's
transaction rollback-only, `recordAll` catches the rejection and computes its per-item outcomes
exactly as designed, and the caller's commit throws them away along with every recording that
succeeded.

`R40` measures that. This ADR decides what to do about it.

⭐ **The decision is not "which propagation is better".** It is **whether that KDoc sentence is a
requirement or a description.** If it is a requirement, the code must honour it whatever the
caller does. If it is a description of the current call graph, it must say so.

## What was measured before choosing

PENDING — `R40` §3. The figures this decision rests on:

| | |
| --- | ---: |
| valid recordings that land, called with no outer transaction | PENDING |
| valid recordings that land, called from a `@Transactional` caller | PENDING |
| what the caller receives in the second case | PENDING |

⛔ **Nothing is decided here on the strength of the mechanism alone.** The mechanism was
understood before the measurement and that is not the same as having measured it.

## Decision

PENDING — to be recorded once `R40` has run. The intended decision, with the alternatives it is
being weighed against:

**The KDoc sentence is a requirement. `AttemptRecorder.record` isolates itself, so that the unit
of work is one recording regardless of who calls it.**

| Option | Effect | What it gives up |
| --- | --- | --- |
| **A — `REQUIRES_NEW` on `record`** | the promise holds unconditionally | **a second connection while the caller's is held** — `Cm = 2` in `R2`'s pool formula, 미측정. And the batch stops being atomic with the caller's own work: recordings commit as they succeed, so a later failure in the caller cannot take them back |
| B — leave `REQUIRED`, weaken the KDoc to *"when called outside a transaction"* | free, honest about what the code does | a correctness property that depends on every future caller knowing an unwritten rule. `R1` §5 rejected exactly this reasoning — *a convention with a deadline* |
| C — leave `REQUIRED`, add a gate refusing transactional callers | the property becomes checkable | narrow: it refuses the shape it names, not the class of defect. And it forbids a thing callers may legitimately want |
| D — `rollbackFor` / `noRollbackFor` tuning on the inner | does not help | the inner's own write **must** roll back; the problem is that its failure escapes into a transaction it does not own |

**Why A rather than C.** C would keep the defect and forbid the caller. But a service that records
attempts alongside its own writes is an ordinary thing to want, and the repository has no reason
to refuse it — what it has a reason to refuse is *silently changing the unit of work underneath
it*. A answers the question the defect is actually asking, which is the same question `R1` §5
answered: **what is the unit of work?** It is one recording. `REQUIRES_NEW` is that sentence
written in a way the framework enforces.

**What would make B correct:** a requirement that a batch is all-or-nothing with the caller's
work — a gradebook import, say, where a partially applied file is worse than a rejected one.
That is a domain requirement, not a technique, and `R1` §5 already named it as the thing that
would flip this choice. It is not this application's requirement: `R14` chose per-item outcomes
after measuring the alternative.

**What A costs, stated rather than glossed.** Two things, and the second is the one that will
surprise someone:

1. **A second connection is held.** `R2` sized the pool; this doubles `Cm` for the recording
   path. 미측정 — it needs load, and therefore the timing lock. Ledger `40.1`.
2. **The recordings are no longer rollback-able by the caller.** Under `REQUIRED` a caller could
   abandon the whole batch by rolling back. Under `REQUIRES_NEW` it cannot: what succeeded is
   committed. That is the *same* trade `R14` already made between recordings, extended to the
   boundary with the caller — and it is a real loss of capability, not a free win.

## Both arms stay in the binary

`R4` §2's argument, and the pattern `proxima.recording.batch`,
`proxima.recording.mastery-update`, `proxima.security.authorisation` and
`proxima.security.expiry-policy` already follow: the rejected arm stays runnable so that the red
state is reproducible from the shipped artefact rather than only from a commit.

    proxima.recording.propagation
      required       the caller's transaction is joined. `red` -- R40's measured loss
      requires-new   one recording is one unit of work whatever the caller is doing

PENDING — which value ships as the default.

## Consequences

- `AttemptRecorder`'s KDoc gains the sentence it was missing: that the isolation is what makes
  the unit-of-work claim true, and is not an optimisation.
- `AttemptRecordingService`'s KDoc currently explains its lack of `@Transactional` with `R1`'s
  proxy argument. **A second reason was riding on the same absence and was never written down.**
  It gets written down.
- The pool question moves from invisible to scheduled: `ADR-014` row `40.1`.
- ⚠️ **This ADR does not fix the checked-exception half of `R40`.** A checked exception thrown
  from `record` still commits its row, in either arm. That is a separate defect with a separate
  remedy — `rollbackFor` — and §7 of `R40` records why no static gate for it can be built in a
  Kotlin codebase.

## What would reopen this

- A caller that genuinely needs the batch to be atomic with its own writes. Then the requirement
  in the KDoc is wrong and B becomes correct.
- A measurement showing `Cm = 2` costs more than the pool can absorb under load. That is `40.1`,
  and until it is taken this decision rests on a correctness argument with an **unmeasured**
  performance cost — which is stated here rather than discovered later.
