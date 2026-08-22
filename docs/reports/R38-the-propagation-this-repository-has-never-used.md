# R38. The propagation this repository has never used, and the test that passed without it

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `98cbe2e` — `NESTED` used in the same place as `REQUIRES_NEW`, asserted to
> behave like a savepoint
> **Green commit**: `35aadbb` — the refusal recorded as a refusal, and the assertion moved off
> the row
> **Answers**: nothing previously written down. `R7` §3.4 measured what `REQUIRES_NEW` buys and
> nothing has ever compared it with the alternative.
> **Status**: ⛔ **This is the small one of slice E's five traps, and it is reported as small.**
> The brief said to say so if it was, and §5 does.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0)
                   15.2.0, 64-bit — read with `select version()` in this session, R37 §3.2.
                   Pinned by digest, from TestcontainersConfiguration.POSTGRES_IMAGE:
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
  Framework      : Spring Boot 4.1.0, and the default this report is about is Spring's, not
                   Boot's — AbstractPlatformTransactionManager.nestedTransactionAllowed
  Concurrency    : NONE. One thread throughout. This trap has no race in it.
  Repetitions    : 1 invocation per arm
  WHAT ELSE WAS RUNNING ON THIS MACHINE: slice D's chained full test runs and slice G, on
                   other worktrees. EVERY FIGURE HERE IS A ROW VALUE, AN EXCEPTION TYPE OR A
                   CONNECTION COUNT. None is a duration and none contends.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`@Transactional(propagation = Propagation.NESTED)` does not run. It is refused before any
transaction exists:

```
org.springframework.transaction.NestedTransactionNotSupportedException:
Transaction manager does not allow nested transactions by default -
specify 'nestedTransactionAllowed' property with value 'true'
```

⭐ **And a test asserting the savepoint's behaviour passed anyway.** That is the finding worth
the report, and it is in §3.2.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.NestedPropagationTest --rerun-tasks
```

At `98cbe2e`. `NestedCounter` calls `InnerIncrementer` — a separate bean, so the propagation
attribute is actually read — twice over, once with `REQUIRES_NEW` and once with `NESTED`, in
otherwise identical code. Three questions: what an outer rollback reaches, what an inner failure
leaves behind, and **how many pool connections each holds.**

## 3. 계측 / Measurement

### 3.1 As this application ships

```
E5 >>> outer rolls back after inner
E5 >>>   REQUIRES_NEW  row=1  outer=java.lang.IllegalStateException: the outer unit of work failed after the inner one committed
E5 >>>   NESTED        row=0  outer=org.springframework.transaction.NestedTransactionNotSupportedException: Transaction manager does not allow nested transactions by default - specify 'nestedTransactionAllowed' property with value 'true'

E5 >>> inner fails, outer catches
E5 >>>   REQUIRES_NEW  row=100  innerThrew=java.lang.IllegalStateException
E5 >>>   NESTED        row=100  innerThrew=org.springframework.transaction.NestedTransactionNotSupportedException

