# R30. A maximum that cannot be reached, and the queue that makes it so

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `3ab75d6` — the state in which the mechanism is observable
> **Green commit**: **none, and the refusal is the decision.** `ADR-018` records why pool 3 keeps
> its unreachable maximum: nothing in this application calls it, and choosing a rejection policy
> for a load that does not exist is building a guard before there is anything to guard — a bill
> this repository has already paid once, and `.githooks/pre-commit`'s own header is where it is
> recorded. The remedy is **measured** in §3.2 and **not adopted**, which is a different sentence
> from "no remedy was found".
> **Reads with**: `R29`, which is the same slice one pool over.

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04,
                   kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  JVM            : Temurin 21.0.12+8
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  PostgreSQL     : server 16.15 — postgres@sha256:cf78e766… (the test lane's container)
  THE FIVE POOLS : 1 workers max=200 minSpare=10 · 2 connections max=10 minIdle=10
                   3 applicationTaskExecutor core=8 max=2147483647 queueCapacity=2147483647
                   4 ForkJoinPool.commonPool parallelism=7 · 5 virtual carriers unset
  Load           : NONE. Every figure below is a COUNT taken by `ThreadPoolBoundsTest`
  What else was running on the machine : other work was, and it does not matter — see below
```

> ⭐ **Nothing in this report is a duration, and that is why it needed no quiet machine.**
> `ThreadPoolExecutor` creates its threads **synchronously inside `execute()`**, on the
> submitting thread, so once the submission loop returns, the pool size it reached is final.
> Pool size, queue depth and rejection count are read exactly rather than sampled. There is no
> race for a busy machine to influence and no percentile for it to inflate.
>
> This is the distinction the round's measurement lock turns on: `R29` cost an hour of an
> exclusive machine and this cost none, because one of them measures *how long* and the other
> measures *how many*.

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Read off this application's running `applicationTaskExecutor`:

```
core = 8    max = 2147483647    queueCapacity = 2147483647
```

Nothing in this repository sets `spring.task.execution.*`, so those are the framework's numbers.
`max` is `Integer.MAX_VALUE` — and **the size of the number is not the finding.** The finding is
that it would be exactly as unreachable if it read `200`, which is what a configuration written
by a person tends to say.

**A thread pool grows past its core size only when its queue refuses a task.** An unbounded queue
never refuses. So `max` is decoration, `core` is the real concurrency limit, and a reader who
budgets capacity from the configuration is out by a factor of the two numbers' ratio.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests '*ThreadPoolBoundsTest' --rerun-tasks
```

One shape, four configurations of it: a `ThreadPoolTaskExecutor` — **the same class Boot builds
pool 3 from** — given a core size, a maximum, a queue capacity and a number of tasks that park
until released. The counters are read after the submission loop returns.

| | core | max | queue | tasks |
| --- | ---: | ---: | ---: | ---: |
| **A** — red, the shape pool 3 has | 2 | 8 | **unbounded** | 20 |
| **B** — the remedy | 2 | 8 | **2** | 20 |
| **A-small** / **B-small** — the control pair | 2 | 8 | unbounded / 2 | **2** |

**`core = 2, max = 8` rather than `8` and `2147483647`.** The mechanism is the ratio between core
and max and whether the queue lets the gap be crossed; small numbers make the counts readable and
the test fast. `PoolCensusGateTest` asserts the real bean's three numbers separately, so nothing
here has to stand in for them.

## 3. 계측 / Measurement

### 3.1 The mechanism: arms A and B, twenty tasks each

Same core, same maximum, same load. **The only variable is the queue.** Counters read after the
submission loop returned — exactly, not sampled, because `ThreadPoolExecutor` adds its workers on
the submitting thread and no thread can appear afterwards.

```
R30 §3 core=2 max=8 queue=unbounded tasks=20 -> poolSize=2 queued=18 rejected=0
R30 §3 core=2 max=8 queue=2         tasks=20 -> poolSize=8 queued=2  rejected=10
```

| | **A** — unbounded queue | **B** — queue bounded at 2 |
| --- | ---: | ---: |
| core / max asked for | 2 / 8 | 2 / 8 |
| tasks submitted | 20 | 20 |
| **pool size reached** | **2** — never left core | **8** — reached max |
| queued | **18** | 2 |
| **rejected** | **0** | **10** |

⭐ **`max = 8` is written down in both arms and only one of them ever gets there.** The setting that
decided it is the **queue**. Eighteen tasks sat in a queue with no limit, nothing was refused, and
nothing was logged — the only symptom available to anybody is that the work finishes later than it
should.

### 3.2 The remedy arm, and the failure mode it buys

Arm B is the same overload with a different failure mode:

| | |
| --- | ---: |
| accepted | **10** = 8 running + 2 queued |
| **refused with `TaskRejectedException`** | **10** |

**Bounding the queue did not make the pool bigger in any useful sense — it made the refusal
possible.** `max` became reachable, and the ten tasks a limitless queue would have absorbed are now
told `no` at submission time instead of `later`.

**Neither column is the safe one.** §4's table is the argument; the number here is that **half the
load is refused** at a queue capacity of 2, which is what makes the choice of that capacity a real
decision rather than a formality — and why `ADR-018` declines to make it for a pool with no callers.

⚠️ **One printed field is not a finding and is named so it is not read as one.** The harness also
prints `started=`, the number of tasks that had begun executing when the counters were read: it was
`1` in arm A and `8` in arm B on this run. **That is a race, not a measurement** — the pool size,
queue depth and rejection count are exact because `ThreadPoolExecutor` adds workers synchronously
inside `execute()`, and `started` is not.

### 3.3 And the two configurations are indistinguishable until they are not

The third arm submits **two** tasks instead of twenty to both configurations. They behave
identically, and that is why this defect survives every test anybody writes for it: at any load
the pool can absorb, the bounded and unbounded queues are the same pool.

```
R30 §3 core=2 max=8 queue=2         tasks=2 -> poolSize=2 queued=0 rejected=0
R30 §3 core=2 max=8 queue=unbounded tasks=2 -> poolSize=2 queued=0 rejected=0
```

**Identical, in every column.** Two tasks, two core threads, nothing queued, nothing refused —
and no test written at this size could tell the two configurations apart.

⭐ **That is why this defect survives review and survives a test suite.** It is not subtle and it is
not hidden; it is simply **invisible below the load that distinguishes it**, and the load that
distinguishes it is the one nobody runs against a pool nothing calls.

## 4. 원인 / Mechanism

`java.util.concurrent.ThreadPoolExecutor.execute` is three cases in a fixed order:

```
1. fewer than corePoolSize threads     -> start a new thread
2. otherwise                           -> try to enqueue
3. only if the enqueue FAILED          -> start a thread, up to maximumPoolSize
4. only if that failed too             -> reject
```

**Case 3 is reachable only through a failed enqueue**, and a queue with no bound cannot fail one.
So cases 3 and 4 are both dead code for pool 3 as configured, and `max = 2147483647` describes a
branch that will not execute.

That ordering is also why the two settings cannot be reasoned about separately. `max` is not
*"how many threads this pool may have"*; it is *"how many threads this pool may have **once the
queue is full**"*, and the queue capacity is what decides whether that clause is ever satisfied.
**They are one setting with two names.**

The consequence under load is a change of failure mode rather than a change of throughput:

| | unbounded queue | bounded queue |
| --- | --- | --- |
| concurrency | pinned at `core` | rises to `max` |
| overload appears as | **latency and heap** | **`TaskRejectedException`** |
| the caller learns about it | when the answer is late | at submission |
| the operator learns about it | from a queue-depth gauge, if one exists | from an exception count |

**Neither column is the safe one.** The left column has no refusal and therefore no bound on how
much work can be accepted; the right column refuses work a slightly larger queue would have
absorbed. What the left column does have is the property that makes it dangerous: it looks
identical to a healthy system in every metric except latency, right up until the heap.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — leave it | `max` stays decoration; nothing uses the pool | zero | **yes, and `ADR-018` records why** |
| B — bound the queue | `max` becomes reachable; rejection becomes possible | a rejection policy nobody has been asked for | no |
| C — set `max` to something believable, queue still unbounded | **changes nothing at all**, and makes the configuration look considered | worse than zero | no |
| D — assert the present shape in a gate | the next person to add `@Async` trips it | one test | **yes** |

**Option C is the row worth reading twice.** It is what a reviewer asks for when they notice
`2147483647` in a metrics endpoint, it is satisfying to do, and it is measured in §3.1 to have no
effect whatsoever. A configuration that reads `core 8, max 200, queue unbounded` runs at 8.

**Option A is chosen because pool 3 has no callers.** `AsyncBoundary` is a fixture; no request
path reaches it. Bounding a queue for a load that does not exist is choosing a rejection policy in
the dark — and this repository has already priced building a guard before there is anything to
guard. `.githooks/pre-commit` says it in its own header: it was *"deliberately not written while
the repository held no data — a guard that protects nothing is not free, it is unbanked, and this
project has already paid a day for building one early."* The condition changed when the generator
ran. Pool 3's condition has not changed yet.

**Option D is what makes A safe.** `PoolCensusGateTest` asserts `core=8`, `max=Integer.MAX_VALUE`
and `queueCapacity=Integer.MAX_VALUE` as they stand, with `THIS ASSERTION EXPECTS THE DEFECT` in
the message. The first commit that puts an `@Async` call on a request path has to change one of
those numbers, which turns the gate red and forces `ADR-018`'s condition to be answered in the
same diff.

## 6. 재계측 / Re-measurement

**None, and none is owed.** Nothing in the application changed, so there is no after-state to
measure — `R2` is the precedent for a report that establishes a mechanism and declines to ship a
remedy on evidence that cannot support one. §3.2's arm is the remedy measured in isolation, and
it is labelled as an arm rather than as an after.

## 7. 회귀 게이트 / Regression gate

`PoolCensusGateTest`'s second test, plus `ThreadPoolBoundsTest` for the mechanism itself.

The mechanism test is the one that matters in five years: if a future JDK changes
`ThreadPoolExecutor`'s ordering — case 3 before case 2, say, which would make `max` reachable and
this whole report obsolete — `ThreadPoolBoundsTest`'s first arm goes red and says so.

## 8. 남는 위험 / Remaining risk

- **The numbers here are `ThreadPoolTaskExecutor`'s, taken on a pool this test built.** They are
  the same class Boot builds pool 3 from, and `PoolCensusGateTest` reads the real bean's three
  numbers — but no test in this repository has ever put a task through the *real*
  `applicationTaskExecutor` under load, because nothing calls it. **미측정.**
- **`core = 8` is not a fixed number either.** Boot's default core size is what it is; whether it
  follows `availableProcessors` on a smaller box, the way `R32` shows the common pool does and
  `R23` shows the heap does, is **미측정** and would change this report's arithmetic inside a
  container.
- **The rejection count in §3.2 is exact for this arm and not a general formula.** It is
  `tasks − (max + queueCapacity)` only while every task is still running; a pool that is draining
  refuses fewer. Nothing here measured a draining pool.
- **No `RejectedExecutionHandler` was measured.** Boot's default is `AbortPolicy`, which throws.
  `CallerRunsPolicy` would convert rejection into backpressure on the submitting thread — which
  on a **web server worker** means the request thread executes the async work, and the async
  boundary silently stops being one. That is a defect worth its own report and it is **미측정**.
- **This says nothing about whether `@Async` *should* be used here.** `R31` is that question, and
  its answer is not about pool sizes.
- **The whole report is about a pool with no callers.** If that stays true, its practical value is
  the gate; if it stops being true, §5 option A expires and nothing automatically notices except
  the gate. That is the intended design and it is still a single point of failure.

## 9. 배운 것 / What I learned

이 보고서에서 제일 위험한 건 측정한 결함이 아니라 **고치고 싶어지는 충동**이었다.

`max = 2147483647`을 처음 봤을 때 반사적으로 든 생각은 "200 정도로 바꾸자"였다. 그럴듯하고,
리뷰에서 칭찬받을 만하고, **아무것도 바꾸지 않는다.** 큐가 무한이면 200이든 20억이든 도달하지
않는다는 걸 재보기 전에는 몰랐다. §5의 옵션 C는 내가 처음에 하려던 일이고, 표에 남겨둔 이유는
그게 틀렸다는 걸 숫자로 보여주는 칸이 필요해서다.

두 번째로 배운 것 — **`max`와 `queue-capacity`는 두 개의 설정이 아니라 이름이 두 개인 하나의
설정이다.** 문서에는 따로 적혀 있고, 설정 파일에서도 따로 쓰고, 사람은 따로 고른다. 그런데
`ThreadPoolExecutor.execute`의 세 갈래 순서를 읽으면 `max`는 "큐가 거절했을 때만" 의미가 있다.
따로 고를 수 있다고 믿는 순간 둘 중 하나는 장식이 된다.

그리고 제일 오래 붙잡고 있었던 것: **고치지 않기로 한 것을 보고서에 어떻게 쓰느냐.** 처음 초안은
큐를 묶고 green 커밋을 만들려고 했다. 이 저장소가 원하는 모양이 red/green 쌍이니까. 그런데 이
executor는 호출자가 없다. 부하가 없는데 거절 정책을 고르는 건 지킬 게 없는데 가드를 만드는 것이고,
green 커밋을 갖고 싶다는 이유로 애플리케이션을 바꾸는 건 정확히 이 라운드가 하지 말라고 한 것이다.

그래서 이 보고서에는 green 커밋이 없다. 대신 **조건**을 적었다 — 언제 이게 결함이 되는지, 그리고
그때 누가 알아차리는지. 게이트가 그 자리에 있는 이유가 그거다. `R2`도 처방을 고르지 않고 끝났고,
그 보고서는 약해서 그런 게 아니었다.
