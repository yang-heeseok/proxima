# R13. The dataset was hiding a strand

> **Created**: 2026-08-14
> **Updated**: 2026-08-14
> **Red commit / Green commit**: **neither, and the reason matters.** Nothing in the
> application is wrong. What this measures is a property of **the data this repository
> measures itself on**, and the finding is that the property removed a defect from view.
> **Answers**: `R3` §3.5's non-reproduction, and the suspicion `R3` §8 and §9 raised about it

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL : Testcontainers postgres:16-alpine -- server 16.14, default settings
  Data       : NOT the shipped dataset. Two synthetic tables of 1,000,000 rows each,
               1,000 learners, built by generate_series inside the server. §2 says why
  Index      : (learner_id, attempted_at) on both, built after the load, as V2 is
  Repetitions: ONE run per cell. §8
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R3` measured five strands of `T4` and reproduced four. The fifth — **stale planner
statistics after a bulk load** — did not reproduce, on either of two attempts. `R3` §3.5
recorded that as a non-result rather than dropping it, and found a real reason for half of it:
`CREATE INDEX` scans the whole table and repairs `reltuples` on its way past, so a
bulk-loaded, indexed, un-analysed table is not as ignorant as it looks.

Then `R3` §9 said something sharper, and left it as a suspicion:

> 이 데이터셋에는 **skew가 없다.** […] 낡은 통계가 무는 지점은 플래너가 선택도를 추정해야
> 하는 곳이고, 균등 분포에서는 추정이 틀려도 손해가 안 난다. **내가 만든 생성기가 T4의 한
> 갈래를 통째로 가리고 있을 수 있다.**

Reading `Generator.kt` confirms the shape but proves nothing about the consequence: every
learner gets exactly `attemptsPerLearner` rows, items are drawn with `nextInt(items)`, and
timestamps are spread evenly across the window. **No skew in row counts, item popularity, or
time.**

This report is that suspicion measured.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.db.StaleStatisticsSkewTest"
```

**The shipped dataset is not touched, deliberately.** Every number in `R2`, `R3` and `R4` was
taken on it, and a test pins its digests so that *same bytes on another machine* means
something. Changing the generator to answer one question would invalidate all of that.

So the variable is isolated instead: two tables, same row count, same index, same query — one
uniform, one skewed — each measured with column statistics present and absent.

**Two choices in the harness are what make it able to answer at all.**

- **The query is an aggregate, not a paged read.** `R3` §3.5 measured
  `… where learner_id = ? order by attempted_at desc limit 20`, which an index serves
  trivially at any selectivity. **No estimate, however wrong, could have changed that plan.**
  An average over all of a learner's rows is the cheapest query where 0.1 % and 30 % of a
  table call for different plans.
- **"Stale" is reproduced by deleting `pg_statistic`, not by skipping `ANALYZE`.** `R3` §3.5's
  own finding is why: the index build already repaired `reltuples`. The state a bulk load
  really leaves is *row count known, distribution unknown*, and only deleting the column
  statistics produces it.

## 3. 계측 / Measurement

Learner 1 holds **1,000** rows in the uniform table and **300,000** — 30 % — in the skewed one.

| | plan | estimated | actual | ratio | time |
| --- | --- | --- | --- | --- | --- |
| uniform, statistics deleted | Bitmap Heap Scan | 5,000 | 1,000 | 5.0× over | 1.7 ms |
| uniform, after `ANALYZE` | Bitmap Heap Scan | 995 | 1,000 | 1.0× | 1.1 ms |
| **skewed, statistics deleted** | **Bitmap Heap Scan** | **5,000** | **300,000** | **60× under** | **46.2 ms** |
| **skewed, after `ANALYZE`** | **Parallel Seq Scan** | 125,375 | 100,000 | 1.25× | **29.2 ms** |

### 3.1 The control holds

**The uniform table does not change plan across `ANALYZE`** — Bitmap Heap Scan before and
after — which is exactly what `R3` §3.5 measured on the shipped dataset. The estimate is 5×
wrong beforehand and it does not matter, because at 1,000 rows out of a million there is no
plan boundary anywhere near. That is the whole reason the trap could not reproduce.

### 3.2 With skew, it reproduces

**The plan changes.** With no column statistics the planner falls back to a default equality
selectivity, estimates 5,000 rows, and picks a Bitmap Heap Scan. The learner actually holds
**300,000** — a **60× underestimate** — and the query takes **46.2 ms**.

After `ANALYZE` the planner sees the distribution, switches to a parallel sequential scan, and
the same query takes **29.2 ms**. **1.58× on this table.**

That is `T4`'s fifth strand, reproduced: *an index that exists and is not used*, chosen against
by a planner working from statistics that describe a table it no longer has.

### 3.3 Reading the parallel row counts

`actual 100,000` in the last row is **per worker**, not the total. Parallel plan nodes report
per-loop figures; three workers × 100,000 is the same 300,000 rows. The estimate on that line
is per-worker too, so the 1.25× ratio is a like comparison — but quoting `100,000` beside
`300,000` without this paragraph would read as a contradiction.

## 4. 원인 / Mechanism

The planner estimates how many rows a predicate will match, and chooses a plan from that
estimate. **On a uniform column, every value matches the same number of rows** — so even a
badly wrong estimate is wrong in a direction that does not reach a decision boundary.
`learner_id = 1` and `learner_id = 743` are the same query with the same cost, and a stale
estimate is a scaled version of a correct one.

