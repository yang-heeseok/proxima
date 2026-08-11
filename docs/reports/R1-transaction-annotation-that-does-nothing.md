# R1. A transaction annotation that does nothing

> **Created**: 2026-08-11
> **Updated**: 2026-08-11
> **Red commit**: `21e7162` — the state in which this was observed
> **Green commit**: `9388743` — the state in which it was not
> **Gate commit**: `4141a65` — the rules that turn red if it returns

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Connection pool: HikariCP 7.0.2, defaults
  Dataset        : none. This report needs three rows, not three million
  Load           : none. This defect appears at concurrency 1
  Repetitions    : counts, not timings — see §3
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A unit of work raised, and a row it had written was still there afterwards.

Recording an attempt writes twice: the `attempt`, then the learner's `mastery`. When the
second write was rejected, the first remained committed. The learner's history said they
had attempted an item; their mastery said they had not.

**The test suite was green.** Not green because the tests were weak in an obvious way —
green while containing a test that reads as a test of exactly this property.

## 2. 재현 / Reproduction

```bash
git checkout 21e7162
export JAVA_HOME=$(echo ~/.jdks/jdk-21*)
./gradlew :api:test
```

Requires a reachable Docker daemon. On this machine Docker runs **natively inside WSL2**,
so the command must be run there — Windows cannot reach the daemon at all.

Concurrency is 1. Dataset size is three rows. **Neither load nor scale is involved**, which
is worth stating in a repository organised around defects that need them: this one needs
only that something fails partway through, and nothing in a normal test run does.

## 3. 계측 / Measurement

The measurement here is a **count of committed rows**, not a latency. There is nothing to
report as p50/p95/p99 and nothing is inferred in their place.

| Metric | Value |
| --- | --- |
| Committed `attempt` rows after a failed unit of work | **1** (expected 0) |
| Committed `mastery` rows after the same | 0 |
| Concurrency | 1 |
| Error rate | n/a — the failure is a raised exception, by construction |
| Test classes green at the red commit | 3 of 4 |

Verbatim, `AttemptRecordingAtomicityTest` at `21e7162`:

```
AttemptRecordingAtomicityTest > a recording that fails on its second write leaves no attempt behind() FAILED
    org.opentest4j.AssertionFailedError: an attempt was committed by a unit of work that failed. The attempt and the mastery it updates are one unit -- a learner whose history and whose state disagree is not reconciled by anything downstream ==> expected: <0> but was: <1>
```

### The measurement that matters more

One failing test proves a row leaked. It does not prove the *interesting* claim, which is
that the ordinary test could not have caught it. That was measured by deleting the
annotation and re-running both classes:

| | `@Transactional` present | annotation **deleted** |
| --- | --- | --- |
| `AttemptRecordingServiceTest` | **GREEN** | **GREEN** |
| `AttemptRecordingAtomicityTest` | RED | RED |

**Four cells, two worlds, no difference.** At the red commit the annotation had no
observable effect on any test in the repository.

## 4. 원인 / Mechanism

`@Transactional` is metadata. It does not execute. Something has to read it and act, and in
Spring that something is a **proxy**: at startup the container replaces the bean with a
generated subclass that opens a transaction, delegates, then commits or rolls back.

That proxy exists **between a caller and the object**. Once control is inside the object,
`this` is the real instance, not the proxy.

```kotlin
fun recordAll(learnerId: Long, recordings: List<Recording>) {
    recordings.forEach { record(learnerId, it) }   // this.record(...) — misses the proxy
}

@Transactional
fun record(learnerId: Long, recording: Recording) { ... }
```

So `record` ran with no transaction. Each `save` then ran inside the transaction Spring
Data opens for its own repository methods, and each committed independently. The first
write was durable before the second was attempted.

### Why the passing test could not see it

`AttemptRecordingServiceTest` carries `@Transactional` on the class — the standard Spring
idiom, used so each test rolls back and leaves the database clean. It has a consequence
that is rarely stated: **the test and the code under test share one transaction.** The
service's writes join the test's transaction and are rolled back by the harness at the end,
whether or not the service had a boundary of its own.

A test that shares a transaction with the code it is testing cannot observe that code's
transaction boundaries. There is no assertion that recovers this; the vantage point is
wrong.

What that test does assert — that no `mastery` row exists after the failure — is true
because the statement that would have written it is the one that failed. **It is asserting
that code after a throw does not run.** That is true in every language and says nothing
about transactions.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — inject the bean into itself, call `self.record(…)` | Works | `record(…)` and `self.record(…)` look identical and mean different things. The next obvious edit reintroduces the defect | |
| B — `AopContext.currentProxy()` | Works | Same, plus requires `exposeProxy` on and is unfamiliar enough to be deleted as noise | |
| C — **move the boundary onto its own bean** | Works | One more class | **✔** |
| D — put `@Transactional` on `recordAll` | Works | Changes the semantics: one bad recording discards a whole batch | |

**C.** A and B fix the symptom and leave a class in which correctness depends on everyone
remembering an unusual call syntax. That is not a property; it is a convention with a
deadline.

The defect was also asking a question, and it was not *how do I reach the proxy*. It was
**what is the unit of work.** Here it is one recording, not the batch — attempts are
independent events, and one learner's invalid submission is not a reason to discard valid
ones recorded beside it. Once that is decided the boundary has an obvious home, and the
proxy question stops existing rather than being worked around.

**What would have made D correct:** a requirement that a batch is all-or-nothing — a
gradebook import, say, where a partially applied file is worse than a rejected one. The
choice is domain, not technique.

## 6. 재계측 / Re-measurement

Identical conditions. **Neither test file changed between the two commits.**

