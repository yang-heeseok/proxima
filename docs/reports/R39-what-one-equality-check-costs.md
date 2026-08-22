# R39. What one equality check costs

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none, and it is not an omission.** Nothing in this application is a defect of
> this shape — `BaseEntity` already closes it and an ArchUnit rule already refuses the shape that
> would reopen it. §2.1 establishes both. `R26` is the precedent for this header.
> **Instrument**: `260dcc2` — `EntityEqualityTest` and `net.gseek.fixtures.basics.EqualityShapes`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e766…0685 — server 16.15,
                   x86_64-pc-linux-musl
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counter        : Hibernate Statistics.prepareStatementCount — R8's instrument, on a
                   standalone SessionFactory so the count is this measurement's alone
  Dataset        : a handful of rows per arm. This defect needs shapes, not scale
  Load           : none. Every number here is a statement count or a boolean
  Concurrently   : slices D and E were active. These are counts and do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

An object was put into a `HashSet`. It was saved. It was then no longer in the set.

`size` still said 1. The reference in the set was still the same reference. Nothing threw. The
set had simply filed the object under a hash that no longer existed, so `contains` walked the
wrong bucket and reported absence — about an object it was holding.

The second symptom has no visible effect at all until it is counted: **comparing two objects
issued SQL.** Not fetching them. Comparing them.

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.EntityEqualityTest' --rerun-tasks
```

### 2.1 Why there is no red commit — this repository already closed it, twice

⭐ **Decided before measuring, and the reason is in the tree rather than in this report.**

**First, `BaseEntity` is already the safe shape.** Every entity here extends it, and it:

- compares by `id`, and only once an `id` exists — so two unsaved instances are equal only if
  they are the same object;
- hashes **constant per type**, so the hash cannot move when the `id` arrives;
- compares types through `Hibernate.getClass` rather than `javaClass`, so a proxy and a loaded
  instance of one row agree about what they are.

**Second, the shape that would undo it is refused by a gate that has been watched refusing it.**
`TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES` fails on an `@Entity` `data class`, and
`TransactionBoundaryRulesSelfTest` plants `proxima.planted.PlantedDataClassEntity` and requires
the rule to refuse it.

So this report measures **what the shipped design is worth**, not a defect it has.

### 2.2 There are two `@Entity data class`es in this tree and the rule has not been defeated

⚠️ **An integrator who greps this tree will find `@Entity data class` in
`net/gseek/fixtures/basics/EqualityShapes.kt` and should not conclude the gate has a hole.**

They are out of the rule's scope twice over, deliberately:

1. `TransactionBoundaryRulesTest` imports `net.gseek.proxima`. The fixtures are in
   `net.gseek.fixtures.basics`, which is not under it.
2. That same import carries `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, and the fixtures are
   test sources.

This is the arrangement `proxima.planted` already uses, and `TransactionBoundaryRulesSelfTest`
states the same belt-and-braces reasoning for it — unreachable "first" because of the package and
"second" because they are test sources.

**Confirmed by running the rules, not by reading the importer again.** `2223e7d`,
`./gradlew :api:test --tests "net.gseek.proxima.arch.*"` — bytecode only, no database:

| Class | Tests | Failures |
| --- | ---: | ---: |
| `TransactionBoundaryRulesTest` — the five rules against production code | **5** | **0** |
| `TransactionBoundaryRulesSelfTest` — the same rule objects against planted violations | **6** | **0** |
| `AuthorisationRulesTest` / `AuthorisationRulesSelfTest` | 2 / 2 | 0 |

So `ENTITIES_ARE_NOT_DATA_CLASSES` still passes on production code **while two `@Entity`
`data class`es exist in this tree**, and still refuses the planted one. Both halves green is the
claim; either alone would not be.

⚠️ **What this does and does not establish.** It shows the production rule cannot see my
fixtures. It does not show the rule would catch a real `data class` entity in
`net.gseek.proxima` — that is the self-test's job, and its `planted.size >= 5` assertion is what
stops *it* from passing on an empty import. Both are green here, which is why the pair is quoted
rather than just the first.

The same placement is what keeps them out of Spring Boot's entity scan. Commit `8e5843a` put four
fixture entities under `net.gseek.proxima` and **34 of 52 tests failed across six classes, none
of them the one that caused it.** `PersistenceUnitGateTest` is what notices now.

### 2.3 Three properties of the build the fixtures depend on, read out of the class files

**MEASURED 2026-08-22** with `javap -p`, at commit `4c25d2b`. Recorded because all three were
**assumptions** when the fixtures were written, and a clean compile does not establish any of
them — the first two fail at *run time*, inside Hibernate, not at compile time.

