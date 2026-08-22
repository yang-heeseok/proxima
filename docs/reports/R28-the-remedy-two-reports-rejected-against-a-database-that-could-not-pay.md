# R28. The remedy two reports rejected against a database that could not pay for it

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none, and the header says why.** Nothing in the application changed. This
> is one arm added to an existing instrument, in the shape `R16` and `R18` established: one
> jar, one session, one difference. What was missing was not a fix but a **comparison**.
> **Closes**: `OPEN-11`

> **Rules for every number below.** `measurement-discipline.md`. Durations are a median of
> three `EXPLAIN (ANALYZE, BUFFERS)` runs at concurrency 1, spread stated, and **no figure here
> may be placed beside a `p99`** — this is a single-query measurement and the load reports are
> not.

## 1. 증상 / Symptom

Two reports rejected a covering index, eleven days apart, on two different tables.

- `R3` rejected `INCLUDE` columns on `attempt`: 87% more space for a difference at the edge of
  noise.
- `R20` §3.6 rejected `(concept_id, prerequisite_id)` on `concept_edge`: **85% more space and
  not faster.**

`R20` then found the mechanism, and it was not about either table. An index-only scan is only
index-only once `VACUUM` has set the visibility map, and **`seed/`'s load path never runs
`VACUUM`** — `Main.kt` offers `generate`, `load` and `analyze` as separate commands and there
is no fourth. So `R20`'s covering arm reported `Heap Fetches: 5,424`: it was visiting the heap
on every row it claimed to avoid.

**That put both verdicts in doubt at once.** If the covering index was rejected because the
database could not pay for it, then this repository has twice priced a remedy against a
condition no production database is in — autovacuum runs there.

## 2. 재현 / Reproduction

`R20` §3.6 measured the covering candidate **either side** of a vacuum:

```
before vacuum   Heap Fetches 5,424   buffers 5,454   3.381 ms
after  vacuum   Heap Fetches     0   buffers 3,618   3.032 ms
```

and the candidate table **before** any vacuum:

| candidate | bytes | rows fed, depth 12 | exec ms, depth 12 |
| --- | --- | --- | --- |
| `(concept_id)` | 163,840 | 5,424 | 3.466 |
| `(concept_id, prerequisite_id)` | 303,104 | 5,424 | 3.621 |

**The only cross-candidate comparison those leave available is *covering after* against
*single before*.** Two different conditions, which rule 3 refuses. `OPEN-11` was opened because
of that gap and not because of the numbers: the row was **undecidable**, not merely
unanswered.

The missing arm is one measurement — both candidates, both vacuumed, same session, same
fixture. `PrerequisiteIndexTest` §*both candidates priced after a vacuum* is it.

## 3. 계측 / Measurement

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 (JDK 21 toolchain, pinned in gradle.properties)
  PostgreSQL     : postgres@sha256:cf78e766… — server 16.15, alpine 3.24.1, musl 1.2.6-r2
                   INDEX DIGEST, pinned in TestcontainersConfiguration.kt as of 8dec7e6
                   default shared_buffers
  Dataset        : SeedConceptGraph — the generator's edge construction, 606 concepts
  Query          : the shipped closure at depth 12, EXPLAIN (ANALYZE, BUFFERS)
  Repetitions    : median of 3 per arm; the whole class run 4 times, all four reported below
