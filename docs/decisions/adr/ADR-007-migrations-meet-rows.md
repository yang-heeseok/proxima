# ADR-007 — Migrations are tested against rows, not screened for syntax

> **Created**: 2026-08-18
> **Updated**: 2026-08-18
> **Status**: Accepted
> **Closes**: `OPEN-7`

## Context

`R15` found a migration that passed every test and could not run. `V3`'s deduplication used a
correlated subquery, which PostgreSQL evaluates once per row; on the seeded database — 600,000
`mastery` rows with no index on `(learner_id, concept_id)`, the index `V3` itself adds — it was
still executing at **32 minutes** when it was killed. The rewritten form took **768 ms**.
Planner cost, verbatim from `R15` §3: **9,139,221,232 against 34,214.**

CI had been green for four days. The reason is not subtle:

> **Every test in this repository applies migrations to an empty schema.** Flyway runs at
> container start, before any fixture inserts anything. `V3`'s delete had never deleted a row.

`R15` §8 asked whether a check should refuse a migration containing a correlated subquery, and
answered *"one could"*. `R19` found that sentence sitting in a *남는 위험* section where nobody
had to act on it, and moved it to `OPEN-7`.

## Decision

**No syntax rule. The coverage moves instead: `PopulatedMigrationTest` runs the migrations
against a table that already holds rows, and asserts the plan of every DML statement they
contain.**

Two tests, both against a real PostgreSQL container:

| | What it does |
| --- | --- |
| *every migration applies to a table that already holds rows* | `V1`, then 20,000 `mastery` rows of which 5,000 are planted duplicates carrying a **different score**, then `V2` and `V3`. Asserts one row per pair survives, that the survivor is the earlier one, and that `V3` reached its constraint |
| *no migration statement is planned to run per row* | Every `insert`/`update`/`delete`/`select`/`with` statement, read **out of the migration files on the classpath**, `EXPLAIN`ed against that populated schema. Fails on a `SubPlan` |

## Why not the rule `R15` §8 named

**1. It would protect nothing that exists.** `MigrationDeduplicationTest` already gates `V3`'s
statement, and `V3` is the only migration here that deduplicates. A text rule would guard the
next migration and no current one. `AGENTS.md` §Scope has a name for that — *a guard that
protects nothing yet is not free, it is unbanked* — and `R0` §4 has the count that gives the
name its weight: **nine test classes here exist to refuse a future edit and exactly one has
ever been paid.**

**2. The syntax is not the defect.** A correlated subquery is one shape the real failure took.
The failure is that *migrations are only ever tested on empty tables*, and the next instance
could be an unindexed bulk `UPDATE`, a `NOT IN` over a large table, or a trigger firing per
row. A rule spelled for subqueries passes all of those and reports a clean bill.

**3. A rule that fires on correct text is a rule nobody reads**, and this repository has paid
that price twice with numbers attached: `R7` §3.5, where the `T3` self-invocation rule flagged
Kotlin's synthetic `$default` bridges and had to learn to skip them; and `R17` §5, where a
prose check fired correctly on the stale tree **and again on the corrected one**, because the
correction quotes the sentence it replaced.

**4. PostgreSQL is a better judge than a regex.** A correlated subquery the planner flattens
into a join is not quadratic and should pass. A text rule cannot tell the difference; `EXPLAIN`
answers it exactly.

## Why the plan and not a clock

The obvious assertion is a time limit, and it is not available here.
`measurement-discipline.md` rule 9: **CI asserts nothing that is a duration.** A shared runner
of unstated size produces no number comparable to anything, and `ADR-004` exists because that
rule was broken once, in `R9` §3.6, by someone who had read it.

A plan is categorical. `SubPlan` under a scan node means *evaluated per row* on any machine, at
any speed, and that is precisely the property separating `R15`'s two statements. The assertion
survives being moved to a laptop, a runner, or a machine ten times faster, and none of those
change its answer.

## What this costs, and what it does not cover

- **Two more container starts.** `PopulatedMigrationTest` runs its own `PostgreSQLContainer`,
  as `MigrationDeduplicationTest` and `H2DivergenceTest` already do. `R9` §3.6 measured
  container reuse as worth ~1.2 s against test classes costing 198–422 s.
- **20,000 pairs is not 600,000.** The row count is chosen so a sequential scan is the
  planner's honest choice while the test stays quick. **It is not chosen to reproduce `R15`'s
  32 minutes**, and it would not: the old statement at this size would finish in seconds and a
  timing assertion would have passed it. That is the second reason the assertion is a plan.
- **DDL is not covered.** `EXPLAIN` does not apply to `CREATE INDEX` or `ALTER TABLE`, so `V2`
  and half of `V3` are exercised by the first test and not the second. A `CREATE INDEX` that
  locks a large table for an hour is a real migration hazard and **is not caught here** —
  recorded rather than implied.
- **One statement is under the plan assertion today.** `V3`'s delete. That is the same
  unbanked-ness the syntax rule was declined for, with one difference that decides it: this
  check **also protects the statement that exists**, and it grows to cover every future DML
  migration without anyone editing it.

## Alternatives, and what would have made one of them right

| Option | Why not |
| --- | --- |
| A regex or ArchUnit-style rule over migration text | §*Why not the rule* above — unbanked, wrong target, and false-positive cost this repository has already measured |
| A timing assertion on migration wall time | Forbidden by rule 9. It would also have passed on the red statement at any row count a test can afford |
| Run the full seeded dataset in CI | 3,963,719 rows. `publication-readiness.md` already records that this project's CI will grow a Testcontainers lane that is not cheap, and this would be several minutes on every push to buy a number rule 9 forbids quoting |
| Do nothing; `MigrationDeduplicationTest` is enough | It gates one statement's **correctness** on six rows. `R15` §8 says so itself: *"the gate proves correctness on six rows, not cost on six hundred thousand"* |

**What would flip this decision:** a second migration whose hazard is textual rather than
planned — one where `EXPLAIN` looks fine and the statement is still wrong at scale. A
`CREATE INDEX` without `CONCURRENTLY` on a live table is exactly that shape, and if one is ever
written here, the check above will not see it.
