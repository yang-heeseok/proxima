# R32. A slice titled three pools had five, and the fourth belongs to the whole JVM

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `3ab75d6` — `parallelStream()` planted, and a second caller starved by it
> **Green commit**: **none for the application, and §5 says why.** The remedy is measured in
> §3.4 and not adopted, because adopting it would mean keeping a `parallelStream()` this
> repository has no use for. `ADR-018` records the decision.
> **Reads with**: `R23`, which found this JVM taking a number from a cgroup that no document
> here had written down. This is the same shape for a different number.

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04,
                   kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  JVM            : Temurin 21.0.12+8
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  THE FIVE POOLS : 1 workers max=200 minSpare=10 · 2 connections max=10 minIdle=10
                   3 applicationTaskExecutor core=8 max=2147483647 queueCapacity=2147483647
                   4 ForkJoinPool.commonPool parallelism=7 · 5 virtual carriers unset
  Load           : NONE. Every figure below is a count of threads or of tasks in flight
  What else was running on the machine : other work was, and it does not matter — nothing
                   here is a duration, so nothing here contends
```

> ⭐ **Why there is not one duration in this report.** The obvious way to show a starved caller
> is to time it. That would have needed the machine's measurement lock, which `R29` was holding
> and which two other slices were waiting on. So the same fact is established as a **count** —
> how many distinct threads ran the work, and how many were in flight at the peak — which is a
> property of `ForkJoinPool` rather than of how fast this machine is. It would read the same on
> a quiet machine and it cost the round nothing.

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

The slice this report belongs to was drafted as *"three pools that do not know about each
other"*: web server workers, connections, and whatever `@Async` uses. Counting them found
**five**.

The fourth is `ForkJoinPool.commonPool()`. It exists in every JVM whether or not anybody uses
it. **No file in this repository configures it, and no file anywhere configures it** — its size
comes from `availableProcessors()`. Nothing in a stack trace says a pool was involved, and
nothing in a configuration file mentions one, because `parallelStream()` is a method call on a
collection.

A search on 2026-08-22, before anything was planted, found **zero** uses:

```bash
grep -rn "parallelStream\|parallel()\|ForkJoin\|ExecutorService\|Executors\." \
     api/src/main seed/src/main
# (no matches)
```

⚠️ **That search covers what this repository wrote and not what it runs.** Its classpath is not
searched, and §8 keeps that as the hole it is: a library holding the common pool produces §3.3's
symptom with nothing in this tree to find.

So this trap was **planted**, and what it is worth is the count it produced: a slice that expected
three pools operates five, and the fourth is shared with every library in the process.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests '*SharedPoolTest' --rerun-tasks
```

### 2.1 How the blocking is done, and why it is not a `CountDownLatch`

⚠️ This is the one methodological choice the whole report rests on, so it is stated in §2 rather
than buried.

The parked tasks poll `LockSupport.parkNanos` rather than awaiting a latch. **`CountDownLatch.await()`
reaches `AbstractQueuedSynchronizer`, and the `java.util.concurrent` synchronisers can hand a
`ForkJoinPool` a `ManagedBlocker`** — which is exactly the mechanism that lets the pool compensate
by starting an extra worker. Blocking that way would have measured the compensation path.

Blocking by parking measures what an ordinary JDBC round trip or socket read does, which is the
case an application actually hits. **The two produce different numbers and a report that does not
say which it used is not reproducible.**

## 3. 계측 / Measurement

### 3.1 What determines the pool's size

```
R32 §3.1 availableProcessors=8  commonPool.parallelism=7  property=unset
```

`availableProcessors() − 1`. The one left over is the submitting thread, which §3.2 measures doing
the work it was left over for.

**`java.util.concurrent.ForkJoinPool.common.parallelism` is unset**, so that seven is the JVM's own
default and not a decision anybody in this repository made — nor one anybody here could find, since
no file mentions the pool at all.

### 3.2 The caller is a worker, and blocking does not grow the pool

`parallelism + 1` elements are released from the parked state only once that many have arrived, so
the peak is a fact about how many the pool would admit rather than about how long anything slept.

Sixteen elements, each parking until the peak has been reached:

