# R34. Two remedies that are correct only while there is one instance

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `3b90db2` — the three remedies, asserted to hold at two instances
> **Green commit**: **this one** — the boundary of each remedy, pinned
> **Answers**: `R6`, one layer up. That report compared six strategies and every one of them
> lived inside the database's understanding of the work; it never asked whether the exclusion
> had to be in the database at all.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0)
                   15.2.0, 64-bit — read with `select version()` in this session (R37 §3.2).
                   Pinned by digest, from TestcontainersConfiguration.POSTGRES_IMAGE:
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
  Isolation      : READ COMMITTED, default, unchanged
  Contention     : 10 threads x 100 increments = 1,000, ALL ON ONE ROW — R6 §3's shape
  Instances      : 1, then 2. TOTAL WORK HELD CONSTANT: ten threads either way
  Repetitions    : 2 invocations of every arm — red (3b90db2) and green. BOTH are reported,
                   because §3.2's central claim is about which numbers move between runs.
  WHAT ELSE WAS RUNNING ON THIS MACHINE: slice D's chained full test runs and slice G, on
                   other worktrees. Three Gradle daemons.
                   EVERY FIGURE IN §3 IS ARITHMETIC — a count of increments that survived.
                   None is a duration and none contends.
  ⛔ COST         : 미측정. NOT IN THIS REPORT. See §5 and §8 — the cost comparison and the
                   contention level at which CAS and locking invert are the two numbers this
                   report does not have, and they need an exclusive machine.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A counter is incremented one thousand times, by an application that is correct.

On one instance it reads **1,000**. A second instance is started — the same jar, the same
code, the same total work — and it reads **565**.

Nothing failed. `failures=0` on every arm. No exception, no rejected write, no constraint, no
log line. **The remedy did not stop working. It stopped applying**, and nothing anywhere is
required to notice the difference.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.LayeredRemedyTest --rerun-tasks
```

At `3b90db2`. Three remedies for one defect, each at a different layer:

```
  (1) synchronized  -- mutual exclusion inside one JVM
  (2) CAS / Atomic  -- an atomic update with no lock at all
  (3) the database  -- one statement the database cannot split
```

Ten threads, one hundred increments each, one `mastery` row — `R6` §3's shape, so the
arithmetic is read the same way. **The total work is held constant across the variable**: ten
threads whether that is one instance serving ten or two serving five apiece. Varying the
instance count without holding the work fixed would compare two different experiments.

### 2.1 What "instance" means here, and the direction of the approximation

An instance is one application instance's copy of the bean: **its own monitor, its own
`AtomicInteger`**, against one shared database. Two instances are two such objects.

⚠ **This is deliberately not a second JVM, and the direction is what makes it usable.** Two
objects in one heap share a JIT, a garbage collector and a cache-coherent view of memory; two
JVMs on two machines share none of it. So this construction is **strictly more favourable to
① and ② than a real fleet is.** Everything it shows them losing, they lose worse in production.

What it therefore cannot support is the opposite claim. **No cost figure taken this way
describes a real second instance**, and §8 keeps that as an open item rather than a caveat.

## 3. 계측 / Measurement

Verbatim. `row` is what the database holds of 1,000; `inMemory` is what each instance would
answer from its own state; `failures` are increments that raised.

```
E1 >>> read-modify-write          instances=1 row=126    inMemory=[0]            failures=0     rowShortBy=874
E1 >>> (1) synchronized           instances=1 row=1000   inMemory=[0]            failures=0     rowShortBy=0
E1 >>> (2) CAS write-through      instances=1 row=1000   inMemory=[1000]         failures=0     rowShortBy=0
E1 >>> (2) CAS write-behind       instances=1 row=1000   inMemory=[1000]         failures=0     rowShortBy=0
E1 >>> (3) database               instances=1 row=1000   inMemory=[0]            failures=0     rowShortBy=0