| Metric | Before (`21e7162`) | After (`9388743`) |
| --- | --- | --- |
| Committed `attempt` rows after a failed unit of work | **1** | **0** |
| `AttemptRecordingAtomicityTest` | RED | GREEN |
| `./gradlew test` | FAILED | 27 tests, 0 failures |

And the fix was verified the way the defect was — by deleting the annotation again:

| | annotation present | annotation **deleted** |
| --- | --- | --- |
| red commit `21e7162` | RED | RED — *no discrimination* |
| green commit `9388743` | **GREEN** | **RED** — *discriminates* |

The red commit's cells were identical, which is what proved the suite was not watching the
annotation. These differ. **The test now fails when the thing it tests is removed**, which
is the only property that makes it evidence rather than decoration.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/arch/TransactionBoundaryRules.kt` — five ArchUnit
rules, run by `.github/workflows/build.yml`, which is also new: until `4141a65` CI had no
test lane at all, only `no-learner-data` and `secret-scan`.

| Rule | Catches |
| --- | --- |
| `TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED` | this defect, exactly |
| `TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED` | a `final` class — and therefore the removal of `kotlin("plugin.spring")` |
| `TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN` | `private`/`final` methods, skipped silently |
| `ENTITIES_CAN_BE_SUBCLASSED` | a `final` entity — and therefore the removal of `kotlin("plugin.jpa")` |
| `ENTITIES_ARE_NOT_DATA_CLASSES` | a generated `equals` meeting a lazy proxy |

**Every rule has been watched refuse a planted violation** —
`TransactionBoundaryRulesSelfTest`, 6 tests. Both halves share the same rule objects, and
the production run is the negative control.

Two plants had to be written in **Java**: the compiler plugins open Kotlin classes at
compile time, so a Kotlin plant would arrive at the rule already fixed and the self-test
would be vacuous.

## 8. 남는 위험 / Remaining risk

**Fixed for one service. Not proved for the codebase.**

- **A partially recorded batch is now the documented behaviour, and the caller is not told
  which recordings landed.** `recordAll` stops at the first failure. That follows from
  choosing per-recording atomicity in §5 and is **not fixed** — fixing it means reporting
  per-item outcomes or making the batch the unit, which is a requirement, not a refactor.
- **The self-invocation rule only sees calls ArchUnit can resolve statically.** A call made
  through a lambda stored in a field, reflection, or a Kotlin `by` delegate is 미측정 — I do
  not know whether the rule sees those, and I have not planted one to find out. **Treat the
  rule as catching the common shape, not as a proof.**
- **`REQUIRES_NEW` is used in `RecordingFixture` and its cost is unmeasured here.** It takes
  a second connection while the first is held. That is `Cm = 2` in the pool-sizing formula
  and it is `T1`'s subject; nothing in this report measured it.
- **A constraint violation poisons the persistence context, and that is 미측정 in this
  report.** It was observed while writing it — see §9 — and deliberately routed to `T6`
  rather than measured here.
- **No timing was taken.** Whether a per-recording boundary costs throughput against a
  per-batch one is 미측정. There is a plausible cost: one transaction per recording is more
  round trips than one per batch.
- **The rules are structural.** They answer *can this work* and not *does this work*. A
  boundary correctly placed around the wrong set of writes passes every rule here.
- **What would break the conclusion:** a Spring release that stops proxying by subclassing,
  or a change to Spring Data so that repository methods no longer open their own
  transactions. The second is what makes the leaked row durable; without it the writes
  would fail rather than commit, and the symptom would be different.

## 9. 배운 것 / What I learned

가장 놀란 건 결함이 아니라 **테스트였다.** 원자성을 검증한다고 믿을 만한 테스트를 쓰고, 초록인 걸
보고, 그게 아무것도 증명하지 않는다는 걸 나중에 알았다. 애노테이션을 지워도 초록이라는 걸 실제로
돌려보기 전까지는 내 테스트를 의심하지 않았다. 저 네 칸짜리 표가 이 리포트에서 제일 중요한 부분이고,
앞으로 "이 테스트가 뭘 검증하나" 싶을 때마다 쓸 방법이 생겼다 — **검증 대상을 망가뜨리고 다시 돌려본다.**

두 번째는 실패를 무엇으로 만드느냐가 실험 설계라는 것. 처음엔 DB의 CHECK 제약으로 두 번째 쓰기를
실패시켰다. 주입한 게 아니라 진짜 DB가 거부하는 거니 더 정직하다고 생각했는데, PostgreSQL이 제약
위반 시 트랜잭션 전체를 abort시켜서 그 뒤 모든 읽기가
`org.hibernate.AssertionFailure: Entry for instance of 'Mastery' has a null identifier` 로
터졌다. 트랜잭션 경계와 아무 상관 없는 에러였다. **한 번에 두 가지를 재고 숫자 하나를 보고할 뻔했다.**
T6가 다룰 주제를 T3 안에 끌고 들어온 셈이라 실패를 애플리케이션 규칙으로 바꿨다.

세 번째는 스스로에 대한 것. 작업 시작할 때 빌드 파일을 읽고 "`allOpen`이 `@Entity`에 안 걸려 있으니
엔티티가 `final`이라 지연 프록시가 안 만들어진다"고 확신했다. 고치기 전에 바이트코드를 봤더니 틀렸다 —
`kotlin("plugin.jpa")`가 이미 열고 있었다. **설정을 그럴듯하게 읽은 것은 동작을 관측한 것이 아니다.**
이 저장소가 다루는 실수를 내가 20분 만에 작게 재현한 거라 커밋에 남겼다.