```

### 3.1 Both candidates, both vacuumed

| run | `(concept_id)` | spread | `(concept_id, prerequisite_id)` | spread | covering is |
| ---: | --- | ---: | --- | ---: | --- |
| 1 | 3.202 ms | 11.0% | 2.771 ms | 2.9% | **1.16×** |
| 2 | 3.062 ms | 25.2% | 2.804 ms | 8.7% | **1.09×** |
| 3 | 3.516 ms | 33.0% | 3.119 ms | 3.5% | **1.13×** |
| 4 | 3.611 ms | 40.6% | 3.496 ms | 15.9% | **1.03×** |

Both arms feed **5,424 rows** through `concept_edge` in every run — asserted, not observed in
passing, because two candidates answering the same predicate must; a difference would mean one
of them was not being used for the lookup and the comparison would be between two plans rather
than two indexes.

| | `(concept_id)` | `(concept_id, prerequisite_id)` |
| --- | --- | --- |
| bytes | 163,840 | **303,104 — 85% more** |
| scan | `Index Scan` | `Index Only Scan` |
| `Heap Fetches` | **n/a** — the node does not report them | **0** |

### 3.2 The effect is the size of the noise, and three of four runs say so

**The covering advantage across four runs: 1.16×, 1.09×, 1.13×, 1.03×.**

**The single-column arm's own spread across the same four runs: 11.0%, 25.2%, 33.0%, 40.6%.**

The test prints the comparison rather than asserting it, because rule 9 forbids CI asserting a
duration:

```
run 1   covering is 1.16x the single column; worst spread 11.0% — outside
run 2   covering is 1.09x the single column; worst spread 25.2% — INSIDE the noise
run 3   covering is 1.13x the single column; worst spread 33.0% — INSIDE the noise
run 4   covering is 1.03x the single column; worst spread 40.6% — INSIDE the noise
```

**The one run that called it outside is the run with the tightest spread.** Taking that run
alone and stopping would have produced the opposite finding — which is `R18`'s lesson arriving
a second time, and the reason four runs were taken rather than one.

### 3.3 A second thing the four runs show, which was not the question

**The spread rose monotonically across the four runs — 11.0% → 40.6% on the single-column
arm — while the covering arm stayed between 2.9% and 15.9%.**

The four runs were taken back to back on a machine that had been running container-heavy test
classes for hours. So the spread here is partly a property of **the machine's state at the time
of measurement**, not only of the arms. That does not weaken the conclusion — it strengthens
it, because the effect being claimed is smaller than a variance that this machine demonstrably
produces on its own — but it does mean **the 40.6% figure is not a property of the query.**

The asymmetry between the arms is the part worth keeping: the `Index Only Scan` is
consistently the *steadier* of the two, which is what one would expect of a plan that reads
only index pages while the other reaches into the heap.

## 4. 원인 / Mechanism

`VACUUM` sets the visibility map, which is what lets an `Index Only Scan` skip the heap. The
load path never runs one, so `R20`'s covering arm was an index-only scan **in name only** — it
made 5,424 heap fetches, exactly one per row it returned.

Vacuum removes that penalty completely: `Heap Fetches` goes to 0 and buffers fall from 5,454 to
3,618.

**And it is still not enough to justify the second column.** The single-column arm does an
`Index Scan`, which reaches the heap by design and cannot be helped by the visibility map — yet
it lands within 3–16% of the covering arm, on a query whose cost at depth 12 is dominated by
the twelve recursive iterations rather than by heap access on the 5,424 rows they feed.

**So the loader's missing `VACUUM` was never why the covering index lost.** It made the
measurement look worse than it was, and correcting it does not change the verdict.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| **Change nothing; record the condition** | Both verdicts stand on a like-for-like comparison. `R3` and `R20` keep their conclusions, and the reason each gave is now known to be incomplete rather than wrong | one report, one test arm | **✔** |
| Add `vacuum` to `load` | The shipped dataset would resemble a production database | **Every number taken before it becomes incomparable with every number after it** — rule 3 — to buy a conclusion that does not move. And it would remove the pre-vacuum state `T4` was given on purpose | |
| Add `vacuum` as a fourth `seed` command | Explicit, opt-in, no default changed | Real but **unbanked** — `AGENTS.md` §Scope. Nothing needs it now that this arm exists, and a command nobody runs is the shape `R0` §4 counts | |
| Ship the covering index | An `Index Only Scan` on the traversal | 85% more space for 1.03–1.16×, inside the measured spread. This is what the report exists to refuse | |

**Chosen: change nothing, and write down what the condition is.**

The third option is the one that was nearly taken, and it lost on `AGENTS.md` §Scope rather
than on merit. It becomes right the moment a question needs a vacuumed dataset **outside** a
single test — and this arm creates its own state, so nothing needs it today.

### What this would be on a managed database

Every managed PostgreSQL offering runs autovacuum by default, so the pre-vacuum state measured
here is **an artefact of a load path that ends at `analyze`**, not something a deployment would
sit in for long. The parameters that decide when it happens are `autovacuum_vacuum_threshold`
and `autovacuum_vacuum_scale_factor`; **their defaults were not verified against vendor
documentation for this report and are therefore 미확인 here** — rule 9. What matters for the
verdict is that the *vacuumed* state is the realistic one, and that is the state §3.1 measures.

## 6. 재계측 / Re-measurement

There is nothing to re-measure: no change was made. §3.1 **is** the re-measurement — `R20`
§3.6's table, re-taken with the condition equalised.

| | `R20` §3.6, as published | `R28` §3.1, like-for-like |
| --- | --- | --- |
| comparison available | covering *after* vacuum vs single *before* | **both after** |
| covering advantage | 3.032 vs 3.466 — reads as 1.14× | **1.03–1.16×, and inside the spread** |
| verdict | covering rejected, mechanism attributed to the loader | **covering rejected, and the loader was not the reason** |

## 7. 회귀 게이트 / Regression gate

`PrerequisiteIndexTest`, the arm named in §2, and what it asserts is **categorical rather than
temporal** because rule 9 forbids the alternative:

- both candidates feed the same rows through `concept_edge` — the precondition, in the shape
  `ADR-015` settled the same morning. Without it this is a comparison between two plans.
- the covering candidate reports `Heap Fetches: 0` after the vacuum — if it does not, the arm
  is not measuring what the report claims.
- the covering index is larger than the single-column one — a sizing sanity check, because
  `pg_relation_size` takes a name and a wrong name returns a plausible number.

**The durations are printed, never asserted.** The verdict lives here, in prose, against a
stated spread.

**And the method order in that class is now load-bearing.** `VACUUM` is a side effect on a
shared table that cannot be undone, so the new arm is `@Order(Int.MAX_VALUE)`. This was found
by breaking it: added without the ordering, it vacuumed ahead of `an index only scan is not
index only until a vacuum has run`, whose assertion then failed on `Heap Fetches: 0` — and
whose message had already named the cause it could not distinguish, *"either something vacuumed
this table … or PostgreSQL has changed when it sets the visibility map."* **Something did, and
it was the test added beside it.** The coupling is real and the guard against getting the order
wrong already existed.

## 8. 남는 위험 / Remaining risk

- **The spread is partly the machine's, and the machine was busy.** Four back-to-back class
  runs after hours of container-heavy work; the single-column arm's spread rose 11.0% → 40.6%
  across them. **A quiet machine might separate the two arms.** The conclusion is that the
  effect is not larger than the variance *this machine produces*, and a machine with less
  variance is a different measurement — `ADR-004`'s hole, unchanged.
- **One query, one depth, one table.** Depth 12 on `concept_edge`. `R3`'s rejection on
  `attempt` is **not** re-tested here and its reason remains the one `R3` gave. This report
  narrows the doubt `OPEN-11` raised over `R3`; it does not remove it.
- **`Heap Fetches` is `n/a` for the single-column arm, not zero**, because an `Index Scan` does
  not report the field. That is now distinguished in the output. It was **printed as `0` in the
  first three runs of this arm** — a missing measurement rendered as a measured zero, which is
  `R5`'s mistake in miniature, caught before it reached this document and fixed for run 4.
  **The three earlier runs' durations are unaffected**; only the fetch column was wrong.
- **The covering index was never measured on a table with dead tuples.** `COPY` into a fresh
  table produces none, so `VACUUM` here only sets the visibility map. On a table with churn it
  also reclaims space, and the two arms might separate differently. 미측정.
- **Autovacuum's defaults are 미확인** — §5 says so rather than quoting them from memory.
- **Nothing here measures what `VACUUM` costs.** The remedy that was rejected includes a step
  whose duration on 3,963,719 rows is 미측정, and that cost is part of why the third option in
  §5 is unattractive — but it is an argument, not a number.
- **`R20` §3.6's published sentence is now incomplete rather than wrong.** It says the covering
  candidate lost because of `Heap Fetches`, which was true of the measurement it took. It is
  annotated rather than rewritten; the correction is this report.

## 9. 배운 것 / What I learned

**두 조건에서 잰 두 숫자는 비교가 아니라 두 개의 사실입니다.**

`R20` §3.6은 정직하게 썼습니다 — 커버링 인덱스를 진공 전후로 쟀고, 두 후보를 진공 전에 쟀습니다.
그런데 그 둘을 합치면 **어떤 비교도 성립하지 않습니다.** 남는 조합이 *커버링(후)* 대 *단일(전)*
뿐이고, 그건 규율 3번이 거부하는 모양입니다.

**그래서 `OPEN-11`은 답이 없는 질문이 아니라 답할 수 없는 질문이었습니다.** 한 팔이 빠져 있었고,
그 팔을 재는 데 4분이 걸렸습니다. 판단이 필요한 행으로 열려 있던 이유는 판단이 어려워서가 아니라
**판단할 재료가 없어서**였습니다. 열기 전에 그 사실을 알아보는 것이 이 라운드에서 배운 것입니다.

**그리고 한 번 재고 멈췄으면 반대 결론이 나왔습니다.** 첫 실행이 1.16배에 편차 11.0%로 *"노이즈
밖"* 이라고 말했습니다. 네 번 돌리니 1.03~1.16배에 편차 11~41%이고, 셋이 *"노이즈 안"* 입니다.
`R18`이 같은 것을 70분 간격의 두 실행으로 배웠고, 여기서는 연속된 네 실행으로 다시 배웠습니다 —
**효과보다 편차가 크면, 효과라고 부를 수 없습니다.**

**마지막으로, 이 리포트를 쓰다가 계측기를 하나 고쳤습니다.** `Heap Fetches` 가 없는 계획에서
정규식이 0을 돌려주고 있었고, 표는 *"단일 컬럼 인덱스도 힙을 건드리지 않는다"* 고 말하고 있었습니다.
**없는 것과 0인 것은 다르고, 그 둘을 같은 칸에 쓰면 측정하지 않은 것이 측정값이 됩니다.** 문서에
닿기 전에 잡혔고, 잡은 것은 게이트가 아니라 *"이 칸이 왜 둘 다 0이지"* 라고 한 번 더 본 것입니다.
