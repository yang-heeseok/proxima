# R39. What one equality check costs

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: **none against application code, and §2.1 is the argument.** But this report
> does **not** conclude that nothing here is affected — see §1. `R26` is the precedent for the
> header; the finding is not `R26`-shaped.
> **Instrument**: `260dcc2`, corrected and attributed at `ae2b2da` — `EntityEqualityTest` and
> `net.gseek.fixtures.basics.EqualityShapes`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767
                   934dd0a95e671f9a0fc20685 — server 16.15 on x86_64-pc-linux-musl.
                   Read from TestcontainersConfiguration.kt:72 and the container's own
                   Flyway line, NOT from measurement-discipline.md, which names a
                   different digest and version and is wrong — slice handoff §3
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counter        : Hibernate Statistics.prepareStatementCount — R8's instrument, on a
                   standalone SessionFactory so the count is this measurement's alone
  Dataset        : a handful of rows per arm. This defect needs shapes, not scale
  Load           : none. Every number here is a statement count or a boolean
  Concurrently   : slices D and E were active on this machine. These are counts and
                   they do not contend
  Repetitions    : counts, not timings. Every figure below reproduced identically across
                   the two runs that produced them — the full run and the targeted re-run
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Three symptoms, and **the third changed what this report is about.**

**An object was put into a `HashSet`. It was saved. It was then no longer in the set.** `size`
still said 1 and the reference in the set was the same reference. The set had filed the object
under a hash that no longer existed, so `contains` walked the wrong bucket and reported absence
— about an object it was holding.

**Two objects standing for one row compared as unequal.** No exception, no warning: a row
reporting itself as not itself.

⭐ **And comparing two entities issued SQL — including with the equality this repository
ships.**

That third one is the finding. `BaseEntity` is the shape every entity here extends, and it was
believed to be free: it compares an `id` and a type, and neither looks like a database
operation. **It is not free.** Its type check goes through `Hibernate.getClass`, and
`Hibernate.getClass` unwraps a proxy **by reaching the proxy's implementation** — which is what
initialisation *is*. `BaseEntity.equals` calls it on **both** operands.

⛔ **So the conclusion this report expected to reach — "this trap is structurally shut here" —
is wrong in one half.** The hash defect really is shut, measured. The query cost is not: it was
never measured, nothing in the tree mentions it, and `BaseEntity`'s KDoc explains why
`Hibernate.getClass` is *correct* without saying what it *costs*.