```
R32 §3.2 SharedPoolObservation(
  elements=16,
  threads=[ForkJoinPool.commonPool-worker-1 … -worker-7, Test worker],
  peakConcurrent=8,
  callerThreadParticipated=true,
  commonPoolParallelism=7,
  commonPoolSizeAfter=7)
```

| | |
| --- | --- |
| distinct threads that ran elements | **8** |
| of which common-pool workers | **7** |
| of which the **calling** thread | **1** |
| peak elements in flight | **8** = `parallelism + 1` |
| common pool size afterwards | **7** — it did not grow |

⭐ **The submitting thread is one of the workers.** `ForkJoinTask.invoke()` executes on the calling
thread before it waits, so a request thread that calls `parallelStream()` is not *waiting on* the
shared pool — it is *inside* it. Anybody budgeting "seven concurrent" is out by one, and anybody
assuming the caller is free meanwhile has the model wrong.

**And blocking did not grow the pool**: `commonPoolSizeAfter=7` with eight elements parked. A
`ForkJoinPool` compensates for a `ManagedBlocker` and for nothing else, so an ordinary JDBC round
trip or socket read simply removes a worker. §2.1 is why the blocking here was done by parking.

### 3.3 ⭐ An unrelated caller is starved, and nothing says so

Two callers with nothing in common. The first fills the common pool with blocking work and holds
it. The second — a different thread, a different call stack, no shared data — then runs an
ordinary `parallelStream()` over trivial work.

**The precondition is asserted before anything else is measured**: the test fails loudly if the
first caller never actually took the pool. A starvation test whose hog did not start would pass by
measuring nothing, and `ADR-015` is this repository's decision that a race test proves its own
precondition.

```
R32 §3.3 starved = SharedPoolObservation(
  elements=28,
  threads=[Test worker],
  peakConcurrent=1,
  callerThreadParticipated=true,
  commonPoolParallelism=7,
  commonPoolSizeAfter=7)
```

| | second caller — **measured** | first caller — **only its precondition is measured** |
| --- | --- | --- |
| elements | 28 | 28 |
| distinct threads | **1** | 미측정 |
| common-pool workers used | **0** | 미측정 |
| peak in flight | **1** | 미측정 |
| what is established | the whole row above | **at least `parallelism` of its elements were running when the second caller started** — the latch it counts down is asserted to reach zero before anything is measured |

⚠️ **The first caller's own observation is not printed and is not claimed.** Its
`SharedPoolObservation` is discarded by the test; what the test asserts about it is only the
precondition — that it had actually taken the pool. **That is the assertion the finding needs**, and
the numbers in its column would be a restatement of §3.2 rather than a second measurement, so they
are written `미측정` rather than copied across.

⭐ **Twenty-eight elements, one thread, no parallelism at all.** Not one of the seven common-pool
workers was available, because all seven are parked inside the first caller's stream and **a parked
ForkJoin worker is not replaced.** The second caller shares no code, no bean and no data with the
first.

**It did not fail. It did not log. It did not slow down in a way anything measures.** A `parallel`
stream ran sequentially, the answer was correct, and the only trace anywhere is that it took
longer — which is exactly `R29`'s incident shape one pool over, and by the same mechanism: the
queue is somewhere with no gauge on it.

### 3.4 The remedy arm, measured while the common pool was still held

Taken **inside the same test method**, before the first caller is released — a remedy measured
after the contention has cleared is a remedy measured against nothing, which is the mistake `R18`
§2 designed its arm ordering to avoid.

```
R32 §5 dedicated = SharedPoolObservation(
  elements=32,
  threads=[ForkJoinPool-1-worker-1, ForkJoinPool-1-worker-3],
  peakConcurrent=1,
  callerThreadParticipated=false,
  commonPoolParallelism=7,
  commonPoolSizeAfter=7)
```

| | |
| --- | --- |
| common-pool workers used | **0** — the remedy's whole claim |
| distinct threads | **2**, both from the dedicated pool |
| the caller participated | **false** |

**The work ran while the common pool was fully occupied**, which is the only condition under which
the claim means anything.

