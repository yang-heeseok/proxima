# R16. The constraint that was also an index

> **Created**: 2026-08-14
> **Updated**: 2026-08-17
> **Red commit / Green commit**: neither. Nothing changed in the application. **Both arms are
> the same binary on the same afternoon**, and the only difference between them is whether
> `uk_mastery_learner_concept` exists.
> **Started as**: an attempt to measure what `T9`'s token filter costs, because `R4`'s p99 was
> taken before the filter existed. §5 is what that question turned out to be worth.

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL : proxima-db — postgres:16-alpine, server 16.14, default settings
  Dataset    : seed 20260810 — 1,000 learners, 3,000,000 attempts, 600,000 mastery
  Schema     : V1..V3. Arm B drops uk_mastery_learner_concept and restores it afterwards
  Pool       : HikariCP, maximum-pool-size 10 (default), open-in-view false
  App        : one JVM per arm, four k6 runs against it — first DISCARDED, then three
  Load       : k6, 200 VU, 30s warm-up discarded, 3min window, steady-state ratio enforced
  Both arms  : same binary, same session, same day. Nothing ran between the runs of an arm
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R4` published p99 **5919.4 ms** at 200 VU on 2026-08-12. Since then `T9` put a token filter
in front of every `/api/v1` request, and the filter's cost was **미측정** while `R4`'s table
sat at the top of `README.md` describing a configuration that no longer shipped.

So: re-measure, and find out what the filter costs.

**Neither of those is what this report contains.** §5.

## 2. 재현 / Reproduction

```bash
bash load-a.sh   # shipped schema
bash load-b.sh   # same binary, uk_mastery_learner_concept dropped, restored on exit
./gradlew :api:test --tests "net.gseek.proxima.security.TokenVerifyBenchTest"
```

Each arm: one application instance, four k6 runs back to back — the first discarded, then
three measured. **Nothing else touched the database between the runs of an arm**, and §8
records why that sentence is in the method rather than in the risks.

## 3. 계측 / Measurement

### 3.1 The two arms

| | A — shipped (`V1..V3`) | B — same binary, no unique constraint | B ÷ A |
| --- | --- | --- | --- |
| p50 | **300.0 ms** | **5413.8 ms** | **18.0×** |
| p95 | **544.2 ms** | **8844.1 ms** | **16.3×** |
| p99 | **743.5 ms** | **11334.6 ms** | **15.2×** |
| error rate | 0.00 % | 0.00 % | |
| responses carrying a recommendation | 21.0 % | 20.7–21.0 % | |

Three measured runs each, medians reported. Spreads, stated because several exceed 10%:

| | run 1 | run 2 | run 3 | spread |
| --- | --- | --- | --- | --- |
| A p50 | 300.0 | 288.0 | 308.9 | 6.8 % |
| A p95 | 544.2 | 473.4 | 639.3 | 26 % |
| A p99 | 743.5 | 650.0 | 891.4 | 27 % |
| B p50 | 5413.8 | 5358.6 | 5592.9 | 4.2 % |
| B p95 | 8844.1 | 7632.9 | 9845.5 | 22 % |
| B p99 | 11334.6 | 10687.9 | 11553.5 | 7.5 % |

**The 15× survives the worst case in both directions** — A's slowest p99 against B's fastest
is 891.4 against 10687.9, still 12×.

Every run's steady-state ratio was inside the harness's 1.3 limit (A: 1.22, 1.00, 1.00;
B: 0.90, 0.90, 0.99). One earlier A run was refused by that check at 1.42 and discarded —
§8.

### 3.2 The mechanism, on one query

The same query outside load, with the constraint dropped inside a transaction and rolled
back:

```
WITH    uk_mastery_learner_concept   Index Scan using uk_mastery_learner_concept on mastery m
                                     Index Scan using uk_mastery_learner_concept on mastery pm
                                     Execution Time: 4.808 ms

WITHOUT it                           Parallel Seq Scan on mastery m
                                     Parallel Seq Scan on mastery pm
                                     Execution Time: 29.528 ms
