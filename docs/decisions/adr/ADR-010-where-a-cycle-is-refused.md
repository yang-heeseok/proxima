# ADR-010 — A cycle is refused by a row predicate, not by a guard

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Status**: Accepted
> **Measured by**: `docs/reports/R21`

## Context

`R20` opened a transitive read of `concept_edge`. `R21` measured what a cycle does to it, and
the finding is that the three shapes such a read can take die three different deaths — and
two of them are indistinguishable from a healthy graph under load:

```
union all,   unbounded, cyclic   57014 -- ERROR: canceling statement due to statement timeout
union all,   unbounded, acyclic  57014 -- ERROR: canceling statement due to statement timeout
```

The question this ADR answers is **not** how to stop a cycle. It is **where**, because both
places cost something and a decision made with one of the two priced is not a decision.

`V1__baseline.sql` has carried an answer since the first commit:

> *Acyclicity is asserted by the generator and checked by a test — a **CHECK constraint
> cannot express it**, and that gap is recorded rather than hidden.*

That sentence is correct about acyclicity in general. A `CHECK` sees one row; a cycle is a
property of the whole graph. It was read as a statement about *this schema* for eleven days.

## Decision

**`V5` adds `check (prerequisite_id < concept_id)`. Nothing guards the read beyond the depth
bound `ADR-011` owns, and no trigger ships.**

The generator already holds something strictly stronger than acyclicity, and its own KDoc
says so: *every edge runs from a lower concept id to a higher one.* If that is true of every
row then a prerequisite walk strictly decreases the id at every hop and cannot return to its
start. **That is a proof of acyclicity, and it is a per-row predicate.**

## The three options, priced

Every figure is from `R21`, on the shipped graph — 3,000 concepts, 8,994 edges.

| | refuses a cycle | cost when there is none | race-safe |
| --- | --- | --- | --- |
| a `BEFORE INSERT` trigger running a reachability query | yes, alone | **2.33 ms/row** batched, 2.20 ms/row set-based | **no** |
| a read-side path array | yes | **324× rows and time at depth 12** | n/a |
| a read-side node-only `union` | yes | loses the depth column entirely | n/a |
| **`check (prerequisite_id < concept_id)`** | **the edge cannot exist** | a comparison of two `bigint`s | **yes** |

### Why not the trigger

**It costs, and the cost falls in the wrong place twice.** 2.33 ms per inserted row is not
large in isolation. `concept_edge` has exactly one writer — `seed/`'s loader — and a
`BEFORE INSERT … FOR EACH ROW` trigger fires per row however the rows arrive, which
`R21` §3.6 measured on a set-based insert as well as a batched one. So a guard added for a
write path that does not exist would be charged to the one that does.

**And it cannot promise what it appears to promise.** Two transactions, two edges that are
individually fine, neither able to see the other's uncommitted row. Both commit; the graph
has a cycle. Then, from `R21` §3.6:

```
cycle present: yes. re-issuing the accepted statement now gives:
  23514 -- ERROR: concept_edge 3001 -> 3002 would close a prerequisite cycle
```

The same statement, accepted and then refused, with nothing in between except somebody else
committing.

This is `R7` with the difference that decides the whole question. There, two concurrent
requests both passed an application-level existence check and `V3` closed it with a unique
constraint — **because uniqueness is a property of a row and the database can hold it.**
Acyclicity is a property of the whole graph. A trigger reduces the window and does not close
it, and the things that would — `SERIALIZABLE`, or a table lock on every edge insert — are
not what a trigger gives you and are not free.

### Why not the read-side defence

The read arm's cost falls on every request, for ever, on a graph that is acyclic today and
will be acyclic on almost every request. So the number that decides it is not what the guard
costs on a cyclic graph; it is what it costs on the graph that ships. `R21` §3.5:

