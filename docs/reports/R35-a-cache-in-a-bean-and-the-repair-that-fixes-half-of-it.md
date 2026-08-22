# R35. A cache in a bean, and the repair that fixes half of it

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `5d1554d` — a `HashMap` on a bean, asserted to keep what it was given
> **Green commit**: **this one** — the two repairs measured apart, and the defect characterised
> **Answers**: nothing previously written down. `ADR-005` decided there is **no cache layer**
> here and that decision is not reopened; §5 says why this report does not touch it.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : NOT USED. No container, no Spring context — the defect is in the heap and a
                   database underneath it would only make it slower to see.
  Concurrency    : 8 threads released from a CyclicBarrier
  Keys           : 2,000 distinct (250 per thread, one writer per key) for the size arms;
                   ONE key, 8 threads, for the compound-operation arms
  Repetitions    : 2 invocations of every arm — red (5d1554d) and green. BOTH are reported.
                   §3.2 exists because the second run disagreed with the first.
  WHAT ELSE WAS RUNNING ON THIS MACHINE: slice D's chained full test runs and slice G, on
                   other worktrees. Three Gradle daemons.
                   EVERY FIGURE HERE IS A COUNT OF MAP ENTRIES OR OF LOADER INVOCATIONS.
                   None is a duration and none contends. See §8 for the one place where load
                   could plausibly have moved a result, and in which direction.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Two thousand distinct keys are written into a cache, each by exactly one thread. The cache
afterwards holds **1,939**.

**Nothing raised.** `threadsRaised=0`. Sixty-one entries are simply absent, and the only way to
know is to have counted what should have been there.

