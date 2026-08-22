# ADR-015 — A race test proves its own precondition, and the safe direction was the one that failed

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: Accepted
> **Closes**: `OPEN-12`

## Context

`UniquenessRaceTest` went **red once** on round two's integration merge — on the merge of
slice `C`, whose tree is byte-identical to the branch the same test was green on, and which
touches nothing in `mastery/`. It passed on the two full runs after it and passes in
isolation. Its assertion message named the ambiguity itself:

```
expected the losers to fail loudly; none did,
so either the race did not happen or something is swallowing the violation
```

**The test could not tell those two apart**, and they are a defect and an instrument failure
respectively. `OPEN-12` was opened at integration rather than by a report, because no slice
owned the file and no branch reproduced it: it needs the load of three slices' test classes
in one suite.

### The mechanism, which the original design had half right

The class KDoc already said the threads are released from a `CyclicBarrier` so that they race
"as simultaneously as this machine allows", and worried about flakiness **in the direction of
passing**.

**A barrier aligns where the calls start, not where their critical sections are.** Each
`provision` call is `@Transactional(REQUIRES_NEW)`, so a call is a transaction: open, read,
write, commit. Under `READ COMMITTED` a racer sees the winner's row **only once the winner has
committed**. Eight racers on an eight-core machine that is also running a Spring context and
several other classes' containers get descheduled between the existence check and the insert;
a racer that resumes after the winner committed finds the row, returns it, and never contends.

So the flakiness is real and **its direction is the opposite of what was feared**.

## Decision

**Every arm measures whether a race was possible, and asserts it separately from what the race
produced.**

`RaceOverlap.peak` returns the largest number of calls open at any single instant — a sweep
over start and end timestamps, with ends ordered first on a tie so that two calls which merely
abut are not counted as overlapping. `assertRaced` requires a peak of **2 or more**, in **all
four** arms, with a message that names an instrument failure rather than a defect.

`RaceOverlap` is a separate file so it can be tested without a database. `RaceOverlapTest`
feeds it intervals whose answer is known by construction, in both directions, and needs no
container.

## Why all four arms and not the one that failed

**The arm that failed is the safe one.** It asserts `failures > 0`, so a run in which nothing
raced raises a **false alarm**.

The other three assert `failures == 0`:

| arm | assertion | on a run where nothing raced |
| --- | --- | --- |
| naive check-then-insert | `failures > 0` | **fails loudly** — the observed behaviour |
| catch + re-read (same tx) | `rows == 1` | passes |
| catch + re-read (inner tx) | `rows == 1`, `failures == 0` | **passes, having exercised nothing** |
| upsert (`on conflict`) | `rows == 1`, `failures == 0` | **passes, having exercised nothing** |

`rows == 1` is also satisfied by serialised calls: the first inserts, the rest find the row.

**So three arms would issue a clean bill for a remedy they never ran**, and that is the shape
`R9` §7 and `R16`'s `rate >= 0.0` threshold are both about. The one that could fail is the one
that did, and fixing only it would have left the dangerous three untouched.

## What this does not prove, stated here rather than discovered later

**Overlap is necessary for a race and is not sufficient.** Two calls can be open together and
still have the second one's `SELECT` land after the first one's `COMMIT`, so a peak of 2 or
more says *the opportunity existed*, not *the race occurred*.

The direction that matters is the other one and it is exact: **a peak of 1 rules a race out.**
That is the whole of what `assertRaced` claims.

## The alternatives, and why they lost

| Alternative | Why not |
| --- | --- |
| **Retry until green** | The flake is the evidence. Retrying deletes exactly the signal that produced this ADR, and would have hidden the three dangerous arms permanently |
| **Weaken to `failures >= 0`** | `R16` already paid for that shape once. An assertion that cannot fail is a comment |
| **Force determinism by holding a lock or sleeping between check and insert** | It would make the race reliable and change what is being tested — the arm would then measure a widened window rather than the one the code has. Worth doing as a **separate** report about window width; not worth doing by editing this one |
| **Run this class alone** | Moves the defect rather than measuring it, and the point of a suite is that classes share a machine |
| **Accept it and move on** | `R0` §4 counts gates that have never refused anything. Adding a ninth that fires on the machine rather than on the code makes that count worse while looking like coverage |

## The control, watched refusing

An instrument nobody has seen refuse anything proves nothing about the runs it passed — this
repository has shipped **six** of those, most recently `study-consistency.yml`'s `S3`, which
printed `OK` over a planted violation for as long as it existed.

So `RaceOverlap.peak` was planted to `return 1` unconditionally — the exact shape `S3` had —
and `RaceOverlapTest` **failed 2 of 5 and exited 1**. Restored, 5 of 5 pass in 2m04s with no
database. The two that failed are the positive half; the negative half still passes on the
planted version, which is why both halves exist.

## Consequences

- Four arms now depend on `RaceOverlap` being right, which is why it has a control rather than
  a comment.
- A future failure of this class says which of two things happened, in its own message.
- **The peak is reported on every run** (`peak=8/8`), so a drift toward serialisation is
  visible before it becomes a failure.
- Measured in isolation, all four arms peak `8/8`. The failure count moves between runs — 7,
  then 6 — and the peak does not, which is the reason the peak is the precondition and the
  failure count is not.

## What would flip this

- **A run with peak ≥ 2 and `failures == 0` on the naive arm.** That is the other half of the
  old message and it would mean the violation really is being swallowed — `R7`'s conclusion,
  not this one.
- **Peaks that sit at 2 or 3 rather than 8 under a full suite.** The assertion would still
  pass, and the arms would be measuring a much weaker race than `R7` reported at 8. The
  threshold is `>= 2` because that is what makes a race *possible*; if the peak drifts down,
  the honest response is a report about what the suite's load does to this measurement, not a
  higher threshold.
