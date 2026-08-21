# R25. A risk written against every ordering number, and there are none

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: `0819a47` — **not the defect this report is about.** It is the state in
> which this report's own AIMED control refused its author, and §9 is why it is here rather
> than quietly fixed.
> **Green commit**: this one — the instrument with a control that is a control
> **Changes no application code and gates nothing.** It measures `R9` §8's first bullet, and
> §7 is the argument for why the gate is absent rather than a promise.
> **Found by**: `ADR-014`, entries `9.1` and `D.8`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 -- not Docker Desktop
  JVM            : Temurin 21.0.12+8. The Gradle test worker runs at -Xmx512m, read verbatim
                   off the worker command line rather than assumed -- see section 3.7
  PostgreSQL     : TWO, both pinned BY DIGEST AND NOT BY TAG. R27 is the reason.
    A  postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
       PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0)
       datcollate = en_US.utf8  datctype = en_US.utf8  datlocprovider = c
       -- the image every number in this repository was taken on
    B  postgres@sha256:e17e86066e5ef83e0952a9347f5c792b7ece00972e2aa787a6986f471b3dd3d5
       PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on x86_64-pc-linux-gnu, gcc (Debian 14.2.0-19)
       datcollate = en_US.utf8  datctype = en_US.utf8  datlocprovider = c
       -- postgres:16, the image R9 section 3.3 could not pull
  Dataset        : none. Every probe builds its own values; the ones that matter are
                   transcribed from seed/src/main/kotlin/net/gseek/proxima/seed/Generator.kt
                   with the line cited beside each
  Load           : none. No duration is claimed in this report. R26 measures the cost, on
                   one binary, because a duration taken on 16.14-on-musl against one on
                   16.15-on-glibc is measurement-discipline rule 3 with extra steps
  Repetitions    : ordering under a fixed collation is deterministic; one run per probe.
                   Section 8 says what that is worth and what it is not
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R9` §8's first bullet has been the widest-reaching risk in this repository since 2026-08-13:

> **This repository's own ordering numbers were taken on a byte-ordering database.** §3.3.
> `postgres:16-alpine` is musl-built and its declared `en_US.utf8` does not produce
> locale-aware collation. Every `order by` on text in every report here — and the production
> behaviour they imply — is unverified against a glibc or ICU-collated deployment. **The
> container tag is in every measurement environment block in this repository, and until now
> nobody had established what that tag decides.**

`TestcontainersConfiguration.kt`'s KDoc repeats it as settled fact: *"Every ordering-dependent
number in this repository was taken under the first behaviour."*

Two things about that bullet were never established, and they point in opposite directions.

**The mechanism was a hypothesis.** `R9` §3.3 says so in the same section — *"The mechanism
above is documented behaviour, not something measured here, and it is written as a hypothesis
rather than a result"* — and names its blocker: the Debian image was not present and WSL had
no network. That is `ADR-014` entry `D.8`.

**And the scope was never counted.** *"Every `order by` on text in every report here"* is a
quantifier over a set nobody had enumerated. `ADR-014` entry `9.1` is that half.

## 2. 재현 / Reproduction

```bash
cd /c/project/airtown/proxima-c
wsl -e bash -lc 'cd /mnt/c/project/airtown/proxima-c \
  && export JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8 \
  && ./gradlew :api:test --tests "net.gseek.proxima.collation.CollationDivergenceTest" \
       --no-daemon --console=plain -i'
