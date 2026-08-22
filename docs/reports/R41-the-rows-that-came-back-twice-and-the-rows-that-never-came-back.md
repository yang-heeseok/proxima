# R41. The rows that came back twice, and the rows that never came back

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none, and it is not an omission.** Nothing in this application is a defect
> of this shape — §2.1 is the sweep that establishes it. `R26` is the precedent for this header.
> **Instrument**: `85943b0`, mechanism added at `ae2b2da` — `TieBreakPagingTest`, which plants
> the tie in a table it creates and drops.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767
                   934dd0a95e671f9a0fc20685 — server 16.15 on x86_64-pc-linux-musl.
                   Read from TestcontainersConfiguration.kt:72 and the container's own
                   Flyway line, NOT from measurement-discipline.md, which is wrong
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Dataset        : 100 rows in 4 tied groups of 25, page size 10 — planted by the test.
                   Separately, 20,000 attempt rows for the plan question in §3.5
  Load           : none. Every number here is a row count or a plan shape
  Concurrently   : slices D and E were active. Row counts and plan shapes are logical
                   facts about code and data; they do not contend
  Repetitions    : counts, not timings. §3.1 and §3.4 reproduced identically across the
                   full run and the targeted re-run
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

⛔ **Scope, decided before measuring.** This report is **not** about collation. `R25` measured
musl-versus-glibc divergence across two containers with five probes and `R26` priced
locale-aware collation; `ADR-014` rows `9.1` and `D.8` are closed by them. The sort column below
is an **integer**, chosen so this report cannot drift into that axis even by accident.

---

## 1. 증상 / Symptom

A caller walked every page of a result set and processed what it received. It processed one row
**twice**. It never saw another row **at all**.

Nothing threw. No constraint was violated. Every page returned exactly the number of rows it
asked for, and the totals add up: **100 rows returned across ten pages of ten, out of a table of
100.** The arithmetic is perfect and the contents are wrong.

⭐ **This is not an ordering complaint.** *"The rows came back in a slightly different order"* is
cosmetic. *"A row exists and appeared on no page"* is silent data loss, produced by the same
missing clause.

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.TieBreakPagingTest' --rerun-tasks
```

### 2.1 Where it is reachable from in this application — nowhere

⭐ **This subsection is why there is no red commit, and it was decided before anything was
measured.**

A sweep of every `order by` and every `Pageable` in `api/src/main`, re-run at this slice's own
SHA rather than inherited:

| Site | Ordering | Paged? | Sort key unique? |
| --- | --- | --- | --- |
| `PrerequisiteQueries.kt:64` | `order by e.prerequisite_id` | no | ✔ |
| `PrerequisiteQueries.kt:104` | `order by min(w.depth), w.prerequisite_id` | no | ✔ |
| `RecommendationQueries.kt:66` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:124` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:158` | `order by a.attempted_at desc, a.id desc` | `limit` only | ✔ |
| `LearnerPageQueries` × 4 | none in the JPQL | the only `Pageable` in `main` | **nothing in `main` calls it** |

**How the matcher was excluded.** A naive `grep -i "order by"` over `api/src/main` reports
**eight** lines. Three are KDoc prose *about* ordering and one is a SQL `--` comment; they
execute nothing. Counting them would have inflated the population by 60%, and an instrument that
counts itself is `R8` §3.3's failure mode. Comment lines are excluded by their leading `*`, `//`
or `--`, leaving the five real orderings above.

**Conclusion: no reachable paged ordering on a non-unique key exists in `api/src/main`.**

⛔ **So the tie is planted in an instrument, not in a shipped query.** Planting one in a shipped
query and calling it reproduced is manufacturing a failure, which the preamble forbids. The
fixture table is created and dropped by the test, in a schema of its own.

### 2.2 A measurement fixture has to be cheaper than its subject

Recorded because it was nearly not true here, and because it was caught by reading rather than by
running.

The 20,000-row seed for §3.5 was first written with two correlated `join lateral (… limit 1)`
subqueries per generated row — **40,000 subquery executions to insert 20,000 rows**, a fixture
costing more than the query it exists to plan, on a machine reserved at the time for another
slice's load window. It now aggregates both id sets into arrays once and indexes them. The array
form also drops an assumption the first version did not need: that freshly inserted ids are
consecutive, which holds only until another test consumes part of the sequence.

### 2.3 `RecommendationQueries.kt:158` is a different defect, and saying so is part of this report

