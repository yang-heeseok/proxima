# R42. The fallback that runs when it is not needed

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none, and it is not an omission.** `api/src/main` contains no `orElse(…)`
> with an argument at all — §2.1. `R26` is the precedent for this header.
> **Instrument**: `85943b0` — `AbsenceCostTest`, on `R8`'s counter.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767
                   934dd0a95e671f9a0fc20685 — server 16.15 on x86_64-pc-linux-musl.
                   Read from TestcontainersConfiguration.kt:72 and the container's own
                   Flyway line, NOT from measurement-discipline.md, which is wrong
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counters       : TWO, deliberately — Hibernate Statistics.prepareStatementCount (R8's
                   instrument) and an independent call counter inside the fallback
  Dataset        : one learner, one concept, one mastery row
  Load           : none. Every number here is a count
  Concurrently   : slices D and E were active on this machine. These are counts and
                   they do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

⛔ **This trap is smaller than the other three in this slice, and saying so is part of the
report.**

`R39` measures an equality check that issues SQL. `R40` measures rows committed by a unit of work
that failed, and rows silently destroyed by one that succeeded. `R41` measures rows vanishing
from a paged result. **This one measures one wasted statement per call.** It is real, it is
countable, and it is not in the same class of harm. Presenting four findings of equal weight
would be the dishonest way to write this slice up.

The symptom: a lookup succeeded, and the code that was supposed to run *only if it had failed*
ran anyway. Nothing is wrong with the answer. The extra work is invisible in the result, in the
logs, and in a code review of the line that causes it.

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.AbsenceCostTest' --rerun-tasks
```

### 2.1 Where it is reachable from in this application — nowhere

`api/src/main` contains **two** `Optional` consumers, both in `MasteryCounter`:

```
MasteryCounter.kt:72    masteries.findById(id).orElseThrow()
MasteryCounter.kt:108   masteries.findById(id).orElseThrow()
```

Both are the **no-argument** `orElseThrow()`. There is no argument to evaluate early, so the trap
has nothing to attach to. There is no `orElse(…)`, no `orElseGet(…)` and no
`orElseThrow(Supplier)` anywhere in `main`.

⭐ **And the reason is the language, not vigilance.** Kotlin's idiom for absence is the elvis
operator, whose right-hand side **is not an argument** — there is nothing to evaluate eagerly
because it is not evaluated to make a call. `AttemptRecorder.viaEntity` is the shipped example:

```kotlin
masteries.findByLearnerIdAndConceptId(learnerId, recording.conceptId)
    ?: Mastery(learner = learner, concept = concepts.getReferenceById(recording.conceptId))
```

That line has this defect's exact shape and **cannot have the defect.** `Optional` appears here
at all only because Spring Data's `CrudRepository.findById` returns one; the repository's own
query methods return `Mastery?`.

## 3. 계측 / Measurement

One lookup, one fallback that issues exactly one Hibernate statement.

```
R42-ABSENCE >>> one lookup, one fallback that issues a statement
  arm                                    statements   fallback calls
  present, orElse(fallback())            2            1
  present, orElseGet { fallback() }      1            0
  absent,  orElse(fallback())            2            1
  absent,  orElseGet { fallback() }      2            1
  present, Kotlin ?: fallback()          1            0