E1 >>> (1) synchronized           instances=2 row=565    inMemory=[0, 0]         failures=0     rowShortBy=435
E1 >>> (2) CAS write-through      instances=2 row=500    inMemory=[500, 500]     failures=0     rowShortBy=500
E1 >>> (2) CAS write-behind       instances=2 row=500    inMemory=[500, 500]     failures=0     rowShortBy=500
E1 >>> (3) database               instances=2 row=1000   inMemory=[0, 0]         failures=0     rowShortBy=0
```

Run 2, green, this commit:

```
E1 >>> read-modify-write          instances=1 row=124    inMemory=[0]            failures=0     rowShortBy=876
E1 >>> (1) synchronized           instances=1 row=1000   inMemory=[0]            failures=0     rowShortBy=0
E1 >>> (2) CAS write-through      instances=1 row=1000   inMemory=[1000]         failures=0     rowShortBy=0
E1 >>> (2) CAS write-behind       instances=1 row=1000   inMemory=[1000]         failures=0     rowShortBy=0
E1 >>> (3) database               instances=1 row=1000   inMemory=[0]            failures=0     rowShortBy=0

E1 >>> (1) synchronized           instances=2 row=557    inMemory=[0, 0]         failures=0     rowShortBy=443
E1 >>> (2) CAS write-through      instances=2 row=500    inMemory=[500, 500]     failures=0     rowShortBy=500
E1 >>> (2) CAS write-behind       instances=2 row=500    inMemory=[500, 500]     failures=0     rowShortBy=500
E1 >>> (3) database               instances=2 row=1000   inMemory=[0, 0]         failures=0     rowShortBy=0
```

| remedy | 1 instance | 2 instances | raised | **moved between runs?** |
| --- | --- | --- | --- | --- |
| *control* — read-modify-write | **126** / **124** | — | 0 | **yes** |
| **① `synchronized`** | 1,000 / 1,000 | **565** / **557** | **0** | **yes** |
| **② CAS, write-through** | 1,000 / 1,000 | **500** / **500** | **0** | **no** |
| **② CAS, write-behind** | 1,000 / 1,000 | **500** / **500** | **0** | **no** |
| **③ the database** | 1,000 / 1,000 | **1,000** / **1,000** | **0** | no |

`2 tests, 0 failures` in run 2.

### 3.1 The premise was checked, not assumed

The slice brief this work came from states that on one instance all three remedies are correct.
**That is a claim about this stack and it was measured before anything was built on it** —
`1,000` on all three, with the unguarded control losing 874 in the same run to prove the
harness was actually racing.

Two things could have gone wrong there and did not. The control could have failed to race, in
which case every arm's `1,000` would have meant nothing. And ② could have been wrong even at one
instance: `incrementAndGet` hands out a unique value, but **a unique value is not an ordered
write**, and two threads holding 998 and 999 could have reached the row in the other order. On
this stack they did not — `row=1000` — and that was not safe to assume.

### 3.2 ⭐ The two failures have different shapes, and ② is the worse one

This is the finding, and it is not visible from the "lost" column alone.

**① fails like a race.** `565` of 1,000 in the first run, `557` in the second, and there is
nothing special about either. Two monitors admit two writers at a time, they interleave
arbitrarily, and the loss is whatever the schedule produced.

**② fails like a partition.** `500`, **exactly, in both runs**, from `inMemory=[500, 500]` both
times. Each instance counted **its own work perfectly**. The two totals add to exactly 1,000. No
increment was lost by either process — every one was counted, in a place the other process
cannot see.

⭐ **The right-hand column of §3's table is the evidence, and it exists because `R35` cost this
session a headline.** That report was drafted from one run and its central number moved 4× on
the second. So this arm was run twice on purpose, and the two runs do not merely agree — **they
disagree in exactly the places the mechanism says they should.** The control and ① moved; ② did
not move at all. A single run could not have distinguished *"② happened to land on 500"* from
*"② lands on 500 by construction"*, and those are different claims.

⭐ **So each instance is internally consistent and confidently wrong.** Ask instance A how many
increments there have been and it answers 500, correctly, from state it has every reason to
trust. Ask B and it answers 500. The database says 500. **Three self-consistent answers and no
disagreement anywhere to investigate** — which is the state that survives a debugging session,
because nothing in it looks broken.

① at least produces a number that is obviously junk. ② produces a number that is exactly half
and looks like a plausible business fact.

### 3.3 ③ did not degrade

`1,000` at one instance and `1,000` at two, `failures=0` both times. The exclusion is attached
to the **row**, and the row is the one thing every instance already shares. Adding instances
does not dilute it because it was never held in a process to begin with.

## 4. 원인 / Mechanism

All three remedies exclude concurrent writers. They differ in **what the exclusion is attached
to**, and that is the only thing that matters when the process count changes.

| remedy | the exclusion is scoped to | survives a second instance |
| --- | --- | --- |
| ① `synchronized` | **an object** — this bean's monitor | no |
| ② `AtomicInteger` | **an object** — this bean's field | no |
| ③ `update … set c = c + 1` | **a row** — in the shared database | yes |

`@Synchronized` puts the monitor on `this`. Every thread in *this process* that calls the
method on *this bean* serialises. That is exact, and it is exact for exactly one reason:
**every writer went through that object.** Nothing in the language, the framework or the
database checks that premise, and nothing reports it when it stops holding.

`AtomicInteger` is the same statement with a different mechanism. A CAS loop cannot lose an
increment and cannot block — within the field it operates on. A second process has a second
field.

**The database's row is not a better lock. It is a lock in the only place that is still shared
when the process count goes up.** That is the whole of §5.

## 5. 처방 / Remedy

| Option | Correct at 1 | Correct at 2 | Cost | Chosen |
| --- | --- | --- | --- | --- |
| read-modify-write | **no** — 126 | no | fastest, and wrong | |
| ① `synchronized` | **yes** — 1,000 | **no** — 565 | 미측정 | |
| ② CAS write-through | **yes** — 1,000 | **no** — 500 | 미측정 | |
| ② CAS write-behind | **yes** — 1,000 | **no** — 500 | 미측정 | |
| **③ one atomic statement** | **yes** — 1,000 | **yes** — 1,000 | 미측정 | **✔** |

⭐ **The output of this report is not "use a database lock".** It is the **boundary** of each
remedy, and the boundary is a deployment fact rather than a code fact:

> ① and ② are correct **while, and only while, every writer is inside one process.**

That condition is invisible in the source. Nothing in `incrementBySynchronized` says *"valid
only at one instance"*, no test catches it, and the day it stops being true is a day somebody
edits a deployment descriptor rather than a `.kt` file.

**Cheap is not wrong — it is narrower.** ① and ② are almost certainly far cheaper than ③, and
that would be a perfectly good reason to choose one of them **for state that is genuinely
process-local**: a per-instance metric, a cache of something derivable, a rate limiter that is
allowed to be approximate per node. The defect is not using an in-process primitive. It is using
one for state that is supposed to be global, and the test suite cannot tell the difference.

⛔ **This report does not compare their cost, and will not until it can.** The environment block
says `미측정` and §8 says what is missing. **Never conclude that CAS is faster than locking**:
it inverts with contention, and the interesting number is where it inverts, not which side of
that point one machine happened to be on.

## 6. 재계측 / Re-measurement

| Metric | ① `synchronized` | ② CAS | **③ database** |
| --- | --- | --- | --- |
| increments surviving at 1 instance, of 1,000 | 1,000 | 1,000 | **1,000** |
| increments surviving at 2 instances, of 1,000 | **565** | **500** | **1,000** |
| increments raised at 2 instances | 0 | 0 | **0** |
| each instance's own view at 2 | `[0, 0]` | `[500, 500]` | `[0, 0]` |

The remedy is a change of layer, not a change of strategy: the same read-modify-write that lost
874 as a control keeps all 1,000 once the read and the write are one statement the database
cannot split.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/LayeredRemedyTest.kt`, run by
`.github/workflows/build.yml`.