Slice H landed that ordering and named slice G as the owner of the general form. They are not the
same defect:

| | top-`n` on a non-unique sort | `LIMIT`/`OFFSET` paging on a non-unique sort |
| --- | --- | --- |
| what moves | **the boundary row** — which tied row is the *n*-th | **which rows appear at all** |
| symptom | a computed value wobbles without the data changing | a row is returned twice; another never |
| is anything lost? | no — every row is still there | **yes** |
| remedy | a unique tie-break | a tie-break **or** keyset paging, and they differ — §5 |

⛔ This report does **not** re-measure `recentOutcomesByCount`. `R44` §3 paid for the specific
case and slice H owns it.

## 3. 계측 / Measurement

### 3.1 How many came back twice, and how many never

```
R41-PAGING >>> 100 rows, 25 per tied group, page size 10
  order by grp            plan fixed             returned 100 of 100  twice  1  never  1
  order by grp            plan shifts            returned 100 of 100  twice  0  never  0
  order by grp, id        plan shifts            returned 100 of 100  twice  0  never  0
  keyset (grp, id) > ..   plan shifts            returned 100 of 100  twice  0  never  0
```

| Walk | Returned | Twice | Never |
| --- | ---: | ---: | ---: |
| `order by grp`, **one plan, nothing touched** | 100 of 100 | **1** | **1** |
| `order by grp`, planner options toggled between pages | 100 of 100 | 0 | 0 |
| `order by grp, id`, same toggling | 100 of 100 | 0 | 0 |
| keyset `(grp, id) > (…)`, same toggling | 100 of 100 | 0 | 0 |

The sizes are chosen so **every page boundary but the last falls inside a group of tied rows** —
a walk whose pages align with the groups crosses no tie and would report that this defect does
not exist, which is exactly why a single-page unit test never catches it.

⭐ **THE DEFECT APPEARED IN THE ARM BUILT AS THE CONTROL, AND NOT IN THE ARM BUILT TO FORCE IT.**

The first row holds one session, changes no planner setting, and touches nothing — and it lost a
row. The second row deliberately toggled `enable_seqscan` between pages, and came back clean.
**The deliberate intervention stabilised it.**

That is a stronger result than the one this test was designed to produce, and it removes an
argument this report would otherwise have had to make. There is no need to persuade anyone that
plan changes between pages are realistic, **because nothing here changed the plan.** §3.2 is why.

⚠️ **The defect arm is reported, not asserted non-empty.** If a planner returned tied rows
identically under both plans, the honest output would be *"it would not shift here"* plus a
measurement of what held it stable. Asserting a failure would make the test lie on a machine
where the defect does not appear. The remedy arms **are** asserted exactly, at zero.

### 3.2 Why it happened — measured, not assumed

```
R41-OFFSET >>> the same statement, one page apart, planned independently
  offset   0  ->  Index Scan using tied_by_grp on tied  (cost=0.14..13.64 rows=100 width=12)
  offset  10  ->  Index Scan using tied_by_grp on tied  (cost=0.14..13.64 rows=100 width=12)
  offset  20  ->  Index Scan using tied_by_grp on tied  (cost=0.14..13.64 rows=100 width=12)
  offset  30  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  40  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  50  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  60  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  70  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  80  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  offset  90  ->  Seq Scan on tied                      (cost=0.00..2.00 rows=100 width=12)
  distinct scan strategies across the walk: 2
```

⭐ **`OFFSET` is part of the plan. The scan strategy flips between page 2 and page 3, in a walk
that changed nothing.**

Pages 0–2 read the tied rows in **index order**. Pages 3–9 read them in **heap order**. The two
orders disagree about which of the 25 rows in a tied group comes first, so the row sitting on the
page-2/page-3 boundary is returned by both — and the row that would have occupied its slot under
the other ordering is returned by neither. **One duplicate, one loss, which is precisely §3.1's
`twice 1 / never 1`.**

The planner is not doing anything wrong. Each page is a separate execution of a separate
statement, costed on its own: `LIMIT 10 OFFSET 90` must produce 100 rows before discarding 90, and
a sequential scan of a 100-row table (cost `2.00`) beats an index scan (`13.64`) once enough rows
are needed. **The crossover is a cost decision, and the ordering of tied rows is not an input to
it, because `ORDER BY grp` never asked for one.**

