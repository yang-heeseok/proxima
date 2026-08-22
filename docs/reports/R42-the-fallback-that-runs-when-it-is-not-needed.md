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
  PostgreSQL     : Testcontainers postgres@sha256:cf78e766…0685 — server 16.15,
                   x86_64-pc-linux-musl
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counters       : TWO, deliberately — Hibernate Statistics.prepareStatementCount (R8's
                   instrument) and an independent call counter inside the fallback
  Dataset        : one learner, one concept, one mastery row
  Load           : none. Every number here is a count
  Concurrently   : slices D and E were active. These are counts and do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

⛔ **This trap is smaller than the other three in this slice, and saying so is part of the
report.**

`R39` measures an equality check that issues SQL. `R40` measures rows committed by a unit of work
that failed. `R41` measures rows silently vanishing from a paged result. **This one measures one
wasted statement per call.** It is real, it is countable, and it is not in the same class of
harm. Presenting four findings of equal weight would be the dishonest way to write this slice up.

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
has nothing to attach to. There is no `orElse(…)`, no `orElseGet(…)`, and no
`orElseThrow(Supplier)` anywhere in `main`.

⭐ **And the reason is the language, not vigilance.** Kotlin's idiom for absence is the elvis
operator, whose right-hand side **is not an argument** — there is nothing to evaluate eagerly
because it is not evaluated to make a call. `AttemptRecorder.viaEntity` is the shipped example:

```kotlin
masteries.findByLearnerIdAndConceptId(learnerId, recording.conceptId)
    ?: Mastery(learner = learner, concept = concepts.getReferenceById(recording.conceptId))
```

That line has this defect's exact shape and cannot have the defect. `Optional` only appears here
at all because Spring Data's `CrudRepository.findById` returns one.

## 3. 계측 / Measurement

PENDING — the measurement window is held for slice D.

One lookup, one fallback that issues exactly one Hibernate statement.

| Arm | Statements | Fallback calls |
| --- | ---: | ---: |
| present, `orElse(fallback())` | | |
| present, `orElseGet { fallback() }` | | |
| absent, `orElse(fallback())` | | |
| absent, `orElseGet { fallback() }` | | |
| present, Kotlin `?: fallback()` | | |

### 3.1 Two instruments, because one that can report zero is not enough

A statement count **and** an independent call counter incremented inside the fallback. They
measure the same claim by different routes, and the test asserts their arithmetic against each
other — a disagreement means the instrument is wrong rather than the finding.

`R8` §3.3 records why this repository does not trust a single counter that can silently return
zero: `R5`'s log appender captured no events and nearly proved an absence, and its
`pg_stat_user_tables` delta passed while measuring the wrong thing.

### 3.2 The fallback goes through the repository, and pointing it elsewhere would have inverted
the result

`StatementCounter` reads Hibernate's `prepareStatementCount`, which counts what the **ORM**
decided to do. A `JdbcTemplate` query inside the fallback would be invisible to it, and this
report would have concluded that `orElse` costs nothing — a false negative produced entirely by
aiming the instrument at the wrong layer. The fallback calls `masteries.count()` so the statement
it issues is one Hibernate can see.

⚠️ An earlier draft of the fallback built a fresh `Mastery` by loading its associations. That cost
three statements and made the expected constant depend on Hibernate's caching within a
transaction — a fragile expectation measuring the wrong thing. It now issues exactly one.

### 3.3 The absent arms are the control

When the value really is absent the fallback is genuinely needed, and both spellings must cost
the same. That is what shows the difference in the `present` rows is **laziness** and not
overhead in `orElseGet`'s lambda machinery.

## 4. 원인 / Mechanism

`orElse(T other)` takes a **value**. Java evaluates arguments before the call, so the expression
producing `other` runs before `orElse` is entered and therefore before anything has looked at
whether the `Optional` is empty. `orElse` then discards it.

`orElseGet(Supplier<T>)` takes a **function**. The expression is not evaluated until the supplier
is invoked, and it is invoked only when the `Optional` is empty.

The two call sites differ by four characters and a pair of braces, and produce different numbers
of database round trips. Nothing warns.

**Was this a Java problem or a Spring problem?** Neither — it is a **language-level evaluation
order** problem, and it is the one trap in this slice that has nothing to do with the framework.
Kotlin's elvis operator is not a method call, so no argument exists to evaluate; it is the same
distinction the language makes structurally rather than through an API pair.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — `orElseGet` everywhere `orElse` takes a computed argument | correct | must be remembered; the two spellings are visually near-identical | |
| B — `orElse` only with a **constant** or an already-computed value | correct, and it is what `orElse` is for | requires knowing which is which at the call site | |
| C — Kotlin's `?:` instead of `Optional` at the boundary | correct **structurally** — there is no argument to evaluate | needs the repository method to return `T?` rather than `Optional<T>`; Spring Data supports both, and this repository already uses the nullable form in `MasteryRepository.findByLearnerIdAndConceptId` | |
| D — a static rule refusing `orElse` with a non-constant argument | enforceable | 미측정 whether ArchUnit can express "argument is not a constant"; and it would refuse legitimate uses | |

PENDING — the recommendation, and what would have made a different option correct.

⭐ **The interesting answer is C, and it is already what this repository mostly does.** The two
`Optional`s in `main` exist because `CrudRepository.findById` returns one; the repository's own
query methods return `Mastery?`. §2.1 is not evidence of discipline about `orElse` — it is
evidence that the codebase mostly never obtains an `Optional` in the first place.

## 6. 재계측 / Re-measurement

Not applicable: **nothing in the application changed, because nothing in the application was
defective.** §3's `orElseGet` and elvis arms are the comparison, measured beside the eager one
under identical conditions.

## 7. 회귀 게이트 / Regression gate

PENDING.

⚠️ **Honest expectation: a gate here would cost more than it is worth.** The defect is one
statement per call on a path that does not exist in this codebase. `R8`'s counter already pins the
statement count of the one read that matters. A rule refusing `orElse` would have to decide
whether its argument is expensive, which is not a structural property. §8 records this rather
than shipping a rule to have shipped one.

## 8. 남는 위험 / Remaining risk

- **This is measured on an instrument, not on shipped code, because the shipped code has no
  instance of it.** The finding is a property of `java.util.Optional`, confirmed on this JVM. It
  is not a claim that this application is slower than it should be.
- **No gate.** If somebody writes `orElse(expensiveThing())` tomorrow, nothing in this repository
  turns red. That is a deliberate omission with a reason in §7, and it is still an omission.
- **The cost measured is one statement.** What that statement *costs in time* is 미측정 — it needs
  a duration and this session does not hold the timing lock. `R8` §8 already says a statement
  count is not a duration, and this report does not pretend otherwise: a fallback that builds an
  object graph or calls a remote service is a different magnitude of waste and was not measured.
- **Only `orElse` versus `orElseGet` was measured.** `Optional.or`, `ifPresentOrElse`, and
  `orElseThrow(Supplier)` have the same evaluation-order distinction and were not measured. 미측정.
- **What would break the conclusion:** a JVM that could prove the argument side-effect-free and
  elide it. It cannot here, because the argument issues a database statement.
- **Which earlier §8 bullet this falsifies:** none known. PENDING — re-checked after the run.

## 9. 배운 것 / What I learned

PENDING — written the same day as the measurement.
