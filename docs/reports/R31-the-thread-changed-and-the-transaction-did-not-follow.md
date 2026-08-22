# R31. The thread changed and the transaction did not follow

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `3ab75d6` — the boundary is planted and nothing crosses it
> **Green commit**: *(the commit that sets `proxima.ops.async-context: copy-mdc`)* — one of the
> four things crosses afterwards, and §5 is about the other three.
> **Reads with**: `R1-transaction-annotation-that-does-nothing.md`. ⭐ **These two reports are
> the same sentence reached from opposite directions and neither is complete without the other.**

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04,
                   kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  JVM            : Temurin 21.0.12+8
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  PostgreSQL     : server 16.15 — postgres@sha256:cf78e766…, via Testcontainers
  THE FIVE POOLS : 1 workers max=200 minSpare=10 · 2 connections max=10 minIdle=10
                   3 applicationTaskExecutor core=8 max=2147483647 queueCapacity=2147483647
                   4 ForkJoinPool.commonPool parallelism=7 · 5 virtual carriers unset
  Load           : NONE. Every figure below is a row count, a boolean or a thread name
  What else was running on the machine : other work was, and it does not matter — nothing
                   here is a duration, so nothing here contends
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R1` found `@Transactional` doing nothing. The cause was that **the proxy was never applied** —
the call went through `this`, never left the object, and Spring's interceptor was not in the path.
A unit test asserting on the return value saw a correct answer.

This is the same symptom with the opposite cause. The proxy **is** applied. The interceptor **does**
run. And the work still leaves the transaction behind, because **the thread changed.**

```
R1    the annotation is on the method, and the interceptor is not in the path
R31   the annotation is on the method, the interceptor runs, and the CONTEXT is not on the thread
```

Both are invisible to a test that asserts on a return value. Both are the kind of thing that
looks correct in review, because the annotation is right there.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests '*AsyncBoundary*' --rerun-tasks
```

`AsyncBoundary.kt` plants the smallest boundary carrying every limb of the question: a
transaction, a request scope, an MDC entry and a bare `ThreadLocal`. Nothing in it is reachable
from a request — this application serves two endpoints and neither is `@Async`; `ADR-009` is why
a third was not added to make it measurable.

### 2.1 Which executor is actually used, and how that was established

⚠️ **Not asserted from the wiring — read from the thread.** The observation records
`Thread.currentThread().name` on the executing thread and prints it, because *"which executor does
an unqualified `@Async` use"* is exactly the kind of claim §0 rule 9 forbids answering from
memory. Boot registers its `ThreadPoolTaskExecutor` under both `applicationTaskExecutor` and the
alias Spring's async infrastructure resolves, so the name is what settles it.

```
R31 §3.2 caller = BoundaryObservation(threadName=Test worker, ...)
R31 §3.2 async  = BoundaryObservation(threadName=task-3,     ...)
R31 §5   async  = BoundaryObservation(threadName=task-1,     ...)
```

**`task-N` is Boot's `ThreadPoolTaskExecutor`** — pool 3, the one `R30` is about, with
`core=8 max=2147483647 queueCapacity=2147483647`. An unqualified `@Async` lands there because Boot
registers that bean under the alias Spring's async infrastructure resolves. **Read off the thread,
not off the wiring**, and it ties this report to `R30`'s: the executor with the unreachable maximum
is the one an `@Async` call would use.

## 3. 계측 / Measurement

### 3.1 There is no Spring Security here, so the security-context limb is a different limb

The trap as usually written lists *"transaction, security context, request-scoped beans, MDC"*.
**This application has no Spring Security** — `api/build.gradle.kts` has no security starter, and
`SecurityContextHolder` is not on the classpath. Authentication is `TokenAuthenticationFilter`,
which puts the verified subject into a **request attribute**, and `RecommendationController` reads
it with `@RequestAttribute`.

So the analogue here is the request attribute, reached through `RequestContextHolder` — a
`ThreadLocal` with no propagation of any kind. **Checked rather than assumed**, and named so that
nobody reads a missing row as a missing measurement.

⚠️ **And it is the limb this report measures worst.** §3.2 shows why: the test runs on a JUnit
thread, so there were never any request attributes on the caller's side either, and the row has no
control. The substitution is honest — a request attribute really is the thing a `SecurityContext`
would be here — but **the measurement of it is not**, and §3.2 marks it rather than counting it.

