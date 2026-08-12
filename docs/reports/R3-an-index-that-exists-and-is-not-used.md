# R3. An index that exists and is not used

> **Created**: 2026-08-12
> **Updated**: 2026-08-12
> **Red commit**: `cceec6a` — `V1`, no index on `attempt`
> **Green commit**: this one — `V2__attempt_learner_time_index.sql`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  PostgreSQL     : postgres:16-alpine — server 16.14
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
                   default shared_buffers
  Dataset        : seed value 20260810 — attempt 3,000,000 rows
  Load           : none. Single-connection EXPLAIN (ANALYZE, BUFFERS)
  Repetitions    : 3 runs, median reported, warm buffers
  Raw output     : load/out/t4/ — plans kept verbatim, one file per cell
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Every read of `attempt` is scoped to one learner and ordered by time. There are three
million rows and no index for that, so each read scans the table:

```
->  Parallel Seq Scan on attempt  (actual time=296.547..353.494 rows=55 loops=3)
      Filter: ((attempted_at >= ...) AND (learner_id = 500))
      Rows Removed by Filter: 999945
```

Nine hundred and ninety-nine thousand rows read and discarded, per worker, to return
twenty.

## 2. 재현 / Reproduction

`load/out/t4/` holds every plan quoted here. The matrix is five index variants against four
queries, three runs each, in one continuous session — `scratchpad/t4.sh`, `t4b.sh`.

## 3. 계측 / Measurement

### 3.1 The matrix

Median of three, milliseconds, warm.

| index | shallow (limit 20) | deep (offset 2000) | keyset | projection only | size | first scan node |
| --- | --- | --- | --- | --- | --- | --- |
| **none** | **36.6** | 37.1 | 41.7 | 41.9 | — | Parallel Seq Scan |
| **`(learner_id, attempted_at)`** | **0.056** | 0.326 | 0.052 | 0.086 | 90 MB | Index Scan |
| `(attempted_at, learner_id)` | 0.197 | **37.3** | 0.285 | 0.275 | 90 MB | Index Scan / **Seq at depth** |
| `(learner_id, attempted_at)` INCLUDE (…) | 0.056 | 0.256 | 0.065 | 0.062 | **168 MB** | Index Only Scan |
| `(correct)` | 38.5 | 36.7 | 40.9 | 34.7 | 20 MB | Parallel Seq Scan |

**36.6 ms → 0.056 ms. About 650×.**

### 3.2 The column order is the finding

`(attempted_at, learner_id)` is the same two columns, the same 90 MB, and it is **used**:
0.197 ms on the shallow query against 36.6 ms with no index. It would pass any review that
checked whether the index was picked up.

At depth it collapses:

```
(attempted_at, learner_id), OFFSET 2000    →  Parallel Seq Scan, 37.3 ms
```

A leading `attempted_at` satisfies the `ORDER BY` but cannot restrict to one learner, so
the planner walks the index and eventually abandons it. **The difference between a correct
index and a plausible one is invisible until a test pages.**

### 3.3 The index that is never used

`(correct)` is a boolean over three million rows — two distinct values. PostgreSQL did not
choose it once, in any of the twelve runs. It costs 20 MB and write amplification on every
insert into an append-only table, and returns nothing.

It is measured here because it is exactly what gets added when someone notices a `WHERE
correct = true` in a query and reasons that indexed columns are faster.

### 3.4 Covering columns, and why they are not shipped

The covering variant produces an `Index Only Scan` for every query measured, which is the
result the technique promises — the heap is never touched.

| | `(learner_id, attempted_at)` | + `INCLUDE` |
| --- | --- | --- |
| shallow | 0.056 ms | 0.056 ms |
| deep | 0.326 ms | 0.256 ms |
| keyset | 0.052 ms | 0.065 ms |
| size | **90 MB** | **168 MB** |

**87 % more storage for a difference at the edge of measurement noise**, and on one of the
four queries it is slower. Not shipped. If a later report shows heap access mattering under
concurrency, this is worth reopening; nothing measured today justifies it.

### 3.5 Stale statistics, now that the planner has a choice

`D1` measured this with no index: `ANALYZE` moved the row estimate from 14,376 to 2,480 and
changed neither the plan nor the runtime, because a sequential scan was the only plan
available.

