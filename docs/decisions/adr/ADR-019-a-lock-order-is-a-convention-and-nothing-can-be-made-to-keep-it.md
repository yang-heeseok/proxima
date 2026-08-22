# ADR-019 — A lock order is a convention, nothing can be made to keep it, and no guard is written for a caller that does not exist

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: **Accepted.** Both halves of its premise are now measured — `R37` green,
> `5501f32`.
> **Answers**: `R37` §8's one bullet that needs a judgement rather than work.
> **Closes**: nothing on its own. `R37` closes `ADR-014` ledger entry `6.6`; this ADR is the
> judgement that entry's closure exposed.

## Context

`R37` measured what happens when two transactions take the same two rows in opposite order:
**10 opposed pairs, 10 deadlocks, `SQLSTATE 40P01`, one casualty each, `bothDied=0`.**

The remedy is not in doubt and is three lines long. Take the lower identifier first, always;
two callers that both sort cannot form a cycle, because a cycle needs one holder waiting on a
lower id while another waits on a higher one, and neither ever asks in that direction.
`RowLocker.lockInAscendingIdOrder` is that method.

**Measured, not assumed**: under the ordered arm, 10 pairs produced **0 casualties**, and the
count that matters is `bothBetweenLocks` going **10 → 0** — the two sides could not both be
between their locks at all, because the second is queued on the first row. `R37` §3.4.
The remedy does not survive the race; it removes it.

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
| **A′.** As A, plus the retry arm as the fallback when order cannot be imposed | recovers a cycle that formed: **10 of 10, one retry each** | prevents nothing; pays a round trip per casualty | noted |
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

## What was still missing when this was Proposed, and what closed it

This ADR was filed `Proposed` on the ground that half its premise was unmeasured: `R37` had
shown the **unsorted** pair deadlocks ten times out of ten and had **not** shown that the
sorted one does not. Deciding that a convention is the remedy before the remedy had been
observed to work would have been the same error `6.6` is being closed out of — a conclusion
reached by argument where a measurement was available.

`5501f32` took both arms. `casualties=0` and `bothBetweenLocks=0` under ascending order;
`retries=10 over 10 pairs` under the retry arm, every loser recovering on its second attempt.
**The decision above is unchanged by the measurement**, which is worth stating plainly: the
numbers did not rescue a decision made without them, they were taken first and the decision
was withheld until they existed.

## What is still missing

- **The ordered arm's control lives in a sibling arm.** `R37` §3.4: `bothBetweenLocks=0` cannot
  by itself distinguish *the order worked* from *the harness stopped racing*. Only the retry
  arm reporting `10` in the same invocation separates them. **Run the arms separately and this
  ADR's evidence quietly becomes vacuous.**
- **Ten pairs, one invocation, one machine.** Run-to-run stability is `미측정`.
- **The retry fallback is measured at one opposed pair at a time.** Whether its recovery rate
  holds when more than two transactions cycle is `미측정`, and `R6` §5's *"pessimistic wins when
  contention is high"* is the neighbouring claim nothing here tests.