Skew is what makes the estimate load-bearing. When one value holds 30 % of the table and the
rest hold 0.07 %, the *same* default estimate is right for almost every learner and
catastrophically wrong for one — and the planner has no way to know which one it is looking at
without the distribution.

**A uniform dataset does not make the planner correct. It makes the planner's correctness
irrelevant.**

## 5. 처방 / Remedy

Nothing to fix in the application. Three things follow, and only the third is a change.

| | |
| --- | --- |
| Run `ANALYZE` after a bulk load | Already the rule — `measurement-discipline.md` says so and the loader does it. This report supplies the number that justifies it: **1.58× on a table with one heavy value, and a different plan** |
| Leave the generator uniform | **The dataset stays as it is.** Making it skewed would answer this question and invalidate `R2`, `R3` and `R4`, whose numbers were all taken on the uniform one. §8 records what that costs |
| **Say what the uniformity hides, with a number** | `seed/README.md` already warned that *any report whose finding depends on skew has to say that this dataset has none.* It can now say what the absence removed from view, which is a different and stronger sentence |

## 6. 재계측 / Re-measurement

Not applicable — nothing changed. `R3` §3.5's non-reproduction stands as a correct measurement
of the shipped dataset, and §3.1 above reproduces it deliberately as this report's control.

## 7. 회귀 게이트 / Regression gate

**There is none, and there cannot usefully be one.** The defect needs skew and the shipped
dataset has none, so no assertion over this application's data could ever fail. Saying that
plainly is better than adding a test that passes for the wrong reason — which is what `R8`
§3.1 and `R9` §7 are both about.

`StaleStatisticsSkewTest` is a **recorder with a control**, not a gate:

- it asserts the **uniform** arm does not change plan across `ANALYZE`, so that if that ever
  stops holding, the comparison beside it is known to be measuring something else;
- it asserts **nothing about the skewed arm.** Whether the plan flips is the finding, and a
  test that required it to flip would be a test reporting the answer it was written to get —
  `R5` §9.

## 8. 남는 위험 / Remaining risk

- **One run per cell.** No medians, no spread. The plan *change* is categorical and robust;
  the **46.2 ms against 29.2 ms is a single pair of samples** and should not be quoted more
  precisely than "about one and a half times".
- **Not the shipped schema and not the shipped data.** Four columns, a million rows, one
  index. `attempt` has foreign keys, a check constraint, three million rows and other indexes,
  and none of that was in the way here.
- **One heavy value is not a heavy tail.** The skew here is a step function — one learner with
  30 %, everyone else equal — chosen because it attributes the finding cleanly. **Real usage
  is Zipf-shaped**, and whether a realistic tail produces the same plan flip is **미측정**.
- **The generator stays uniform, and that is now a known cost rather than an unknown one.**
  Every future report on this dataset inherits it. `T4`'s fifth strand is the one that was
  found; **nothing here establishes it is the only one**, and the same argument applies to any
  finding that depends on distribution rather than volume.
- **`work_mem`, `random_page_cost` and parallelism settings are the image's defaults** and
  were not varied. The plan boundary this report crosses is a function of all three, so the
  30 % figure is not a threshold — it is one point on a surface nobody mapped.
- **What would break the conclusion**: a query shape where the plan cannot change. §2 explains
  that `R3`'s original paged read was exactly that, which is why this report had to choose a
  different query — and choosing the query that can show an effect is a step away from
  measuring what the application does. **`R3`'s query is the one that ships. This one is not.**

## 9. 배운 것 / What I learned

**생성기가 무엇을 가릴 수 있는지는, 생성기를 바꾸지 않고도 잴 수 있었다.**

R3 §9에 "내 생성기가 T4의 한 갈래를 통째로 가리고 있을 수 있다"고 적어놓고 사흘을 뒀다. 확인하려면
데이터셋을 바꿔야 하고, 그러면 R2·R3·R4의 숫자가 전부 무효가 되니까. **둘 다 안 해도 되는 선택지가
있었다** — 변수만 격리해서 옆에 세우면 된다. 출하 데이터는 한 줄도 안 건드렸고, 답은 나왔다.

**그리고 R3가 재현에 실패한 진짜 이유는 데이터가 아니라 쿼리이기도 했다.**

R3 §3.5는 `order by attempted_at desc limit 20`으로 쟀다. 인덱스가 어떤 선택도에서든 압도적인
쿼리다. **추정이 60배 틀려도 계획이 바뀔 수 없는 쿼리로 "추정이 틀리면 계획이 바뀌는가"를 물었던
것이다.** 데이터셋에 skew가 없었던 것과, 질문이 답을 담을 수 없는 형태였던 것 — 두 가지가 겹쳐 있었고
R3는 앞의 것만 의심했다.

그런데 이건 §8에 적은 대로 양날이다. **효과를 보여줄 수 있는 쿼리를 고르는 것은, 애플리케이션이
실제로 하는 일에서 한 발 멀어지는 것**이다. R3의 쿼리가 출하되는 쿼리고 이 쿼리는 아니다. 재현했다는
사실보다 그 문장이 더 중요하다.

**마지막으로, 균등 분포는 플래너를 옳게 만들지 않는다. 플래너가 옳은지를 무의미하게 만든다.**
이 저장소는 3백만 행으로 "현실적인 규모"를 샀지만 분포는 사지 않았고, 규모가 답할 수 있는 질문과
분포가 답할 수 있는 질문은 겹치지 않는다.