With an index present the question is live for the first time. Statistics were reset to the
post-`COPY` state, the index built, and the query run before and after `ANALYZE`:

| | planner's estimate | plan | median |
| --- | --- | --- | --- |
| before `ANALYZE` | rows=15000 | Index Scan | 0.101 ms |
| after `ANALYZE` | rows=2994 (actual 3000) | Index Scan | 0.128 ms |

**The plan did not change, and the trap did not reproduce.**

The reason is worth more than the non-result: `CREATE INDEX` scans the whole table, and
PostgreSQL updates `reltuples` and `relpages` while it is there. Reset to the "never
analysed" sentinel and then indexed, the table reported `reltuples = 3000000` before
`ANALYZE` ran. **Building an index partly repairs the statistics a bulk load left stale.**

So on this schema, across both attempts, stale statistics never produced a wrong plan.
Recorded as a defect that **did not reproduce**, not quietly dropped.

### 3.6 Offset paging against keyset paging

**This domain's actual access pattern**, scoped to one learner — bounded at ~3,000 rows:

| | median |
| --- | --- |
| `OFFSET 0` | 0.054 ms |
| `OFFSET 1000` | 0.158 ms |
| `OFFSET 2000` | 0.240 ms |
| `OFFSET 2900` | 0.473 ms |
| keyset, same page | **0.057 ms** |

8.3× — and the loser takes half a millisecond. **On this access pattern the standard advice
does not pay for itself.**

**Unbounded paging, which this domain does not do**, over the whole table:

| | median |
| --- | --- |
| `OFFSET 0` | 0.054 ms |
| `OFFSET 100000` | 30.5 ms |
| `OFFSET 1000000` | 228.7 ms |
| `OFFSET 2500000` | **701.2 ms** |
| keyset, same page | **0.239 ms** |

**2,930×**, growing linearly with depth, because `OFFSET` counts rows by walking them.

Both tables are the same mechanism. Only the second one matters, and only if the pattern is
unbounded.

## 4. 원인 / Mechanism

A B-tree is ordered by its leading column first. `(learner_id, attempted_at)` puts one
learner's rows contiguously, already in time order, so the query is a descent plus a short
backward walk — twenty rows read. `(attempted_at, learner_id)` orders by time globally;
one learner's rows are scattered through it, so restricting to a learner means walking, and
past a certain depth the planner correctly decides scanning the table is cheaper.

`OFFSET n` is not a seek. The database produces and discards `n` rows to reach row `n+1`,
so the cost is linear in the offset. A keyset predicate is a seek: it becomes part of the
index descent.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave it | 36.6 ms per read | — | |
| `(attempted_at, learner_id)` | 0.197 ms shallow, **37.3 ms at depth** | 90 MB | |
| **`(learner_id, attempted_at)`** | **0.056 ms** | 90 MB | **✔** |
| + `INCLUDE (correct, elapsed_ms, item_id, id)` | 0.056 ms, index-only | 168 MB | |
| `(correct)` | never chosen | 20 MB + write cost | |
| keyset paging in the API | 0.057 ms vs 0.473 ms at this domain's depth | an API change | **not now** |

Keyset paging is **not** adopted. It is the right answer for unbounded paging and this
domain's paging is bounded by one learner's history; the measured gain is 0.4 ms and the
cost is a cursor in a public API contract. **That decision reverses if any endpoint ever
pages across learners.**

## 6. 재계측 / Re-measurement