```

| Arm | Statements | Fallback calls |
| --- | ---: | ---: |
| **present**, `orElse(fallback())` | **2** | **1** ⟵ ran when not needed |
| **present**, `orElseGet { fallback() }` | **1** | **0** |
| **present**, Kotlin `?: fallback()` | **1** | **0** |
| absent, `orElse(fallback())` *(control)* | 2 | 1 |
| absent, `orElseGet { fallback() }` *(control)* | 2 | 1 |

⭐ **The number: `orElse` costs one extra statement per call when the value is present.**

### 3.1 The absent arms are the control, and they are why this is laziness

When the value really is absent the fallback is genuinely needed, and both spellings cost the
same — **2 and 2, 1 call and 1 call.** That is what shows the difference in the `present` rows is
**evaluation order** and not overhead in `orElseGet`'s lambda machinery. Without these two rows,
`orElseGet` being cheaper could equally have meant `orElse` was doing something expensive.

### 3.2 Two instruments, because one that can report zero is not enough

A statement count **and** an independent call counter incremented inside the fallback. The test
asserts their arithmetic against each other — `orElseGet` statements + one fallback statement
must equal `orElse` statements — so a disagreement means the instrument is wrong rather than the
finding.

`R8` §3.3 records why this repository does not trust a single counter that can silently return
zero: `R5`'s log appender captured no events and nearly proved an absence, and its
`pg_stat_user_tables` delta passed while measuring the wrong thing.

### 3.3 The fallback goes through the repository, and pointing it elsewhere would have inverted the result

`StatementCounter` reads Hibernate's `prepareStatementCount`, which counts what the **ORM**
decided to do. **A `JdbcTemplate` query inside the fallback would be invisible to it**, and this
report would have concluded that `orElse` costs nothing — a false negative produced entirely by
aiming the instrument at the wrong layer. The fallback calls `masteries.count()` so the statement
it issues is one Hibernate can see.

⚠️ An earlier draft of the fallback built a fresh `Mastery` by loading its associations. That cost
three statements and made the expected constant depend on Hibernate's caching within a
transaction — a fragile expectation measuring the wrong thing. It now issues exactly one, and the
constant is named `FALLBACK_STATEMENTS` so changing the fallback changes one number in one place.

## 4. 원인 / Mechanism

`orElse(T other)` takes a **value**. Java evaluates arguments before the call, so the expression
producing `other` runs before `orElse` is entered — and therefore before anything has looked at
whether the `Optional` is empty. `orElse` then discards it.

`orElseGet(Supplier<T>)` takes a **function**. The expression is not evaluated until the supplier
is invoked, and it is invoked only when the `Optional` is empty.

The two call sites differ by four characters and a pair of braces, and produce different numbers
of database round trips. Nothing warns.

⭐ **Was this a Java problem or a Spring problem?** **Neither** — it is a **language-level
evaluation-order** problem, and it is the one trap in this slice with nothing to do with the
framework. Kotlin's elvis operator is not a method call, so no argument exists to evaluate; the
language makes the distinction structurally rather than through an API pair. §3's fifth row
measures that: `1` statement, `0` fallback calls, same as `orElseGet` and without having to
choose correctly.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — `orElseGet` wherever `orElse` takes a computed argument | correct | must be remembered; the two spellings are visually near-identical | |
| B — `orElse` only with a **constant** or an already-computed value | correct, and it is what `orElse` is for | requires knowing which is which at the call site | |
| C — **Kotlin's `?:`, by not obtaining an `Optional` at all** | correct **structurally** — there is no argument to evaluate | needs the repository method to return `T?`; Spring Data supports both, and this repository already uses the nullable form in `MasteryRepository.findByLearnerIdAndConceptId` | **✔ (already, by accident)** |
| D — a static rule refusing `orElse` with a non-constant argument | enforceable in principle | 미측정 whether ArchUnit can express *"this argument is not a constant"*; and it would refuse legitimate uses | |

⭐ **The interesting answer is C, and it is already what this repository mostly does — but not
because anyone decided it.** §2.1 is not evidence of discipline about `orElse`. It is evidence
that the codebase **mostly never obtains an `Optional` in the first place.** The two that exist
are there because `CrudRepository.findById` returns one.

**What would make A or B the right answer instead:** a codebase that must consume a Java API
returning `Optional` — most of `java.util`, most of the JDK's newer surface — where C is not
available because the `Optional` is not yours to avoid.

## 6. 재계측 / Re-measurement

Not applicable: **nothing in the application changed, because nothing in the application was
defective.** §3's `orElseGet` and elvis arms are the comparison, measured beside the eager one
under identical conditions in the same test method.

## 7. 회귀 게이트 / Regression gate

**None, and that is a decision rather than an oversight.**

The defect is one statement per call on a path that does not exist in this codebase. `R8`'s
counter already pins the statement count of the one read that matters. A rule refusing `orElse`
would have to decide whether its argument is expensive, which is not a structural property.

⛔ **§8 records this as an omission, because it is one.** A gate shipped to have shipped a gate is
worse than none — `R17` is the report about a guard that was a person, and `ADR-017` about one
that stopped finding its input.

## 8. 남는 위험 / Remaining risk

- **No gate.** If somebody writes `orElse(expensiveThing())` tomorrow, nothing in this repository
  turns red. Deliberate, with the reason in §7, and still an omission.
- **This is measured on an instrument, not on shipped code, because the shipped code has no
  instance of it.** The finding is a property of `java.util.Optional` confirmed on this JVM. It is
  **not** a claim that this application is slower than it should be.
- **The cost measured is one statement, and what that statement costs in time is 미측정** — it
  needs a duration and this session does not hold the timing lock. `R8` §8 already says a
  statement count is not a duration, and this report does not pretend otherwise: **a fallback that
  builds an object graph or calls a remote service is a different magnitude of waste** and was not
  measured.
- **Only `orElse` versus `orElseGet` was measured.** `Optional.or`, `ifPresentOrElse` and
  `orElseThrow(Supplier)` have the same evaluation-order distinction and were not measured. 미측정.
- **C is "chosen" only in the sense that the codebase already does it.** No decision record says
  repository methods should return `T?` rather than `Optional<T>`, so nothing stops the next
  repository method from returning an `Optional` and reintroducing the surface. That is a
  convention with no owner.
- **What would break the conclusion:** a JVM that could prove the argument side-effect-free and
  elide it. It cannot here, because the argument issues a database statement — which is also why
  §3.3's choice of layer matters.
- **Which earlier §8 bullet this falsifies:** none.

## 9. 배운 것 / What I learned

이 리포트는 슬라이스에서 제일 작은 결과이고, **작다고 말하는 것 자체가 결과의 일부**라는 걸 브리프가
먼저 말해 줬다. 그런데 실제로 써 보니 그게 겸양이 아니라 정확성의 문제였다. 네 개를 같은 무게로
늘어놓으면 읽는 사람은 네 번째도 첫 번째만큼 중요하다고 읽는다. 한 호출에 statement 하나는 `R41`의
"행이 사라진다" 와 같은 종류의 피해가 아니다.

기술적으로 제일 위험했던 순간은 fallback을 `JdbcTemplate`으로 쓸 뻔한 것이다. 그랬으면 Hibernate
카운터에는 **아무것도 안 잡히고**, 이 리포트는 *"`orElse`는 공짜다"* 라고 결론 내렸을 것이다. 결함이
없어서가 아니라 계측기를 엉뚱한 층에 겨눠서. 0이 나왔을 때 그게 "없다"인지 "안 보인다"인지 구분하는
건 이번 슬라이스에서 다섯 번 나온 문제인데, 여기서는 **결론이 정반대로 뒤집힐 뻔했다**는 점이 다르다.

마지막으로, §2.1을 쓰면서 순서가 바뀌었다. 처음엔 *"이 저장소는 `orElse`를 조심해서 쓰고 있다"* 라고
쓰려고 했는데, 실제로 세어 보니 조심한 게 아니라 **`Optional`을 애초에 거의 안 만든다.** 코틀린의
엘비스 연산자는 인자가 아니라서 평가될 일이 없고, 그래서 이 함정이 붙을 자리가 없다. 규율처럼 보이는
것이 사실은 언어의 구조였고, 그 둘을 구분하지 않았으면 이 저장소에 없는 미덕을 칭찬할 뻔했다.