Two arms, and the first one is the precondition for the second:

- **`on ONE instance all three remedies keep every increment`** pins the premise, and pins that
  the unguarded control **does** lose updates. If the control ever stops losing them, the harness
  has stopped racing and the second arm's numbers mean nothing.
- **`on TWO instances only the database remedy survives`** pins the boundary: ③ exact at 1,000,
  ① and ② strictly below it, `failures == 0` across all four arms, and — the assertion that
  carries §3.2 — **the per-instance totals summing to exactly 1,000 while the row holds half.**

That last assertion is the one worth keeping. It fails if ② ever starts losing increments
*within* an instance, which would be a different defect wearing this one's numbers.

## 8. 남는 위험 / Remaining risk

- ⛔ **The cost comparison does not exist, and it is half of what this trap was for.** The slice
  brief asks for the contention level at which CAS and locking **invert**, and calls that the
  headline. **`미측정`** — it is a duration sweep, it needs an exclusive machine, and slice D's
  and G's work was on this one throughout. Nothing here should be read as a performance claim in
  either direction.
- **`565` and `557` are two samples of a race.** Only `< 1000` is stable, and the gate asserts
  only that. **`500` did not move**, which supports §3.2's mechanism — but **two runs establish
  that a number is stable across two runs and nothing more.** A third could differ; the
  distribution is `미측정`.