### 3.2 Four contexts, one hop

One `@Transactional` caller, one `@Async` collaborator, four contexts read on each side.

| what | on the caller's thread | on the async thread | crossed? |
| --- | --- | --- | --- |
| thread name | `Test worker` | **`task-3`** | — |
| `isActualTransactionActive()` | **`true`** | **`false`** | ⛔ **no** |
| transaction name | `…TransactionalAsyncCaller.observeInsideTransaction` | **`null`** | ⛔ no |
| `RequestContextHolder` attributes | `false` | `false` | ⚠️ **see below** |
| MDC entry | `set-on-the-caller` | **`null`** | ⛔ **no** |
| bare `ThreadLocal` | `set-on-the-caller` | **`null`** | ⛔ **no** |

**Three of the four are established by a control that passed on the caller's side and failed on the
async side.** The transaction is genuinely active on the caller, the MDC genuinely holds a value,
the `ThreadLocal` genuinely holds one — and none of the three is present one method call later.

⚠️ **The request-attributes row is the weak one and it is marked rather than counted.** It reads
`false` on **both** sides, because this test runs on a JUnit thread and not inside a request —
there were never any request attributes to lose. **So that row demonstrates nothing about
crossing a boundary.** It is `R5` §3.3's shape a third time: an assertion that passes because
the thing it is watching for was never there.

It is left in the table, marked, rather than deleted, because the *mechanism* is not in doubt —
`RequestContextHolder` is a `ThreadLocal` like the other three — but **the mechanism is not the
measurement**, and this report does not have the measurement. Ledger entry 31.6.

### 3.3 The rollback that reached one row of two

`TransactionalAsyncCaller.writeBothThenRollBack` is one `@Transactional` method. It saves a row,
hands a second row to an `@Async` collaborator, waits for it, and then throws.

```
R31 §3.3 after rollback: sync rows=0  async rows=1
```

**The caller's own write is gone. The async one is still there.** No exception was logged about it,
nothing warned, and the method that wrote the surviving row carries no propagation setting at all.

A reader who believes `@Async` work joins the caller's transaction expects to find neither row.
A reader who knows it does not still has to notice that **the surviving write is durable before
the caller has decided whether to commit** — the async insert committed while the outer transaction
was still open, through `SimpleJpaRepository`'s own transaction on the async thread. §4 is why that
is the same fact `R1` §4 records.

## 4. 원인 / Mechanism

Every one of the four is a `ThreadLocal`, and that is the whole of it:

| what | where it lives |
| --- | --- |
| the transaction | `TransactionSynchronizationManager`'s `ThreadLocal`, holding the bound `EntityManager` and its JDBC connection |
| the request attributes | `RequestContextHolder`'s `ThreadLocal` |
| the MDC | the logging backend's `ThreadLocal` |
| a `ThreadLocal` | itself |

`@Async` submits to an executor. The executor runs the task on **one of its own threads**, which
has none of those. Nothing is lost or dropped — the values were never on that thread.

⭐ **And `R1`'s failure is the same fact one level up.** Spring's transaction management is
implemented as a proxy that binds a `ThreadLocal` before the call and unbinds it after. `R1` broke
the *proxy*, so the binding never happened. `R31` keeps the proxy and changes the *thread*, so the
binding happens on a thread the work does not run on. The mechanism both reports are about is that
**`@Transactional` is thread-affinity wearing an annotation's clothes**, and neither report can
show that on its own.

**The two even fail into the same fallback, which is what makes the pairing exact rather than
poetic.** `R1` §4 records what happened to its writes once the annotation was inert:

> *"Each `save` then ran inside the transaction Spring Data opens for its own repository methods,
> and each committed independently. The first write was durable before the second was attempted."*

§3.3's async row survives a rollback by **precisely that route** — `AsyncWriter.insertLearner`
carries no `@Transactional` at all, so the row is written inside the transaction
`SimpleJpaRepository.save` opens on the async thread, and it commits before the method returns.
Two reports, two different reasons for the caller's transaction to be missing, and **the same
silent per-statement commit underneath both.** A framework that had no such fallback would have
thrown in both cases and neither defect would exist.

### 4.1 Why `REQUIRES_NEW` is the wrong vocabulary for this