| Assumption | Read from the bytecode |
| --- | --- |
| `kotlin("plugin.jpa")` synthesises a no-arg constructor for an `@Entity` `data class` | **yes** — `public DataClassChild();` sits beside the primary constructor and the defaults bridge |
| `@field:`-targeted JPA annotations land on the backing field, so Hibernate uses field access | **yes** — `private Long id` carries `@Id` and `@GeneratedValue(IDENTITY)`; `parent` and `secondary` carry `@ManyToOne(fetch=LAZY)` and `@JoinColumn`; `label` carries `@Column` |
| the compiler plugin opens `@Entity` classes, **including `data class`es**, so Hibernate can proxy them | **yes** — all five fixture classes compile to `public class`, none `final` |

⭐ **The third row was inferred before it was read, and the inference is worth keeping because it
turned out to be right for a checkable reason.** `TransactionBoundaryRulesSelfTest` says each
planted class *"violates exactly one rule"*. `proxima.planted.PlantedDataClassEntity` is a
`data class` entity, and if the plugin did not open it, it would violate
`ENTITIES_CAN_BE_SUBCLASSED` as well — two rules, not one. So the self-test's own sentence
implied the plugin opens data classes. That is now read rather than deduced.

⚠️ **A Kotlin `data class` cannot be declared `open`** — the compiler refuses the modifier. The
opening is done to the bytecode by the plugin, so the source and the class file disagree about
finality, and only the class file governs whether Hibernate can build a proxy.

### 2.4 The tables are a test fixture, not a migration

The five fixture tables are created by one `create schema` over JDBC plus `hbm2ddl` on a
standalone `SessionFactory`, into a schema named `g_basics`, and dropped in `@AfterAll`.
**`db/migration` is untouched and the ceiling is still `V5`.** They are outside `public` because
`BaselineMigrationTest` asserts that schema holds exactly seven base tables — a fixture whose
safety depends on another test's `WHERE` clause is one edit away from producing a failure that
reads as a migration defect.

## 3. 계측 / Measurement

### 3.1 The hash that moves

PENDING — the measurement window is held for slice D.

| Shape | hash before → after | moved? | in the set afterwards? |
| --- | --- | --- | --- |
| `data class`, `id` in the constructor | | | |
| `BaseEntity`-shaped, id equality | | | |

### 3.2 A proxy and the row it stands for, under three type checks

PENDING.

| Check | Agrees that the proxy and the loaded instance are one row? |
| --- | --- |
| `Hibernate.getClass` | |
| `javaClass` | |
| `instanceof` | |
| statements the type checks cost | |

### 3.3 The headline — how many statements one `==` costs

PENDING.

Two distinct objects standing for **the same row**, each holding its own uninitialised proxies,
compared once.

⭐ **They must be the same row, and that is not a contrivance to inflate the number.** Kotlin's
generated `equals` compares constructor properties in declaration order and **short-circuits at
the first mismatch**. Two *different* rows differ at `id`, return `false` immediately, and never
reach the association — a test written that way would have reported that this trap does not
exist. Two objects for one row is also the realistic case: it is what `contains`, `distinct` and
`indexOf` do for a living.

| Shape | equal? | statements |
| --- | --- | ---: |
| id equality, 2 lazy associations — **the shipped shape** | | |
| `data class`, 2 lazy associations, parent overrides `equals` | | |
| `data class`, 1 lazy association, parent does not override `equals` | | |

## 4. 원인 / Mechanism

PENDING in its particulars. The structure:

A Kotlin `data class` generates `equals` and `hashCode` over **every constructor property**.
Put an entity's `id` in the constructor and `hashCode` depends on a value that is `null` before
persist and a number afterwards — which breaks the one thing the `hashCode` contract asks for,
that an object's hash does not change while it lives. A hash-based collection files by hash on
insert and looks up by hash on `contains`; it has no way to notice that the object moved.

Put a **lazy association** in the constructor and `equals` reaches it. The field holds a proxy, so
the comparison calls `equals` **on the proxy**, and what happens next depends on the associated
class rather than on the class being compared — which is why the third arm of §3.3 exists and why
the answer is a property of the *pair*, not of the `data class` alone.

## 5. 처방 / Remedy — the candidates, and what each gives up

⭐ The brief asks for this table specifically: there is more than one remedy and they are not
interchangeable.