**This report predicted 0 and measured 1.** §3.2 carries both numbers; §9 says what that was like.

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.EntityEqualityTest' --rerun-tasks
```

### 2.1 Why there is no red commit against application code

**First, `BaseEntity` is already the safe shape for the hash defect.** Every entity extends it,
and it hashes **constant per type**, so the hash cannot move when the `id` arrives.

**Second, the shape that would undo it is refused by a gate that has been watched refusing it.**
`TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES` fails on an `@Entity` `data class`, and
`TransactionBoundaryRulesSelfTest` plants `proxima.planted.PlantedDataClassEntity` and requires
the rule to refuse it.

⚠️ **That covers the hash and the `data class` shape. It does not cover §1's third symptom**,
which is a property of the equality this repository *chose*, not of the one it rejected.

### 2.2 There are two `@Entity data class`es in this tree and the rule has not been defeated

An integrator who greps this tree will find `@Entity data class` in
`net/gseek/fixtures/basics/EqualityShapes.kt` and should not conclude the gate has a hole. They
are out of scope twice over: `TransactionBoundaryRulesTest` imports `net.gseek.proxima` and the
fixtures are in `net.gseek.fixtures.basics`; and that import carries
`ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`, and the fixtures are test sources.

**Confirmed by running the rules, not by reading the importer.** Bytecode only, no database:

| Class | Tests | Failures |
| --- | ---: | ---: |
| `TransactionBoundaryRulesTest` — five rules against production code | **5** | **0** |
| `TransactionBoundaryRulesSelfTest` — same rule objects against planted violations | **6** | **0** |
| `AuthorisationRulesTest` / `AuthorisationRulesSelfTest` | 2 / 2 | 0 |

⚠️ **What this does and does not establish.** The production pass shows the rule **cannot see**
my fixtures. It does not show the rule still works — that is the self-test's job, and its
`planted.size >= 5` assertion is what stops *it* passing on an empty import. Both are green,
which is why the pair is quoted rather than the first alone.

⭐ **The same shape came up five times in this slice**, and naming it once is cheaper than
re-deriving it. **The unit, stated — because a count whose unit is unstated is the defect this
list is about:** *an occasion during slice G on which a green result would have been read as
establishing more than it does, and something had to be added or checked to make it mean what it
appeared to mean.*

| # | The green | What it did not establish | What was added |
| --- | --- | --- | --- |
| 1 | CHECK 5 passes on my eight new sources | that the KDoc I wrote is true | nothing can be — they make **no index claim at all**, so the green is *thin*. `R43` §3.5's was *vacuous* (empty input, which has a guard). Ledger `40.2`, class **(c)** |
| 2 | `TransactionBoundaryRulesTest` passes with two `data class` entities present | that the rule still works | the **self-test**, plus its `planted.size >= 5` assertion |
| 3 | `:api:compileTestKotlin` BUILD SUCCESSFUL | that `plugin.jpa` synthesised the no-arg constructor — its absence fails at **run time** | `javap` on the class file — §2.3 |
| 4 | `AbsenceCostTest` would have shown `orElse` costing nothing | that `orElse` is cheap | the fallback moved onto the **repository** — `R42` §3.2 |
| 5 | `proxy.javaClass` costs 0 statements | that the counter can see an initialisation at all | the **`label` control** — §3.2, which must be 1 |

**Rows 1 and 2 differ in whether the gap is mechanisable**, which is what ledger `40.2` records.

⛔ The shared lesson is not "be careful". It is that **a passing check reports the conjunction of
"the property holds" and "the check could have seen it fail", and only one of those is usually
what the reader wants.** `R8` §3.3 and `ADR-017`'s *"a guard that stops finding its input and
reports OK"* are this repository's two earlier encounters.

### 2.3 Three properties of the build the fixtures depend on, read out of the class files

**MEASURED** with `javap -p`. Recorded because all three were **assumptions** when the fixtures
were written, and a clean compile establishes none of them — the first two fail at *run time*.

| Assumption | Read from the bytecode |
| --- | --- |
| `kotlin("plugin.jpa")` synthesises a no-arg constructor for an `@Entity` `data class` | **yes** — `public DataClassChild();` beside the primary constructor and the defaults bridge |
| `@field:`-targeted JPA annotations land on the backing field | **yes** — `private Long id` carries `@Id` / `@GeneratedValue(IDENTITY)`; `parent` and `secondary` carry `@ManyToOne(fetch=LAZY)` / `@JoinColumn`; `label` carries `@Column` |
| the plugin opens `@Entity` classes **including `data class`es**, so Hibernate can proxy them | **yes** — all five fixture classes compile to `public class`, none `final` |

⚠️ **A Kotlin `data class` cannot be declared `open`** — the compiler refuses the modifier. The
opening is done to the bytecode by the plugin, so source and class file disagree about finality,
and only the class file governs whether Hibernate can build a proxy.

### 2.4 The tables are a test fixture, not a migration

Five fixture tables are created by one `create schema` over JDBC plus `hbm2ddl` on a standalone
`SessionFactory`, into schema `g_basics`, and dropped in `@AfterAll`. **`db/migration` is
untouched and the ceiling is still `V5`.** They sit outside `public` because
`BaselineMigrationTest` asserts that schema holds exactly seven base tables.

## 3. 계측 / Measurement

### 3.1 The hash that moves

```
R39-HASH >>> one entity, added to a HashSet before persist, looked up after
  data class   id 2  hash 839576932 -> 839636514  moved=true   size=1  sameReferenceInSet=true  contains=false
  id equality  id 2  hash 135782570 -> 135782570  moved=false  size=1  sameReferenceInSet=true  contains=true
