# R18. The pool was not the explanation

> **Created**: 2026-08-17
> **Updated**: 2026-08-21
> **Red commit / Green commit**: neither, for the 2×2 — nothing in the application changed
> and all four arms are the same jar (`sha256 1ed8188a…`) on the same afternoon. **For the
> harness defect §3.5 found, red is `01e16af` and green is the commit carrying this report.**
> **Answers**: `R16` §8's last bullet — *"a pool large enough that scans stop queueing… would
> separate 'the index matters' from 'the pool is small'"*, recorded there as 미측정.

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker     : Docker Engine, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM        : Temurin 21.0.12+8
  PostgreSQL : proxima-db — postgres:16-alpine, server 16.14
               max_connections=100, shared_buffers=128MB (defaults, UNCHANGED — see §2)
               max_parallel_workers_per_gather=2
  Dataset    : seed 20260810 — 1,000 learners, 3,000,000 attempts, 600,000 mastery
  Schema     : V1..V3. Arms B and D drop uk_mastery_learner_concept and it is restored after
  Pool       : HikariCP — 10 or 50, the variable. open-in-view false
  App        : ONE jar, sha256 1ed8188a92a6d69b672010650f0b6242719d30010898772a0900671be607c977
               one JVM per arm, four k6 runs against it — first DISCARDED, then three
  Load       : k6, 200 VU, 30s warm-up discarded, 3min window
  Session    : 2026-08-17 15:11:36 → 16:24:00 +0900, one WSL invocation, uninterrupted
  Sampling   : NOTHING queried the database inside a measurement window. R16 §8
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R16` measured the recommendation endpoint with and without `uk_mastery_learner_concept` and
found **15.2×** on p99. Its §8 then refused to let that stand as the whole story:

> **What would break the conclusion**: a pool large enough that scans stop queueing. `R4` §3
> measured pool 50 and found it worse than fixing the query; the same experiment against arm
> B is **미측정** and would separate *"the index matters"* from *"the pool is small"*.

The mechanism is not in dispute — a sequential scan holds its connection for as long as it
takes, so ten connections queue and fifty queue less. **What is in dispute is how much of the
15× is the index and how much is the queue**, and one pool size cannot tell them apart.

## 2. 재현 / Reproduction

Four arms and a control, on one binary in one session, because
`measurement-discipline.md` rule 3 forbids comparing today's numbers against `R16`'s — taken
three days earlier on a machine that has since been rebooted.

| Arm | `uk_mastery_learner_concept` | `maximum-pool-size` |
| --- | --- | --- |
| **A** | present | 10 |
| **C** | present | 50 |
| **B** | dropped | 10 |
| **D** | dropped | 50 |
| **A′** | present | 10 — **drift control**, run last |

Order: `A, C` → one `DROP` → `B, D` → one `ADD` → `A′`. One index change instead of three, and
the constraint is restored in an `EXIT` trap so a failure anywhere cannot leave the table
without it. The load is read-only `GET`s, so `T6`'s race has nothing to race on while it is
gone.

**A′ exists because `R4` §8 wished it did.** That report recorded *"a re-run of arm A landed at
the end of the session… in-session drift could be concentrated in arm A"* and had no way to
say how much. §3.3 is that number, and it is not small.

**The server configuration was not touched.** Pool 50 against `max_connections=100` with up to
2 parallel workers per query can reach the ceiling, and raising `max_connections` would have
made the arms incomparable with each other and with `R4`. If the ceiling had been hit, that
would have been the finding. It was not: error rate is 0.00 % in all fifteen measured runs.

## 3. 계측 / Measurement

### 3.1 Medians of three measured runs

| Arm | index | pool | p50 | p95 | **p99** | err | responses carrying a recommendation | p99 spread |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **A** | ✓ | 10 | 284.8 | 510.1 | **690.0** | 0.00 % | 21.0 % | 1.24× |
| **C** | ✓ | 50 | 267.3 | 421.0 | **517.8** | 0.00 % | 21.0 % | **1.94×** |
| **B** | — | 10 | 4407.7 | 5249.0 | **8739.8** | 0.00 % | 20.9 % | 1.02× |
| **D** | — | 50 | 2672.9 | 3061.1 | **4506.7** | 0.00 % | 20.9 % | 1.14× |
| **A′** | ✓ | 10 | 272.1 | 394.1 | **544.1** | 0.00 % | 21.0 % | 1.14× |

All values in ms, at 200 VU. The spread column is max/min of the three p99s, reported because
rule 5 requires it above 10 % and three of the five are above it.

**The spread is itself a result.** The index-absent arms are the tight ones (1.02×, 1.14×) and
the index-present arms are the loose ones (1.24×, 1.94×). When the query is a three-second
scan, the scan is the latency; when it is fast, the latency is everything else, and everything
else varies.

### 3.2 The four ratios the experiment exists to produce

```
index effect, pool 10    B / A  =  8739.8 / 690.0  =  12.7×
index effect, pool 50    D / C  =  4506.7 / 517.8  =   8.7×