| Option | Equality means | Gives up | Chosen |
| --- | --- | --- | --- |
| A — `data class`, everything generated | all properties | the hash moves on persist; `equals` issues SQL; a proxy compares unequal to its own row. Three defects for one keyword | |
| B — **id equality, constant hash per type** (`BaseEntity`, what ships) | database identity | hash distribution within one entity type — every instance of a type lands in one bucket. Also: two *unsaved* instances are never equal unless identical, so a `Set` of new entities de-duplicates nothing | **✔ (already)** |
| C — id equality, `hashCode` from `id` | database identity | the hash moves on persist. This is A's worst defect kept in a hand-written `equals`, and it is the version people write when they fix A halfway | |
| D — a **business key** (`external_ref`, `code`) | domain identity | needs a natural key that is immutable and non-null from construction. `Learner.externalRef` and `Concept.code` qualify — they are `updatable = false`. `Attempt` and `Mastery` have none | |
| E — `javaClass`-guarded equality | type + id | a proxy reports the generated subclass, so one row compares unequal to itself — §3.2 measures it | |

**B is what ships and this report did not change it.** What B gives up is worth naming rather
than glossing: hash distribution is sacrificed on purpose, and the argument that it does not
matter is an argument about **collection sizes an entity graph produces**, not a general one. A
`HashSet` of a hundred thousand entities of one type degenerates to a linked list under B. That
condition does not exist in this application and would if something bulk-loaded a table into a
set.

**What would make D correct:** an aggregate whose identity is genuinely the business key and
which is compared across persistence boundaries — imports, message payloads, reconciliation. Then
id equality is the wrong answer, because two objects representing the same real thing with
different ids should be equal and under B they are not.

## 6. 재계측 / Re-measurement

Not applicable in the usual sense: **nothing in the application changed, because nothing in the
application was defective.** §3's `BaseEntity`-shaped arms are the comparison, measured beside
the defective shapes under identical conditions on the same `SessionFactory`.

## 7. 회귀 게이트 / Regression gate

`TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES`, which **already existed** and is run by
`.github/workflows/build.yml`. This report did not add it; it priced what it is worth.

PENDING — whether `EntityEqualityTest` itself is left as a gate or as a measurement.

⚠️ **What the existing rule does and does not catch.** It refuses `@Entity data class`. It does
**not** catch option C above — a hand-written `hashCode` computed from `id` on a normal class is
the same defect and passes every rule in this repository. §8 records that.

## 8. 남는 위험 / Remaining risk

- **The gate catches a keyword, not a defect.** `ENTITIES_ARE_NOT_DATA_CLASSES` refuses
  `data class`. A hand-written `override fun hashCode() = id.hashCode()` on an ordinary entity
  reproduces the hash-moves-on-persist defect exactly and **passes**. 미측정 whether a rule could
  distinguish that statically.
- **The measurement is on fixtures, not on shipped entities.** The shipped arm is `BaseEntity`'s
  shape reproduced on a fixture class, not `Attempt` or `Mastery` themselves. It is the same code
  shape; it is not the same class. Whether every shipped entity actually inherits the behaviour —
  rather than merely extending the class — is asserted by `EntityMappingTest` and not re-measured
  here.
- **`BaseEntity`'s constant hash is a deliberate trade and its breaking point is 미측정.** All
  instances of one entity type share a bucket. At what collection size that becomes a cost was not
  measured, and the argument that it does not matter is a claim about this application's
  collection sizes rather than a general one.
- **Two unsaved entities are never equal under `BaseEntity` unless identical**, so a `Set` of
  newly constructed entities de-duplicates nothing. That is correct and it is also a sharp edge;
  nothing in the tree warns about it.
- **Only Kotlin's generated `equals` was measured.** A Java `record` entity, or Lombok's
  `@EqualsAndHashCode`, produces a related defect with different details. 미측정.
- **What would break the conclusion:** Hibernate ceasing to proxy by subclassing, which would
  change §3.2's `javaClass` result; or Kotlin changing `data class` generation.
- **Which earlier §8 bullet this falsifies:** PENDING.

## 9. 배운 것 / What I learned

PENDING — written the same day as the measurement.

One item is already fixed, and it belongs here rather than in a commit message: **two defects in
this report's own instrument were found by having to explain its lifecycle, not by anything going
red.** `@BeforeAll` and `@AfterAll` were instance methods with no `@TestInstance(PER_CLASS)`,
which JUnit rejects outright — the class would not have run at all — and the fixture never
dropped its schema, so it would have outlived its own test in a container Spring caches across the
whole module. Both were found while answering the question *"how do those tables come into
existence?"*. That is the same shape as `R43` §3.4, where a check was silent for the wrong reason
and only a second configuration exposed it.