```

| Shape | hash moved on persist? | still in the `Set`? |
| --- | --- | --- |
| `data class`, `id` in the constructor | **yes** | **NO** |
| `BaseEntity`-shaped, id equality | no | yes |

**`size=1` and `sameReferenceInSet=true` in both rows.** The object never left. Only the bucket
it would be looked for in changed.

### 3.2 A proxy, and what each way of asking about it costs

```
R39-PROXY >>> one row, one uninitialised proxy, one loaded instance
  proxy runtime class        : EqParent$HibernateProxy
  Hibernate.getClass agrees  : true
  javaClass agrees           : false
  instanceof agrees          : true
  BaseEntity-shaped equals   : true
  --- what each way of asking costs, each on its OWN fresh proxy ---
  proxy.javaClass            : 0
  proxy is EqParent          : 0
  proxy.id  (identifier)     : 0
  Hibernate.getClass(proxy)  : 1
  proxy.label  (CONTROL)     : 1
```

⭐ **`Hibernate.getClass` costs a statement. `javaClass`, `instanceof` and the identifier getter
do not.**

**This refuted the prediction.** The first version asserted *"asking a proxy for its type must not
initialise it"*, expected **0** for the three type checks as a group, and measured **1**. The
assertion is now attributed per operation and corrected to the measured value; it is still exact
in both directions, and the superseded prediction is written beside it **in the source**, not
only here.

⛔ **Each probe needs its own proxy, and the original could not report this because it lacked
that.** The first operation that initialises a proxy makes every later one free, so five
operations against one proxy credit the whole cost to whichever ran first — which is exactly why
the group figure was `1` and said nothing about which of the three caused it.

⭐ **`label` is the control and it is what makes the zeros mean anything.** It is an ordinary
property, so reading it *must* initialise. It measures **1**. Had it measured 0, the counter
would be blind and every 0 in the table would be worthless.

### 3.3 The headline — how many statements one `==` costs

```
R39-EQUALITY >>> two loads of ONE row, compared once
  shape                                               equal?   statements
  id equality, 2 lazy associations (SHIPPED)          true     0
  data class, 2 lazy assoc, parent overrides equals   true     4
  data class, 1 lazy assoc, parent plain equals       false    0
