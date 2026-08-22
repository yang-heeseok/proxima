# Round 3 · Slice G — where language basics meet the framework

> **Slice**: G · **Branch**: `round3/basics` · **Base**: `99d558b` on `round3/recency`
> **Written**: 2026-08-22, as the work happened rather than after it.

> ⚠️ **One number is still outstanding and it is named as such in §3**: the slice-level test count
> on the **final** tree. Every trap number is measured and final. ⛔ Nothing here is an estimate.

---

## 1. WHAT I OPENED

| Trap | Verdict |
| --- | --- |
| **G1** — an entity's identity changes the moment it is persisted | **REPRODUCED** on an instrument; **NOT-REPRODUCED** against application code — *and the split is not where I expected it*, see below |
| **G2** — a transaction does not roll back on every exception | **REPRODUCED**, red `94fe9ee`, green `022675b` |
| **G3** — an `ORDER BY` with no tie-break loses rows across pages | **REPRODUCED** on an instrument; **NOT-REPRODUCED** against application code |
| **G4** — `Optional`: two ways to handle absence, with different costs | **REPRODUCED** on an instrument; **NOT-REPRODUCED** against application code |

⭐ **G1's verdict moved during the slice and that is the slice's best finding.** I expected
"structurally shut": `BaseEntity` compares by `id`, hashes constant per type, and an ArchUnit rule
refuses the `data class` shape. The hash half **is** shut — measured. The other half is not.

> **`Hibernate.getClass` initialises the proxy it unwraps, and `BaseEntity.equals` calls it on
> both operands.** So the equality this repository *chose* issues a statement per uninitialised
> proxy handed to it. Nothing in the tree said so, and I asserted the opposite before measuring it.

That is a finding about **shipped code**, reached by writing an exact assertion that turned out to
be wrong. `R39` §1 and §3.2.

**Three of four traps have no red commit against application code, and that is a finding rather
than a thin slice.** What closes each is named and measured, not assumed:

- **G1** — `BaseEntity` + `ENTITIES_ARE_NOT_DATA_CLASSES`, the latter watched refusing a plant.
- **G3** — the §2 sweep: **no reachable paged ordering on a non-unique key in `api/src/main`.**
- **G4** — absence: `main` holds **two** `Optional` consumers, both the no-argument
  `orElseThrow()`. There is no `orElse(…)` at all, and the reason is the language rather than
  vigilance — Kotlin's elvis right-hand side is not an argument.

**G2 is the one that was open**, and `docs/roadmap.md:99` says so in the tree's own words: *"the
swallowed exception / rollback-only case is **not done at all**."*

---

## 2. COMMITS

### Numbers I took, and why

Derived on this base before the first commit, with the commands `§0` specifies:

```
docs/reports/R*.md            ceiling  R45
docs/decisions/adr/ADR-*.md   ceiling  ADR-021
db/migration/V*.sql           ceiling  V5
```

| | Assigned | **Taken** | Why |
| --- | --- | --- | --- |
| Reports | `R39`–`R42` | **`R39`–`R42`** | free on this base — the ceiling is `R45` but `R29`–`R42` is an unused gap, because slice H took its share from above |
| ADR | `ADR-020` | **`ADR-020`** | free — `ADR-018`–`ADR-020` unused |
| `open.md` | not assigned | **`OPEN-13`** | ceiling was `OPEN-12`; §7 explains why the row is here rather than in the ledger |
| Migrations | none permitted | **none taken** | ceiling stays `V5`; `db/migration` is untouched |

⭐ **No shift was needed and none was made.** G is numbered *below* the base it descends from,
deliberately: H took `R43`–`R45` from above the gap so G's assigned range stayed free. ⛔ I did not
tidy the range upward — that would consume four numbers nobody assigned me and leave a second gap.

### Commits

| Trap | Commit | What it is |
| --- | --- | --- |
| G1 | `260dcc2` | instrument — five entity shapes, three equality implementations |
| G2 | **`94fe9ee`** | **red** — the shipped batch path from a transactional caller. **Watched failing** |
| G3 · G4 | `85943b0` | instruments — the tie-break walk, `44.3`'s plan question, the eager fallback |
| G1 | `ae2b2da` | the refuted prediction, attributed per operation; G3's mechanism probe |
| G2 | **`022675b`** | **green** — `AttemptRecorder.record` is `REQUIRES_NEW` |
| — | `0f70d7f` `9046476` `dec730e` `9d98670` `f694bbb` `cce6bcd` `7f6c6e9` `9691a6a` `136142f` `9647758` `6edba90` `4bfa268` | documents |

