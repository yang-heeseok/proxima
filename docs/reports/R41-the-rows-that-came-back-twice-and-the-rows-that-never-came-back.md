# R41. The rows that came back twice, and the rows that never came back

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none, and it is not an omission.** Nothing in this application is a defect
> of this shape — §2 is the sweep that establishes it. `R26` is the precedent for this header.
> **Instrument**: `85943b0` — `TieBreakPagingTest`, which plants the tie in a table it creates
> and drops.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e766…0685 — server 16.15,
                   x86_64-pc-linux-musl
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Dataset        : 100 rows in 4 tied groups of 25, page size 10 — planted by the test.
                   Separately, 20,000 attempt rows for the plan question in §3.4
  Load           : none. Every number here is a row count or a plan shape
  Concurrently   : slices D and E were active. Row counts and plan shapes are logical
                   facts about code and data and do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

⛔ **Scope, decided before measuring.** This report is **not** about collation. `R25` measured
musl-versus-glibc divergence across two containers with five probes and `R26` priced
locale-aware collation; `ADR-014` rows `9.1` and `D.8` are closed by them. The sort column below
is an **integer**, chosen so that this report cannot drift into that axis even by accident.

---

## 1. 증상 / Symptom

A caller walked every page of a result set and processed what it received. It processed one row
twice. It never saw another row at all.

Nothing threw. No constraint was violated. Every page returned exactly the number of rows it
asked for. The application was not told, the log says nothing, and **the only way to find out is
to count.**

⭐ **This is not an ordering complaint.** "The rows came back in a slightly different order" is a
cosmetic problem. "A row exists and appeared on no page" is silent data loss, and it is produced
by the same missing clause.

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.TieBreakPagingTest' --rerun-tasks
```

### 2.1 Where it is reachable from in this application — nowhere

⭐ **This subsection is the reason there is no red commit, and it was decided before anything was
measured.**

A sweep of every `order by` and every `Pageable` in `api/src/main`, re-run at `85943b0`:

| Site | Ordering | Paged? | Sort key unique? |
| --- | --- | --- | --- |
| `PrerequisiteQueries.kt:64` | `order by e.prerequisite_id` | no | ✔ |
| `PrerequisiteQueries.kt:104` | `order by min(w.depth), w.prerequisite_id` | no | ✔ |
| `RecommendationQueries.kt:66` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:124` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:158` | `order by a.attempted_at desc, a.id desc` | `limit` only | ✔ |
| `LearnerPageQueries` × 4 | none in the JPQL | the only `Pageable` in `main` | **nothing in `main` calls it** |

**How the matcher was excluded.** A naive `grep -i "order by"` over `api/src/main` reports eight
lines. Three are KDoc prose *about* ordering and one is a SQL `--` comment; they execute nothing.
Counting them would have inflated the population by 60%, and an instrument that counts itself is
the failure mode `R8` §3.3 records. Comment lines are excluded by their leading `*`, `//` or
`--`, leaving the five real orderings above.

**Conclusion: no reachable paged ordering on a non-unique key exists in `api/src/main`.**

⛔ **So the tie is planted in an instrument, not in a shipped query.** Planting one in a shipped
query and calling it reproduced is manufacturing a failure, which the preamble forbids. The
fixture table is created and dropped by the test, in a schema of its own.

### 2.2 A measurement fixture has to be cheaper than its subject

⭐ **Recorded because it was nearly not true here, and because it was caught by reading rather
than by running.**

The 20,000-row seed for §3.4 was first written with two correlated `join lateral (… limit 1)`
subqueries — one for a learner id, one for an item id, per generated row. That is **40,000
subquery executions to insert 20,000 rows**: a fixture that costs more than the query it exists
to plan, on a machine that was at the time reserved for another slice's load window. It now
aggregates both id sets into arrays once and indexes them.

The array form also drops an assumption the first version did not need to make either way — that
freshly inserted ids are consecutive, which holds only until another test consumes part of the
sequence.

Nothing failed to reveal this. It was found by re-reading the fixture while the machine was
unavailable, which is the only way it could have been found without spending someone else's
window on it.

### 2.3 `RecommendationQueries.kt:158` is a different defect, and saying so is part of this report

Slice H landed that ordering and named slice G as the owner of the general form. The two are not
the same defect:

