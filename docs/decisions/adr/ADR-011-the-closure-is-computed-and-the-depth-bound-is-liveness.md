# ADR-011 — The closure is computed on read, and the depth bound is liveness rather than domain

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Status**: Accepted
> **Measured by**: `docs/reports/R20`, `docs/reports/R21`, `docs/reports/R22`

## Context

`PrerequisiteQueries.closure` walks `concept_edge` transitively in one statement, bounded by
a `maxDepth` the caller passes. Two things about that were unargued when it was written, and
both are decisions rather than details.

**Whether the closure is computed at all.** The alternative is a materialised transitive
closure — a `concept_closure(concept_id, prerequisite_id, depth)` table maintained on write —
which turns every read into a single index lookup. This is the standard answer for graphs in
a relational database and it is not obviously wrong.

**What the depth bound is.** `maxDepth` reads like a domain statement — *a learner's
prerequisites go six levels back* — and it is not one. It is the only thing standing between
this read and a chain `PrerequisiteDepthTest` measured at **294 edges**, and it is
accidentally the only thing that made the shipped read survive a cycle before `V5` existed
(`R21` §6).

## Decision

**The closure is computed on every read. `maxDepth` is a liveness bound, documented as one,
with no default and no policy value — and no API offers a page over an expanded closure.**

### Computed, not materialised

| | read | write | truth |
| --- | --- | --- | --- |
| computed (`WITH RECURSIVE`) | 1 statement, 2,209 working rows at depth 12, **4.994 ms** | nothing | one place |
| materialised closure table | 1 index lookup | every edge insert rewrites every affected ancestor pair | two places that can disagree |

The measured read is 4.994 ms at depth 12 on the shipped graph, in one statement, on a
projection that holds no session (`R20` §6). **A closure table is a cache**, and `ADR-005`
already refused a cache layer in this repository with measurements rather than an argument:
PostgreSQL's own buffer cache had already moved a conclusion here, and a cache hides the
thing being measured.

The size argument is available and it is the one that settles it. The graph reaches 606
concepts at depth 14 from a single start; a full closure over 3,000 concepts is a table whose
row count is the sum of every concept's ancestor set, and every edge inserted invalidates a
slice of it. **That is write amplification bought for a read that already costs five
milliseconds.**

There is a condition that flips it, and it is not "the graph got bigger": it is *the closure
is read more often than the graph is written, by enough that five milliseconds times the read
rate exceeds the rewrite cost times the write rate.* `concept_edge` has one writer today —
`seed/`'s loader — so the write rate is zero and the trade is not close. **When a
curriculum-editing path exists, this is the second thing it changes** (`ADR-010` is the
first).

### The bound is liveness, not domain

`maxDepth` does not say anything about learning. It says the traversal must stop.

- The longest chain in the shipped graph is **294 edges** (`R20` §3.1). An unbounded closure
  is not a hypothetical cost.
- With `union all`, the working table at depth 14 is **7,174,452 rows** for 606 concepts.
- Before `V5`, the bound was the *only* reason the shipped read returned on a cyclic graph:
  `R21` §6 measured the identical query unbounded timing out with `57014`, and the shipped
  one returning 512 concepts in one statement. **The read was cycle-safe by accident of a
  clause written for an unrelated reason**, and the accident would have ended the day somebody
  raised `maxDepth` to reach the full chain.

So: no default. `PrerequisiteGraph` requires `maxDepth >= 1` and takes it as an argument, and
every caller has to say a number out loud. A default would be a policy nobody chose, in a
parameter whose value changes what the answer *is* — which is `R22` §3.3's finding.

### No page over an expanded closure

`R22` measured what *the next 20* becomes once the graph opens:

- `limit` inside the recursion and `limit` after it share **1 item of 20**, and **182 of the
  answer's 202 items** cannot be reached by any page;
- `order by difficulty, id` — the domain's answer — loses **5 of 20** rows off page 1 when
  `maxDepth` moves from 6 to 7;
- `order by depth, difficulty, id` is stable and is no longer *easiest first*.

**Neither ordering is both meaningful and stable**, so the API does not offer the choice.
`docs/explanation/domain-model.md` says the rule returns `N`; `RecommendationService` takes a
`limit` and no offset; there is no page 2 in this application. A `limit` with no offset over
an ordering that is stable while `maxDepth` is fixed is exactly as correct as the graph is
static — and `maxDepth` is not a request parameter.

The day paging is offered, `order by depth, difficulty, id` is the ordering, the cost is that
the page means *nearest* rather than *easiest*, and `R22` is what that feature reads first.

## Consequences

**What this buys.** One place holds the truth about the graph. A read is one statement and
five milliseconds. Nothing has to be invalidated when an edge changes, because nothing is
derived and stored.

**What this costs.** Every read pays the traversal — 5,424 rows fed through `concept_edge`
at depth 12 even with `V4`'s index — and the cost grows with depth in a way an index does not
flatten: `R20` §3.5 measured the index's advantage falling from 500× at depth 3 to 18.2× at
depth 12. **An index is not a substitute for a depth bound**, and this decision leans on the
bound.

**What this rules out.** Answering *is A a prerequisite of B* in constant time, and answering
*every concept whose closure contains X* at all — the reverse direction has no index and no
query here. Both are 미측정 and neither is asked for.

## Alternatives, and what would have made one of them right

| Option | Why not |
| --- | --- |
| a materialised `concept_closure` table | a cache, refused by `ADR-005`'s reasoning; write amplification bought for a 5 ms read on a table with no writer |
| a `maxDepth` constant in `PrerequisiteGraph` | the value changes what the answer is (`R22` §3.3); a constant would be a policy nobody chose |
| no bound at all, relying on `V5` for termination | `V5` guarantees termination and not affordability — the 294-edge chain still exists, and `union all` at depth 14 is 7,174,452 rows |
| offering offset paging with `order by difficulty, id` | measured unstable: 5 of 20 rows change on page 1 when `maxDepth` moves |

**What would flip this decision:** a read rate high enough to price the traversal against a
closure table's write amplification, on a graph with a writer. Both halves have to be true,
and today neither is. The number that would decide it — what a transitive closure read costs
at 200 VU beside the recommendation query — is **미측정**, and `R20` §8 says so.

## What was not measured

- **The closure read under load.** Every figure here is a single-query median of three at
  concurrency 1. `R16` §3.4 is why it would not be a simple addition: four requests in five
  on this dataset answer with an empty list.
- **A materialised closure table at all.** Its size, its rewrite cost, and its read latency
  are 미측정. This ADR declines it on `ADR-005`'s reasoning and on a write rate of zero, not
  on a measurement of the thing declined.
- **Keyset paging over `(depth, difficulty, id)`.** `R3` measured keyset beating offset at
  depth on `attempt`. Whether a keyset cursor survives a change of `maxDepth` is mechanism
  here — the tuple only grows at the end — and mechanism is not a measurement. `R22` §8.
- **Any depth bound's *domain* correctness.** Nothing in this repository knows whether a
  learner's proximal zone is two levels deep or ten. `domain-model.md` is explicit that a
  recommendation policy cannot be validated without learners, content, and teachers, and this
  ADR is careful to decide only the liveness half.