⭐ **And the one arm that could have announced the defect stayed silent too.** A separate arm
iterated the plain map through 200,000 concurrent writes specifically to provoke a
`ConcurrentModificationException` — the only place in `E2` where this failure has a *name*. It
never fired, in either run. **So both of this report's failure modes are silent**: entries that
are quietly not there, and an expensive load that quietly ran eight times. The absence of the
exception is the worst news in this report, not a relief, and §3.3 is where it is measured.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.SingletonStateTest --rerun-tasks
```

At `5d1554d`. `SharedScoreCache` is an ordinary object with an ordinary field:

```kotlin
private val plain = HashMap<Long, BigDecimal>()
```

⭐ **The first test in the file passes, and it is the most important one in this report.**

```
E2 >>> single-threaded plain     entries=2000 expected=2000
```

That is the test anybody would actually write against a cache, and it is green against the
defective field. **There is no unit test that catches this single-threaded, because
single-threaded there is nothing wrong.** Spring beans are singletons by default, so a field on
one is not per-request state and not per-user state — it is process-wide state reached by every
thread in the pool at once, and **the declaration is identical to a field on an object only one
thread touches.** There is no annotation to forget and no configuration to get wrong.

## 3. 계측 / Measurement

Run 1 — red, at `5d1554d`:

```
E2 >>> single-threaded plain     entries=2000 expected=2000
E2 >>> plain HashMap             entries=1939 expected=2000 threadsRaised=0
E2 >>> ConcurrentHashMap         entries=2000 expected=2000
E2 >>> check-then-act on CHM     loads=8  (threads=8, keys=1)
E2 >>> computeIfAbsent on CHM    loads=1  (threads=8, keys=1)
E2 >>> iterate-while-writing     raised=0
```

Run 2 — green, this commit:

```
E2 >>> single-threaded plain     entries=2000 expected=2000
E2 >>> plain HashMap             entries=1968 expected=2000 threadsRaised=0
E2 >>> ConcurrentHashMap         entries=2000 expected=2000
E2 >>> check-then-act on CHM     loads=2  (threads=8, keys=1)
E2 >>> computeIfAbsent on CHM    loads=1  (threads=8, keys=1)
E2 >>> iterate-while-writing     raised=0
```

`5 tests, 0 failures` (`SingletonStateTest`) in run 2.

### 3.1 The entries — and the silence

| arm | entries kept, of 2,000 | **lost** | raised |
| --- | --- | --- | --- |
| plain `HashMap`, single thread | 2,000, every run | 0 | 0 |
| **plain `HashMap`, 8 threads** | **1,939 / 1,968 / 1,936 / 1,817** | **61 / 32 / 64 / 183** | **0**, every run |
| `ConcurrentHashMap`, 8 threads | 2,000, every run | 0 | 0 |

⭐ **Four runs, and the same sentence covers this arm as covers §3.2: the direction reproduces
and the magnitude does not.** Every run lost entries; every run raised nothing. The size of the
loss spans **32 to 183, nearly 6×**, and **no single figure for it is published** — not a mean,
not a rate, not a percentage. A loss rate quoted from this table would be a coincidence of
scheduling wearing the clothes of a property.

Every key had exactly one writer, so there is no last-writer-wins ambiguity to explain the
gap: in run 1, 61 insertions were performed and are not there. Concurrent `put`s collide during resize
and bucket linkage, and one thread's table write is overwritten by another's.

**`raised=0` is the finding and not an aside.** A `HashMap` under concurrent write is permitted
to lose data, corrupt its own structure, or report a wrong `size()`, and on this run it chose
the option that reports nothing — in both runs.

### 3.2 What the concurrent collection fixes, and what it does not

`ConcurrentHashMap` returns all 2,000. That is the whole of what it fixes.

| arm, 8 threads on **one** key | run 1 | run 2 | ⛔ run 3 | run 4 (300 ms loader) |
| --- | --- | --- | --- | --- |
| **`get`, then `put` — on the `ConcurrentHashMap`** | **8** | **2** | *refused* | **8** |
| `computeIfAbsent` — on the same map | **1** | **1** | *refused* | **1** |

⛔ **Run 3 is not a data point and must not be averaged with the other three.** It was taken
inside the full 146-test suite and **no thread overlapped the window**, so both arms returned
`1`. That is **the absence of an observation, not an observation of no difference** — the
instrument refused, the precondition assertion said so, and the run is listed only because
hiding a refusal would be worse than showing one.

⛔ **The first run said 8. The second said 2. This report was drafted claiming 8 was "once per
thread — the worst case, not a partial one", and that claim was not supportable from one run.
It is retracted here rather than quietly edited out.**

⭐ **The claim this report makes is one sentence, and it is not a number:**

> **The direction reproduces and the magnitude does not.**

`> 1` against `1` survives every run that measured anything. The *size* of the difference
survives none of them — 8, then 2, then 8, across three valid runs on one machine in one
afternoon. **No average of those is offered**, because an average across a 4× spread is a number
that looks like a property and is a coincidence of scheduling. `R18` refused this shape once and
`R28` refused it again; this is the third.

**Run 3 is why the distinction has teeth.** In it both arms returned `1` and were
**indistinguishable** — the report's entire finding would have evaporated into two identical
numbers with nothing anywhere saying why.

⭐ **The precondition assertion caught it and the suite went red.** `check-then-act must load
more than once, or the threads did not overlap and this arm compared nothing` — the same shape
`ADR-015` requires and the same direction `OPEN-12` identified as the safe one: an unraced run
raises a false alarm rather than issuing a false clean bill.

**But a gate that fires on scheduling is a gate somebody disables**, so the flakiness is fixed at
the source rather than by loosening the assertion. The loader now takes **300 ms**. That is not
padding: **a cache exists because loading is expensive**, so a loader that actually takes time is
*more* faithful to the defect, and it makes the overlap hold **by construction** — the same
repair `DeadlockTest` makes with a barrier between its two locks. Run 4 is that fix: `8` against
`1`, and the window is no longer luck.

That is `R18`'s lesson landing in a report that had already written `R18`'s lesson into its own
§8: the hedge *"8 is the worst case and was reached, not what always happens"* was drafted
before run 2 existed, and run 2 turned it into the finding. **A second invocation cost two
minutes and changed the headline.**

Both calls are individually atomic. `get` is atomic, `put` is atomic. **The pair is not**, and
the gap between them is where every other thread with that key lives. So the cache is *correct*
— it holds the right value afterwards — and it did precisely the work it exists to avoid, with
nothing anywhere reporting it.

`computeIfAbsent` holds the bin's lock across the mapping function, so the function runs at most
once per key however many threads arrive together — **1 in both runs, and 1 by contract rather
than by luck.** That asymmetry is the whole result: one arm's number moves between runs and the
other one cannot. **The difference is not the map. It is whether the compound operation was
ever handed to the map as one.**

⚠ **Kotlin's `getOrPut` on a mutable map is the eight, not the one.** It reads as a single call
and compiles to `get`-then-`put`.

### 3.3 The arm that did not reproduce

```
E2 >>> iterate-while-writing     raised=0
```

**No `ConcurrentModificationException`, in either run.** One thread iterated the plain map 200,000 writes
long, and nothing was thrown.

That arm existed because it is the one place in `E2` where the defect would have **announced
itself** — a named exception instead of a quiet miscount. It did not fire. `HashMap`'s
modification check is best-effort and is not required to detect anything; here it detected
nothing, and no key was ever removed, which is the case its `modCount` check is least likely to
catch.

**So both of this report's failure modes are silent.** The one that had a name did not happen.

### 3.4 ⭐ And does any bean this application actually ships do this?

**No. Measured, by reflection over the running context, not by grep.**

```
E2 >>> shipped-bean sweep   beans=19 fields=39 findings=0
```

`ShippedBeanStateTest` enumerates every bean definition in the started `ApplicationContext`,
resolves each through `AopProxyUtils.ultimateTargetClass` so a proxy cannot hide the class that
declares the state, walks the superclass chain, and flags a field if it is **either** non-`final`
**or** a `final` reference to a container type — arrays, `Collection`, `Map`, and the atomics.

| | |
| --- | --- |
| beans examined (`net.gseek.proxima`, excluding this slice's own instruments) | **19** |
| declared instance fields examined | **39** |
| **findings** | **0** |

**Why reflection and not a text search**, since the text search is the obvious tool: `var` in
source is not the population. A grep cannot see inherited fields, `lateinit`, delegated
properties, anything Kotlin lowers into a backing field, or — the case that matters most for
this report — **a `val` holding a `MutableList`**, which is a final reference to a mutable
object and is exactly as shared as a `var`. Nor can it see through an AOP proxy.

**The sweep asserts that it looked**, before it asserts what it found: at least 5 beans and at
least 5 fields, or the test fails. A sweep that examined zero beans would report zero findings
and mean nothing, which is `ADR-015`'s vacuous pass with a different subject.

⭐ **A clean negative is a result here, and `R5` is the precedent** — *the defect the framework
already fixed*. It changes what this whole report is entitled to say. Not *"proxima has a
concurrency defect"*, and not merely *"this can happen in Spring"*, but the narrower and more
useful:

> **The class of defect is reachable in this stack, no shipped bean in this repository is in
> it, and the reason is not that anyone guarded against it.**

That last clause is §8's problem, not §3's: nothing enforces this, and the sweep is now the
thing that would notice.

## 4. 원인 / Mechanism

Two different mechanisms produce §3.1 and §3.2, and conflating them is what makes *"use a
concurrent collection"* feel like a complete answer.

**§3.1 is a data-structure race.** `HashMap` has no synchronisation at all. Two threads writing
different keys still touch the same table array, the same bucket list, and the same resize
path. The 61 missing entries are writes that landed in a table another thread replaced.

**§3.2 is not about the data structure.** `ConcurrentHashMap` keeps every entry and is
faultless throughout. The defect is one level up, in the **caller's** sequence of two calls,
and no map can fix a compound operation it was never told about. A thread-safe collection makes
each operation atomic; it cannot make *your* pair of operations atomic, because it never saw
them as a pair.

That distinction is why §5's answer is not a single line.

## 5. 처방 / Remedy

| Option | Keeps entries | Loads once per key | Cost | Chosen |
| --- | --- | --- | --- | --- |
| plain `HashMap` | **no** — 1,939 of 2,000 | no | none, and wrong | |
| `ConcurrentHashMap` + `get`/`put` | **yes** | **no — 8 of 8 threads loaded** | one class name | |
| **`ConcurrentHashMap` + `computeIfAbsent`** | **yes** | **yes — 1** | one method name; the mapping function runs under the bin lock | **✔** |
| `synchronized` around the pair | yes | yes | serialises every reader on the whole map | |
| `Collections.synchronizedMap` | yes | **no** — same compound-operation gap | a lock per operation, and the gap remains | |

**`computeIfAbsent`**, and the reason it wins is `Collections.synchronizedMap` sitting two rows
below it: that option is *fully synchronised* and still loads eight times, because the gap is
between the caller's two calls and not inside either of them. **Thread-safety of the container
is not the axis this defect lives on.**

⛔ **What this report does not do is propose a cache.** `ADR-005` decided there is **no cache
layer** here, on measurements rather than on the argument it was opened with, and nothing in
this report disturbs those measurements. `SharedScoreCache` has no production caller and ships
on no request path. **The point is that this defect does not need a cache layer to arrive — it
needs one field**, and a field is not a layer and passes no review as one.

## 6. 재계측 / Re-measurement

| Metric | Before (`HashMap`, `get`/`put`) | After (`ConcurrentHashMap`, `computeIfAbsent`) |
| --- | --- | --- |
| entries kept, of 2,000 | **1,939** / **1,968** | **2,000** / **2,000** |
| entries lost with nothing raised | **61** / **32** | **0** / **0** |
| loader invocations, 8 threads on 1 key | **8** / **2** | **1** / **1** |

Two runs each, run 1 then run 2. ⭐ **The "after" column is identical across both runs and the
"before" column is not**, which is the sharpest available statement of what the remedy bought:
not a smaller number — a *stable* one.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/SingletonStateTest.kt`, run by
`.github/workflows/build.yml`.