**The red was observed, not merely written to fail.** Verbatim from `94fe9ee`:

```
4 valid recordings were attempted and 0 survived. [...] Raised: UnexpectedRollbackException
  ==> expected: <4> but was: <0>
```

### G3 — the `order by` and `Pageable` sweep, re-run at my own SHA

⚠️ **Re-run rather than inherited.** The pack supplied this table as a reading of `5ac5fd5` and
told me not to paste it. It agrees with mine, which is worth stating *because* it agrees.

**How the matcher was excluded.** A naive `grep -i "order by"` over `api/src/main` reports
**8 lines**. Three are prose *about* ordering inside KDoc and one is a SQL `--` comment — they
execute nothing. Counting them would have inflated the population by 60%. Comment lines are
excluded by their leading `*`, `//` or `--`, leaving **5 real orderings**:

| Site | Ordering | Paged? | Sort key unique? |
| --- | --- | --- | --- |
| `PrerequisiteQueries.kt:64` | `order by e.prerequisite_id` | no | ✔ |
| `PrerequisiteQueries.kt:104` | `order by min(w.depth), w.prerequisite_id` | no | ✔ |
| `RecommendationQueries.kt:66` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:124` | `order by i.difficulty, i.id` | `limit` only | ✔ |
| `RecommendationQueries.kt:158` | `order by a.attempted_at desc, a.id desc` | `limit` only | ✔ — **slice H's** |
| `LearnerPageQueries` × 4 | none in the JPQL | the only `Pageable` in `main` | **nothing in `main` calls it** — the two `main` files naming it do so in *prose* |

⭐ **Conclusion: no reachable paged ordering on a non-unique key exists in `api/src/main`.**

⭐ **`RecommendationQueries.kt:158` is a different defect and saying so is part of G3.** A top-`n`
read on a non-unique sort makes the **boundary row** wobble; `LIMIT`/`OFFSET` paging makes rows
**repeat and vanish**. Same cause, different blast radius, only one is data loss. ⛔ I did not
re-measure `recentOutcomesByCount` — `R44` §3 owns it.

---

## 3. NUMBERS

### Measurement environment

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8   (read from the run, not from a document)
  PostgreSQL     : Testcontainers postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767
                   934dd0a95e671f9a0fc20685 — server 16.15 on x86_64-pc-linux-musl
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counters       : Hibernate Statistics.prepareStatementCount (R8's instrument);
                   committed row counts read OUTSIDE the writing transaction;
                   Method.getExceptionTypes and javap -p for the signature comparison;
                   plain EXPLAIN — never EXPLAIN ANALYZE — for every plan
  Load           : none. Every number in this slice is a count or a plan shape
  Concurrently   : SLICES D AND E WERE ACTIVE. These are counts and plan shapes —
                   logical facts about code and data — and they do not contend. The
                   disclosure is made because the rule is about disclosure, not
                   about susceptibility
```

⚠️ **The PostgreSQL line disagrees with `docs/explanation/measurement-discipline.md` and this
document is the correct one.** It was read from `TestcontainersConfiguration.kt:72` and from
Flyway's own startup line — `Database: jdbc:postgresql://localhost:32820/test (PostgreSQL 16.15)`
— never from that document. §8 carries the correction; ⛔ I did not edit the file.

### The traps — every figure final

**G1 — `R39`.** Statement counts on a standalone `SessionFactory`.

| | |
| --- | ---: |
| `data class` entity: hash moved on persist / still findable in its `HashSet` | **moved / NOT findable** |
| `BaseEntity` shape: hash moved / still findable | **did not move / findable** |
| `proxy.javaClass` · `proxy is EqParent` · `proxy.id` | **0 · 0 · 0** |
| **`Hibernate.getClass(proxy)`** | **1** ⟵ refuted the prediction of 0 |
| `proxy.label` — **the control**, must initialise | **1** |
| one `==`, shipped shape, both operands loaded | `true`, **0** |
| one `==`, `data class`, 2 lazy associations | `true`, **4** |
| one `==`, `data class`, associated class without `equals` | **`false`**, **0** |

**G2 — `R40`.** Committed rows after one write and one failure.