pool effect, index ✓     A / C  =   690.0 / 517.8  =   1.33×
pool effect, index —     B / D  =  8739.8 / 4506.7 =   1.94×
```

**Five times the pool buys at most 1.94×, and only when the index is missing.** The index buys
8.7× even at the larger pool.

The interaction is real and it is the direction `R16` §8 predicted from mechanism: the index's
advantage **shrinks** as the pool grows, 12.7× → 8.7×. A bigger pool does take some of the
queueing away. It does not come close to taking the scan away.

### 3.3 The drift control, and what it costs this report

```
A   (start of session, 15:11)   p99  690.0
A′  (end of session,   16:09)   p99  544.1        1.27× apart, same configuration
```

**1.27× of drift across seventy minutes on nominally identical arms — against a pool effect of
1.33×.**

> **So `A / C = 1.33×` is not established by this experiment.** The difference between pool 10
> and pool 50 *with the index present* is inside the drift the control measured, and this
> report declines to claim it. `R4` §8 asked for this control and could not have it; the first
> time it ran, it deleted one of the four conclusions it was brought in to check.

`B / D = 1.94×` and both index effects are outside the drift band and survive.

### 3.4 Verbatim, arm B run 3 and arm D run 1

```
-- B run3
  p50 : 3630.4 ms   p95 : 5249.0 ms   p99 : 8739.8 ms   err : 0.00 %   vus : 200
  responses carrying a recommendation : 20.9 %
  steady state: first half 4683 ms, second half 3320 ms  (ratio 1.41)

  *** NOT STEADY STATE. The first half of the measurement window was 1.41x slower
  *** than the second, so the system was still warming while being measured.
  *** DO NOT PUBLISH THIS RUN.
```
```
-- D run1
  p50 : 2641.0 ms   p95 : 3035.4 ms   p99 : 4463.7 ms   err : 0.00 %   vus : 200
  steady state: first half 2665 ms, second half 2622 ms  (ratio 1.02)