⚠️ **`peakConcurrent=1` is not a limit and must not be read as one.** The remedy arm's work is an
empty lambda, so elements complete before the next one starts and nothing overlaps. **What this arm
establishes is *where* the work ran, not how much of it ran at once** — and only two of the
dedicated pool's four workers were needed to finish thirty-two trivial elements. A concurrency
figure for the dedicated pool is **미측정**.

## 4. 원인 / Mechanism

Three facts compose into the finding, and each is unremarkable alone.

1. **`parallelStream()` has no executor parameter.** The `Stream` API offers no way to say where
   the work runs. It submits to `ForkJoinPool.commonPool()`, and that is not a default that can be
   overridden at the call site — it is the only option the API exposes.
2. **The common pool is one per JVM.** Every caller in the process shares it: this application,
   any library that ever calls a parallel stream, and `CompletableFuture`'s no-executor overloads.
   There is no isolation of any kind between them.
3. **A `ForkJoinPool` compensates only for a `ManagedBlocker`.** A worker that blocks on ordinary
   IO is simply gone; the pool does not know, does not grow, and does not replace it.

Put together: **N blocking calls inside a parallel stream remove N workers from a pool the size of
your CPU count, for every other caller in the process, and there is no signal anywhere.** The
second caller does not fail and does not log. Its `parallel` stream runs sequentially on its own
thread, which is a correctness-preserving, silent, whole-process performance change.

⭐ **This is why it is an operational incident rather than a slow function.** Whoever is paged
sees the *second* feature degrade. The cause is in the first, which is behaving exactly as
written, and nothing connects them but a pool neither of them mentions.

### 4.1 The size is not stable across the boundary `R23` and `R24` measured

`parallelism = availableProcessors() − 1`. On this eight-core host that is seven.

`R23` measured this JVM taking its heap ceiling from a **cgroup** rather than from the host, and
`R24` ran two and three application instances in containers. The same code inside a two-CPU
container has a common pool of **one**, at which point `parallelStream()` is a slower sequential
stream — and nothing in the source, the configuration, or any document would have changed.
**Whether the parallelism actually follows a CPU quota here is 미측정**, and it is in `ADR-018`'s
unmeasured list rather than asserted.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — do not use `parallelStream()` for blocking work | the common pool is never held | none; the loop is sequential | **yes, and it is the rule** |
| B — run the stream inside a dedicated `ForkJoinPool` | measured in §3.4: the common pool is untouched | a pool to own, size and shut down | measured, not adopted |
| C — set `java.util.concurrent.ForkJoinPool.common.parallelism` | a bigger shared pool is still shared | makes the blast radius larger, not smaller | no |
| D — wrap the blocking call in a `ManagedBlocker` | the pool compensates and grows | unbounded growth under load, and every call site must remember | no |

**Option B is the one people reach for and it is a workaround, not a fix.** It works — §3.4
measures it working while the common pool is fully occupied — but note what it costs: the only way
to move a parallel stream is to run its terminal operation from inside another `ForkJoinPool`,
because the API has no executor parameter. That is a real limitation rather than a style
preference, and having gone that far, an `ExecutorService` and an ordinary loop are simpler and
say what they mean.

**So the remedy adopted is A, and there is no green commit**, because there is no
`parallelStream()` in this application to remove. `SharedPoolWork` is a fixture and `ADR-018`
records that it stays out of every request path.

**Option C deserves its own sentence** because it is the tempting one for anybody who reads §3.3
and concludes the pool is too small. Enlarging a shared pool does not make it less shared. It
raises the number of blocking calls needed to starve everybody else, which converts a reproducible
failure into an intermittent one.

## 6. 재계측 / Re-measurement

**None for the application, because nothing in the application changed.** §3.4 is the alternative
measured in isolation, in the same test, while the contention was still present — a remedy
measured after the contention has cleared is a remedy measured against nothing.

## 7. 회귀 게이트 / Regression gate

`SharedPoolTest`, and `PoolCensusGateTest`'s third test for the size.

`SharedPoolTest`'s starvation test asserts **its own precondition** before it asserts anything
else — that the first caller really did take the pool — because a starvation test whose hog never
started would pass by measuring nothing. `ADR-015` is this repository's decision that a race test
proves its own precondition, and this is that rule applied to a pool.