E5 >>> active pool connections
E5 >>>   idle=0  duringREQUIRES_NEW=2  duringNESTED=-1
```

| question | `REQUIRES_NEW` | `NESTED` |
| --- | --- | --- |
| outer rolls back after the inner ran | **row = 1** — the inner survived | **row = 0** — nothing ran |
| inner fails and is caught | **row = 100**, inner threw `IllegalStateException` | **row = 100**, inner threw `NestedTransactionNotSupportedException` |
| pool connections held while the inner call is open | **2** | **`-1`** — refused before a connection is taken |

`3 tests, 0 failures` at `35aadbb`.

**`duringREQUIRES_NEW=2` is the number this repository did not have.** A `REQUIRES_NEW` called
from inside a transaction holds **two** pool slots for the duration of the inner call — the
outer's, which is idle and waiting, and the inner's. `R2` sized this pool and `R24` put three
instances against one `max_connections`; **neither varied the propagation**, so both were
measuring a demand figure that doubles wherever this shape appears.

**`duringNESTED=-1` is not a connection count.** It is the refusal, recorded rather than thrown,
so the arm reports *"there was no number to take"* instead of failing and reporting nothing.

### 3.2 ⭐ The arm that passed while measuring nothing

Look at the middle row of that table again. **Both propagations leave `row = 100`.**

The original assertion was exactly that:

```kotlin
assertEquals(100, afterNested, "rolling back to a savepoint leaves the outer transaction usable")
```

**It was green.** And it was green for a reason with nothing to do with savepoints: `NESTED` was
refused at transaction creation, the inner unit of work **never ran**, `runCatching` swallowed
the refusal, and the outer wrote its 100 exactly as it would have after a clean savepoint
rollback.

⛔ **The row cannot tell those two stories apart**, and the row was the whole assertion. A test
named for savepoint semantics passed on a stack that has no savepoints enabled.

The repair is not a stronger assertion on the row — no assertion on the row can work. It is to
assert **what the inner call threw**, which `NestedCounter` now returns:

| arm | row | inner threw | what the row is evidence of |
| --- | --- | --- | --- |
| `REQUIRES_NEW` | 100 | `IllegalStateException` | the inner ran, failed, rolled back; the outer survived |
| `NESTED` | 100 | `NestedTransactionNotSupportedException` | **nothing** |

⭐ **This is `ADR-015`'s vacuous pass in a test with no concurrency anywhere in it.** That ADR
was written about races — `RaceOverlap.peak` exists because a barrier proves nothing about a
critical section — and every example in it is a scheduling problem. There is not one thread in
this report.

So the shape generalises past the thing `ADR-015` was written for, and this is the statement of
it worth keeping:

> **A vacuous pass is a property of any test whose observable is reachable by two routes when
> the test is named for only one of them.**

Concurrency is one way to get a second route and it is not the only one. Here the second route
is a configuration default: `row = 100` is reached by *a savepoint rolled back cleanly* and by
*the propagation was refused and nothing ran*, and the test was named for the first. `R9` §7's
`rate >= 0.0` and `R16`'s three tests are the same failure with a third and fourth kind of
second route.

**`ADR-015` may owe a second worked example**, one with no scheduling in it, so that the rule is
not read as being about races. That is a judgement rather than work and is routed to the
integrator rather than decided here.

### 3.3 With the switch the exception message names

⚠ **미측정 in this report.** `NestedEnabledPropagationTest` exists and sets
`nestedTransactionAllowed = true` in its own application context, so that the shipped default
stays measured by `NestedPropagationTest` in the same run. **It has not produced a result yet**
— the first attempt was a `BeanPostProcessor` declared as a `@Bean`, which does not work and
§4.1 explains why, and the corrected version's run was killed before it reached the test phase.

**Nothing about savepoint semantics on this stack is claimed by this report.** What §5 says
about `NESTED` is drawn from `REQUIRES_NEW`'s measured behaviour and from the refusal, not from
an observed savepoint.

## 4. 원인 / Mechanism

`AbstractPlatformTransactionManager` carries a `nestedTransactionAllowed` flag that is **`false`
by default**, and `JpaTransactionManager` inherits it. Spring Boot's autoconfiguration does not
set it. So `PROPAGATION_NESTED` raises before a transaction, a connection, or a savepoint
exists.

That is why `row = 0` in the first arm rather than `1`: with `REQUIRES_NEW` the inner increment
committed independently and survived the outer's rollback; with `NESTED` the increment never
happened at all.

### 4.1 Why the first attempt to enable it did not work

The switch was first set from a `BeanPostProcessor` declared as a `@Bean` in a
`@TestConfiguration`. The context started, the code was reached, and every arm still failed with
`NestedTransactionNotSupportedException`.

**A `@Bean`-declared `BeanPostProcessor` is itself an ordinary bean**, and it can only
post-process beans created after it exists. The transaction manager was already built. This is
the same class of mistake as `R6` §3.3's self-invocation — a piece of framework wiring that
*looks* applied and is not — and it was found the same way, by the measurement failing rather
than by reading.

The flag is now set on the live bean in `@BeforeEach` and restored in `@AfterEach`.

## 5. 처방 / Remedy

⛔ **There is nothing to fix, and that is the honest finding.**

| Option | Effect | Chosen |
| --- | --- | --- |
| **leave `nestedTransactionAllowed` at `false`** | `NESTED` raises loudly wherever anyone writes it | **✔** |
| set it to `true` globally | enables savepoints application-wide to serve no existing caller | |
| replace `REQUIRES_NEW` with `NESTED` anywhere | changes rollback semantics for a saving of one pool connection, for callers that do not exist | |

**No code in this repository uses `NESTED`, and after this report none does.** The default is
kept, for the reason `ADR-007` and `ADR-019` both give: a change that serves no existing caller
is unbanked. What the report buys is not a fix — it is that the **two pool connections** in
§3.1 are now a measured number rather than an assumption, and that the difference between the
two propagations is written down before somebody needs it at 3 a.m.

**When `NESTED` would be the right answer**, stated so the decision is reversible: a caller that
wants an inner failure to be recoverable **and** wants the inner work discarded if the outer
fails. `REQUIRES_NEW` cannot express that — its inner half commits independently, which §3.1
measures as `row = 1` surviving a rollback. That is a real requirement and this application does
not have it.

## 6. 재계측 / Re-measurement

| Metric | Red (`98cbe2e`) | Green (`35aadbb`) |
| --- | --- | --- |
| arms asserting on the row alone | **2** | **0** |
| arms asserting on what the inner call threw | 0 | **2** |
| arms that could pass without the propagation working | **1** | **0** |
| `NESTED` connection count | reported as a test failure | **`-1`, recorded as a refusal** |

**Nothing about the application changed between these two commits.** The defect was in the
instrument, and the re-measurement is of the instrument.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/NestedPropagationTest.kt`, run by
`.github/workflows/build.yml`.