```

### 3.5 The instrument printed *DO NOT PUBLISH* and exited 0

The block above is quoted for the number and it contains a second finding. That run **told the
operator not to publish it, and `k6` exited `0`.** It went into §3.1's median because nothing
stopped it.

`load/recommendations.js` opens with:

> *thresholds that FAIL the run rather than printing a warning, **because a threshold that only
> warns is a comment.***

and its `thresholds` block already carries a paragraph about the error threshold having been
exactly that placeholder for four days. **The same defect was in the same file twice, and
fixing one of them did not cause anyone to look at the other.**

There is a second half to it. The test was `early / late > 1.3` — **one-sided**. It fires only
when a run gets *faster* as it goes. A run whose second half is 1.33× slower produces a ratio
of `0.75` and passes in silence:

| Run | ratio | symmetric skew | old verdict | correct verdict |
| --- | ---: | ---: | --- | --- |
| A run1 | 0.75 | 1.33× | silent pass | **FAIL** — second half slower |
| C run2 | 0.76 | 1.32× | silent pass | **FAIL** — second half slower |
| B run3 | 1.41 | 1.41× | banner, exit 0 | **FAIL** — first half slower |

**Three of fifteen.** Two of them the instrument could not see at all.

### 3.6 Does the conclusion depend on those three runs?

No, and here is the arithmetic rather than the assurance. Excluding the three:

| | all three runs (median) | steady runs only |
| --- | ---: | ---: |
| A p99 | 690.0 | 671.3 — *two runs, so this is a mean, not a median* |
| C p99 | 517.8 | 495.1 — *mean of two* |
| B p99 | 8739.8 | 8716.1 — *mean of two* |
| D p99 | 4506.7 | 4506.7 — all three steady |
| A′ p99 | 544.1 | 544.1 — all three steady |
| **index @10** | **12.7×** | **13.0×** |
| **index @50** | **8.7×** | **9.1×** |
| **pool, index ✓** | 1.33× | 1.36× |
| **pool, index —** | **1.94×** | **1.93×** |
| **drift** | 1.27× | 1.23× |

The headline row is the median of three, per rule 5. The right-hand column is **not** a median
— with two valid runs there is no median, and calling a mean one would be the kind of quiet
substitution this repository keeps finding. It is reported as a check on robustness and
nothing more.

## 4. 원인 / Mechanism

A request that runs a sequential scan over 600,000 `mastery` rows holds its connection for the
duration of the scan. With ten connections, 200 virtual users queue behind ten scans; with
fifty, they queue behind fifty. Little's law from `.study/2장`: `λ_max = N / W`. **Raising `N`
five-fold moves `λ_max` five-fold only if `W` is unchanged and nothing downstream saturates.**

It is not five-fold. It is 1.94×, and the reason is that `W` grows as the pool grows: fifty
concurrent scans over the same table contend for the same 128 MB of `shared_buffers` and the
same eight cores, so each scan takes longer than it did when there were ten. The index removes
`W` instead of dividing the queue, which is why it wins at both pool sizes.

That is the same shape as `R4` §7's finding about arm C, restated one layer down:

> **`C` is a capacity decision and `D` is a correctness decision. They are not alternatives** —
> even for a system that needs more capacity, that is not a licence to hold connections it is
> not using.

Here: the pool is a capacity lever and the index is a correctness one, and **the capacity lever
does not reach.**

## 5. 처방 / Remedy

Nothing in the application changes. The 2×2 is a measurement, and `V3` already carries the
index — `R16` established that and this report is about how much of its value survives a
different pool.

What does change is the instrument.

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave the steady-state check printing | The three runs in §3.5 keep passing, and so does the next one | zero | no |
| Raise `STEADY_STATE_RATIO` until nothing trips | Green, meaningless | zero | **no — this is the move the PO's rule about the secret-scan thresholds forbids** |
| Make it symmetric, and make the verdict a file the runner must read | Catches both directions; enforcement is one step outside k6 and said so | a file, a README step, and an admission | **yes** |
| Express it as a k6 threshold | Would keep enforcement inside the script | **impossible**: a threshold is evaluated over one metric and this is a ratio between two, known only at the end. `teardown` cannot read metric values either | no |

The last row is why the third is worded the way it is. The gap is stated in the source rather
than left for someone to rediscover, because `R2` §9's failure mode is a requirement resting on
a premise nobody checked.

## 6. 재계측 / Re-measurement

The instrument, not the application.

| | Before — `01e16af` | After — this commit |
| --- | --- | --- |
| A run whose first half is 1.41× slower | banner printed, **exit 0** | `steady-state.txt` = `FAIL`, and `load/README.md` requires the runner to read it |
| A run whose **second** half is 1.33× slower | **not detected at all** | `FAIL`, with the direction named |
| Runs of `R18`'s fifteen that would now be refused | 0 | **3** |

**The first run after the fix failed, in the direction that had never been watched:**

```
  steady state: first half 226 ms, second half 307 ms  (ratio 0.74, skew 1.36x)

  *** NOT STEADY STATE. The SECOND half of the measurement window was 1.36x slower,
  *** so the system DEGRADED while being measured -- a cache filling, a plan changing,
  *** something else on the machine. This direction went unwatched until R18.
  *** DO NOT PUBLISH THIS RUN. steady-state.txt says FAIL and the runner must read it.