| Arm | Committed rows |
| --- | ---: |
| control, no failure | 1 |
| `RuntimeException` · `Error` | **0** · **0** |
| **checked `IOException`, Kotlin and Java alike** | **1** ⟵ the defect |
| `rollbackFor = Exception`, both languages | **0** |
| declared checked exceptions: Java `writeThenThrowChecked` / Kotlin `writeThenThrow` | **`[IOException]`** / **`[]`** |
| swallowed inner failure: outer's own write survived | **0** — and `UnexpectedRollbackException` |
| `REQUIRES_NEW` inner: outer's write survived | **1** |
| **shipped batch, no outer transaction** | **4** of 4 valid |
| **shipped batch, from a `@Transactional` caller** | **0** of 4 valid |

**G3 — `R41`.** 100 rows, 4 tied groups of 25, page size 10.

| Walk | Twice | Never |
| --- | ---: | ---: |
| `order by grp`, **one plan, nothing touched** | **1** | **1** |
| `order by grp`, planner toggled between pages | 0 | 0 |
| `order by grp, id` | 0 | 0 |
| keyset `(grp, id) > (…)` | 0 | 0 |

⭐ **The defect appeared in the arm built as the control and not in the arm built to force it**,
and §3.2 of `R41` measures why: the scan flips from **Index Scan at offsets 0–20 to Seq Scan at
30–90**, in a walk that changed nothing. `OFFSET` is part of the plan.

**`ADR-014` row `44.3`, on the real table and `V2`'s real index:**

| | scan node | extra node |
| --- | --- | --- |
| `order by attempted_at desc` | `Index Scan Backward using ix_attempt_learner_attempted_at` | — |
| as shipped, `+ id desc` | **the same node, same estimate `0.29..772.72`** | `Incremental Sort`, presorted on `attempted_at` |

⭐ **`V2`'s index still serves it.** ⚠️ Plan **shape** answered; plan **cost in time** 미측정.

**G4 — `R42`.** `orElse` present: **2 statements / 1 fallback call.** `orElseGet` present:
**1 / 0.** Kotlin elvis present: **1 / 0.** Both absent arms: **2 / 1** — the control that makes
it laziness rather than overhead.

### Slice-level test counts, and which tree each came from

⚠️ **This is the one figure still owed.** Three readings exist and they are **not**
interchangeable:

| Reading | Tree | `:api:test` | `:seed:test` |
| --- | --- | --- | --- |
| baseline | base `99d558b`, before any commit of mine | 125 tests / 48 classes | 15 / 5 |
| mid-slice | after the instruments, **before** `ae2b2da` and `022675b` | 138 tests / 53 classes, **2 failures** | 15 / 5 |
| **final tree** | `4bfa268` | **PENDING — a full run is owed** | **PENDING** |

⭐ **The baseline is corroborated, not merely remembered.** The orchestrator counted 48 / 125 / 0
from the same XML independently before it was overwritten. The `:seed:test` XML survives on disk.

⛔ **The mid-slice reading's 2 failures were both mine and both intended-or-informative**: the G2
red, and the refuted `Hibernate.getClass` prediction. Neither was a flake — both were deterministic
`AssertionFailedError`s with expected/actual values, checked for timeout and connection signatures
before being believed.

### What may not be compared

- **The baseline run's wall time was 11m54s. It is not a number, may not be cited, and appears in
  no table with any other duration.** Taken with D and E loading the same eight cores. Recorded
  only so nobody later finds it in a log and treats it as a measurement.
- **No duration of any kind was taken in this slice.** Where a question needed one I did not take
  it — `44.3`'s cost half, `REQUIRES_NEW`'s throughput, and `R42`'s statement-in-milliseconds are
  all 미측정 rather than estimated.
- **The connection figures in `R40` §8 are D's and E's, not mine.** They are attributed in place
  and never combined arithmetically with anything of mine.

---

## 4. REPORTS WRITTEN

| Report | Title | §8 non-empty? |
| --- | --- | --- |
| `R39` | *What one equality check costs* | ✔ 9 bullets |
| `R40` | *The annotation is applied, the proxy is crossed, and the row is still there* | ✔ 9 bullets |
| `R41` | *The rows that came back twice, and the rows that never came back* | ✔ 8 bullets |
| `R42` | *The fallback that runs when it is not needed* | ✔ 7 bullets |
| `ADR-020` | *A unit of work stays a unit of work when it is not the outermost one* | Accepted |
| `OPEN-13` | *Must the pool report its own exhaustion before the recording path gets a transactional caller?* | Open |