| | before (`V1`) | after (`V2`) |
| --- | --- | --- |
| one learner's history, limit 20 | **36.6 ms** | **0.056 ms** |
| same at `OFFSET 2000` | 37.1 ms | 0.326 ms |
| plan | Parallel Seq Scan, 999,945 rows discarded per worker | Index Scan |

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/db/BaselineMigrationTest.kt` —
`the performance indexes are exactly those a report has justified` asserts the index set as
an **exact list**, so both a deletion and an unmeasured addition fail. Run by
`.github/workflows/build.yml`.

**This gate is weaker than it looks and the weakness is the point.** It asserts the index
*exists*, not that it is *used* — §3.2 is an entire section about an index that exists and
is used and is still wrong. A gate that asserts the plan would be stronger and is
**미측정**: nothing here yet asserts on `EXPLAIN` output.

## 8. 남는 위험 / Remaining risk

- **Nothing asserts the plan.** The gate would pass if a future migration reversed the
  column order, which §3.2 shows is a 660× regression on paged reads. That is the most
  valuable test this report did not write.
- **Every number here is single-connection.** Under concurrency the ranking can move — `R2`
  measured a statement that costs 140 ms alone and 555 ms with 8.5 running at once. The
  covering index's rejection in §3.4 is the decision most exposed to this, because avoiding
  heap access matters more when the cache is contended. **미측정.**
- **The `INCLUDE` decision rests on differences of 0.07 ms.** That is within run-to-run
  spread; three runs cannot separate them. Reported as "no measurable difference" rather
  than as "faster", but a larger sample could change the sign.
- **`ANALYZE` never mattered here, on two attempts.** That is a statement about this schema
  and this query, not about bulk loading in general. A query whose selectivity the planner
  must estimate — a range over a skewed column, a join order — is where stale statistics
  would bite, and this dataset has **no skew at all**: every learner has exactly 3,000
  attempts, evenly spread. **The generator's uniformity may be hiding this defect
  entirely.**
- **Index size is measured on a table that is never updated.** `attempt` is append-only and
  was bulk loaded. Bloat, `REINDEX` cost, and the write amplification the `(correct)` index
  would cause are all 미측정.
- **Keyset paging was rejected on this domain's current endpoints.** If an admin or export
  path ever pages across learners, §3.6's second table applies and the decision is wrong.
- **What would break the conclusion:** a change to how `attempt` is read. Every number here
  assumes reads are scoped to one learner and ordered by time. That assumption is in
  `domain-model.md` and it is the reason the chosen column order is correct; a report that
  reads `attempt` any other way starts over.

## 9. 배운 것 / What I learned

제일 놀란 건 650배가 아니라 **틀린 인덱스가 얼마나 멀쩡해 보이는가**였다. `(attempted_at, learner_id)`는
같은 두 컬럼이고 같은 90MB이고, 얕은 쿼리에서 실제로 쓰인다. 36.6ms가 0.197ms가 되니 리뷰에서
"인덱스 잘 탔네" 하고 넘어갔을 것이다. **깊이 들어가야 무너진다.** 인덱스가 쓰이는지 확인하는 테스트는
쉽고, 그 테스트는 이 결함을 못 잡는다.

두 번째는 낡은 통계가 **또** 재현되지 않은 것. D1에서는 인덱스가 없어서 플랜에 선택지가 없었고, 이번엔
선택지가 있는데도 플랜이 안 바뀌었다. 이유를 찾다가 알았다 — `CREATE INDEX`가 테이블을 어차피 전부
읽으니 그 김에 `reltuples`를 갱신한다. 통계를 -1로 되돌리고 인덱스를 만들었더니 `ANALYZE` 전에 이미
3000000이 들어가 있었다. **대량 적재 후 인덱스를 만들면 통계가 부분적으로 복구된다**는 건 어디서도
읽은 적이 없다.

세 번째가 제일 불편하다. 이 데이터셋에는 **skew가 없다.** 모든 학습자가 정확히 3000개씩, 18개월에
균등하게 갖는다. 낡은 통계가 무는 지점은 플래너가 선택도를 추정해야 하는 곳이고, 균등 분포에서는
추정이 틀려도 손해가 안 난다. **내가 만든 생성기가 T4의 한 갈래를 통째로 가리고 있을 수 있다.**
seed/README에 "이 데이터는 구조적이지 현실적이지 않다"고 적어둔 게 오늘 처음으로 대가를 청구했다.

그리고 keyset 페이징을 안 쓰기로 한 것. 교과서는 항상 keyset을 쓰라고 하고 그건 맞다 — offset
2,500,000에서 2,930배다. 그런데 **이 도메인의 페이징은 학습자 한 명의 3000행으로 묶여 있어서** 0.4ms
차이다. 공개 API에 커서를 넣는 값이 아니다. 조언이 옳은 것과 그 조언이 여기서 값을 하는 것은 다른
문제이고, 그걸 구분하려면 재보는 수밖에 없다.