steady-state.txt:  FAIL skew=1.355
```

That is a positive control obtained for nothing: the newly-watched direction fired on the
first run after being enabled, which says the silence before was not because it was rare.

## 7. 회귀 게이트 / Regression gate

**Partial, and the honest description is that this one is weaker than most here.**

`load/recommendations.js` now writes `steady-state.txt` on every run, and `load/README.md`
step 2 is the check. That is enforcement by procedure, and procedure is what `PUB-4`'s
`reviewed` rows are made of — `R17` is a whole report about how that ends.

- **What is genuinely gated:** the symmetric band and the verdict file are in the script, so a
  run cannot produce a verdict the script disagrees with, and no operator can *forget* the
  direction that was previously invisible.
- **What is not:** nothing fails a build if the runner ignores the file. The load lane does not
  run in CI at all — it needs a seeded 3.9-million-row database — so there is no build to fail.

  > **Still true on 2026-08-18, and worth disambiguating now that a workflow called `load
  > harness` exists.** `load-harness.yml` runs three one-iteration scenarios through the wrapper
  > and no database; it tests the wrapper's control flow, which is machine-independent. **The
  > load scenario itself still never runs in CI**, and nothing there fails a build because an
  > operator ignored a verdict on a real measurement. `ADR-008` records why that lane is not the
  > rejected one and is not a step toward it.
- **Why not a k6 threshold:** §5, last row. It is a limit of the tool.

> **Closed by `ADR-008`, 2026-08-18.** `R19` moved this to `OPEN-9`'s neighbour `OPEN-8`,
> because *"what enforces it"* is a judgement and not a risk. **`load/run.sh` now wraps
> `k6 run`** and exits 1 on `FAIL`, 2 when no verdict was written at all. Three planted
> scenarios and `load-harness.yml` require exactly those three exits, with the clean one
> returning 0 as the negative control. **The CI load lane was rejected for a reason beyond
> cost** — `ADR-004` forbids CI asserting a duration, so it could never produce a citable
> latency and would exist only to enforce the verdict of a measurement it may not take.
> It still does not remove the person; it removes the second step.

The 2×2 itself has no gate and cannot usefully have one, for `R13`'s reason: the shipped schema
has the index, so an assertion about the no-index arm would have nothing to run against.

## 8. 남는 위험 / Remaining risk

- **`A / C = 1.33×` is inside the drift band and is not claimed.** §3.3. The experiment cannot
  say whether a larger pool helps when the index is present. Answering it needs arms
  interleaved rather than run in blocks, and that is **미측정**.
- **Where the drift came from is 미측정.** 1.27× over seventy minutes, on a machine also
  running Windows, WSL2, Docker and an IDE. Page cache, CPU thermal state, and background work
  are all candidates and none was sampled — deliberately, because sampling inside a window is
  the contamination `R16` §8 recorded.
- **Two pool sizes and one concurrency.** 10 and 50 at 200 VU. The knee is **미측정** in both
  index conditions, and `measurement-discipline.md` says a report that measures one concurrency
  level has found a point rather than a curve. This one found two points on two curves.
- **`max_connections=100` was never reached but was never far.** Pool 50 with up to 2 parallel
  workers per query could have. Error rate was 0.00 %, so it did not — but **how close it came
  is 미측정**, because measuring it means querying `pg_stat_activity` inside the window.

  > **Measured 2026-08-21 — `R24` §3.1. Reached, 28 times, and this bullet is falsified.**
  > Not by a larger pool on one instance but by a second and third instance holding pools of
  > their own: 3 × 60 against `max_connections=100` produces **28 `FATAL: sorry, too many
  > clients already`** — and the application logs nothing, all 120 requests answering `200`.
  > **The 0.00 % error rate this bullet reasons from is exactly what the defect looks like**,
  > because the connection is refused when a pool fills itself at startup rather than when a
  > request asks for one. The measurement did not need `pg_stat_activity` inside the window;
  > it needed a second instance, and one-instance was the assumption neither `R2` nor this
  > report knew it was making.
- **Arms B and D are `V1..V3` with one constraint dropped**, not a reconstruction of the schema
  before `T6`. `R16` §8 says the same and it is still true.
- **21.0 % of responses carried a recommendation**, so four requests in five in every number
  above did none of the work the report is about. `R16` §3.4 established this and it applies
  unchanged; p50 here is largely the empty path and p99 is the working one.
- **The three refused runs are still in §3.1's medians.** §3.6 shows the conclusion holds
  either way, but the headline table contains data the current instrument would reject. Re-running
  those three under the fixed instrument is **not done** — it would cost another seventy minutes
  and, by §3.3, land inside a drift band wider than the difference it would resolve.
- **The verdict file is enforcement by procedure.** §7. `R17` is this repository's report on
  what happens to a rule whose only enforcement is a person remembering it, and this one is now
  in that category by construction.

  > **No longer, as of `ADR-008`.** `load/run.sh` returns non-zero and is the only documented
  > way to run a scenario, and `load-harness.yml` watches it refuse. **What remains true is the
  > smaller claim**: somebody still has to invoke the wrapper, and nothing stops a direct
  > `k6 run`. The failure is now loud where it happens rather than in a log two hours later,
  > which is where this report actually found it.
- **What would break this conclusion:** a machine where `shared_buffers` is large enough to hold
  `mastery` entirely, or a pool small enough that even the indexed path queues. Both change
  which term dominates `W`, and neither was varied.

## 9. 배운 것 / What I learned

이 실험은 `R16` §8이 *"결론을 깨뜨리는 것"* 이라고 지목한 항목이었고, 저는 그게 깨질 거라고
반쯤 기대하고 시작했습니다. 안 깨졌습니다 — 풀을 다섯 배로 키워도 2배밖에 못 사고, 인덱스는
그 뒤에도 8.7배입니다. **예상이 맞은 게 이 리포트에서 제일 안 중요한 부분입니다.**

값진 건 대조군이었습니다. `R4` §8이 *"세션 내 드리프트가 arm A에 몰렸을 수 있다"* 고 적어두고
숫자를 못 냈는데, 그걸 처음 재봤더니 **1.27배**였고 그게 제가 주장하려던 것 중 하나(`A/C =
1.33배`)보다 컸습니다. **대조군이 자기가 검증하러 온 결론 하나를 그 자리에서 죽였습니다.**
없었으면 저는 "인덱스가 있어도 풀 50이 33% 낫다"를 그냥 적었을 겁니다. 그리고 그건 드리프트를
성능이라고 부른 문장이었을 겁니다.

두 번째는 더 아픕니다. 로그를 읽다가 `B run3`이 `DO NOT PUBLISH THIS RUN`을 찍고 **exit 0**으로
끝난 걸 봤습니다. 그 파일 헤더가 *"경고만 하는 임계값은 주석이다"* 라고 써 있고, 같은 파일
아래쪽에 **오류 임계값이 나흘간 정확히 그런 자리표시자였다는 문단이 이미 있습니다.** 한 파일 안에
같은 결함이 두 개 있었고, 하나를 고칠 때 다른 하나를 안 봤습니다. 고친 사람이 저입니다.

그리고 한쪽만 보고 있었다는 것 — `early/late > 1.3` 은 실행이 **빨라질** 때만 걸립니다.
느려지는 판은 비율 0.75로 조용히 통과했고, 열다섯 판 중 둘이 그랬습니다. 대칭으로 바꾸고
검증하러 한 판 돌렸더니 **그 판이 바로 새 방향에서 걸렸습니다.** 안 걸리던 게 드물어서가
아니었습니다. 안 보고 있어서였습니다.

이번 실행 전에 한 번을 통째로 버렸습니다. 파이프라인의 `grep` 패턴에 `p50/p95/p99`가 없어서
**45분치 세 팔의 백분위가 전부 사라졌고, 남은 건 "이 실행은 정상 상태였다"는 진단뿐**이었습니다.
무엇이 정상 상태였는지는 없었습니다. 계기판이 자기가 재려던 것만 정확히 버린 겁니다 — 이 저장소
여섯 번째입니다. 다행히 이건 조용히 통과한 게 아니라 아무것도 안 남겨서 즉시 보였습니다.
**조용한 쪽이었으면 45분이 아니라 리포트 하나를 잃었을 겁니다.**