```

**6.1× on one query. 15–18× under load.** The gap between those two numbers is the report:
a sequential scan that costs 25 extra milliseconds alone costs a *connection* for those
milliseconds, and at 200 VU against a pool of ten, the queue is what the user waits in. That
is the mechanism `R4` measured for a different cause, reproduced here by removing an index
nobody added for performance.

### 3.3 The constraint was never meant to be an index

`V3` exists because of `T6`: eight concurrent requests produced eight `mastery` rows and
nothing failed. `R7` recorded what the constraint bought — *"it converts 7 silent duplicates
into 7 exceptions"* — and said nothing about reads, because nothing had measured reads.

The recommendation query touches `mastery` twice, both times by `(learner_id, …)`:

```sql
from mastery m           where m.learner_id = :learnerId and m.score < 0.700
left join mastery pm     on pm.concept_id = e.prerequisite_id and pm.learner_id = :learnerId
```

`unique (learner_id, concept_id)` is exactly the index those two want. **A correctness
constraint turned out to be the performance fix for the endpoint this repository's first
report is about, and no report noticed for two days.**

`R7` §9 named a pattern: *three times in a row, the thing I thought was "the solution" was
the enabler of the next decision.* This is the fourth, running the other way — a thing
adopted for correctness that was silently paying for performance.

### 3.4 Four requests in five do no work

**21.0 %** of measured responses carried a recommendation. The rest are `200` with an empty
list, and that is not a failure: the rule only yields items for a learner who has an
unmastered concept whose prerequisites are *all* mastered, and on this seed that is **210
learners in 1,000** — counted in SQL, and matched independently by the harness's own rate.

That changes how the percentiles above must be read:

| | what it is measuring |
| --- | --- |
| **p50** | the **empty** path — the CTE finds no target concept and the query ends |
| **p95, p99** | the **working** path, since 21 % > 5 % > 1 % |

**p50 and p99 are measuring different code.** The discipline's rule that *p99 decides* picked
the right number here by luck rather than by knowing this.

`R4`'s table has the same property and does not say so. §7.

### 3.5 What the filter costs

```
RequestToken.verify(): 911 ns/op over 1,000,000 calls
  as a share of the measured p50 (300.0 ms): 0.00030 %