```

| Shape | equal? | statements |
| --- | --- | ---: |
| id equality, 2 lazy associations — **the shipped shape**, both operands loaded | **true** | **0** |
| `data class`, 2 lazy associations, associated class overrides `equals` | true | **4** |
| `data class`, 1 lazy association, associated class does **not** override `equals` | **false** | **0** |

⭐ **Four, not two, and §3.2 is why.** Each association costs **two** initialisations: the proxy
on the left when `equals` is delegated to it, and the proxy on the right when `EqParent.equals`
calls `Hibernate.getClass(other)`. Two associations, four statements. **The number is a
consequence of §3.2's finding rather than an independent measurement**, which is what makes it
evidence rather than a coincidence.

⭐ **The third row is the one to remember: 0 statements and the wrong answer.** When the
associated class does not override `equals`, the proxy answers by object identity without
initialising — cheap, and it reports one row as two.

⚠️ **Row 1 is `0` because both operands were loaded entities, not proxies.** That is the common
case and it is genuinely free. Hand `BaseEntity.equals` an *uninitialised proxy* and §3.2's
figure applies: **one statement per proxy operand.**

## 4. 원인 / Mechanism

A Kotlin `data class` generates `equals` and `hashCode` over **every constructor property**. Put
an entity's `id` there and `hashCode` depends on a value that is `null` before persist and a
number after — breaking the one thing the `hashCode` contract asks, that an object's hash does
not change while it lives. A hash-based collection files by hash on insert and looks up by hash
on `contains`; nothing tells it the object moved.

Put a **lazy association** there and `equals` reaches it. The field holds a proxy, so the
comparison calls `equals` **on the proxy**, and what happens next depends on the *associated*
class rather than on the class being compared. If it overrides `equals`, Hibernate delegates to
the implementation and initialises. If it does not, the proxy answers by identity and returns
`false` for one row.

**And the shipped shape's cost has the same root.** `Hibernate.getClass` is defined to return the
*entity* class rather than the proxy subclass, and the only way to obtain that from a proxy is
through the proxy's implementation — so unwrapping and initialising are the same operation. The
identifier is different: a proxy is constructed *with* its identifier, so the identifier getter
is answerable without a round trip, which §3.2 measures as `0`.

## 5. 처방 / Remedy — the candidates, and what each gives up

| Option | Equality means | Gives up | Chosen |
| --- | --- | --- | --- |
| A — `data class`, everything generated | all properties | hash moves on persist; `equals` issues two statements per lazy association, or answers wrongly. **Three defects for one keyword** | |
| B — **id equality, constant hash per type** (`BaseEntity`, what ships) | database identity | hash distribution within one type; two *unsaved* instances never equal unless identical; **and one statement per uninitialised proxy operand — §3.2** | **✔ (already)** |
| C — id equality, `hashCode` from `id` | database identity | the hash moves on persist — A's worst defect kept in a hand-written `equals`, and the version people write when they fix A halfway | |
| D — a **business key** (`external_ref`, `code`) | domain identity | needs a natural key immutable and non-null from construction. `Learner.externalRef` and `Concept.code` qualify (`updatable = false`); `Attempt` and `Mastery` have none | |
| E — `javaClass`-guarded equality | type + id | a proxy reports the generated subclass, so one row compares unequal to itself — §3.2 measures `javaClass agrees : false` | |

**B is what ships and this report did not change it.** What B gives up is now **three** things
rather than two, and the third was discovered by this report.

⚠️ **A cheaper unwrap may exist and this report did not measure one.** Whether the type check can
be satisfied without initialising — by reading the proxy's persistent class from its
`LazyInitializer` rather than its implementation — is **미측정**. ⛔ Not attempted here, because
changing `BaseEntity.equals` alters equality for every entity in the application and needs its
own red/green pair, not a footnote in someone else's report. Ledger `39.2`.

**What would make D correct:** an aggregate whose identity is genuinely the business key and
which is compared across persistence boundaries — imports, message payloads, reconciliation.

## 6. 재계측 / Re-measurement

Nothing in the application changed, so there is no before/after on application code. What was
re-measured is **this report's own instrument**, after its prediction was refuted:

| | first version | corrected |
| --- | --- | --- |
| what was asserted | three type checks cost **0** in total | each operation measured on its **own fresh proxy** |
| what it could report | `1` for the group, attributable to nothing | `javaClass` 0, `instanceof` 0, `id` 0, `getClass` **1**, `label` **1** |
| control | none | `label`, which must be 1 or the counter is blind |

Both runs that produced §3's figures — the full run and the targeted re-run — returned
**identical numbers**. The raw hash *values* in §3.1 differ between runs, which is expected and
not asserted: only *whether they moved* is.

## 7. 회귀 게이트 / Regression gate

`TransactionBoundaryRules.ENTITIES_ARE_NOT_DATA_CLASSES`, which **already existed** and is run by
`.github/workflows/build.yml`. This report did not add it; it priced what it is worth.

`EntityEqualityTest` is kept as a gate for §3.2 and §3.3. Its assertions are exact, so a
Hibernate release that changes what `Hibernate.getClass` costs turns it red rather than silently
changing every entity comparison in the application.

⚠️ **What the existing rule does not catch.** It refuses a **keyword**. A hand-written
`override fun hashCode() = id.hashCode()` on an ordinary entity reproduces §3.1's defect exactly
and **passes every rule in this repository.** Ledger `39.1`.

## 8. 남는 위험 / Remaining risk

- ⭐ **`BaseEntity.equals` issues one statement per uninitialised proxy operand, and nothing in
  the application accounts for it.** Where entity comparison meets proxies — `contains`,
  `distinct`, `indexOf` over lazily-associated entities — the statement count scales with the
  comparisons, not with the rows. **미측정 on any shipped path**: this report measured the
  per-comparison cost on a fixture, not its incidence in the application.
- **Whether the type check can be done without initialising is 미측정.** Ledger `39.2`. It needs
  its own red/green pair because it changes equality for every entity.
- **The gate catches a keyword, not a defect.** Ledger `39.1`.
- **The measurement is on fixtures, not on shipped entities.** The shipped arm reproduces
  `BaseEntity`'s shape on a fixture class; it is not `Attempt` or `Mastery` themselves.
- **`BaseEntity`'s constant hash is a deliberate trade and its breaking point is 미측정.** All
  instances of one entity type share a bucket. At what collection size that costs anything was
  not measured, and the argument that it does not matter is a claim about *this application's*
  collection sizes.
- **Two unsaved entities are never equal under `BaseEntity` unless identical**, so a `Set` of
  newly constructed entities de-duplicates nothing. Correct, and a sharp edge nothing warns about.
- **Only Kotlin's generated `equals` was measured.** A Java `record` entity or Lombok's
  `@EqualsAndHashCode` produces a related defect with different details. 미측정.
- **What would break the conclusion:** Hibernate ceasing to proxy by subclassing, which changes
  §3.2's `javaClass` row; a change to what `Hibernate.getClass` does, which changes the central
  finding; or Kotlin changing `data class` generation.
- **Which earlier §8 bullet this falsifies:** none. `R1` §7 lists
  `ENTITIES_ARE_NOT_DATA_CLASSES` as catching *"a generated `equals` meeting a lazy proxy"*,
  which remains true — this report adds what the **accepted** shape costs, which `R1` did not
  claim either way.

## 9. 배운 것 / What I learned

이 리포트에서 제일 값진 줄은 내가 **틀린 것을 공개적으로 단언해 둔 덕분에** 나왔다.
`assertEquals(0, …)` 옆에 *"프록시에 타입을 묻는 것이 초기화를 유발해서는 안 된다"* 라고 써 놓고
돌렸더니 1이 나왔다. 그 자리를 `assertTrue(cost <= 1)` 같은 느슨한 형태로 썼다면 초록으로 지나갔을
것이고, **이 리포트의 §1은 존재하지 않았을 것이다.** 정확한 단언은 맞았을 때 게이트가 되고 틀렸을 때
발견이 된다. 느슨한 단언은 둘 다 못 한다.

두 번째는 계측 설계의 실수였다. 처음엔 세 가지 타입 검사를 **하나의 프록시에** 대고 한꺼번에 셌다.
그러면 먼저 실행된 연산이 초기화를 다 가져가 버려서, 1이라는 숫자가 셋 중 무엇 때문인지 영원히 알 수
없다. 프록시는 **한 번만** 초기화되지 않은 상태이고, 그게 계측 단위를 강제한다. 측정 행위가 측정
대상을 파괴하는 종류의 실험이었는데 나는 그걸 나중에야 알았다.

세 번째가 가장 오래 갈 것 같다. `label` 대조군은 습관으로 넣은 것이지 통찰이 아니었는데, 그게
없었으면 `javaClass : 0`을 *"초기화하지 않는다"* 로 읽을지 *"카운터가 안 보고 있다"* 로 읽을지
구분할 방법이 없었다. 이 저장소가 `R5`와 `R8` §3.3에서 두 번 데인 바로 그 자리다. **0은 그 자체로는
아무 말도 하지 않는다. 1을 낼 수 있다는 걸 보여준 계측기에서 나온 0만이 말을 한다.**

마지막으로 `BaseEntity`에 대해. KDoc은 `Hibernate.getClass`가 **옳은** 이유를 아주 잘 설명하고
있고 그건 지금도 맞다. 다만 **비용은 한 글자도 적혀 있지 않다.** 나는 그 문서를 읽고 "그러니 공짜"
라고 넘겨짚었다. 옳음을 설명하는 문장을 비용에 대한 침묵과 함께 읽으면 사람은 비용이 0이라고 읽는다.
이건 문서가 거짓말을 한 게 아니라 **문서가 말하지 않은 것을 내가 채워 넣은 것**이고, `R43`이 다루는
실패와 방향만 반대다.
