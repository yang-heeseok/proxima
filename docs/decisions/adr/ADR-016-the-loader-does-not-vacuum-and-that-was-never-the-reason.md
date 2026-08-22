# ADR-016 — The loader does not vacuum, and that was never why the covering index lost

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: Accepted
> **Closes**: `OPEN-11`

## Context

`R20` §3.6 rejected a covering index on `concept_edge` and then found the mechanism: an
index-only scan is only index-only once `VACUUM` has set the visibility map, and `seed/`'s
load path never runs one. Its covering arm reported `Heap Fetches: 5,424` — one per row it
claimed to avoid.

`R3` had rejected `INCLUDE` columns on `attempt` eleven days earlier, under the same
condition, and attributed the result to the index.

**So the question was not whether the covering index is worth it. It was whether this
repository had twice priced a remedy against a database that could not pay for one** —
production runs autovacuum; this dataset does not.

`OPEN-11` was opened as a judgement because changing the loader trades **every prior number's
comparability** (rule 3) against document accuracy, and because the alternative — saying so in
two reports and leaving the loader alone — is a decision about what this repository claims
rather than about what it does.

## What made it undecidable rather than merely unanswered

`R20` measured the covering candidate either side of a vacuum, and both candidates before one.
**Those leave no like-for-like comparison**: the only cross-candidate pairing available was
*covering after* against *single before*, which rule 3 refuses.

The row could not be decided by argument, because the argument needed a number nobody had
taken. `R28` takes it: one arm, both candidates, both vacuumed, same session.

## Decision

**The loader is not changed. The covering index stays rejected. The condition is recorded.**

`seed/`'s commands stay `generate`, `load`, `analyze`. No `vacuum` step is added — not to
`load`, and not as a fourth command.

## Why

**Because the verdict does not move.** `R28` §3.1, four runs of the class:

| | |
| --- | --- |
| covering advantage, both arms vacuumed | **1.16×, 1.09×, 1.13×, 1.03×** |
| the single-column arm's own spread, same runs | **11.0%, 25.2%, 33.0%, 40.6%** |
| space | **85% more** |

**Three of four runs put the effect inside the measurement's own variance**, and the one that
did not is the run with the tightest spread — taking it alone would have produced the opposite
finding. That is `R18`'s lesson reached a second way: with four consecutive runs rather than
two runs seventy minutes apart.

So vacuuming makes the covering arm look better and **still does not make it worth 85% more
space**. The loader's omission made the measurement look worse than it was; correcting it
changes nothing that was concluded.

## The alternatives, and what each would have cost

| Alternative | Why not |
| --- | --- |
| **`vacuum` inside `load`** | Every number taken before it becomes incomparable with every number after it, for a conclusion that does not move. It would also remove the pre-`ANALYZE` state `T4` was deliberately given — `load` does not analyze for exactly that reason, and vacuuming would analyze as a side effect |
| **`vacuum` as a fourth `seed` command** | The near miss. Explicit, opt-in, changes no default — and **unbanked**: nothing needs it now that `R28`'s arm creates its own vacuumed state, and a command nobody runs is what `R0` §4 counts. It becomes right the day a question needs a vacuumed dataset outside one test |
| **Ship the covering index anyway** | 85% more space for an effect inside the spread. This is the thing the measurement exists to refuse |
| **Leave `OPEN-11` open** | It was open because a number was missing, not because the trade was hard. Once the number exists, carrying the row is the failure `ADR-003` condemned — a deadline that cannot arrive |

## Consequences

- `R3`'s and `R20`'s verdicts stand, and **the reason each gave is now known to be incomplete
  rather than wrong.** `R20` §3.6 is annotated in place with a forward link; its body is not
  rewritten.
- The shipped dataset remains in a state no production database sits in for long. **That is now
  written down** rather than being a property nobody had noticed.
- `PrerequisiteIndexTest`'s method order became load-bearing, because `VACUUM` is an
  irreversible side effect on a shared table. The new arm is `@Order(Int.MAX_VALUE)` and the
  coupling is documented — see `R28` §7 for how it was found, which was by breaking it.

## What would flip this

- **A read path that is genuinely index-only-servable.** The traversal at depth 12 spends its
  cost on twelve recursive iterations, not on heap access to 5,424 rows, which is why the
  second column buys so little. A query whose cost *is* the heap access would price it
  differently, and then the fourth-command option stops being unbanked.
- **A quiet machine separating the two arms.** The claim is that the effect is not larger than
  the variance this machine produces, and `ADR-004`'s hole — every number here comes from one
  machine — is unchanged by this ADR.
- **A table with dead tuples.** `COPY` into a fresh table produces none, so `VACUUM` here only
  sets the visibility map. Where it also reclaims space the arms might separate. 미측정.

## What was not measured

- What `VACUUM` costs on the full 3,963,719-row dataset. It is part of why the fourth-command
  option is unattractive, and it is an argument rather than a number.
- `R3`'s table. This ADR narrows the doubt `OPEN-11` raised over `R3`; it does not remove it,
  and `R28` §8 says so.
- Autovacuum's threshold defaults on a managed offering — `미확인`, not quoted from memory.