```

**0.9 microseconds**, against a harness whose p99 varies by ~240 ms between runs of the same
arm. The filter is **five orders of magnitude below this measurement's noise floor.**

So the original question is answered, and the answer is that it cannot be answered this way:
*the token filter's contribution to the endpoint's latency is not resolvable under load on
this machine, and here is the number that establishes it.* That is not the same as "it is
free" — §8.

## 4. 원인 / Mechanism

Without an index on `(learner_id, concept_id)`, both `mastery` accesses become parallel
sequential scans over 600,000 rows. Under 200 VU against ten connections, each of those scans
holds a connection for its duration, so the cost is multiplied by the queue rather than added
to it — which is why 6× on one query becomes 15× on a percentile.

## 5. 처방 / Remedy

**Nothing to change.** The shipped configuration is arm A, and it already has the index —
because `T6` needed the constraint for a reason that had nothing to do with this.

What the report changes is what is **known**:

| | before | after |
| --- | --- | --- |
| what `V3` is for | uniqueness (`R7`) | uniqueness **and** the recommendation read's index — 15× |
| what the filter costs | 미측정 | ≤ 0.9 µs/req, below this harness's floor |
| what `README`'s p99 row describes | a configuration from 2026-08-12 | today's, re-baselined |
| how much traffic does real work | not recorded | 21.0 % |

## 6. 재계측 / Re-measurement

§3.1 is the re-measurement. **`R4`'s numbers are not restated as a before-and-after**, and §7
says why.

## 7. `R4`와 비교하지 않는 이유 / Why this is not compared with `R4`

The obvious table is `R4`'s p99 5919.4 against today's 743.5, an eightfold improvement. **It
is not written here**, because `measurement-discipline.md` rule 3 forbids it:

> **Before and after come from the same run conditions.** Different machine, different
> dataset, different day without re-baselining — the comparison is not made.

Two days apart, on a machine that has since run a 32-minute runaway migration (`R15`), with a
page cache in an unknown state. `ADR-004` was written yesterday because that rule had been
broken once already, by someone who had read it.

**And the evidence that the caution is warranted is in this report.** Arm B is the closest
thing to `R4`'s schema that exists today — no unique index — and it measures p99 **11334.6 ms**
against `R4`'s **5919.4 ms**. Nearly 2× apart, for reasons this report cannot attribute:
the filter is ruled out at 0.9 µs, and everything else is **미측정**.

So `R4` is not wrong and today is not wrong. **They are not comparable**, and the honest
output is two self-contained baselines rather than one ratio.

## 8. 남는 위험 / Remaining risk

- **`README`'s results table now cites two runs two days apart.** `R4`'s row and this one
  describe different days. Anyone reading the table as a progression is reading something
  §7 says is not there.
- **The 15× is one dataset, one pool size, one concurrency.** 200 VU, pool 10, 600,000
  `mastery` rows. The mechanism (a scan holding a connection) says the ratio grows with
  concurrency and shrinks with pool size; **neither was varied.**
- **Arm B is not `V2`.** It is `V1..V3` with one constraint dropped, which leaves `V3`'s
  other effects — none that touch reads, as far as anyone has looked — in place. It is a
  clean single-variable test **of the index**, not a reconstruction of the past.
- **The filter's 0.9 µs excludes everything the filter does around `verify`** — reading a
  header, allocating a response on refusal, and the servlet chain. `verify` is the dominant
  term by construction and the rest is **미측정**.
- **21 % is a property of this seed and this rule together.** `domain-model.md` places
  recommendation quality out of scope — *a recommendation policy cannot be validated without
  learners, content, and teachers* — so the figure is recorded, not judged. But every
  latency number in this repository is now known to be four-fifths empty-path traffic, and
  no report before this one said so.
- **This measurement contaminated itself three times before it produced a number**, and all
  three were self-inflicted: a coverage query run between warm-up and measurement (caught by
  the steady-state check at ratio 1.42), a stale jar carrying a superseded migration, and a
  split WSL session that let `vmIdleTimeout` stop the database. **The diagnostics and the
  subject share a machine**, and nothing in the harness knows that.
- ~~**What would break the conclusion**: a pool large enough that scans stop queueing. `R4` §3
  measured pool 50 and found it worse than fixing the query; the same experiment against arm
  B is **미측정** and would separate "the index matters" from "the pool is small".~~

  **Measured 2026-08-17 — `R18`, and the conclusion held.** A 2×2 over {index, no index} ×
  {pool 10, pool 50}, all four arms on one jar in one session: **five times the pool buys
  1.94× on the no-index arm and the index is still worth 8.7× on top of it.** The prediction
  in this bullet was directionally right — the index's advantage does shrink with pool size,
  12.7× → 8.7× — and the pool cannot substitute for it.

  **`R18` also found what this report could not have**: a drift control on identical
  configuration seventy minutes apart measures **1.27×**, which is larger than the pool
  effect *with* the index. So one of `R18`'s four ratios is inside its own noise and is not
  claimed. This report's 15.2× is far outside that band and is unaffected.

## 9. 배운 것 / What I learned

**재려던 것은 못 쟀고, 못 쟀다는 것을 측정했다.**

토큰 필터의 비용을 재려고 시작했다. 답은 911 ns — 이 하네스의 편차 240 ms보다 다섯 자릿수 아래다.
**부하로는 원리적으로 분해되지 않는다.** 그런데 그 문장을 쓸 수 있는 것과 "아마 무시할 만하다"고
넘기는 것은 다르다. `미측정`을 지우는 방법이 항상 그것을 재는 것은 아니다 — **왜 이 도구로는 잴 수
없는지를 재는 것**도 답이다.

**그리고 정확성을 위해 넣은 제약이 성능 인덱스였다.**

T6은 동시 요청 여덟 개가 mastery 행 여덟 개를 만드는 것을 막으려고 유니크 제약을 넣었다. R7은 그것이
*"조용한 중복 7개를 시끄러운 예외 7개로 바꾼다"* 고만 적었다. 읽기에 대해서는 아무도 재지 않았기
때문이다. 그 제약을 떼면 추천 엔드포인트의 p99가 **15배** 나빠진다. 이틀 동안 아무 리포트도 몰랐다.

R7 §9가 "내가 해결책이라고 알던 것이 다음 결정의 전제였다"를 세 번 세었는데, 이번은 **반대 방향의 네
번째**다 — 정확성을 위해 채택한 것이 성능을 조용히 지불하고 있었다.

**마지막으로, R4와 비교하지 않기로 한 것.**

8배라고 쓰고 싶었다. 숫자가 좋고 이야기가 깔끔하다. 규칙 3이 금지한다 — 이틀 차이, 그 사이에 32분짜리
폭주 마이그레이션이 돈 기계. 그리고 **그 조심이 옳았다는 증거가 이 리포트 안에 있다**: R4의 스키마에
가장 가까운 B가 11334.6 ms로, R4의 5919.4 ms와 2배 차이가 난다. 필터는 0.9 µs로 배제됐고 나머지는
`미측정`이다. **어제 ADR-004를 쓰면서 배운 것을, 오늘 유혹이 실제로 왔을 때 지켰다.**
