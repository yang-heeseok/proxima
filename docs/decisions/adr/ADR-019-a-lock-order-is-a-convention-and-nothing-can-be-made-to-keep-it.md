# ADR-019 — A lock order is a convention, nothing can be made to keep it, and no guard is written for a caller that does not exist

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: **Proposed.** ⚠ Half of its premise is measured and half is not — see *What is
> still missing* at the end. It does not become Accepted until `R37` has a green commit.
> **Answers**: `R37` §8's one bullet that needs a judgement rather than work.
> **Closes**: nothing yet.

## Context

`R37` measured what happens when two transactions take the same two rows in opposite order:
**10 opposed pairs, 10 deadlocks, `SQLSTATE 40P01`, one casualty each, `bothDied=0`.**

The remedy is not in doubt and is three lines long. Take the lower identifier first, always;
two callers that both sort cannot form a cycle, because a cycle needs one holder waiting on a
lower id while another waits on a higher one, and neither ever asks in that direction.
`RowLocker.lockInAscendingIdOrder` is that method.

**The difficulty is that nothing can make anyone call it.**

This is not a gap in the schema that a migration could fill. `V3`'s own comment sets out the
shape this repository has used before, for the uniqueness race `R7` measured:

> there is no version of *"look, then leap"* that closes the gap, because the gap is between
> two statements and **only the database can be inside it**.

That was true, and the answer was to move the rule **into** the database as
`uk_mastery_learner_concept`. **Lock order has nowhere to move to.** The sorted call and the
unsorted call issue the same two statements against the same two rows; PostgreSQL has no
notion that `id` orders locks, and no constraint, `GRANT`, setting or schema object
distinguishes them. `R7`'s defect could be handed to the database. This one cannot.

### And the detector is not a substitute, which is the part that had to be measured

The tempting reading of `bothDied=0` is *"the database handles it"*. `R37` §3.1 is what makes
that reading refusable rather than merely suspicious: PostgreSQL detected and killed one side
**ten times out of ten without anyone having ordered anything.**

It handled nothing. The cycle formed ten times, work was discarded ten times, and a client
received an exception ten times. What the detector bought is that the failure is **bounded,
attributable and typed `Transient`** instead of unbounded — which is worth a great deal, and
is not prevention. `lock_timeout` and `statement_timeout` are both `0` at `source=default`, so
**had the detector not run, nothing on this server would ever have ended the wait.**

So the two halves have to be stated together, and each alone is misleading:

- **Prevention is a convention** the database cannot enforce.
- **Detection is a mechanism** the database does enforce, and it prevents nothing.

## The question

Given a remedy that is correct and unenforceable: **does this repository build something to
enforce it, or does it record the gap and stop?**

## Options

| Option | What it would catch | What it would not | |
| --- | --- | --- | --- |
| **A.** Nothing beyond the method, its KDoc and `R37` | — | everything | **✔** |
| **B.** An ArchUnit rule: only `lockInAscendingIdOrder` may call `lockInGivenOrder` | a second caller inside `RowLocker`'s own shape | any code that writes `for update` itself, which is the realistic way this arrives | |
| **C.** Make the unordered method non-public | the same subset as B | the same as B, and it would have to stay reachable for `DeadlockTest`'s red arm, which is the whole gate | |
| **D.** Forbid multi-row locking entirely; require one statement | the class of defect, genuinely | it forbids work this domain may legitimately need, and no such work exists yet to weigh it against | |

## Decision

**A. The convention is documented where a caller will meet it, and nothing is built to
enforce it.**

The method exists, its KDoc says in as many words that the database does not enforce this,
`R37` §5 says it again with the measurement behind it, and `DeadlockTest` pins that the
unsorted shape really does deadlock so the claim cannot quietly become false.

**No structural rule is written, and the reason is this repository's own precedent rather than
a shrug.** `ADR-007` refused a check on migrations containing correlated subqueries and gave
three reasons; the first applies here **unchanged**:

> It would have protected no migration that exists — `AGENTS.md` §Scope's *unbanked*.

Nothing in this application takes two row locks. `RowLocker` exists because `R37` needed an
instrument, and its only caller is a test. A rule written now would guard **one shape, which
is the test's own**, and `ADR-007`'s second reason bites too: the syntax is not the defect.
A rule spelled for `lockInGivenOrder` is passed by the next person who writes `select … for
update` twice in a service, which is how this actually arrives.

`ADR-007`'s third reason is the one that decides it. **A rule that fires on correct text is a
rule nobody reads**, and this repository has paid for that twice already — `R7` §3.5 and
`R17` §5.

## What this costs, stated rather than implied

**This decision leaves a known defect class with no automated guard, and that is the trade.**

It is not softened by calling the KDoc a control. A comment is not a gate. If a future service
takes two row locks in an order derived from anything other than the identifier — request
order, a `Set`'s iteration order, a join's output order — **nothing in this repository will go
red**, and the first signal will be a `PessimisticLockingFailureException` in production with
`log_lock_waits=off` meaning the server logged nothing about the waiting that preceded it.

## What flips this

**The moment a second caller exists.** `ADR-007`'s *unbanked* argument is a statement about
today's tree and it expires the day the tree changes. Concretely, this ADR is reopened when
any of these becomes true:

1. **Any production code path takes two row locks in one transaction.** The guard is banked
   the moment there is a real caller to protect, and option **B** becomes correct rather than
   premature.
2. **A deadlock is observed outside `DeadlockTest`.** One occurrence is evidence the shape
   arrived by a route nobody predicted, which is the argument option **D** would need.
3. **`lock_timeout` or `statement_timeout` stops being `0`.** Every sentence above assumes the
   detector is what ends the wait. `R37` §8 and `ADR-014` `37.3` carry this as importance
   **H**, and it changes which failure a caller sees.

## What is still missing

⚠ **This ADR is `Proposed` and not `Accepted`, because half its premise is unmeasured.**

`R37` measured that the **unsorted** pair deadlocks, ten times out of ten. It has **not** yet
measured that the sorted one does not — `lockInAscendingIdOrder` is committed and has never
been run, and `R37` §6 is `미측정` with no green commit behind it.

Deciding that a convention is the remedy, before the remedy has been observed to work, would
be the same error `ADR-014` `6.6` is being closed out of: **a conclusion reached by argument
where a measurement was available.** Both remaining arms — the ordered pair and a
retry-outside pair — are counts, so neither needs the measurement lock, and this ADR is
expected to reach `Accepted` inside slice E rather than being carried.