`PoolCensusGateTest` asserts `parallelism == availableProcessors − 1` and that the overriding
system property is unset. If a future JDK changes the formula, or if somebody sets the property,
the gate goes red and §4.1's arithmetic is no longer this JVM's.

## 8. 남는 위험 / Remaining risk

- **No `parallelStream()` runs on a request path here, so the blast radius is 미측정 in the only
  configuration that would matter.** §3.3 starves one test thread with another. Whether the same
  contention against **two hundred concurrent requests** produces the same counts, or whether the
  request threads' own participation changes the arithmetic, was not measured.
- **The parallelism inside a CPU-limited container is 미측정.** §4.1 argues from `R23` that it
  should follow the cgroup, and arguing from a neighbouring measurement is what rule 3 forbids
  turning into a number.
- **`CompletableFuture`'s no-executor overloads use the same pool and were not measured.**
  `supplyAsync(fn)` with no executor is the common pool, and it is a far more common way into this
  defect than `parallelStream()` — it appears in code that has no stream in it at all. **미측정**,
  and it is the entry point this report would look at next.
- **Nothing checked whether any *dependency* uses the common pool.** The search that found zero
  uses covered this repository's source, not its classpath. A library holding the pool would
  produce §3.3's symptom with no `parallelStream()` anywhere in the tree. **미측정.**
- **The counts are from one JVM on an eight-core host.** `peakConcurrent = parallelism + 1` is a
  statement about `ForkJoinTask.invoke` executing on the submitting thread; it was measured once,
  on one shape of work, with one element count.
- **`ManagedBlocker` compensation was avoided, not measured.** §2.1 explains the choice. What the
  pool actually does when a task blocks *through* a synchroniser — how far it grows, and whether it
  ever shrinks back — is **미측정**, and option D in §5 is rejected on reasoning rather than on
  numbers.

## 9. 배운 것 / What I learned

이 슬라이스의 제목은 *"서로를 모르는 세 개의 풀"* 이었다. 세어보니 **다섯 개**였고, 네 번째는
내가 만든 적도, 설정한 적도, 이름을 본 적도 없는 풀이었다.

제일 인상적이었던 건 **호출한 스레드가 워커라는 것**이다. `parallelStream()`이 일을 넘기고
기다린다고 막연히 생각했는데, `ForkJoinTask.invoke()`는 호출 스레드에서 먼저 실행한다. 그래서
동시 실행 수가 parallelism이 아니라 parallelism+1이고, 요청 스레드가 이걸 호출하면 **기다리는 게
아니라 풀 안에 들어가 있다.** 하나 차이지만 모델이 완전히 다르다.

그리고 §2.1 — 이게 이 보고서에서 제일 오래 걸린 판단이다. 처음엔 `CountDownLatch`로 막았다.
그런데 AQS가 `ForkJoinPool`에 `ManagedBlocker`를 넘길 수 있다는 걸 알고 나서, 내가 재려던 건
"평범한 블로킹"인데 재고 있던 건 "보상 경로"일 수도 있다는 걸 깨달았다. **둘은 다른 숫자를
내놓는다.** 어느 쪽으로 막았는지 안 적힌 보고서는 재현이 안 된다. 숫자보다 이 문장 하나가 더
오래 남을 것 같다.

세 번째 — 락 때문에 배운 것. 이 결함을 보여주는 가장 자연스러운 방법은 **시간을 재는 것**이었다.
"굶은 호출자가 N배 느렸다"가 제일 읽기 쉽다. 그런데 그러려면 머신 측정 락이 필요했고, 락은 `R29`가
쥐고 있었고, 다른 두 슬라이스가 기다리고 있었다. 그래서 **스레드 개수와 동시 실행 수**로 같은
사실을 세웠다. 결과적으로 더 나은 증거다 — 머신이 조용하든 시끄럽든 같은 값이 나오고, 재현하는
사람이 내 CPU를 갖고 있을 필요가 없다. **지속시간으로 물어야만 하는 질문은 생각보다 적다.**