`MasteryCounter.kt` uses `Propagation.REQUIRES_NEW`, and a reader who has seen it may reach for
the same word here. It does not apply. Propagation is a decision the transaction interceptor makes
**when it finds, or fails to find, an existing transaction on the current thread**. On the async
thread there is nothing to find, so there is no propagation decision at all — `REQUIRES_NEW`,
`REQUIRED` and `NESTED` would behave identically.

The distinction matters because `REQUIRES_NEW` is at least *visible*: it is a word in the source
saying "this is a separate transaction". `@Async` produces a separate transaction with **no word
anywhere**, and that is the more dangerous of the two.

## 5. 처방 / Remedy

| Option | What crosses afterwards | What still does not | Chosen |
| --- | --- | --- | --- |
| A — leave it | nothing | everything | no |
| B — `TaskDecorator` copying the MDC | the MDC | the transaction, the request scope | **yes** |
| C — B, plus copying the request attributes | the MDC and a **stale** request scope | the transaction | **no — see below** |
| D — pass what is needed as arguments | whatever was passed | nothing else, and that is the point | **yes, as the rule** |
| E — do not cross the boundary | — | — | the actual answer for this application |

**Option C is refused and the refusal is the interesting one.** Copying `RequestAttributes` onto
the async thread makes `RequestContextHolder.getRequestAttributes()` return non-null, which is
exactly what a reader wants to see. But the request those attributes belong to **may already have
completed**, and its objects may have been recycled by the container. The remedy would replace a
loud `null` with a quiet wrong answer, and this repository has a name for that shape: it is `R7`'s
check-then-act and `R24`'s readiness probe — an instrument that answers confidently about a state
that no longer exists.

**Option D is the rule and option B is the concession.** Anything the async work needs should be
an argument, because an argument is copied by the language and cannot be stale in a way nobody
declared. The decorator exists for the one thing that cannot be an argument: the correlation id
that every log line on the async thread would otherwise be missing.

**Option E is what this application actually does**, and it is worth saying plainly: there is no
`@Async` on any request path here, so none of the above is load-bearing today. The green commit
ships B because it is correct and free, not because anything needs it.

### 5.1 What a decorator cannot do, and why it is not a gap in the decorator

**A transaction cannot be propagated by anything of this shape.** It is a JDBC connection bound to
one thread. Re-binding that connection to a second thread that runs *concurrently* is not
propagation — it is two threads issuing statements down one connection, which the driver does not
support and which would corrupt the protocol state rather than share a transaction.

The only thing that could join the caller's transaction is work that runs **on the caller's
thread**, which is to say: not asynchronous work. **`@Async` and "participates in the caller's
transaction" are mutually exclusive by construction**, and no library can bridge them.

## 6. 재계측 / Re-measurement

The same four readings, taken on the async thread, before and after the green commit. **Both
columns come from the same test class shape against a real PostgreSQL through Testcontainers**;
the only difference is the value of `proxima.ops.async-context`.

| read on the async thread | before — `async-context: none` | after — `copy-mdc` |
| --- | --- | --- |
| thread | `task-3` | `task-1` |
| **MDC entry** | **`null`** | **`green-arm`** ✅ |
| `isActualTransactionActive()` | `false` | **`false`** — unchanged |
| transaction name | `null` | **`null`** — unchanged |
| request attributes present | `false` | **`false`** — unchanged |

Both rows come from the same shape of test against a real PostgreSQL through Testcontainers; the
only difference is the property. **The green row proves the wiring as well as the mechanism** — Boot
really does apply a unique `TaskDecorator` bean to the executor it builds, which is a framework
behaviour this repository must not assume.

⭐ **Three of the four rows do not move, and that is the result rather than a shortfall.** A
remedy that fixed all four would be a remedy that had copied a request scope into a thread whose
request may already be over (§5, option C) and re-bound a JDBC connection to a second thread
(§5.1). **The green commit fixes exactly the one thing that is safe to fix**, and §8's first bullet
keeps the rest where a reader will find it.

## 7. 회귀 게이트 / Regression gate

`AsyncBoundaryTest` and `AsyncBoundaryWithContextCopyTest`.