- **Two invocations is the floor, not a sweep.** `R35`, from the same session, had its headline
  move 4× between consecutive runs, which is why these arms were repeated at all. Two is enough
  to tell a moving number from a still one and not enough to characterise either.
- ⭐ **"Two instances" is two objects in one heap, not two JVMs.** §2.1 states the direction:
  the construction is strictly more favourable to ① and ② than a real fleet, so the correctness
  result is a lower bound on the breakage. **What it cannot support is any cost claim**, and it
  also cannot show effects that need real separation — GC pauses, clock skew, network partition,
  or a rolling deploy where the two instances run different code. Round 2 slice B's container
  harness (`load/ops/harness.sh`) is the instrument that would close this and was not used.
- **Only 2 instances, only 10 threads, only 1 row.** `R24` ran three instances and found the
  starvation split unequal and unexplained; nothing here varies the instance count past two.
  `미측정`.
- **② was measured in two forms and both are strawmen in one respect**: neither writes through a
  shared coordinator. A real fleet using ② would usually add one — a distributed lock, a leased
  leader, a message queue — and **this report measures none of them.** It measures ② as it is
  actually written by someone who has not yet noticed there are two instances, which is the
  defect, and not ② as a considered distributed design.
- **The one-instance results are a single run each.** They are exact (`1,000`) and exactness is
  cheap to trust, but a `1,000` that happened not to race would look identical. The control
  losing 874 in the same run is what stops that reading, and it is the only thing that does.
- **What would break the conclusion:** state that is genuinely process-local. §5 says it
  plainly — ① and ② are not defective primitives, they are correctly-scoped ones applied to
  the wrong scope, and a per-instance counter is a legitimate use with no defect in it at all.

## 9. 배운 것 / What I learned

**제일 오래 남을 숫자는 `inMemory=[500, 500]`이다.** 두 인스턴스가 각자 500을 세었고, 둘을 더하면
정확히 1,000이다. **아무 증분도 유실되지 않았다.** 전부 세어졌고, 다만 상대가 볼 수 없는 곳에
세어졌다. 그래서 A한테 물어도 500, B한테 물어도 500, DB한테 물어도 500이다. **세 답이 서로
모순되지 않는다.** 조사할 불일치가 없다는 게 이 결함의 제일 고약한 부분이었다.

`synchronized` 쪽은 565가 나왔는데, 오히려 이게 낫다. 딱 봐도 쓰레기 숫자라서 누군가 이상하다고
생각할 여지가 있다. **CAS 쪽은 정확히 절반이고, 절반은 그럴듯한 업무 숫자처럼 생겼다.** "레이스면
숫자가 튄다"는 직관이 여기서는 반대로 작동했다 — 덜 튀는 쪽이 더 위험했다.

두 번째. 처방 세 개가 "무엇을 잠그느냐"가 아니라 **"잠금이 무엇에 붙어 있느냐"로만 갈렸다.**
①은 객체, ②도 객체, ③은 행. 인스턴스가 늘어날 때 살아남는 건 프로세스 안에 안 들어 있던 것뿐이다.
DB 잠금이 더 좋은 잠금이라서 이긴 게 아니라, **프로세스 수가 늘어도 여전히 공유되는 유일한 자리에
있어서** 이겼다.

세 번째가 좀 불편한 건데, ①과 ②는 **틀린 도구가 아니다.** 범위가 맞게 붙어 있는 도구를 범위가 다른
상태에 쓴 것뿐이다. 인스턴스별 메트릭이었으면 아무 결함도 아니었을 거다. 그리고 소스만 봐서는
그 조건이 어디에도 안 적혀 있다. `incrementBySynchronized`에 "인스턴스 하나일 때만 유효"라고
쓰여 있지 않고, 테스트도 못 잡고, 그게 깨지는 날은 `.kt` 파일이 아니라 **배포 설정을 고치는 날**이다.
