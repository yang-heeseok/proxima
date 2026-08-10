# ADR-001 — How QueryDSL is generated on Kotlin

> **Created**: 2026-08-10
> **Updated**: 2026-08-10
> **Status**: **Accepted** — 2026-08-10. Closes `OPEN-2`.
> **Timebox**: 30 minutes. Used ~15. The fallback was not needed.

## Context

QueryDSL generates its `Q` classes with an annotation processor. On Kotlin that means
`kapt`, which Kotlin's own maintainers describe as in maintenance mode — it is not being
developed alongside K2, and it works by generating Java stubs from Kotlin sources, which is
where most of the friction lives.

Compounding it: the Jakarta migration split the artefact coordinates, and there is a
community fork maintained separately from the original project. A wrong combination
produces a build that either generates nothing or generates against the wrong persistence
API, and both failure modes present as an unhelpful "cannot resolve `QAttempt`".

This is a real, current, and slightly annoying piece of ecosystem state. It is exactly the
class of problem that does not appear in tutorials, because tutorials are written in Java.

## Options

| Option | Note |
| --- | --- |
| A — `kapt` + the original artefact with the Jakarta classifier | The conventional path. Verify the classifier and processor coordinates against the current release, not against a blog post |
| B — the community fork | Maintained separately; check whether it currently publishes what this project needs |
| C — **Fallback: no QueryDSL.** JPQL plus constructor projections, and the Criteria API where a query must be assembled dynamically | Loses type-safe query construction. Loses nothing this repository actually measures |

## Decision

**Option B — the community fork, `io.github.openfeign.querydsl` 7.0, via `kapt` with the
`jakarta` classifier on the processor.**

```kotlin
kotlin("kapt") version "2.3.21"

implementation("io.github.openfeign.querydsl:querydsl-jpa:7.0")
kapt("io.github.openfeign.querydsl:querydsl-apt:7.0:jakarta")
```

## What was actually tried, and what happened

Both A and B were built and run — not inspected, not reasoned about from release notes.
The probe was an entity, a generated `Q` class, and a query executed against a real
PostgreSQL 16-alpine container through Testcontainers `@ServiceConnection`, asserting on
the returned row. Compiling is not the test; a `Q` class that compiles and then fails
against Hibernate at run time is the failure mode worth catching.

| | Latest release | Published | `Q` generated | Query executed against PostgreSQL |
| --- | --- | --- | --- | --- |
| A — `com.querydsl` | 5.1.0 | **2024-01-29** | yes | **yes — passed** |
| B — `io.github.openfeign.querydsl` | 7.0 | **2025-06-09** | yes | **yes — passed** |

Resolved stack underneath both, read off `runtimeClasspath` rather than assumed:

```
org.hibernate.orm:hibernate-core        7.4.1.Final
jakarta.persistence:jakarta.persistence-api  3.2.0
com.zaxxer:HikariCP                     7.0.2
org.postgresql:postgresql               42.7.11
org.flywaydb:flyway-database-postgresql  12.4.0
Kotlin 2.3.21 / kapt · Gradle 9.5.1 · Temurin 21.0.12+8
```

**The predicted friction did not happen.** This ADR's context expected a fight over
classifiers and persistence APIs, and there was none — both artefacts generated and ran on
Jakarta Persistence 3.2 and Hibernate 7.4.1 on the first attempt. That is worth recording
precisely because the expectation was wrong: the ecosystem state described above is real,
but it is a hazard when choosing coordinates, not when running them.

## Why B and not A, given that both passed

A passed on a release that is **two and a half years old** and predates both Jakarta
Persistence 3.2 and Hibernate 7 entirely. It works today by the API it depends on having
stayed still, not by anyone having tested it against this stack. That is a working
dependency with nobody behind it.

This is the same trade as `ADR-000`, and it is decided the same way: **supported beats
familiar.** The consistency matters more than either individual call — a repository that
picks the maintained option for its framework and the dormant one for its query library has
not applied a rule, it has expressed two preferences.

The familiarity cost is smaller here than in `ADR-000`. A reader on `com.querydsl` changes
a group id and a version; the API is the same, and the passing run of option A above is the
evidence that it is the same. That translation is recorded here so nobody has to rediscover
it.

## Why not C, and what would still make C right

C was the fallback for *neither builds*. Both built, so the fallback rule does not fire.

C remains genuinely attractive on the merits — this repository's use of QueryDSL is thin,
and `kapt` is a maintenance-mode processor in the build. It is kept because the
recommendation query in `docs/explanation/domain-model.md` is assembled from optional
predicates (mastery threshold, prerequisite satisfaction, difficulty band, a recency
window), which is the one shape where dynamic type-safe construction earns its cost.

**The escape cost is low and is the reason this is not an anxious decision.** Nothing in
`docs/roadmap.md` depends on QueryDSL: the traps are properties of pooling, proxying,
paging, indexing, and locking. If `kapt` becomes a build-time problem, C is a rewrite of a
small number of query methods, not a redesign.

## Consequences

**`kapt` is now in the critical path of every build**, including CI. It adds a stub
generation step before Kotlin compilation. **Its cost is 미측정** — it was deliberately not
measured here, because the probe project holds one entity and a build-time delta measured
on one entity would not predict the cost on the real schema. It is measured once the seven
entities exist, and the number belongs in a report rather than in this ADR.

**A `Q` class is generated code that the IDE must be told about.** `build/generated/source/kapt/main`
is a source root; a fresh clone that has never run a build will not resolve `QAttempt`
anywhere. This is the single most common way this setup looks broken while being fine.

## What would cause this to be revisited

- **`kapt` fails to keep up with a Kotlin release** this project wants. It is in
  maintenance mode; that is the stated risk of this option, not a surprise if it happens.
- **The fork's release cadence stops.** 7.0 is from 2025-06-09 — already a year old at the
  time of this decision, and itself predating Spring Boot 4. B was chosen as the *more*
  maintained of two, not as an actively-tracked one. If both are dormant at the next
  Hibernate major, C stops being a fallback and starts being the answer.
- **The measured `kapt` build cost** turns out to be a meaningful fraction of CI time once
  the real entities exist. That number is the trigger, and it does not exist yet.
