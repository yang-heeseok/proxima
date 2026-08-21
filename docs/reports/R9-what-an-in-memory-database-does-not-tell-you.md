# R9. What an in-memory database does not tell you

> **Created**: 2026-08-13
> **Updated**: 2026-08-21
> **Red commit**: **none, and that is a finding.** The classic form of this defect — a test
> lane silently running on an embedded database — **does not reproduce on Spring Boot
> 4.1.0**, because a framework default changed. §3.5 measures which one and proves the
> mechanism is still live underneath it.
> **Green commit**: this one — the comparison, the gate, and the gate's control

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL     : Testcontainers postgres:16-alpine
                   PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0)
                   datcollate = en_US.utf8   datctype = en_US.utf8   datlocprovider = c
                   ICU collations installed  = 908
  H2             : 2.4.240 -- the version Spring Boot 4.1.0's BOM manages, read out of
                   spring-boot-dependencies-4.1.0.pom rather than chosen
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Flyway 12.4.0, Kotlin 2.3.21
  Dataset        : 4-6 rows, built by each probe. This report is about behaviour, not volume
  Load           : none
  Caveat         : WSL2 had no outbound network during this work, so the H2 artefact was
                   downloaded from Windows and resolved from a local file repository via a
                   Gradle init script. The jar is the Maven Central one (2,685,418 bytes) and
                   settings.gradle.kts was not modified. Nothing else in the build changed.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`TestcontainersConfiguration` has said since the first commit that this repository tests
against PostgreSQL and not H2. It gave reasons. It gave no numbers.

That is the same shape as every claim this repository exists to distrust: a decision
defended by a plausible argument. The argument is also the standard one, which makes it
worse rather than better — a claim everybody repeats is a claim nobody re-measures.