Four arms, and three of them are characterisation assertions on a defect — the shape
`LostUpdateTest`'s first arm established here:

- the plain map **must** lose entries (`plainSize < 2000`) **and must raise nothing**. If the
  first half passes and the second fails, something started reporting and that is news.
- `check-then-act` **must** load more than once. If it stops, the threads stopped overlapping
  and the arm is comparing nothing — a precondition, in the `ADR-015` sense.
- `computeIfAbsent` is asserted **exactly** at 1, because that is a contract the map makes.
- the iteration arm is pinned at `raised == 0` with a message saying it is characterising a
  **silence**, not certifying that iterating a racing `HashMap` is safe.

## 8. 남는 위험 / Remaining risk

- **`1,939` and `1,968` are two runs that disagree by 29 entries.** The count is a race
  outcome; only the *direction* is stable. Nothing here establishes a loss rate, and quoting
  `61` or `3.05 %` as a property of `HashMap` would be quoting one sample of a distribution
  nobody characterised. **How the loss scales with threads or key count is `미측정`.**
- ⭐ **The magnitude is unstable in both arms and this report characterises neither.** `loads`
  went 8 → 2 → *refused* → 8; entry loss went 61 → 32 → 64 → 183. **A distribution over many
  invocations is `미측정`**, and every raw value is published rather than any average of them.
  **Three of the four runs are valid and they do not agree**, which is enough to know a mean
  would mislead and not enough to say what the truth is.