```

Five probes in `CollationDivergenceTest`, two containers, no Spring context — Flyway and JDBC
directly, so an answer is attributable to a database rather than to autoconfiguration. The
corpus sweep in §3.6 is `git ls-files` and `grep`, quoted with its command.

## 3. 계측 / Measurement

### 3.1 Which two servers these are

| | A — musl | B — glibc |
| --- | --- | --- |
| `version()` | `PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit` | `PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit` |
| `datcollate` | `en_US.utf8` | `en_US.utf8` |
| `datctype` | `en_US.utf8` | `en_US.utf8` |
| `datlocprovider` | `c` | `c` |
| ICU collations installed | **908** | **871** |
| libc collations installed | 3 | 6 |
| collation of the `name` type | `C` | `C` |

**The two databases declare the same locale and the same provider.** Nothing in a
configuration dump distinguishes them, which is the whole difficulty: `datcollate =
en_US.utf8` is what both say, and one of them means it.

**The server versions differ — 16.14 against 16.15 — and that is a confound.** It is not an
oversight; there is no Debian build of 16.14 available under the tag, and §3.2's third row is
the control that removes it.

### 3.2 `R9` §3.3's four strings — the `미측정` this report exists to close

The same four values, in the same order, so the two reports are comparable at all:

| | result |
| --- | --- |
| A musl, `order by v` | `Apple,Banana,apple,cherry` |
| **B glibc, `order by v`** | **`apple,Apple,Banana,cherry`** |
| B glibc, `order by v collate "C"` | `Apple,Banana,apple,cherry` |

**`R9`'s hypothesis was right, and it is now a measurement.** A glibc PostgreSQL declaring
the same `en_US.utf8` orders these four strings differently, and the order it produces is
exactly what `R9` got out of the alpine image when it named `en-US-x-icu` explicitly.

**The third row is the control that makes the first two mean what they say.** Told to use
`C`, the glibc server reproduces the musl server's order character for character. So the
difference between arms A and B is the collation and not the patch level, not the compiler,
and not the two images' defaults — because within one binary, changing only the collation
reproduces the other binary's answer.

### 3.3 How far apart the two collations are

`R9` measured four strings. Four strings can only say *"they differ"*. This asks how much of
the character set is re-ranked, which is the number a reader needs to decide whether their own
data is exposed:

| | |
| --- | --- |
| two-character strings compared | **4,465** — every ordered pair over printable ASCII |
| positions at which the two orders differ | **4,461** |
| first differing position | 3 |
| single printable-ASCII characters re-ranked | **90 of 95** |

```
printable ASCII, A musl  :  !"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~
printable ASCII, B glibc :  !"#%&'()*+,-./:;<=>?@[\]^_`{|}~$0123456789aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ
```

The two are not variations on one order. **Byte order interleaves punctuation with digits and
letters; the locale-aware order puts punctuation first, then `$`, then digits, then letters
with the two cases adjacent.** 99.91 % of pairs land somewhere else.

That is a much stronger statement than `R9`'s bullet made, and it is the half of this report
that makes the other half surprising.

### 3.4 The five text columns this schema actually holds

`V1__baseline.sql` has five columns that hold text. The value shapes are transcribed from
`Generator.kt` with the line cited beside each, because `:api` does not depend on `:seed` and
`PUB-7` is the reason for the module split.

| column | values | positions displaced |
| --- | --- | --- |
| `learner.external_ref` — `learner-000001` | 13 | **0** |
| `concept.code` — `concept-000001` | 13 | **0** |
| `concept.name` — `Concept 000001` | 13 | **0** |
| **`concept.grade_band`** — the complete set of five | 5 | **2** |
| `item.code` — `item-000001` | 13 | **0** |

**One column of five, and it is the one whose values are not fixed-width:**

```
A musl : G1-2,G10-12,G3-4,G5-6,G7-9
B glibc: G10-12,G1-2,G3-4,G5-6,G7-9
```

Byte-wise, `-` (0x2D) precedes `0` (0x30), so `G1-2` sorts before `G10-12`. Under the
locale-aware collation the hyphen carries no primary weight, so the comparison is `G12`
against `G1012` and `0` beats `2`. The four identifier columns are immune for a reason that
is a property of the generator rather than of the schema: **every value is the same width, so
the digits decide before any punctuation can.**

**The AIMED control, which is in this section because it belongs to this counter:**

```
AIMED control, planted case flip    : A=Item-000001,Item-000002,item-000001,item-000002
                                      B=item-000001,Item-000001,item-000002,Item-000002
                                      displaced=4