**CHECK 4 verified locally**: §8 line counts 21 / 22 / 19 / 14 against a floor of 3.

---

## 5. GATES AND CI

| Workflow | Changed by me? | State |
| --- | --- | --- |
| `.github/workflows/build.yml` | **no** | the completion signal — full run owed |
| `.github/workflows/docs-consistency.yml` | **no** | **expected-red at CHECK 3** |
| `.github/workflows/secret-scan.yml` | **no** | untouched by this slice |
| `.github/workflows/no-learner-data.yml` | **no** | untouched by this slice |
| `.github/workflows/load-harness.yml` | **no** | not exercised |

⭐ **I changed no workflow file.**

### `docs consistency` is expected-red, and it was red before I arrived

CHECK 3 wants a `docs/roadmap.md` row per report and **I am forbidden to touch that file.**
Measured by running CHECK 3's own loop on both branches:

```
round3/basics    FAIL — R39 R40 R41 R42 R43 R44 R45     (seven)
round3/recency   FAIL — R43 R44 R45                     (three, at my base 99d558b)
```

⛔ **Review this branch by diff, not by CI colour.** `docs consistency` was failing before my first
commit and will be failing after my last, for a reason neither touches. **Slice F owes seven rows,
not four.**

### The other four checks were verified against my documents rather than assumed

| Check | Result |
| --- | --- |
| CHECK 1 — every named artefact exists | **OK** — ran its exact loop over my documents; every token resolves |
| CHECK 2 — `Updated` matches the last substantive change | **OK** — everything dated and committed 2026-08-22 |
| CHECK 4 — §8 non-empty | **OK** — 21 / 22 / 19 / 14 against a floor of 3 |
| CHECK 5 — no comment denies an index a migration creates | **OK, by absence rather than by luck** |

⚠️ **CHECK 5 reads KDoc only.** 172 of this tree's 552 comment blocks are outside its corpus.
⛔ A green CHECK 5 must not be read as "no comment in this tree is false" — including for the KDoc
I added, which is substantial. **My sources pass because they make no index claim at all**, which
is not the same as an axis having verified the claims they do make.

⭐ **That is `R43` §3.5's lesson from the other direction.** There, a green was **vacuous because
the input was empty** — and that has a guard, which `R43` shipped. Here a green is **thin because
the subject is empty**, and **no guard is possible**, because no workflow can check whether the
thing it looks for was ever going to be present. Ledger `40.2`, class **(c)**.

### ⚠️ I scoped a regression check by package name, and the package name was an accident

Recorded because the orchestrator caught it, not me, and because the reasoning looked sound.

After `022675b` I scoped a targeted regression check as *the change is in `recording`, so run
`net.gseek.proxima.recording.*`*. **`ConnectionHoldingGateTest` is `R4`'s gate, it exists to
notice a connection held across a boundary, `022675b` changes how many are held at once — and it
lives in `recommendation`, so my filter could not see it.** `QueryCountTest` lives in `perf` and
was missed the same way.

⛔ **Choosing test scope by package is choosing it by an accident of layout.** The unit is *what
the change can affect*. Resolved by not relying on the filter: the closing number is a full run.

### New tables are a test fixture, NOT a migration

⭐ **An integrator reading "new tables" will look for a `V6`. There is none.** They are created
test-side — one `create schema` over JDBC plus `hbm2ddl` on a standalone `SessionFactory`, into
`g_basics` and `g_paging`, both dropped in `@AfterAll`. `db/migration` is unchanged at `V1`…`V5`.

Verified by reading all three consumers of the real schema rather than assuming:

| Test | What it reads | Can it see the fixtures? |
| --- | --- | --- |
| `BaselineMigrationTest` | `information_schema.tables` where `table_schema = 'public'` | **no** |
| `CollationDivergenceTest` | same filter, and it starts its own containers | **no** |
| `PopulatedMigrationTest` | enumerates no tables at all | **no** |

---

## 6. WHAT I DID NOT DO

⭐ **Silent reduction is the worst failure in this repository, so everything dropped is named.**