Both arms are asserted, and the second one is why the pair is a gate rather than a demonstration:
it asserts that the `TaskDecorator` **bean is actually consumed by the executor Boot builds**,
through the real `@Async` path rather than by calling the decorator by hand. If a future Boot
stops applying a unique `TaskDecorator` bean, the green arm goes red rather than silently
reverting to the red behaviour — which is the failure mode `R16`'s `rate>=0.0` threshold and
`R9` §7's substitution gate are both about.

`TransactionBoundaryRules` is untouched and still passes: the planted `@Transactional` methods are
public, in their own beans, and not self-invoked.

## 8. 남는 위험 / Remaining risk

- **The transaction still does not cross, in the green commit as much as in the red.** §5.1 argues
  it cannot. That argument is a construction proof, not a measurement, and no arm here tried to
  defeat it. **미측정.**
- **The rollback in §3.3 was measured with one async call and one row.** What a partial failure
  looks like with several async writes, some committed and some not, is **미측정** — and that is
  the shape a real feature would have.
- **Nothing here ran under load.** The boundary was crossed by a test, not by two hundred
  concurrent requests, so the interaction between `@Async` and pool 3's unbounded queue — `R30` —
  is **미측정**. `R30` §8 records the same hole from its side.
- **`CallerRunsPolicy` would delete the boundary silently.** If pool 3 were ever bounded (`R30`
  §5, option B) and given that handler, a rejected async task would execute **on the submitting
  thread** — which on a request path is a web server worker, and the async boundary would stop
  being one without any annotation changing. It would also mean the transaction *does* cross,
  intermittently, which is worse than never. **미측정**, and it is the most surprising thing this
  slice found without measuring.
- **The MDC's propagation was measured for a `ThreadPoolTaskExecutor`.** Under
  `spring.threads.virtual.enabled=true` the executor is replaced (`R33` §3.1) and whether Boot
  applies the same `TaskDecorator` bean to the replacement is **미측정**.
- **No log line was inspected.** The MDC is asserted through `MDC.get`, not by reading what the
  appender wrote. A correlation id present in the MDC and absent from the pattern is a
  configuration this repository has never checked.

## 9. 배운 것 / What I learned

`R1`을 다시 읽고 나서야 이 보고서가 무슨 이야기인지 알았다.

처음엔 `@Async`가 트랜잭션을 못 넘긴다는 걸 재는 일이라고 생각했다. 알려진 사실이고, 재봤자
"역시 그렇다"로 끝날 것 같았다. 그런데 `R1`의 결함 — self-invocation으로 프록시를 건너뛴 것 —
과 나란히 놓으니까 **둘이 같은 문장**이었다. `@Transactional`은 메서드에 붙는 게 아니라
**스레드에 붙는다.** `R1`은 프록시를 부숴서 바인딩이 아예 안 일어났고, 여기는 바인딩은
일어났는데 그 스레드에서 일을 안 한다. 어노테이션은 두 경우 다 멀쩡히 붙어 있고, 리뷰에서 둘 다
정상으로 보인다.

두 번째로 배운 건 **처방을 거절하는 법**이다. 옵션 C — request attribute까지 복사하기 — 는
처음에 당연히 넣으려던 거였다. `getRequestAttributes()`가 null이 아니게 되니까 고쳐진 것처럼
보인다. 그런데 그 request는 이미 끝났을 수 있다. **시끄러운 null을 조용한 오답으로 바꾸는
수정**이고, 이 저장소는 그 모양을 이미 두 번 만났다 — `R7`의 check-then-act, `R24`의 readiness
probe. 고쳐진 것처럼 보이는 게 제일 비싸다.

그리고 제일 마음에 걸리는 건 §8에 쓴 `CallerRunsPolicy` 항목이다. **재보지도 않았는데 이 슬라이스
전체에서 제일 놀라운 것**이었다. `R30`이 큐를 묶으라고 하고, 묶으면 거절 정책이 필요하고, 가장
흔한 선택인 CallerRunsPolicy를 고르면 거절된 작업이 요청 스레드에서 실행된다 — 그러면 비동기
경계가 어노테이션 하나 안 바뀌고 사라진다. 그리고 트랜잭션은 **가끔** 넘어간다. 한 번도 안
넘어가는 것보다 나쁘다. `R30`과 `R31`을 따로 읽으면 이게 안 보인다. 두 보고서가 각자 옳고,
합치면 새 결함이 나온다.