- ⛔ **This report's own gate was flaky and the flakiness is fixed rather than tolerated.** Run 3
  fired the precondition assertion inside the full suite: no thread overlapped, `loads=1`, and
  both arms became indistinguishable. **The gate caught it — that is `ADR-015` working — but a
  gate that fires on scheduling is a gate somebody disables**, so the loader was made to take
  300 ms. **The 300 ms is a chosen parameter, not a measurement**, and whether the defect still
  appears with a faster loader is `미측정` — it plainly appeared at 8 and 2 with no sleep at all.
- ⭐ **The overlap now holds BY CONSTRUCTION, and that is a narrowing as well as a
  strengthening.** With a 300 ms loader every thread is inside the window because the window is
  wide, not because the scheduler cooperated. **What that buys**: the arm no longer depends on
  timing luck, so a red result means the code changed rather than that the machine was busy.
  **What it costs, and the reader is owed this half**: this arm can no longer say anything about
  **how often the race occurs unaided.** Runs 1 and 2 could — 8 and 2, with no sleep, are
  evidence that the window is hit in ordinary conditions; run 3 is evidence that it sometimes is
  not. **Run 4 is evidence of neither.** It measures the consequence of an overlap it manufactures.
  A separate arm that does not manufacture it would be needed to answer the frequency question,
  and there is not one. `미측정`.
- ⚠ **The slow loader may have changed the entry-loss arm too.** Run 4 lost **183** of 2,000
  against 32–64 in the earlier runs, and it is the only run with a `Thread.sleep` anywhere in
  the class. Nothing here establishes a connection — the arms use different maps and the sleep
  is in a different test — but **the coincidence is recorded rather than dismissed**, and it is
  `미측정`.
- ⭐ **The figure load could move, and which way — now observed rather than predicted.**
  `loads` is bounded above by the thread count, so a busy machine cannot push it past 8; it
  falls when threads are scheduled far enough apart that some find the value already present.
  Run 1 gave 8 and run 2 gave 2 **under nominally the same conditions**, which is why the gate
  asserts `> 1` and never a value. What differed between the two runs is `미측정` — slice D's
  load was present for both, and nothing here isolates a cause.