⛔ **This was a hypothesis before it was a measurement, and it is written down only now.** The
first run produced §3.1's counts and no explanation; the explanation was guessed, and then the
guess was tested by planning the same statement at every page boundary. Had the plans come back
identical, this section would say the hypothesis failed.

### 3.3 What the tie-break costs the index

```
R41-PLAN >>> plain EXPLAIN, no ANALYZE, no duration produced
  --- order by grp ---
    Limit  (cost=0.14..1.49 rows=10 width=12)
      ->  Index Scan using tied_by_grp on tied  (cost=0.14..13.64 rows=100 width=12)
  --- order by grp, id ---
    Limit  (cost=4.16..4.19 rows=10 width=12)
      ->  Sort  (cost=4.16..4.41 rows=100 width=12)
            Sort Key: grp, id
            ->  Seq Scan on tied  (cost=0.00..2.00 rows=100 width=12)
```

**On this fixture the index stops serving the query.** `order by grp` uses `tied_by_grp`;
`order by grp, id` abandons it for a sequential scan plus an explicit `Sort`. The index covers
`grp` alone, and it cannot supply a second sort key it does not contain.

⚠️ **This is the fixture's answer, not a general law**, and §3.5 shows a case that goes the other
way on the same server. The difference is whether the leading column of the index is the leading
column of the sort — here the tie-break is a column the index has never heard of; there it is a
tie-break appended to a key the index already provides.

### 3.4 `EXPLAIN`, deliberately, and not `EXPLAIN ANALYZE`

⭐ **A choice with a reason, not an incidental one.**

The question is which nodes the plan contains. That is a logical fact about the query, the schema
and the statistics. `EXPLAIN ANALYZE` answers the same question **and** produces actual execution
times. This session does not hold the machine's timing lock, and the right response is to choose
the form of the query that **cannot** produce a duration rather than to take one and discard it.

Every figure above is a node name or a **planner cost estimate**. ⛔ A planner cost estimate is
not a millisecond and is never treated as one here.

### 3.5 `ADR-014` row `44.3`, on the real table and the real index

```
R41-44.3 >>> the shipped recency read, on the real table and V2's index
  attempt rows seeded: 20000   plain EXPLAIN, no ANALYZE, no duration
  --- order by attempted_at desc            (no tie-break) ---
    Limit  (cost=0.29..15.74 rows=20 width=9)
      ->  Index Scan Backward using ix_attempt_learner_attempted_at on attempt a  (cost=0.29..772.72 rows=1000 width=9)
            Index Cond: (learner_id = 1)
  --- order by attempted_at desc, id desc   (as shipped) ---
    Limit  (cost=2.07..18.03 rows=20 width=17)
      ->  Incremental Sort  (cost=2.07..799.93 rows=1000 width=17)
            Sort Key: attempted_at DESC, id DESC
            Presorted Key: attempted_at
            ->  Index Scan Backward using ix_attempt_learner_attempted_at on attempt a  (cost=0.29..772.72 rows=1000 width=17)
                  Index Cond: (learner_id = 1)
```

`44.3` reads: *"plan cost of `recentOutcomesByCount`'s `attempted_at desc, id desc` tie-break —
does `V2`'s index still serve it without a sort"*, noted *"the comparison was refused rather than
approximated."*

⭐ **Answer: `V2`'s index still serves it. The scan is byte-for-byte the same node with the same
estimate (`0.29..772.72`). What the tie-break adds is an `Incremental Sort` above it**, which
exploits `attempted_at` as a **presorted key** and only orders within each group of ties — rather
than discarding the index and sorting everything, which is what happened in §3.3.

| | scan node | extra node | `Limit` estimate |
| --- | --- | --- | ---: |
| no tie-break | `Index Scan Backward using ix_attempt_learner_attempted_at` | — | 15.74 |
| as shipped | **the same node, same estimate** | `Incremental Sort`, presorted on `attempted_at` | 18.03 |

The rows are seeded with `generate_series` — the technique `PopulatedMigrationTest` uses on these
same tables — because **a plan taken against an empty table is a plan for an empty table**.
`analyze` runs before the `EXPLAIN` so the planner decides on statistics. Rows *and* histogram are
removed afterwards: a later test planning against 20,000 phantom rows would be a defect introduced
by a measurement.