So the question is not *does H2 differ from PostgreSQL*, which is documented and dull. It
is: **does the difference reach this code, and would this repository's own reports survive
being measured on H2 instead?**

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.db.*"
```

Four classes. `H2DivergenceTest` runs the migrations and 23 statements against both
databases and prints a comparison; `EmbeddedSubstitutionGateTest` and
`EmbeddedSubstitutionControlTest` are the gate and its control; `ContainerStartupCostTest`
prices the thing H2 is chosen to avoid.

## 3. 계측 / Measurement

### 3.1 The migrations that ship

Both databases, same three files, Flyway 12.4.0. **PostgreSQL is the control** — if `V1..V3`
failed there too, an H2 failure would say nothing about H2.

| target | result |
| --- | --- |
| PostgreSQL 16 (control) | `applied 3 migrations; tables = [attempt, concept, concept_edge, item, item_concept, learner, mastery]` |
| H2, `MODE=PostgreSQL` | `!! Unknown data type: "TIMESTAMPTZ"` |
| H2, as Spring Boot substitutes it (no mode set) | `!! Unknown data type: "TIMESTAMPTZ"` |

**H2 cannot create the first table of `V1`.** Not the tenth statement, not an edge case —
`learner`, the first table in the file, on the third column.

The table names are read back from JDBC metadata rather than the migration count being
trusted, and §3.4 is the reason: this report found a database where Flyway reported success
and the tables were somewhere else entirely.

Choosing `MODE=PostgreSQL` is what someone who picks H2 *because it behaves like PostgreSQL*
would do, so measuring H2 in its own dialect would have been a straw man. It makes no
difference here; both spellings of the configuration fail identically.

### 3.2 The statements this repository issues

Every probe below is a statement that appears in a migration, a query, or a report in this
repository, and each names where it comes from. Each runs on a fresh schema on both
databases, so no probe can affect another.

**23 statements: 11 LOUD, 11 AGREE, 1 SILENT.**

| kind | meaning |
| --- | --- |
| `AGREE` | both accept, same answer — H2 tells the truth about this statement |
| `LOUD` | one refuses — a build breaks and someone finds out |
| `SILENT` | both accept, different answers — **nothing tells anyone** |

#### The one that is silent

| statement | comes from | PostgreSQL | H2 |
| --- | --- | --- | --- |
| a constraint violation aborts the transaction | `R7` §4 | **the next statement was REFUSED (25P02)** | **the next statement SUCCEEDED** |

This is not a difference near `R7`. **It is `R7`.** That report's central measurement is that
catching a unique-violation and re-reading *inside the same transaction* fails 7 times out of
8, because PostgreSQL marks the transaction aborted and refuses everything until rollback.
On H2 that arm does not fail, because H2 leaves the session usable.

So a test suite on H2 would have shown the naive catch-and-continue remedy **working**. Not
"failed to show the defect" — actively certified the broken remedy as correct. And `R7` would
have concluded that `on conflict` and transaction isolation were unnecessary.

#### The eleven that are loud

| statement | comes from | PostgreSQL | H2 |
| --- | --- | --- | --- |
| `timestamptz` (the abbreviation) | `V1`, every `created_at` | accepted | `Unknown data type: "TIMESTAMPTZ"` |
| `comment on constraint … on <table>` | `V3`, last statement | accepted | syntax error |
| `insert … on conflict do nothing` | `MasteryProvisioner`, `R7` | `0 rows inserted` | syntax error |
| `insert … on conflict do update … excluded` | `R7` §5, the upsert arm | `1 row, attempts_count=9` | syntax error |
| `pg_stat_user_tables` | `R5`, `R8` | readable | table not found |
| `pg_stat_activity.backend_type` | `R2`, `R4` | readable | table not found |
| `explain (analyze, buffers)` | `R2`, `R3` — every plan here | accepted | syntax error |
| `setval(pg_get_serial_sequence(…))` | `OPEN-3` | accepted | `Function "setval" not found` |
| `order by … collate "en-US-x-icu"` | the control for §3.3 | `apple,Apple,Banana,cherry` | syntax error |
| a column named `value` | roadmap strand | accepted | syntax error |
| a column named `key` | roadmap strand | accepted | syntax error |

Four of these are not application SQL — they are the **instruments**. `explain (analyze,
buffers)` produced every plan in `R2` and `R3`; `pg_stat_user_tables` counted rows in `R5`
and `R8`; `pg_stat_activity` measured pool occupancy in `R2` and `R4`. On H2 those reports
could not have been written at all. Not "would have been less accurate" — there is no
statement to run.

#### The eleven that agree

`timestamp with time zone` spelled out, adjacent string literals across lines, `comment on
column`, `delete` with a correlated alias, `select … for update`, `update … set x = x + 1`,
a bare boolean column as a predicate, a quoted mixed-case alias, `numeric(4,3)` rounding
(`0.12349` → `0.123` on both), `create index on (a, b)`, and mixed-case ordering — with a
caveat that took a control to find, next.

`update … set x = x + 1` agreeing is worth a sentence: the arm `R6` measured as **5.1×
faster and correct** is portable. The arm this application still uses is not the issue here;
`R6` §8 already carries that.

### 3.3 A prediction that failed, and the control that explained it

Mixed-case ordering was expected to diverge. It did not:

| | result |
| --- | --- |
| `order by label`, PostgreSQL | `Apple,Banana,apple,cherry` |
| `order by label`, H2 | `Apple,Banana,apple,cherry` |
| `order by label collate "en-US-x-icu"`, PostgreSQL | **`apple,Apple,Banana,cherry`** |

Reporting the first two rows alone would have produced a true sentence with a false meaning:
*"the two databases order text identically."*

They do not. **This PostgreSQL orders text byte-wise**, and naming a collation explicitly
changes the answer. The database declares `datcollate = en_US.utf8` and `datlocprovider = c`,
and the server is built against **musl** (`x86_64-pc-linux-musl`), which does not implement
locale-aware collation — so the declared locale is byte order in practice. 908 ICU collations
are installed and none of them is the default.

That is a fact about **this repository's own measurement environment**, not about H2. Every
ordering-dependent result in every report here was taken on a database that sorts like `C`,
and a deployment on a glibc build would sort differently. It is in §8.

**미측정**: whether a glibc PostgreSQL orders these four strings differently. The
Debian-based image is not present locally and WSL had no network to pull it. The mechanism
above is documented behaviour, not something measured here, and it is written as a
hypothesis rather than a result.

### 3.4 The finding that needed no H2 at all

`@DataJpaTest` carries `@AutoConfigureTestDatabase`. Setting `replace = ANY` — a one-line
change, and the default in every Spring Boot before 3.4 — produces this:

```
Replacing 'dataSource' DataSource bean with embedded version
Starting embedded database: url='jdbc:h2:mem:…;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false'
Successfully validated 3 migrations
Successfully applied 3 migrations to schema "public", now at version v3
…
Schema validation: missing table [attempt]
```

Flyway says it applied three migrations. Hibernate then cannot find a table. §3.1 says H2
cannot execute `V1` at all, so both cannot be true of one database — and they are not:

| | database | application tables in it |
| --- | --- | --- |
| the JPA datasource | **H2** | `[]` |
| the container, in the same context | **PostgreSQL** | `[attempt, concept, item, learner, mastery]` |

**Flyway migrated PostgreSQL. Hibernate ran on H2. In one Spring context, at the same time.**

The container starts. `@ServiceConnection` is honoured — by Flyway. The datasource bean is
replaced — for JPA. Each half of the stack is correctly wired to a different database, and
the entire symptom is one line that reads like a broken migration.

This is measured in `EmbeddedSubstitutionControlTest`, which asks the container directly
instead of reasoning about which bean Flyway resolved. The tables are either there or they
are not.

### 3.5 Why it does not happen by default, measured rather than assumed

`@AutoConfigureTestDatabase.replace` defaults to **`NON_TEST`** on Spring Boot 4.1.0 — read
from the annotation's `AnnotationDefault` attribute with `javap`, not from documentation and
not from memory:

```
public abstract …AutoConfigureTestDatabase$Replace replace();
  AnnotationDefault:
    default_value: …AutoConfigureTestDatabase$Replace.NON_TEST