- **`raised=0` on the iteration arm is a negative result on one JVM, one run.** `HashMap`'s
  `modCount` check is best-effort; a different interleaving would throw. **This does not
  establish that iterating a racing `HashMap` is safe**, and §3.3's assertion is worded to
  characterise the silence rather than certify it.
- **No `size()` corruption or infinite loop was observed**, and neither was looked for. The
  pre-Java-8 resize cycle is gone from this implementation; whether any structural corruption
  short of entry loss occurred here is `미측정` — nothing inspected the table.
- **Eight threads and 2,000 keys are chosen numbers.** No sweep was run. `R6` §8 carries the
  identical item about its own chosen retry count.
- **The sweep is a snapshot and nothing keeps it true.** §3.4 found `0` of 39 fields across 19
  beans, and `ShippedBeanStateTest` will keep finding it — **but a bean that is never
  instantiated in the test context is a bean the sweep never sees.** It enumerates
  `beanDefinitionNames` from a context started by `@SpringBootTest`; anything conditional on a
  profile, a property, or a runtime environment this test does not create is outside the
  population. **How much of the shipped bean graph the test context actually instantiates is
  `미측정`.**
- **The container filter is deliberately over-broad and was never exercised against a positive.**
  It flags any `Collection`, `Map`, array or atomic. Nothing in this run tripped it, so **the
  instrument has never been watched refusing anything** — `R0` §4 keeps a count of exactly that
  shape. A planted violation would fix it and was not written.
- **`0 findings` says nothing about mutable state held anywhere other than a field.** A bean
  reaching a `ThreadLocal`, a companion object, a top-level `object`, a static, or an external
  cache is invisible to this sweep by construction. `미측정`.
- **`ADR-005` is untouched and this report must not be read as pressure on it.** If anything it
  strengthens it: the no-cache decision removes the most likely place this shape would have
  appeared.
- **What would break the conclusion:** a `HashMap` implementation that either kept every entry
  under concurrent write or reliably threw. §7 goes red in both directions.

## 9. 배운 것 / What I learned

**제일 인상 깊었던 건 `loads=8`이었고, 그 다음이 `loads=2`였다.** 첫 실행에서 8개 스레드가 키
하나에 로더를 8번 돌렸다. 최악값이 그대로 나온 거라 신나서 "스레드당 한 번"이라고 헤드라인을
썼다. 두 번째 실행에서 2가 나왔다. **한 번 재고 쓴 문장이 두 번째 재자마자 틀린 문장이 됐다.**
R18이 정확히 이 얘기인데, 나는 그걸 §8에 미리 적어놓고도 본문에서는 8을 성질처럼 썼다. 남는 건
`> 1` 대 `1`이고, 그게 처음부터 유일한 논점이었다.

그리고 이게 왜 안 잡히는지가 §5의 `Collections.synchronizedMap` 줄에 다 들어 있다. 그건 **전부
동기화된 맵인데도 여전히 8번 로드한다.** 틈이 `get` 안에 있는 것도 아니고 `put` 안에 있는 것도
아니라서, 컨테이너를 아무리 안전하게 만들어도 안 없어진다. **틈은 내 코드 두 줄 사이에 있다.**
"동시성 컬렉션 쓰세요"가 절반짜리 조언인 이유가 이거였는데, 나머지 절반을 아무도 말 안 해준다 —
R6 §9에서 `@Version`에 대해 배운 거랑 정확히 같은 모양이다.

세 번째. **이름 있는 실패는 안 일어났다.** `ConcurrentModificationException`이 뜨는 팔을 일부러
넣었다. E2에서 결함이 스스로 신고하는 유일한 자리라서. 안 떴다. 그래서 이 보고서의 실패 두 개가
**둘 다 조용하다** — 61개가 그냥 없고, 로더가 그냥 8번 돌았다. 예외가 안 뜬 게 다행이 아니라
이 보고서에서 제일 나쁜 소식이라는 걸 쓰면서 알았다.

마지막으로, 파일 맨 위의 단일 스레드 테스트가 초록으로 지나가는 걸 보는 게 묘했다. 저게 사람들이
실제로 쓰는 테스트다. 저 테스트는 아무 잘못이 없고, 통과하는 것도 맞다. **단일 스레드에서는 정말로
아무 문제가 없기 때문이다.**