⚠️ **This answers `44.3` in one half only.** The row says *"plan cost"*. Plan **shape** is
answered; what the tie-break costs **in time** is **미측정** and stays open — it needs a duration,
and this session does not hold the lock. §8 and ledger `41.1`.

## 4. 원인 / Mechanism

`ORDER BY` on a column whose values are not unique specifies a **partial** order. Rows that tie
on the sort key may be returned in any order, and the database is not promising to choose the
same one twice — it returns whatever its access method produces.

`LIMIT`/`OFFSET` then makes that promise load-bearing without asking for it. Each page is a
**separate execution of the same statement** with a different `OFFSET`: the database computes an
ordering, discards `OFFSET` rows from the front, and returns the next `LIMIT`. §3.2 shows the
ordering is not stable across those executions, because the *plan* is not — and the plan is not
stable because `OFFSET` changes how many rows must be produced, which changes which access method
is cheapest.

**Both failures come from the same shift and they come in pairs**: for every row that appears
twice, some other row appears not at all, because each page still returns exactly `LIMIT` rows.

## 5. 처방 / Remedy

⛔ **"Always add a tie-break" is not the conclusion of this report.** It is one of two remedies,
they solve different problems, and one of them also fixes something a tie-break cannot touch.

| Option | Solves | Gives up | Chosen |
| --- | --- | --- | --- |
| A — append a unique tie-break to the sort key | the order becomes **total**, so two executions agree — measured, `twice 0 / never 0` | the index may stop serving the sort — **measured, §3.3: index scan → seq scan + sort.** Does **not** fix drift under concurrent insert/delete | |
| B — keyset (seek) paging | the same, **and** the page no longer depends on a row count: rows inserted or deleted earlier cannot shift later pages | cannot jump to page *n* without walking; a "page 47" UI is not expressible; the predicate must match the sort key exactly | |
| C — snapshot the result and page in memory | exact | cost grows with the result set, not the page. Fine for hundreds, not for `attempt` | |
| D — a repeatable-read transaction spanning every page | exact | a transaction held open across user think time — `R4` measured what holding a connection across a request costs | |

⭐ **The difference that decides it.** A tie-break makes the *ordering* deterministic. It does
**not** make `OFFSET` stable: insert one row that sorts before page 1 and every later page shifts
by one, tie-break or no tie-break, and a row is skipped. Keyset paging is immune because it names
*where it got to* rather than *how many it has passed*.

So the two answer different questions:

- *"can the same query disagree with itself?"* → **a tie-break**, and it is necessary either way,
  because keyset paging on a non-unique key is broken for the same reason `OFFSET` is;
- *"can the data change under me between pages?"* → **keyset paging**, and a tie-break does
  nothing for it.

**A tie-break is a precondition for keyset paging, not an alternative to it.** Both measured arms
returned `twice 0 / never 0`; they are not distinguished by these counts, and this report does not
pretend the counts choose between them.

**What this repository should do:** nothing yet, and §2.1 is why — there is no paged non-unique
ordering to fix. **When one is written**, `LearnerPageQueries` being the obvious candidate, the
choice is B if the caller walks the pages in sequence and A if it must address them by number.
⚠️ Note what A costs on a real index before choosing it: §3.3 lost the index entirely, §3.5 kept
it. Which of those two a given query gets depends on whether the tie-break extends the index's own
key or introduces a column it does not contain.

## 6. 재계측 / Re-measurement

Not applicable in the usual sense: **nothing in the application changed, because nothing in the
application was defective.** §3.1's remedy arms are the re-measurement — the same walk, the same
conditions, with the sort key made total: `twice 0 / never 0` against `twice 1 / never 1`.

§3.1 and §3.2 reproduced identically across the full run and the targeted re-run.

## 7. 회귀 게이트 / Regression gate

`TieBreakPagingTest`, run by `.github/workflows/build.yml`. Its remedy arms assert `0` duplicates
and `0` losses exactly, so a change that breaks keyset paging or the tie-break turns it red.

⚠️ **What a gate here cannot be.** The sweep in §2.1 is a fact about one commit. A gate asserting
*"no paged non-unique ordering exists"* would have to parse SQL out of `@Query` annotations and
decide uniqueness against the schema — a static analysis this repository has no instrument for.
What is gated is the **instrument**, so that the day someone adds a paged ordering, the counting
apparatus is already there and already trusted.