| | top-`n` on a non-unique sort | `LIMIT`/`OFFSET` paging on a non-unique sort |
| --- | --- | --- |
| what moves | **the boundary row** — which of the tied rows is the *n*-th | **which rows appear at all** |
| symptom | a computed value wobbles without the data changing | a row is returned twice; another is returned never |
| is anything lost? | no — every row is still there | **yes** |
| remedy | a unique tie-break | a unique tie-break **or** keyset paging, and they differ — §5 |

⛔ This report does **not** re-measure `recentOutcomesByCount`. `R44` §3 paid for the specific
case and slice H owns it.

## 3. 계측 / Measurement

### 3.1 How many came back twice, and how many never

PENDING — the measurement window is held for slice D.

100 rows, 4 tied groups of 25, page size 10. The sizes are chosen so that **every page boundary
but the last falls inside a group of tied rows** — a walk whose pages align with the groups
crosses no tie and would report that this defect does not exist, which is exactly why a
single-page unit test never catches it.

| Walk | Returned | Twice | Never |
| --- | ---: | ---: | ---: |
| `order by grp`, one plan throughout | | | |
| `order by grp`, plan changes between pages | | | |
| `order by grp, id`, plan changes between pages | | | |
| keyset `(grp, id) > (…)`, plan changes between pages | | | |

**Why the plan is changed between pages, and why that is honest.** Two pages are two requests.
Between them autovacuum can run, statistics can move, an index can be created, or the row count
can cross a threshold. Toggling `enable_seqscan` makes that **deterministic and reproducible**
instead of waiting for it to happen by luck. It is a model of a real event, not a thumb on the
scale.

⚠️ **The defect arm is reported, not asserted non-empty.** If this machine's planner returns tied
rows identically under both plans, that is a result — the honest output is then "it would not
shift here" plus a measurement of what held it stable. Asserting a failure would make the test
lie on a machine where the defect does not appear.

### 3.2 What the tie-break costs the index — plan shape

PENDING.

### 3.3 `EXPLAIN`, deliberately, and not `EXPLAIN ANALYZE`

⭐ **This is a choice with a reason, not an incidental one.**

The question is which nodes the plan contains. That is a logical fact about the query, the schema
and the statistics. `EXPLAIN ANALYZE` would answer the same question **and** produce actual
execution times. This session does not hold the machine's timing lock, and the right response to
that is to choose the form of the query that **cannot** produce a duration rather than to take
one and discard it. Every figure below is a node name or a planner estimate in cost units, and a
planner cost estimate is not a millisecond.

### 3.4 `ADR-014` row `44.3`, on the real table and the real index

PENDING.

`44.3` reads: *"plan cost of `recentOutcomesByCount`'s `attempted_at desc, id desc` tie-break —
does `V2`'s index still serve it without a sort"*, noted *"the comparison was refused rather than
approximated."*

20,000 `attempt` rows are seeded with `generate_series` — the technique `PopulatedMigrationTest`
uses on these same tables — because **a plan taken against an empty table is a plan for an empty
table** and would answer a different question. `analyze` runs before the `EXPLAIN` so the planner
decides on statistics rather than on defaults. Rows *and* histogram are removed afterwards: a
later test planning against 20,000 phantom rows would be a defect introduced by a measurement,
which is the worst kind.

| Query | Plan |
| --- | --- |
| `order by a.attempted_at desc` (no tie-break) | |
| `order by a.attempted_at desc, a.id desc` (as shipped) | |

⚠️ **This answers at most half of `44.3`.** The row says *"plan cost"*. Plan **shape** is
answered here; what the tie-break costs **in time** is 미측정 and stays open. See §8.

## 4. 원인 / Mechanism

`ORDER BY` on a column whose values are not unique specifies a **partial** order. Rows that tie on
the sort key may be returned in any order, and the database is not promising to choose the same
one twice — it is free to return whatever its access method and its sort produce.

`LIMIT`/`OFFSET` then makes that promise load-bearing without asking for it. Each page is a
**separate execution of the same statement** with a different `OFFSET`. The database computes an
ordering, discards `OFFSET` rows from the front, and returns the next `LIMIT`. If the second
execution orders the tied rows differently from the first, the row that sat at position 10 in one
ordering can sit at position 11 in the other — so it is discarded by page 2's `OFFSET` **and** was
never returned by page 1.