```

The counter reports 4 of 4 on a set whose divergence is already established, so **"0" above
is a fact about those values and not about the routine.** §9 is about what that control cost
before it worked.

### 3.5 The two text orderings that exist in this repository's tests

`BaselineMigrationTest` is the only tracked artefact outside `R9`'s own probe that orders by
text. Both of its orderings, run against both images after `V1`→`V3`:

| | A musl | B glibc | identical |
| --- | --- | --- | --- |
| `order by table_name` | `[attempt, concept, concept_edge, flyway_schema_history, item, item_concept, learner, mastery]` | the same | **yes** |
| `order by i.indexname` | 15 index names | the same | **yes** |

And the mechanism, asked of the server rather than assumed:

```
B glibc | pg_indexes.indexname collation: C
```

**PostgreSQL's `name` type carries its own collation, and it is `C`.** The two catalog
orderings are therefore invariant under the database's collation by the type system, not by
luck and not because the names happen not to collide. A deployment on glibc changes nothing
about what `BaselineMigrationTest` reads.

### 3.6 Every `order by` in the tree, and what each one orders

The quantifier in `R9` §8's bullet, enumerated. **At `a417ce3`**, the base this round started
from, so the set is the one the risk was written about and not one this report added to:

```bash
for f in $(git ls-tree -r --name-only a417ce3 | grep -E '\.(kt|kts|sql|js)$'); do
  git show a417ce3:"$f" | grep -niE "order by" | sed "s|^|$f:|"
done
```

**Thirteen occurrences. Nine are SQL clauses and four are prose** — a comment in
`V2__attempt_learner_time_index.sql`, two `Probe` labels in `H2DivergenceTest.kt`, and a KDoc
line in `StaleStatisticsSkewTest.kt` quoting `R3` §3.5's query. The nine:

| # | where | expression | column type | collation-dependent |
| --- | --- | --- | --- | --- |
| 1 | `RecommendationQueries.kt:58` | `order by i.difficulty, i.id` | `smallint`, `bigint` | **no** |
| 2 | `RecommendationQueries.kt:116` | `order by i.difficulty, i.id` | `smallint`, `bigint` | **no** |
| 3 | `BaselineMigrationTest.kt:46` | `order by table_name` | `name` | **no — §3.5** |
| 4 | `BaselineMigrationTest.kt:70` | `order by installed_rank` | `integer` | **no** |
| 5 | `BaselineMigrationTest.kt:100` | `order by i.indexname` | `name` | **no — §3.5** |
| 6 | `MigrationDeduplicationTest.kt:135` | `order by id` | `bigint` | **no** |
| 7 | `PopulatedMigrationTest.kt:131` | `order by m.id` | `bigint` | **no** |
| 8 | `H2DivergenceTest.kt:295` | `order by label` | `varchar` | **yes — `R9` §3.3's probe** |
| 9 | `H2DivergenceTest.kt:306` | `order by label collate "en-US-x-icu"` | `varchar` | **yes — and it names a collation, which is the control that found the risk** |

**Nine clauses. Four are on text: two on PostgreSQL's `name` type, which §3.5 shows is
`C`-collated by the type system, and two on `varchar` — and both of the `varchar` ones are
`R9` §3.3's own probe, the second of them existing specifically to name a collation
explicitly.**

**So the set `R9` §8's quantifier ranges over — an `order by` on a `varchar` column that
somebody would deploy — is empty.** The application's single ordered query, the recommendation
read that `R4`, `R8`, `R16` and `R18` all measure, orders by `smallint, bigint`; every latency
figure in `README.md`'s results table comes from it.

> **This table was corrected before this report's own verification run finished, and what it
> got wrong is worth more than what it says.** The first version merged rows 8 and 9 into one,
> silently included the `V2` comment as a tenth row to keep the total at nine, and concluded
> *"two are on text, both on the `name` type"* — which is false, and false in the direction
> that flattered the finding. The total *nine* survived only because two errors cancelled.
>
> What caught it was re-running the sweep against `a417ce3` and counting the output instead of
> reading the table again. That is the discipline `ADR-014` §*The corpus* states about `R19`'s
> 145 — *a count carried forward is a claim nobody re-establishes* — and this report, which
> came out of `ADR-014`, did not apply it to its own table for two commits.

### 3.7 Two things read off this run that belong to other documents

**The Gradle test worker runs at `-Xmx512m`**, verbatim from the worker command line:

```
Starting process 'Gradle Test Executor 1'. … Command: /home/airto/.jdks/jdk-21.0.12+8/bin/java
  -Dorg.gradle.internal.worker.tmpdir=… -Dspring.profiles.active=test @… -Xmx512m
  -Dfile.encoding=UTF-8 -Duser.country -Duser.language=en -Duser.variant -ea …
