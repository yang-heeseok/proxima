# ADR-006 — The score band lives in two places, and the gate is on their ordering

> **Created**: 2026-08-14
> **Updated**: 2026-08-14
> **Status**: Accepted
> **Closes**: `OPEN-6`

## Context

`R12` moved the `0..1` rule on `mastery.score` out of the application and into a `WHERE`
clause, so that a recording outside the band **matches no row** instead of raising a
constraint error and aborting the transaction (`R1` §9). That is the finding the report is
about and it stands.

It also left the rule written twice:

| where | what it is |
| --- | --- |
| `ck_mastery_score` in `V1` | `check (score >= 0 and score <= 1)` — **authoritative**, and the only thing that constrains a writer who is not this application |
| `RecordingQueries.applyRecording` | `and score + :delta between 0 and 1.000` — what the recording path actually consults |

`R12` §8 recorded that as remaining risk. It was on the wrong shelf and became `OPEN-6` the
same day: *a risk in a report is something someone chose to live with; a row in `open.md` is
something someone still has to decide.*

**And `OPEN-6` was opened with a deadline of the shape this repository has already condemned.**
*Before a second write path touches `score`* — there may never be a second write path.
`ADR-003` closed `OPEN-3` with the sentence **"A deadline that cannot arrive is not a
deadline"**, having watched exactly that turn a provisional choice into a permanent one over
three days. Writing another one the next day is the reason this ADR exists now rather than
whenever the trigger fires.

## Decision

**Keep both. Gate the ordering between them, not their text.**

Neither can be removed:

- Deleting the constraint would leave the band enforced only by the one code path that
  currently happens to consult it, on a schema that also has to survive a migration, a
  backfill, and somebody with `psql`.
- Deleting the predicate would put the rule back on the constraint, which is `R12`'s defect —
  a violation aborts the transaction and the error message that says *what the score would
  have become* cannot even be read.

They are **not supposed to be equal.** They are supposed to be ordered, and only one direction
is dangerous:

| relationship | consequence |
| --- | --- |
| predicate **stricter** than the constraint | some valid recordings are refused. Wrong, immediately visible, and harmless to the data |
| predicate **equal** to the constraint | today's state |
| predicate **laxer** than the constraint | a recording passes the guard, reaches the row, violates `ck_mastery_score`, and aborts its transaction — **`R12`'s defect, reintroduced through the code that was built to prevent it** |

So the gate asserts the property that matters rather than string equality between a SQL
`CHECK` and a Kotlin string:
`api/src/test/kotlin/net/gseek/proxima/recording/ScoreBandGateTest.kt` drives seven boundary
deltas through the real recorder and requires that **every refusal arrives as
`IllegalArgumentException` from the guard and never as `DataIntegrityViolationException` from
the constraint.**

A `DataIntegrityViolationException` in that test means one of three things happened — the
predicate was widened, the constraint was tightened, or one of them moved — and the test does
not need to know which to say that it did.

### Why not the alternatives

| Option | Why not |
| --- | --- |
| read `pg_constraint` and compare the two texts | string equality between a rendered `CHECK` and a Kotlin literal. It breaks on formatting and passes on semantics it does not understand |
| generate the predicate from the constraint | plausible and worse: the predicate is `score + :delta` and the constraint is `score`. They are the same rule about different expressions, and a generator that flattened that difference would be inventing one |
| drop the constraint, keep the predicate | see above — the schema outlives this code path |
| drop the predicate, keep the constraint | `R12`'s defect, restored |
| **accept the duplication and gate the ordering** | **✔** |

## Consequences

**What this buys.** The band can be tightened in either place without ceremony, and loosening
the predicate past the constraint fails the build with a message naming `R1` §9. The
lower-bound half is now covered for the first time: `require(updated <= 1)` never checked it,
so before `R12` a negative delta went straight to `ck_mastery_score` and aborted the
transaction. Two of the seven probes are negative for that reason.

**What this costs.** Two definitions of one rule, permanently, plus a test that exists only
to relate them. Anyone changing the band has to change it twice and will be told so by a
failure rather than by a comment — which is the trade this ADR is making, not a side effect
of it.

**What this rules out.** A second write path that updates `score` without going through
`RecordingQueries`. Nothing structural prevents one; the gate covers the recording path only,
and §*What was not measured* says so rather than implying coverage.

## What was not measured

- **Whether any other write path exists.** `MasteryCounter` and `MasteryProvisioner` write
  `attempts_count` and create rows; neither touches `score` today. That was checked by reading
  them, not by a rule, and a rule in the `T3` style would be the thing that generalises it.
- **The behaviour at `numeric(4,3)`'s own limits.** `9.999` is probed and refused by the
  predicate; a delta needing more than four digits of precision fails earlier and differently,
  and that path is unmeasured.
- **Concurrent boundary recordings.** `R12`'s gate drives a thousand recordings that land
  exactly on `1.000`, so the edge is exercised under contention there. Whether two recordings
  racing at the boundary can both pass the predicate is **미측정** — the statement is atomic,
  so the mechanism says no, and the mechanism is not a measurement.