**This report closes `ADR-014` row `44.3` in its plan-shape half** and leaves the timing half
open as `41.1`. ⛔ It is recorded as *corrected and half-answered*, not closed — the precedent is
`43.3`, which slice H corrected rather than closed when it turned out wrong in one half and right
in the other.

## 8. 남는 위험 / Remaining risk

- **`44.3` is half-answered.** Plan shape measured; plan cost **in time** 미측정, because it needs
  a duration and this session does not hold the timing lock. Ledger `41.1`.
- **The sweep is a fact about one commit and nothing keeps it true.** `LearnerPageQueries` exists,
  is `Pageable`, and is one caller away from being a live instance of this defect — its KDoc says
  nothing calls it *yet*. `R26`'s risk section has the same shape for the same reason.
- ⭐ **The plan flip was observed on a 100-row table, and row count is exactly what drives it.**
  Whether the crossover happens at a comparable *page depth* on a table the size of `attempt` is
  **미측정**. It is not safe to read `offset 30` as a threshold — it is this table's crossover, not
  a general one.
- **One page size, one tie density, one table.** How the counts scale with page size, group size
  or row count is 미측정.
- **The toggled arm's cleanliness is unexplained.** Forcing `enable_seqscan` off on alternate
  pages produced `0/0`; the plausible reading is that it pinned both halves of the walk to one
  access method, but **that was not verified** and this report does not claim it.
- **PostgreSQL only.** Whether another engine's `LIMIT`/`OFFSET` behaves the same on tied rows is
  미측정, and `R9` is the report about assuming one database answers for another.
- **What would break the conclusion:** somebody adding a `Pageable` read on a non-unique sort, or
  calling `LearnerPageQueries` from application code. Either turns this report from `R26`'s shape
  into `R6`'s.
- **Which earlier §8 bullet this falsifies:** none. `R44` §3's tie-break cost was refused rather
  than approximated, and this report answers the plan-shape half of that refusal rather than
  contradicting it.

## 9. 배운 것 / What I learned

이 리포트에서 제일 중요한 줄은 **내가 대조군이라고 부른 쪽에서 나왔다.**

계획을 페이지마다 흔드는 팔을 만들어 놓고 "이게 결함을 재현하는 팔"이라고 생각했는데, 실제로 행을
잃은 건 아무것도 건드리지 않은 쪽이었고 내가 일부러 흔든 쪽은 깨끗했다. 처음엔 계측 버그를 의심했다.
**그리고 그 순간이 이 슬라이스에서 제일 위험한 지점이었다** — 예상과 다른 결과를 계측 오류로 처리하고
넘어가면, 진짜 발견을 손으로 지우는 셈이 된다.

그래서 가설을 적지 않고 **측정했다.** 같은 문장을 페이지 경계마다 `EXPLAIN` 했더니 offset 30에서
Index Scan이 Seq Scan으로 바뀌어 있었다. `OFFSET`이 계획의 입력이라는 건 알고 있었지만, **그게 한
번의 페이지 순회 안에서 실제로 갈린다는 걸 본 적은 없었다.** 알고 있는 것과 그 조합에서 무엇이
일어나는지 아는 것은 다르다는 이 슬라이스의 전제를, 하필 내 예측이 틀리는 방식으로 확인했다.

두 번째로 배운 건 처방에 관한 것이다. 티브레이크와 키셋 페이징은 **둘 다 `0/0`을 냈다.** 숫자만
보면 구분이 안 된다. 구분은 숫자가 아니라 **질문**에서 온다 — *"같은 질의가 자기 자신과 어긋날 수
있나"* 와 *"내 밑에서 데이터가 바뀔 수 있나"* 는 다른 질문이고, 전자만 티브레이크가 답한다. 측정이
선택을 대신해 주지 않는 경우가 있다는 걸, 측정을 다 하고 나서야 알았다.

세 번째는 §3.3과 §3.5가 서로 반대 방향이라는 것. 픽스처에서는 티브레이크가 인덱스를 **버리게**
만들었고(Seq Scan + Sort), 진짜 `attempt` 테이블에서는 인덱스를 **유지한 채** Incremental Sort만
얹혔다. 같은 서버, 같은 문법, 반대 결과. 차이는 티브레이크가 인덱스가 이미 가진 키를 **연장**하느냐,
인덱스가 모르는 컬럼을 **끌고 오느냐**였다. *"티브레이크를 붙이면 정렬이 생긴다"* 라고 한 줄로
외웠다면 절반은 틀렸을 것이다.