**Both failures come from the same shift, and they come in pairs**: for every row that appears
twice, some other row appears not at all, because each page still returns exactly `LIMIT` rows.

## 5. 처방 / Remedy

⛔ **"Always add a tie-break" is not the conclusion of this report.** It is one of two remedies,
they solve different problems, and one of them also fixes something a tie-break cannot touch.

| Option | Solves | Gives up | Chosen |
| --- | --- | --- | --- |
| A — append a unique tie-break to the sort key | the order becomes **total**, so two executions of the same statement agree | the index that served the old sort may no longer serve the new one — §3.2 measures whether it does. Does **not** fix drift under concurrent insert/delete | |
| B — keyset (seek) paging, `where (k, id) > (…)` | the same, **and** the page no longer depends on a row count: rows inserted or deleted earlier in the sequence cannot shift later pages | cannot jump to page *n* without walking; a "page 47" UI is not expressible; the predicate must match the sort key exactly | |
| C — snapshot the whole result and page in memory | exact | cost grows with the result set, not the page. Fine for hundreds, not for the `attempt` table | |
| D — a repeatable-read transaction spanning every page | exact | a transaction held open across user think time — `R4` measured what holding a connection across a request costs | |

⭐ **The difference that decides it.** A tie-break makes the *ordering* deterministic. It does
**not** make `OFFSET` stable: insert one row that sorts before page 1 and every subsequent page
shifts by one, tie-break or no tie-break, and a row is skipped. Keyset paging is immune to that
because it names *where it got to* rather than *how many it has passed*.

So the two remedies answer different questions:

- *"can the same query disagree with itself?"* → **a tie-break**, and it is necessary either way,
  because keyset paging on a non-unique key is broken for the same reason `OFFSET` is;
- *"can the data change under me between pages?"* → **keyset paging**, and a tie-break does
  nothing for it.

**A tie-break is a precondition for keyset paging, not an alternative to it.**

PENDING — what this repository should do, and what would have made a different option correct.

## 6. 재계측 / Re-measurement

Not applicable in the usual sense: **nothing in the application changed, because nothing in the
application was defective.** §3's remedy arms are the re-measurement — the same walk, the same
plan changes, with the sort key made total.

## 7. 회귀 게이트 / Regression gate

PENDING.

⚠️ **What a gate here can and cannot be.** The sweep in §2.1 is a fact about one commit. A gate
that asserted "no paged non-unique ordering exists" would have to parse SQL out of `@Query`
annotations and decide uniqueness against the schema, which is a static analysis this repository
has no instrument for. What is cheap and honest is a test that keeps the *instrument* working, so
that the day someone does add a paged ordering the counting apparatus is already there and
trusted.

## 8. 남는 위험 / Remaining risk

- **`44.3` is half-answered and is not closed.** Plan shape measured; plan cost **in time** 미측정,
  because it needs a duration and this session does not hold the timing lock. The precedent for
  recording it this way is `43.3`, which slice H **corrected rather than closed** when it turned
  out to be wrong in one half and right in the other. Ledger row `41.1`.
- **The sweep is a fact about `85943b0` and nothing keeps it true.** `LearnerPageQueries` exists,
  is `Pageable`, and is one caller away from being a live instance of this defect — its KDoc says
  nothing calls it *yet*. `R26`'s risk section has the same shape and for the same reason.
- **The measurement is at one page size on one table with one tie density.** How the counts scale
  with page size, group size, or row count is 미측정.
- **Plan shifts were induced with `enable_seqscan`.** That is a faithful model of a plan changing
  between requests and it is not a proof that *this* application's plans do change. Whether
  autovacuum, statistics drift, or index creation produce the same effect on the shipped tables is
  미측정.
- **PostgreSQL only.** Whether another engine's `LIMIT`/`OFFSET` behaves the same on tied rows is
  미측정, and `R9` is the report about assuming one database answers for another.
- **What would break the conclusion:** somebody adding a `Pageable` read on a non-unique sort, or
  calling `LearnerPageQueries` from application code. Either turns this report from `R26`'s shape
  into `R6`'s.
- **Which earlier §8 bullet this falsifies:** PENDING.

## 9. 배운 것 / What I learned

PENDING — written the same day as the measurement.