- **`ADR-014` rows `45.1` and `45.2` — declined deliberately.** They audit the ArchUnit rule set
  next to G2 and neither is G's. Taking them would repeat the H3-for-`R45` trade **without the
  reason that justified it** — H3's budget was already spent by structural absence; G2's was not.
  They stay open and unclaimed.
- **`44.3` taken in half only.** Plan shape measured; plan cost in time **미측정**. ⛔ Recorded as
  *corrected and half-answered*, not closed — `43.3`'s precedent.
- **`recentOutcomesByCount` was not re-measured.** `R44` §3 owns the specific case.
- **No collation measurement.** `R25`/`R26` closed that axis. `R41`'s sort column is an integer
  specifically so the report could not drift into it.
- **`rollbackFor` was not applied anywhere in production.** Nothing in `main` raises a checked
  exception from a `@Transactional` method; applying it everywhere would defend against a shape
  this codebase does not have. `R40` §5 says so rather than shipping it for tidiness.
- **No ArchUnit rule for the checked-exception half**, because §3.2 measured that one cannot be
  built in Kotlin. `R40` §7.
- **No gate for `R42`.** One statement per call on a path that does not exist. `R42` §7 gives the
  reason and §8 records it as an omission.
- **No `.study/` chapter.** Round-three's Korean chapters are slice F's.
- **No timing lock requested and no duration taken.**
- **I did not fix `measurement-discipline.md`.** It is shared by four branches and is F's. §8.

---

## 7. NEW UNMEASURED

Ledger ids derived from my own report numbers; `43.x`–`45.x` were taken.

| id | claim | class | minutes | importance | note |
| --- | --- | --- | ---: | --- | --- |
| `39.1` | whether a static rule can distinguish a hand-written `hashCode` computed from `id` from a safe one | **a** | 30 | M | `ENTITIES_ARE_NOT_DATA_CLASSES` refuses a **keyword**, not a defect. `R39` §7 |
| `39.2` | whether `BaseEntity`'s type check can be satisfied **without initialising a proxy** | **a** | 45 | **H** | `Hibernate.getClass` costs 1 statement per proxy operand. Needs its own red/green pair — it changes equality for every entity. `R39` §5 |
| `39.3` | the **incidence** of proxy-operand comparison on shipped paths | **a** | 60 | M | `R39` measured the per-comparison cost, not how often the application pays it |
| `40.3` | whether any static analysis could see a Kotlin method's escaping checked exceptions | **a** | 40 | M | the `Exceptions` attribute is absent from the class file — `R40` §3.2. If a future Kotlin emits one, `R40` §7's reasoning expires |
| `41.1` | what the tie-break costs `V2`'s index **in time**, as distinct from in plan shape | **a** | 40 | **H** | the unclosed half of `44.3` |
| `41.2` | whether the `OFFSET`-driven plan flip happens at a comparable **page depth** on a table the size of `attempt` | **a** | 45 | **H** | `R41` §3.2 observed it on 100 rows, and row count is what drives it. ⛔ `offset 30` is this table's crossover, not a threshold |
| `40.2` | **a green from a check whose subject may legitimately be absent cannot be made self-verifying** | **c** | — | **H** | **closed on arrival — the entry names no unmeasured quantity.** `R43` §3.5's green was *vacuous* and has a guard; this one is *thin* and admits none, because no workflow can check whether its subject was ever going to be present. Filing it (a) would put a permanent property on a work list forever — `ADR-003`'s *"a deadline that cannot arrive is not a deadline"* |

**Not a ledger entry, and that is the point:** the pool-observability question is **`OPEN-13`**,
because there is no number of minutes that settles it. §8 and `R19` §7.

**Ledger entries closed or corrected by this slice:**

| id | disposition |
| --- | --- |
| `44.3` | ⛔ **corrected and half-answered, NOT closed.** Plan shape answered on the real table and index — `V2`'s index still serves the tie-break, with an `Incremental Sort` above it. Plan **cost in time** stays open as `41.1`. `43.3`'s precedent |
| `40.2` | **closed on arrival**, by being class (c) |

---

## 8. FOR THE INTEGRATOR

**For `docs/explanation/measurement-discipline.md`** — not mine to edit, and wrong in three ways:

> The pinned image is `postgres@sha256:cf78e766…`, **PostgreSQL 16.15 on x86_64-pc-linux-musl**.
> The recorded `sha256:57c72fd2…` / 16.14 is not the pin and has not been since `8dec7e6`; it
> survives in this tree only as the **comparison arm** of `CollationDivergenceTest` and
> `ImageTagDriftTest`. Still musl, so `R25` and `R26` are unaffected.