It pins the **shipped default**: that `NESTED` raises `NestedTransactionNotSupportedException`,
that `REQUIRES_NEW`'s inner half survives an outer rollback, and that it holds **2** pool
connections. If a future Spring Boot enables nested transactions by default, the first assertion
goes red — which is the correct outcome, because §5's decision was made against a default that
would then have changed.

## 8. 남는 위험 / Remaining risk

- ⚠ **Savepoint behaviour on this stack is `미측정`.** §3.3 — the enabling test exists, its
  first form was wrong for the reason §4.1 gives, and its corrected run has not produced a
  result. **Everything §5 says about `NESTED` is reasoning from the refusal and from
  `REQUIRES_NEW`'s measured behaviour**, not from an observed savepoint. Until that arm runs,
  this report describes what this application does and not what the alternative does.
- **`duringREQUIRES_NEW=2` is one shape at one depth.** One outer, one inner, no concurrency.
  What the multiplier is when a `REQUIRES_NEW` calls another, or when `n` concurrent callers
  each hold two slots against a pool of `n`, is `미측정` — and that second one is the question
  `R2` and `R24` were actually about.
- **No concurrency anywhere in this report.** Every arm is single-threaded. The pool-exhaustion
  consequence of the ×2 is argued in §3.1 and not measured.
- **One invocation per arm.** The figures are exact row values and exception types rather than
  race outcomes, so repetition matters less here than it did in `R34` and `R35` — but it was not
  done, and `R35`'s headline moved between two consecutive runs.
- **The default was read from the exception message and from behaviour, not from Spring's
  source.** `nestedTransactionAllowed = false` is what the error says and what the runs show.
  No version of Spring's source was consulted, and `measurement-discipline.md` rule 9 is why
  that is stated rather than glossed.
- **What would break the conclusion:** a caller that needs an inner failure to be recoverable
  *and* the inner work discarded when the outer fails. §5 names it. There is no such caller
  today and the decision expires the day there is.
- **Whether any bullet here needs a judgement rather than only work.** §5's choice is a
  non-decision — keep a default that nothing uses — and needs no `open.md` row. **But §3.2 does
  raise one**: whether `ADR-015` should carry a second worked example with no concurrency in it,
  so its rule is not read as being about races. That is a judgement, it belongs to whoever owns
  that ADR, and it is not decided here.

## 9. 배운 것 / What I learned

**초록으로 통과한 테스트가 아무것도 재고 있지 않았다.** `assertEquals(100, afterNested)` —
"세이브포인트가 롤백돼도 바깥 트랜잭션은 계속 쓸 수 있다". 통과했다. 그런데 통과한 이유가
세이브포인트랑 아무 상관이 없었다. `NESTED`가 트랜잭션 만들기도 전에 거부당했고, 안쪽 작업은
아예 실행이 안 됐고, `runCatching`이 그 거부를 삼켰고, 바깥은 100을 썼다. **세이브포인트가
깨끗하게 롤백됐을 때랑 행 값이 똑같다.**

행으로는 두 얘기를 구분할 수가 없다. 그래서 고친 게 "더 센 단언"이 아니라 **단언의 대상을 바꾼
것**이다 — 행이 아니라 안쪽이 무엇을 던졌는지.

제일 놀란 건 이게 ADR-015랑 같은 모양이라는 거다. 그 ADR은 경합 얘기고, 예시가 전부 스케줄링
문제다. 여기는 **스레드가 한 개도 없다.** 그러니까 그 함정은 동시성의 성질이 아니라, **관측값에
도달하는 경로가 둘인데 테스트 이름은 그중 하나만 가리키는** 모든 테스트의 성질이다. 그게 이번에
제일 크게 배운 거고, 아마 다음 라운드에서 다른 데서 또 만날 것 같다.

두 번째는 작은 건데 뼈아팠다. `BeanPostProcessor`를 `@Bean`으로 선언해서 스위치를 켜려고 했다.
컨텍스트도 뜨고 코드도 도달했는데 안 먹었다. **`@Bean`으로 선언한 후처리기도 결국 그냥 빈이라서,
자기보다 먼저 만들어진 트랜잭션 매니저는 못 건드린다.** R6 §3.3의 self-invocation이랑 같은
부류다 — 적용된 것처럼 생겼는데 적용이 안 된 프레임워크 배선. 그리고 이번에도 읽어서 안 게 아니라
**측정이 실패해서** 알았다.