| depth | `union` + depth | with a path array |
| --- | --- | --- |
| 6 | 343 rows, 2.897 ms | 1,092 rows, 4.607 ms |
| 12 | 2,209 rows, 4.994 ms | **797,160 rows, 1,616.873 ms** |

**324×, to defend against something that is not there.** The mechanism is worth carrying out
of this ADR because it is not about cycles at all: `union` deduplicates whole rows, a `path`
column makes every row unique, and the recursion silently degenerates into `union all`. The
same thing happens to a `depth` column. **Every column added to a recursive term for a good
reason removes the deduplication you already had.**

The node-only `union` keeps the immunity and loses the depth, and depth is what makes a
closure a *proximal* zone rather than a list.

### Why the constraint wins

It is checked **without a snapshot**. `prerequisite_id < concept_id` is true or false of a
row on its own, so the concurrent insert that defeats the trigger is refused by it —
`CycleGuardCostTest` measures that first, then lifts `V5` so that the trigger can fail.

It costs nothing measurable: a comparison of two `bigint`s, on a table with one writer.

And it removes the fragility from the schema rather than paying for it in the query, which
is what makes `R20`'s and `R21`'s conclusions compatible. `R20` chose one statement over the
application walk on statement counts, 1 against 138. `R21` found the application walk is the
arm a cycle cannot kill, because a `HashSet` deduplicates on node identity where `union`
deduplicates on row identity. **The faster read is the more fragile one**, and `V5` is what
makes that choice free.

## Consequences

**What this buys.** A cycle is not a thing that can be in this database. `R21`'s three deaths
are unreachable rather than defended against, and every recursive read here can be written
for the cheap case.

**What this costs, and it is not small.** Acyclicity is welded to the surrogate key.
`concept.id` is `generated by default as identity`, so a new concept always gets a higher id
than every existing one and **can never become a prerequisite of an existing concept.** A
curriculum author who realises that fractions belong before division cannot say so.

`ck_concept_edge_forward` is also **stronger than acyclicity**: plenty of legal DAGs violate
it — any whose topological order disagrees with its insertion order. This repository's graph
does not, by construction. That is a narrow warrant for a permanent constraint and it is
stated rather than implied.

**What makes it acceptable today** is the test `ADR-007` used to decline a guard: it protects
**data that exists** rather than data somebody might write. `concept_edge` has one writer and
that writer emits forward edges by construction.

**What this rules out.** A curriculum-editing path, until the constraint is lifted.

## What would flip this decision

**A second writer for `concept_edge`.** Every argument above rests on there being exactly
one, and `ADR-009` closed the equivalent question for the recording path by declining an
endpoint rather than by assuming one would never exist.

The migration that lifts `V5` is **not a `drop constraint`.** It needs an explicit ordering
column on `concept` — a `sequence_no` that is not the primary key — so that the ordering the
graph depends on stops being an accident of insertion order. At that point the trigger's
2.33 ms/row becomes the live number, `R21` §3.6 is its starting point rather than its answer,
and the race there — reproduced at two connections and **미측정 at 200** — is the first thing
to re-measure.

## What was not measured

- **`COPY` with a row trigger.** `R21` §3.6. The PostgreSQL driver is `runtimeOnly` in
  `api/build.gradle.kts`, so `CopyManager` is not on the test compile classpath. A row trigger
  fires per row whatever the statement shape, which two statement shapes establish; the
  `COPY` figure itself was not taken.
- **The trigger under real concurrency.** The race is reproduced at two connections. What it
  does at 200 is 미측정, and the direction — more concurrency, wider window — is mechanism
  rather than measurement.
- **`SERIALIZABLE` as an alternative to the constraint.** Not run. It would convert the race
  into serialisation failures on the write path, which is a different trade and a real one,
  and this ADR declines it on the grounds that it prices a write path that does not exist.
- **Whether a graph that violates `ck_concept_edge_forward` and is still a DAG would ever
  arise here.** It cannot from the generator. Whether a hand-authored curriculum would need
  one is a domain question nobody has been asked.