⭐ **And the deeper fix is a rule, not a number.** Three sessions counted that file's affected
environment blocks and returned three different totals; the document's own figure lands on the
subset that *already carried a digest* every time.

> `measurement-discipline.md` requires an environment block for **every number**, and does **not**
> require a count to publish **the unit it counted**. Its own count was wrong for precisely that
> reason.

⛔ **Do not fix this by writing a corrected number.** A corrected number with an unstated unit goes
stale exactly as the first did and nothing notices — which is `R19`. That is `R8` §3.3's failure
mode occurring in prose: an instrument blind to exactly the population it exists to find.

**For `docs/roadmap.md`, `T3`'s row** — the clause reading *"the swallowed exception /
rollback-only case is **not done at all**"* is closed **in the half that concerns this
application**: `R40` reproduces it, `022675b` fixes the shipped path, `BatchInsideATransactionTest`
gates it. ⚠️ The **checked-exception** half is measured and **not fixed** — `rollbackFor` exists and
is applied nowhere, deliberately, because no `@Transactional` method in `main` raises one. Do not
mark the row done wholesale.

**For `docs/roadmap.md`, new rows** — `R39`, `R40`, `R41`, `R42`, plus `R43`–`R45` inherited from
the base. **Seven, not four.**

**For `R0`** — the gate count changes by one: `BatchInsideATransactionTest` is a new gate that has
been **watched refusing** (it failed at `94fe9ee` and passes at `022675b`). `R39`'s and `R41`'s
tests are gates that have never been watched refusing anything, and should not be counted as paid.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution you performed?**
**No.** Every run used `--rerun-tasks` and Gradle reported all tasks executed. The one number still
outstanding is outstanding *because* I will not quote a run I did not perform.

**b. Does any number here cross machines, sessions, or a long time gap?**
**No number of mine.** ⚠️ Two figures in `R40` §8 are **other sessions'** — D's pool measurement and
E's connection count — and they are attributed in place, on the same machine and the same day, and
**never combined arithmetically** with anything of mine. `R14`'s four-of-five was **re-measured on
this base** rather than imported. `R44` §3's tie-break cost is cited, not re-presented.

**c. Did you loosen a threshold, a sample size, or an assertion to make something pass?**
**No, and one assertion changed in a way that needs stating.** `EntityEqualityTest` asserted the
three proxy type checks cost **0** and measured **1**. It was corrected to the measured value —
**still exact in both directions**, now attributed per operation, with a `label` control that must
be 1 or the counter is blind. **The superseded prediction is written beside it in the source**, not
only in the report. ⛔ That is a corrected prediction, not a loosened threshold: the original could
not have reported *which* operation cost the statement, and the replacement can.

One assertion is deliberately **weaker** than the finding, for the opposite reason:
`TieBreakPagingTest` **reports** the defect arm's duplicate/missing counts instead of asserting
they are non-zero, because asserting a failure would make the test lie on a machine where the
planner is stable. The remedy arms are asserted exactly, at zero.

**d. Is there any claim in a code comment that your work has made false?**
**No, and two were made true.** `AttemptRecordingService`'s *"holds no transaction and no
`@Transactional`, deliberately"* is still true — I added no annotation to it — and it now also
records the **second** reason that absence was load-bearing, which was never written down.
`RecommendationQueries.kt:151`'s *"the general form of this defect belongs to slice G"* is
**satisfied** by `R41` rather than falsified. `AttemptRecorder`'s unit-of-work sentence was
**unconditional and not true under a transactional caller**; `022675b` makes it true rather than
weakening it.

**e. Did you write any version number, default value, or API behaviour from memory?**
**No, and three things exist specifically to avoid it.** The PostgreSQL version was read from my own
run's Flyway line, not from `measurement-discipline.md` — which turned out to be wrong. Kotlin's
annotation-target resolution was **not** recalled: the `data class` fixtures use explicit `@field:`
targets, and `javap` confirmed where they landed. **Spring's rollback rule is not quoted from
documentation anywhere in `R40`** — `RollbackRuleTest` executes each exception kind against a real
database and counts committed rows, which is why §3.1 is a table and not a citation.

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**
**No.**