```

Under `NON_TEST`, a datasource contributed by a test configuration — which is what
`@ServiceConnection` produces — is left alone. Measured end to end:

```
T8-GATE    >>> @DataJpaTest connected to PostgreSQL at jdbc:postgresql://localhost:32790/test
T8-CONTROL >>> replace=ANY: the JPA datasource is H2
```

So the trap is closed **by a framework default**, not by anything this repository does. That
is worth stating precisely, because "we are safe" and "the version we happen to be on is
safe" are different claims with the same green build.

### 3.6 What the container actually costs

The argument for H2 is speed. Here is the price, three runs, `postgres:16-alpine` already
pulled:

| | runs (ms) | median |
| --- | --- | --- |
| cold start | 1622, 1506, 1426 | **1506** |
| with Testcontainers reuse | 2490 *(this one still creates it)*, 216, 315 | **~265 after the first** |

Reuse takes roughly **1.2 s** off a container start. For scale, in the same run:

| class | tests | wall time |
| --- | --- | --- |
| `H2DivergenceTest` | 3 | 4.1 s |
| `ContainerStartupCostTest` | 1 | 6.3 s |
| `EmbeddedSubstitutionGateTest` | 1 | 12.0 s |
| `EmbeddedSubstitutionControlTest` | 1 | 21.9 s |
| `BaselineMigrationTest` | 4 | **197.9 s** |

`BaselineMigrationTest` also measured **422.0 s** on an earlier run in this same session.
That spread is far beyond 10% and **what drives it is 미측정** — both runs are quoted rather
than the convenient one.

**And those local numbers are not the ones to argue from.** The same `./gradlew :api:test`,
with no filter, completes in **65 s** on a GitHub `ubuntu-latest` runner — commit `96ad9bb`,
step *api tests — Testcontainers, real PostgreSQL*, read from the workflow-run API. The
local figures are dominated by something about this machine, most plausibly the project
living on a `drvfs` mount, and that cause is **미측정**.

So the honest form of the trade is a range, not a slogan:

| | container start | whole `:api:test` | share |
| --- | --- | --- | --- |
| this machine (WSL2, `/mnt/c`) | 1.5 s | 198–422 s | 0.4–0.8% |
| GitHub `ubuntu-latest` | 1.5 s *(local figure; not re-measured there)* | 65 s | ~2% |

An earlier draft of this section said "well under one percent" full stop. That was true of the
machine it was measured on and it is not true of the runner the gate actually runs on. Two
percent is still not the reason to give up §3.2 and §3.4 — but the number that survives is
the range, not the flattering end of it.

> **Amended 2026-08-13 by `ADR-004`: the `~2%` cell breaks this repository's own rule 3.**
> *Before and after come from the same run conditions.* The container-start figure was
> measured on this machine; the 65 s was read off the workflow API. Dividing one by the other
> is exactly the comparison rule 3 forbids — and §8 below was uneasy about that sentence for a
> different reason without noticing which rule it broke. **`OPEN-5` had been guarding CI
> against publishing numbers, and the number left through the other door: a report reached in
> and took one.** The row stays, annotated rather than deleted, because it is the evidence
> that the rule as written was not enough. `ADR-004` adds a per-run environment block to the
> lane, so whoever measures both ends on the runner can make this comparison properly.

**미측정 — per-class timings in CI.** The workflow uploads test results only `if: failure()`,
so a green run leaves no artefact to read them from. The 65 s above is the whole step, and it
includes pulling `postgres:16-alpine`, which never appears in the local numbers at all.

**미측정 — CI time before and after reuse.** The roadmap asked for it and the honest answer
is that the question does not apply: Testcontainers reuse works by leaving a container
running between JVM runs *on the same machine*. A CI job starts on a fresh runner with
nothing running on it, so there is nothing to reuse and the second number would be the first.
That is a finding about the feature, recorded here rather than demonstrated by pushing a
commit to watch a number not move.

## 4. 원인 / Mechanism

Two independent mechanisms, and conflating them is the mistake this report is about.

**Dialect.** H2 in PostgreSQL compatibility mode implements a subset. The subset excludes
type aliases (`timestamptz`), upsert (`on conflict`), the statistics views, `explain
(analyze, buffers)`, sequence functions, and explicit collations. These are *loud*: the
statement does not parse.

**Semantics.** Where both databases accept a statement, they may still do different things.
Transaction-abort behaviour after a constraint violation is the one that bit `R7`, and it is
silent by construction: there is no error to catch, because the divergence *is* the absence
of an error.

The second mechanism is why "we only use portable SQL" is not a defence. Portable SQL is
exactly the set of statements that are silent when they differ.

## 5. 처방 / Remedy

Nothing to remedy in the application. `TestcontainersConfiguration` already made the right
choice; this report supplies the evidence it was missing and makes the choice enforceable.

| Option | Why not |
| --- | --- |
| H2 for tests, PostgreSQL in production | §3.2, §3.4 — and `R7` would have been wrong |
| H2 for "unit" tests, PostgreSQL for "integration" tests | the split is drawn by test *style*, and the divergences are drawn by *statement*. `R7`'s remedy is not an integration concern |
| add `spring.test.database.replace: NONE` globally | **rejected on measurement.** §3.5 shows the default already prevents it. Adding a fix for a defect never observed here is precisely the mistake `R3` §3.5 and this session's own history keep producing |
| **keep PostgreSQL, gate it, and control the gate** | **✔** |

## 6. 재계측 / Re-measurement

Not applicable. Nothing in the application changed. What changed is what CI knows.

## 7. 회귀 게이트 / Regression gate

Two tests, run by `.github/workflows/build.yml`:

- `api/src/test/kotlin/net/gseek/proxima/db/EmbeddedSubstitutionGateTest.kt` — a
  `@DataJpaTest` asserting the connection it gets is PostgreSQL. It asserts the **effect**,
  not the property, for the reason `R4` §7 gives: a gate that asserts a setting passes while
  the setting sits in a file nobody loads. This repository lost six days to exactly that.
- `api/src/test/kotlin/net/gseek/proxima/db/EmbeddedSubstitutionControlTest.kt` — asks for
  substitution explicitly and asserts it gets H2. **This is what stops the gate becoming
  vacuous.** The gate passes both when something is protecting it and when there is no
  embedded database to substitute at all; only the control distinguishes those. Delete
  `com.h2database:h2` and the control turns red on purpose.
- `api/src/test/kotlin/net/gseek/proxima/db/H2DivergenceTest.kt` asserts nothing about which
  database wins. It is a recorder: every outcome is a finding, and it fails only if it could
  not run.

## 8. 남는 위험 / Remaining risk

- **This repository's own ordering numbers were taken on a byte-ordering database.** §3.3.
  `postgres:16-alpine` is musl-built and its declared `en_US.utf8` does not produce
  locale-aware collation. Every `order by` on text in every report here — and the production
  behaviour they imply — is unverified against a glibc or ICU-collated deployment. **The
  container tag is in every measurement environment block in this repository, and until now
  nobody had established what that tag decides.**

  > **Measured 2026-08-21 — `R25`. The mechanism is confirmed and larger; the quantifier is withdrawn.** §3.3's `미측정` is closed: a glibc PostgreSQL declaring the same `en_US.utf8` orders those four strings `apple,Apple,Banana,cherry`, and **4,461 of 4,465 two-character ASCII pairs re-order**. But *"every `order by` on text in every report here"* names an empty set — the only `varchar` ordering in the tree is §3.3's own probe, and `BaselineMigrationTest`'s two catalog orderings are on PostgreSQL's `name` type, whose collation is fixed at `C`. `R26` prices the deployment that would pay for it; `R27` is what the tag has done since.
- **The H2 dependency is itself the hazard.** It is on the test classpath so §3.2 can exist,
  and its presence is exactly what makes §3.4 possible. Two tests hold it down; a third
  configuration nobody thought of is not covered.
- **`replace = NON_TEST` is a version fact, not a guarantee.** §3.5. A Boot upgrade that
  changed it back would be caught by the gate, which is the point — but the gate is the only
  thing standing there.
- **`COPY … FROM STDIN` was not probed.** On PostgreSQL it is a driver API (`CopyManager`),
  not a statement, so a SQL-level probe would have failed on both databases and printed a row
  implying PostgreSQL cannot do it. The bulk-load path does not exist yet either (`OPEN-3`).
- ~~**Identifier generation and batching were not measured.**~~ **Closed 2026-08-13 by
  `ADR-003`**, the day after this was written and because writing it twice was embarrassing
  enough to act on. Measured: `IDENTITY` costs 1,000 statements for 1,000 rows,
  `SEQUENCE(allocationSize = 50)` costs 40 — about 10× — and `SEQUENCE(allocationSize = 1)`
  costs 1,020 and gains nothing, so the win is the allocation size and not the sequence.
  `IDENTITY` stays, because no path here inserts more than one row per transaction. The
  original text is struck through rather than deleted: **this bullet said the same thing in
  `R8` §8 first, and a risk that survives two reports unchanged is not being carried, it is
  being avoided.**
- **23 statements is not the application.** The probes cover the migrations, the
  recommendation query, and the statements named in `R2` through `R8`. Everything Hibernate
  generates on its own — every derived query, every `join fetch`, every dialect-specific
  paging rewrite `R5` measured — is unprobed.
- **Container reuse is measured locally and 미측정 in CI**, and §3.6 argues it cannot apply
  there. That argument is reasoning, not a measurement, and it is the kind this repository
  has been wrong about before.
- **`EmbeddedSubstitutionControlTest` adds a second Spring context to every CI run** —
  different configuration, so the context cache misses. Measured at 21.9 s **on this machine**;
  its share of CI's 65 s is 미측정, because a green run uploads no test-results artefact
  (§3.6). That is the price of the control and it is worth it, but it is a real cost paid on
  every build and nobody can currently say how large it is where it is paid.
- **The cost argument in §3.6 was overstated in its first draft** and is now a range spanning
  0.4% to 2% depending on the machine. It is still the weakest part of this report: the
  container-start figure was measured here and not on the runner, so the 2% is a local number
  divided by a remote one.
- **What would break the conclusion**: a PostgreSQL-compatible embedded database that does
  implement `on conflict` and transaction-abort semantics. The finding is about H2, not about
  in-memory databases as a category, and the title of this report is broader than its
  evidence.

## 9. 배운 것 / What I learned

**대조군이 없으면 참인 문장으로 거짓말을 하게 된다.**

정렬 결과가 양쪽 다 `Apple,Banana,apple,cherry`로 같게 나왔다. 여기서 멈췄으면 "두 데이터베이스는
텍스트를 같은 순서로 정렬한다"고 썼을 것이다. 한 글자도 틀리지 않은 문장이고, 완전히 잘못된
결론이다. 콜레이션을 명시했더니 PostgreSQL은 `apple,Apple,Banana,cherry`를 냈다. 같았던 게 아니라
**이 컨테이너가 언어적 정렬을 못 하는 것**이었다. 게다가 그건 H2에 대한 발견이 아니라 **이 저장소가
지금까지 낸 모든 숫자의 측정 환경에 대한 발견**이었다. 예측이 빗나갔을 때 "다행이네" 하고 넘어갔으면
못 찾았다.

**게이트는 통과할 때가 아니라, 통과할 수 없을 때 의미가 있다.**

`@DataJpaTest`가 PostgreSQL에 붙는 걸 확인하고 초록불을 켰다. 그런데 그 초록불은 두 가지 이유로
켜진다 — 뭔가가 막아주고 있거나, **바꿔치기할 임베디드 DB가 애초에 없거나.** 둘째 경우라면 그 게이트는
어떤 코드 위에서도 통과한다. 그래서 `replace = ANY`를 명시해서 "바꿔치기가 실제로 작동하는가"를
따로 못 박았다. R5의 로그 appender, R8의 통계 대조군에 이어 세 번째다. **이제 계측기든 게이트든,
제일 먼저 쓰는 코드는 "이게 죽어 있는지 확인하는 코드"다.**

**그리고 가장 큰 발견은 H2와 아무 상관이 없었다.**

한 컨텍스트 안에서 Flyway는 PostgreSQL에, Hibernate는 H2에 붙어 있었다. 증상은
`missing table [attempt]` 한 줄. 마이그레이션이 깨진 것처럼 보이지, 스택이 데이터베이스 두 개로
쪼개져 있다고는 절대 안 읽힌다. 나는 처음에 로그의 `Successfully applied 3 migrations`를 보고
"H2 기본 모드에서는 V1이 적용되는구나"라고 결론 낼 뻔했다. **로그가 성공했다고 말한 것과, 성공한
곳이 어디인지는 다른 질문이다.** 컨테이너에 직접 접속해서 테이블을 세어보기 전까지는 몰랐다.