```

`measurement-discipline.md` says of that same flag: *"It said `-Xmx512m`. Nothing sets that.
The heap flag is 미측정 as a property of these runs."* That is `ADR-014` entry `D.1`. It is
now measured **for the test lane and only the test lane** — Gradle's own default worker heap,
not anything this repository configured. **The `bootRun` lane every load number came from is
still 미측정** and is a different JVM. The document is not this slice's to edit; the number is
here so whoever edits it does not have to re-take it.

**`lc_collate` stopped being a settable parameter in PostgreSQL 16.** A probe asking for it
gets `unrecognized configuration parameter "lc_collate"` on both images, which is recorded
because it is the second time in this report that *"the same server, one patch level apart"*
turned out to be a claim rather than a default.

## 4. 원인 / Mechanism

**Two layers, and `R9` named the first correctly.**

PostgreSQL delegates collation to the C library when `datlocprovider = c`. musl implements
`strcoll` as `strcmp` — byte comparison — so a database created with `LC_COLLATE=en_US.utf8`
on an Alpine image gets byte order, and there is no error, no warning, and no field in any
catalog view that distinguishes it from a locale-aware one. glibc implements `strcoll`
against the ISO 14651 table, where punctuation is ignored at the primary level and case
separates only at the tertiary level. §3.2's third row is what pins the attribution.

**The second layer is the one that decides the scope, and it is a fact about types rather
than about locales.** PostgreSQL gives the `name` type — the type of every identifier in
`pg_catalog`, and the base of `information_schema.sql_identifier` — a fixed `C` collation, so
catalog listings do not move when a database's collation does. That is why §3.5 comes out
identical on two servers that disagree about 4,461 of 4,465 pairs.

**And the third is why this repository's own data is nearly immune.** Collation only decides
an ordering when two values differ first at a character whose *class* differs — punctuation
against digit, upper against lower. `Generator.kt`'s `ref()` emits `kind-` followed by
`padStart(6, '0')`, so the hyphen is at the same offset in every value of a column and the
comparison is decided by digits, which both collations rank identically. `GRADE_BANDS` is the
one set in the generator that is not fixed-width, and it is the one that diverges.

## 5. 처방 / Remedy

**Nothing in the application changes, and this section is about what to do with the risk.**

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave `R9` §8's bullet as it stands | a standing risk against *"every ordering number"* whose subject is empty. Every future report inherits a caveat that is not true of it | none | |
| Delete the bullet | erases the finding along with the overstatement. The mechanism is real and §3.3 makes it more real, not less | none | |
| Pin `lc_collate = C` explicitly in the container | makes the current behaviour deliberate instead of accidental — and **hides the divergence from any future measurement that would want it**, which is `ADR-005`'s argument against a cache in a different domain | small | |
| **Correct the scope in a new report, link it from `R9`, and leave `R9`'s body alone** | the mechanism is kept and strengthened; the quantifier is replaced by an enumeration | this report | **✔** |

**Why not the third option even though it is tidy.** A byte-ordering test database is not a
defect here — it is the condition under which every published number was taken, and making it
explicit would be an improvement to reproducibility. But `TestcontainersConfiguration.kt`
belongs to another slice this round, and more importantly `R26` measures that a locale-aware
collation is **2.66× slower on the same sort** — so pinning `C` is a performance decision
disguised as a hygiene one, and it should be made where performance decisions are made.

**What would have made a different option correct.** If a single `order by` on a `varchar`
column had existed anywhere in the application, the answer would have been to measure that
query on both images and re-baseline it, not to correct a scope.

## 6. 재계측 / Re-measurement

Not applicable. Nothing in the application changed; what changed is what the repository knows
about the risk it was carrying.

The one thing that was re-measured is `R9` §3.3's own probe, and it reproduces exactly:
`Apple,Banana,apple,cherry` on arm A, eight days and one report later.

## 7. 회귀 게이트 / Regression gate

**Nothing turns red if somebody adds an `order by` on a `varchar` column tomorrow, and that
is stated rather than promised.**

What exists:

- `api/src/test/kotlin/net/gseek/proxima/collation/CollationDivergenceTest.kt` — the two
  controls. **ALIVE** turns red if arm B stops producing a locale-aware order, which is what
  would happen if the digest were changed to another musl build or the comparison stopped
  reaching a database. **AIMED** turns red if the displacement counter goes blind, which is
  what would make §3.4's four zeroes meaningless. Neither is a gate on the finding; they are
  gates on the instrument.

What does not exist, named so it is a task rather than a caveat: **a structural rule that
fails when an `order by` in tracked source names a column declared `varchar`, `text` or
`char` in `V1__baseline.sql`.** It would be in the `T3` style — it reads structure, not prose,
so it is not the check `R17` §5 discarded — and it is what keeps §3.6's enumeration true.

It was not written here for the reason `AGENTS.md` §Scope gives: this slice's item is the
measurement, and a guard is a separate decision with its own cost. **`R19` §3.3's line puts it
on the *work* side rather than the *judgement* side** — it would protect source that exists,
namely the enumeration in §3.6 — so it is a task and it is recorded in `ADR-014` as one.

## 8. 남는 위험 / Remaining risk

- **`R9` §8's first bullet is corrected in scope and not in substance, and the correction is
  the narrower claim.** The mechanism holds and §3.3 makes it larger than `R9` stated. What is
  withdrawn is the quantifier: **there is no `order by` on a `varchar` column in this
  repository outside the probe that raised the risk**, and the two catalog orderings that do
  exist are `C`-collated by the type system. Annotated in `R9` §8 beside the bullet.
- **`concept.grade_band` diverges and nothing reads it in an order.** No query in the
  application, no migration, and no test orders, ranges over, or compares that column with
  anything but equality. **The moment one does, its result depends on the image**, and
  nothing would say so.
- **Two servers, one patch level apart.** §3.1. The within-image `collate "C"` control removes
  the confound for the ordering question and removes it for nothing else: any other difference
  between arms A and B in this report is **미측정** as to whether it is libc or 16.14→16.15.
- **One run per probe, and no repetition.** Ordering under a fixed collation is deterministic,
  which is why this is defensible and not why it is complete: a probe that connected to the
  wrong container would be deterministic too. The ALIVE control is what stands between this
  report and that.
- **The value shapes in §3.4 are transcribed from `Generator.kt`, not generated by it.**
  `:api` does not depend on `:seed`. If the generator's identifier format changes, this table
  goes stale and **nothing checks it** — the same class of defect `R17` is about, introduced
  by this report, in a table whose whole point is that the format decides the answer.
- **Only ASCII.** §3.3 covers printable ASCII because that is what `V1__baseline.sql`'s
  columns hold under this generator. What either collation does with the rest of UTF-8 —
  Hangul, combining marks, anything where glibc and ICU famously disagree — is **미측정**, and
  this domain's real data would be full of it.
- **`TestcontainersConfiguration.kt`'s KDoc still says *"Every ordering-dependent number in
  this repository was taken under the first behaviour"***, which this report narrows. It is
  another slice's file this round and **is not corrected here.** That sentence is an instance
  of the hole `R17` §8's last bullet names — *a claim moved out of prose into a code comment
  leaves the corpus entirely* — and `R19` §8 records that nothing has ever swept KDoc. This
  report adds a second instance of it, knowingly, and hands it over rather than reaching into
  a file it does not own.
- **This report's own three test classes cost CI time, and the two figures for it differ by
  an order of magnitude.** Run alone: `CollationDivergenceTest` 4.170 s, `CollationCostTest`
  3.233 s, `ImageTagDriftTest` **52.910 s** — the last absorbing Docker and Ryuk
  initialisation because it ran first. Inside the whole suite, where that initialisation is
  paid once by whatever runs first: **3.664 s, 2.411 s and 3.359 s.** The second set is the
  one that describes the marginal cost and the first is the one that would be quoted by
  accident. **Five containers are started across the three.** Their share of a CI run is
  **미측정** for the reason `R9` §8 gives: a green run uploads no test-results artefact.
- **What would break this conclusion**: an `order by`, a `<`/`>`, a `between`, a `like`, or a
  `min`/`max` on any `varchar` column reaching the application. §3.6 is a snapshot of one
  tree, and the parallel work on a concept graph in this same round is exactly the kind of
  change that would add an ordered read.
- **No bullet here needs a judgement rather than work**, so none of them is a row in
  `docs/decisions/open.md`. The gate in §7 is the closest, and `R19` §3.3's line puts it on
  the work side: it would protect source that exists.

## 9. 배운 것 / What I learned

**대조군이라고 적어놓은 게 사실은 가설이었다.**

이 계측기에는 대조군을 두 개 심었다. R0 §9가 그러라고 했기 때문이다 — 하나는 계측기가 살아 있는지,
하나는 내가 옳은 곳을 겨누고 있는지. 살아 있는지 보는 쪽(ALIVE)은 통과했다. 겨누는 곳을 보는
쪽(AIMED)은 **첫 실행에서 빨간불이 났고, 잡힌 건 나였다.**

내가 심은 건 `learner-1`과 `learner-000001`이었다. "고정폭 식별자에서 하이픈 위치가 어긋나면
콜레이션에 민감해진다"는 게 근거였는데, 그건 근거가 아니라 **추측**이었다. 두 이미지 모두 같은 순서를
냈다 — glibc 쪽에서 하이픈은 1차 가중치가 없으니 결국 숫자가 결정하고, C 쪽에서도 숫자가 결정한다.
그러니까 나는 "이 루틴이 차이를 볼 수 있다"를 증명하려고, **차이가 없는 집합을 골라놓고** 있었다.

여기서 배운 건 대조군 자체가 아니다. **대조군 자리에 가설을 써넣으면, 실행하기 전까지는 대조군처럼
읽힌다는 것.** 초록불이었다면 나는 "5개 중 0개"를 그대로 리포트에 썼을 것이고, 그 0이 데이터의
성질인지 계측기의 침묵인지 구분할 방법은 없었을 것이다. R10에서 카나리가 통과한 채로 결론이 틀렸던
것과 방향만 반대인, 같은 실패다.

고친 대조군은 **이미 측정된 메커니즘**을 심는다 — 대소문자. ALIVE 대조군이 바로 위에서 이 두
이미지에 대해 재놓은 그 성질이다. 대조군은 새로운 사실을 주장하면 안 되고, 이미 아는 사실 위에
계측기를 얹어봐야 한다. 그게 대조군과 실험의 차이다.

**그리고 정작 큰 발견은 반대편에 있었다.**

두 콜레이션은 4,465쌍 중 4,461쌍에서 다르게 정렬한다. 99.91 %다. 그런데 이 저장소에서 `varchar`를
정렬하는 절은 **R9가 이 위험을 제기하려고 만든 프로브 두 개가 전부**다 — 하나는 기본 콜레이션,
하나는 콜레이션을 명시한 대조군. 애플리케이션의 유일한 `order by`는 `smallint, bigint`고, 테스트의
나머지 텍스트 정렬 두 개는 `name` 타입이라 타입 시스템이 이미 `C`로 고정해 놨다.

R9 §8은 *"every `order by` on text in every report here"*라고 썼다. 그 한정사가 가리키는 집합의
크기를 아무도 세어본 적이 없었고, 세어보니 **0**이었다. 메커니즘은 맞았고 범위는 틀렸다 —
`ADR-014`가 하는 일이 정확히 이거다. **`미측정`은 세어보기 전까지 크기를 모르는 채로 인용된다.**

**그리고 §3.6의 그 표를, 나는 처음에 틀리게 셌다.**

`ADR-014`를 쓰면서 나는 R19의 145를 그대로 옮겨 적지 않고 `b1c1b95`에서 다시 세어봤다. 맞았다.
그래놓고 **이 리포트의 표는 grep 출력을 한 번 훑고 손으로 옮겨 적었다.** 결과: 8번과 9번을 한 행으로
합치고, `V2` 주석을 행으로 끼워 넣고, *"텍스트 정렬은 두 개, 둘 다 `name` 타입"*이라고 썼다. 총계
아홉은 **오차 두 개가 서로 상쇄돼서** 살아남았다.

잡힌 건 검증 실행을 기다리는 동안 `a417ce3`에 대고 sweep을 다시 돌려서 출력 줄 수를 세어봤을 때다.
표를 다시 읽었으면 못 잡았다. **셈을 다시 세는 것과 셈을 다시 읽는 것은 다르다** — 그게
`ADR-014`가 R19의 145에 대해 한 말인데, 그 ADR에서 나온 리포트가 자기 표에는 두 커밋 동안 적용하지
않았다.
