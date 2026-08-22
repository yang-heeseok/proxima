# Round 3 — Slice H handoff

> **Branch**: `round3/recency`, pushed to `origin` · **Worktree**: `../proxima-h`
> **Base commit**: **`77022a5`** — every number in this document was measured on that base plus
> the six commits in §2, and on nothing else
> **Written**: 2026-08-22

---

## 1. WHAT I OPENED

| Trap | Verdict |
| --- | --- |
| **H1** — the stale comment is itself the first defect | **REPRODUCED** |
| **H2** — implement `recent`, both ways, same learner different bands | **REPRODUCED** |
| **H3** — if you chose last N days, whose day is it | **NOT-REPRODUCED** |
| **H4** — the window moves, so the same request answers twice differently | **REPRODUCED** |
| *(unbriefed)* — the self-invocation rule catches 1 of 2 spellings | **REPRODUCED** |

**H1** found **two** false claims rather than one, and a third specimen that is *true* and that
my own check fired on until `7ee5552` fixed it.

**H2** reproduced — 158 of 1,000 learners get a different band — **but not by the mechanism the
trap predicts.** The trap predicts divergence from differing learner cadence; this seed has
*none* (spread 0) and the divergence is sample size alone. A third finding fell out: on the
shipped dataset the implemented step 4 is **inert**.

**H3** is NOT-REPRODUCED and what holds it shut was measured by me, not taken from the brief:
**zero** occurrences of `date_trunc`, `::date`, `current_date`, `atStartOfDay`, `truncatedTo`,
`LocalDate`, `ZoneId` or `ZonedDateTime` in `api/src/main` or `seed/src/main`; `learner` has only
`id`, `external_ref`, `created_at`; no timezone or offset column exists anywhere in the schema.
`ADR-021` chose a **rolling** window, which has no midnight to disagree about. **H3's defect needs
a day boundary to exist first.** Neither the cost of adding a learner time zone nor the error from
omitting one was measured — §6.

**H4** reproduced and **it is the small one**, which `R44` §3.6 says in those words.

**The unbriefed finding is the strongest thing in the slice** and became `R45`. §2's note.

---

## 2. COMMITS

Base **`77022a5`**. Six commits, pushed.

| Trap | red | green | what actually flipped |
| --- | --- | --- | --- |
| H1 | `5f03310` | `2c7f16d` | CHECK 5 reads KDoc against the migrations. Guard job **2 findings → 0** |
| H1 | — | `7ee5552` | the check was firing on a **true** claim one backtick away; it now judges a claim as of the migration the claim itself names |
| H1 | — | `135dbe0` | `R43` |
| H2, H4 | *see note* | `ebcc99c` | step 4 implemented; `QueryCountTest` re-baselined 1→2, 2→3, 2+n→3+n; `R44`, `ADR-021` |
| **R45** | **`77022a5`** | `4726416` | the self-invocation rule follows the `$default` bridge instead of exempting it. Arm B **green → red** |
| H3 | **no red commit** | — | held shut by there being no date boundary in the tree and by `ADR-021`'s rolling window. Measured, §1 |

**Note on H2's red.** There is none and `R44`'s header says so. Step 4 not being implemented was a
*documented deviation with a stated reason*, not a defect — `R43` is the report about the reason
having expired. **The defect H2 did find has no green either:** step 4 is inert on the shipped
seed, and `ADR-021` says of that, in its own words, that this *"is a reason the decision **cannot
be validated on the shipped seed**"*. It is `open.md`'s, not mine.

**Note on `R45`'s red.** `77022a5` is the base — the rule as shipped since `R7`. It is red in the
sense that matters: it **passes** on a defect it is written to refuse, and §3.3 below is the
three-arm experiment that establishes it.

---

## 3. NUMBERS

### 3.1 The test suite — from runs I executed, both modules named separately

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 -- not Docker Desktop
  JVM            : Temurin 21.0.12+8, Gradle test worker at -Xmx512m
  PostgreSQL     : Testcontainers, the digest pinned by ADR-017
  Command        : ./gradlew :api:test :seed:test --rerun-tasks
  Repetitions    : one run per tree state. Counts are exact, not medians
```

| tree | | classes | tests | failures | errors | skipped |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `135dbe0` — H1 complete | `:api:test` | 48 | **125** | 0 | 0 | 0 |
| | `:seed:test` | 4 | **14** | 0 | 0 | 0 |
| | **both** | **52** | **139** | **0** | **0** | **0** |
| `4726416` — **branch tip** | `:api:test` | 48 | **125** | 0 | 0 | 0 |
| | `:seed:test` | **5** | **15** | 0 | 0 | 0 |
| | **both** | **53** | **140** | **0** | **0** | **0** |

Tip run: `BUILD SUCCESSFUL in 9m 29s`, `16 actionable tasks: 16 executed`, finished
`2026-08-22T13:36:37+09:00`. The one new test is `RecencyDefinitionTest` in `:seed:test`.
`:api:test` is unchanged at 125 — `QueryCountTest`'s assertions moved, its test count did not.

> **Three earlier runs were discarded and none is quoted anywhere.** §9(a) has the history.

### 3.2 H1 — the comment corpus

Deterministic over a fixed tree; the check was extracted from the YAML **by a parser rather than
retyped**. No durations, so no hardware block — `R17` and `R0` do the same.

| | |
| --- | ---: |
| tracked `*.kt` + `*.java` | 93 |
| comment blocks / of which KDoc / **out of scope** | 552 / 380 / **172** |
| migrations / tables given a `create index` | 5 / 2 |
| findings at **full comment scope** | **3, of which 1 FALSE** |
| findings at **KDoc scope** (shipped) | **2, of which 0 false** |
| the two claims were false for | **10 days** (`V2` = `8bbe8b9`, 2026-08-12) |
| self-test cases / of which required-silent | **7 / 5** |

### 3.3 R45 — one defect, three spellings

| arm | the call in `nextRows` | rule at `77022a5` | rule at `4726416` |
| --- | --- | :---: | :---: |
| **A** | `evidence(...)` — private, unannotated | SUCCESSFUL | SUCCESSFUL |
| **B** | `difficultyBandFor(learnerId)` — **default omitted** | **SUCCESSFUL** ← blind | **FAILED** |
| **C** | `difficultyBandFor(learnerId, RECENCY_BASIS)` | FAILED | FAILED |

Arm A green in both columns is the half that matters: the fix did not buy its finding by becoming
indiscriminate, and the self-test's planted violation is still refused in the same run.

### 3.4 H2 and H4 — the two definitions of *recent*

**No database.** Every figure is a single-threaded computation over the shipped `Generator`'s
output at `Scale.FULL`, seed value 20260810, 1,000 learners × 3,000 attempts = 3,000,000 rows.
**No figure here may be placed beside a `p99`.**

| | |
| --- | ---: |
| span of the last 20 attempts, min / max over 1,000 learners | 81 h / 83 h |
| attempts in the last 7 days, min / max over 1,000 learners | **39 / 39 — spread 0** |
| learners whose **band disagrees** between the two definitions | **158 / 1,000 = 15.8%** |
| newest attempt in the dataset | `2026-08-09T20:40:41Z` |
| dataset older than the clock, at `2026-08-22T04:28:05Z` | **12.3 days** |
| learners with an empty 7-day window **measured from now** | **1,000 / 1,000** |
| **H4** — learners whose band flips at the **very next** crossing | **25 / 1,000 = 2.5%** |
| **H4** — crossings over one full window rotation | 38,000 |
| **H4** — of those, crossings that change the band | 3,627 = 9.5% |
| statements per `nextRows` / `nextItems` | 1 → **2** / 2 → **3** |

### 3.5 What may not be compared

- **Nothing in §3.4 went through PostgreSQL.** It is computed over the generator's bytes. The
  loader `COPY`s exactly those bytes so it *should* be identical; *should be* is not measured.
- **No latency figure for step 4 exists.** The brief required H2's performance under `R3`'s
  conditions or the comparison refused. **It is refused.** The plan cost of
  `recentOutcomesByCount`'s `attempted_at desc, id desc` tie-break is **미측정**.
- **§3.4's 9.5% is an upper bound, not a rate.** A fixed dataset can only express a window that
  *drains*; the later crossings use smaller samples than a live system would. **Quote the 2.5%.**
- **§3.2, §3.3 and §3.4 are not comparable with one another**, and none is comparable with any
  load number here. **Every number in this document predates any merge of D, E or G** — slice D
  changes the runtime environment.

---

## 4. REPORTS WRITTEN

| | Title | §8 non-empty |
| --- | --- | --- |
| `R43` | A comment is a document, and no check here read one | **yes** — 9 bullets |
| `R44` | Step 4, implemented, and the window was empty for all one thousand | **yes** — 10 bullets |
| `R45` | The exemption that hid the defect it was added beside | **yes** — 7 bullets |

`ADR-021` — *Recent is a span of days, and on the shipped seed that window is empty for everybody.*
Records the choice, **what the rejected reading did better**, that the choice cannot be validated
on this dataset, and that H4's wobble is a specification rather than a defect.

**Numbering.** Assigned `R43`–`R46` and `ADR-021`; derived from the tree before the first commit
as §0 requires. The tip carried `R28` and `ADR-017`, so the range was free and **nothing was
shifted**. `R46` is unused and remains free.

---

## 5. GATES AND CI

**I changed one workflow.** `.github/workflows/docs-consistency.yml` — one new guard check, one
new self-test step. Checks 1–4 untouched. `build.yml`, `image-pin.yml`, `load-harness.yml`,
`no-learner-data.yml`, `secret-scan.yml` and `study-consistency.yml` are **byte-identical** to
`77022a5`.

I also changed a test-source gate: `TransactionBoundaryRules.kt`, the `T3` self-invocation rule
(`R45`).

Run locally, scripts extracted from the YAML by a parser:

| check | verdict at the tip |
| --- | --- |
| CHECK 1 artefacts | **OK** |
| CHECK 2 dates | **OK** |
| CHECK 3 roadmap rows | **FAIL — `R43 R44 R45`, expected** |
| CHECK 4 §8 non-empty | **OK** |
| CHECK 5 comments *(new)* | **OK** |
| CHECK 5 self-test *(new)* | **OK** — 2 fire, 5 silent |
| `:api:test` + `:seed:test` | **OK** — 140 tests, 0 failures |

> ⚠️ **CHECK 3 IS RED AND THE INTEGRATOR MUST CLEAR IT.** Every `docs/reports/R*.md` must be named
> by `docs/roadmap.md`, and my brief forbids me touching it. Rows are in §8. **This branch cannot
> go green until they are added.** Confirmed by the PO as correct behaviour — do not "fix" it here.

**No CI verdict is quoted, and I cannot produce one.** The push triggered GitHub Actions, but
`gh` is not installed on this machine and the run results are not readable from here. `ADR-004`
forbids quoting a local figure as a CI one, so every verdict above is labelled local. **Somebody
with access must check the actual run** — CHECK 5 has never executed on a runner.

---

## 6. WHAT I DID NOT DO

**H3 was investigated and not measured.** Verdict NOT-REPRODUCED with the evidence in §1. What
the brief asked for and I did **not** deliver:

- the **cost** of adding a per-learner time-zone column — 미측정
- the **error** from not having one — 미측정
- the reproduction of two learners in different zones diverging on "today's accuracy" — **not
  built**; with a rolling window there is no boundary at which to diverge
- whether the index survives a per-learner zone in the query — 미측정

**`R46` was not written.** The slice was scoped for four reports; it produced three, and one of
those three (`R45`) is not on the brief at all.

**H2's database arm is missing.** Every H2/H4 number is over the generator's output rather than
the loaded database, and the step-4 latency comparison was **refused** rather than approximated.
The refusal is correct; the absence is still a gap.

**`R45`'s self-test has no default-argument fixture**, so the exact defect `4726416` fixed would
regress unnoticed. Named in `R45` §7–§8 **and now scheduled** as ledger entry `45.2`.

**Migrations.** None added. `V6` was available and unused — H3 is what would have needed one.

---

## 7. NEW UNMEASURED

**Fifteen entries added to `ADR-014`'s ledger in that ledger's own format**, under a new section
`### R43, R44, R45 — round three, slice H`. They are in the ADR; the ones the integrator should
look at first:

| id | claim | class | minutes | importance |
| --- | --- | --- | ---: | --- |
| `44.1` | **the date step 3's 30-day window stops excluding anything on the shipped seed — arithmetic says ~2026-09-09, never observed** | **a** | 20 | **H** |
| `43.2` | three files assert *"three million rows"* in prose; nothing checks it against `Scale.FULL` | **a** | 45 | **H** |
| `44.3` | plan cost of the tie-break — does `V2`'s index still serve it without a sort | **a** | 40 | **H** |
| `44.7` | `RecentAccuracy`'s thresholds and `RecencyDefinitionTest`'s restatement are related by nothing | **a** | 30 | **H** |
| `45.1` | whether any **other** ArchUnit rule has the same shape — an exclusion that hides a true positive | **a** | 60 | **H** |
| `45.2` | `TransactionBoundaryRulesSelfTest` has no default-argument fixture | **a** | 25 | **H** |
| `44.4` | how often the sliding window changes a band — **now measured**, see §3.4; entry kept for the *live*-window case | **a** | 30 | M |
| `44.5` | cost of a per-learner time-zone column and the error from omitting one | **a** | 120 | M |
| `44.6` | what 15.8% becomes on a population with real cadence variation | **b** | — | **H** |

**LEDGER ENTRY CLOSED — and I reported this wrong at checkpoint 1.** I said this slice closed
nothing. It does not:

> **`19.6` — *"nothing read code comments; the same sweep over KDoc"*, class (a), 120 min,
> importance M, filed with the note *"`R14`'s existence is evidence it would find something"*.**

`R43` **partly** closes it, and the prediction it was filed with was correct. It is annotated in
place as **partly** closed rather than closed, because it is not the general sweep the row asks
for: **index-existence claims only, KDoc only, and 172 of the tree's 552 comment blocks out of
scope.** Keep that denominator when you re-score.

---

## 8. FOR THE INTEGRATOR

**`docs/roadmap.md` — required, or CHECK 3 stays red:**

> `R43` — **A comment is a document, and no check here read one.** Two KDoc comments asserted, in
> the present tense, that `attempt` had no index on `(learner_id, attempted_at)`; `V2` created it
> ten days earlier. `docs-consistency.yml` gains CHECK 5, resolving an index claim in a KDoc
> against the migrations. Partly closes `ADR-014` `19.6`.

> `R44` — **Step 4, implemented, and the window was empty for all one thousand.** The
> recommendation rule's step 4 is implemented for the first time. The two readings of *recent* band
> **158 of 1,000** learners differently — on a seed with **zero** cadence variation, so the cause is
> sample size, not cadence. Measured against the wall clock, all 1,000 learners have an empty
> window and every band falls back to the constant step 4 replaced.

> `R45` — **The exemption that hid the defect it was added beside.** The `T3` self-invocation rule
> caught one of two spellings of the same defect: `R7`'s justified exclusion of Kotlin `$default`
> bridges also hid every self-invocation reached *through* one. A justified exception is a place
> defects hide.

**`R17` — annotate §8's last bullet in place, beside the sentence, not by deleting it:**

> **Partly closed 2026-08-22 — `R43`.** `docs-consistency.yml` CHECK 5 now reads KDoc and refuses a
> comment that denies an index a migration creates. It found **two** live false claims on the tree
> this bullet was written against, one of which had been false for five days when this sentence was
> written. **The bullet is otherwise intact**: 172 of the tree's 552 comment blocks are outside the
> KDoc scope, only index-existence claims are checked, and `R14`'s two contradicting comments are
> still in a file no check reads.

**`R7` — annotate §3.5, beside the exclusion, without reversing it:**

> **Extended 2026-08-22 — `R45`.** The exclusion was correct and its reason still holds. It was
> also **wider than that reason**: a same-class caller that omits the default reaches the annotated
> method only through the bridge, so the exclusion hid it. The rule now follows the bridge to its
> callers. `R7`'s measurement is unchanged; what was missing was a count of what the exception took
> with it.

**`R0` — the round-three scoring line for this slice:**

> Slice H drafted a check that reproduced `R17` §5's discarded prose check at 1 site in 3; a guard
> that fired on a true sentence one backtick from where it was silent; and a service that
> reintroduced `R1`'s self-invocation on the first attempt. **The third was caught by an existing
> gate, and the first two were caught by running the instrument in a second configuration — none
> by review.** A fourth failure, a green that was vacuous because `git ls-files` returned nothing,
> was caught by suspicion rather than by any instrument and is the **sixth** case here of something
> reporting into nothing. **The slice also found that the gate which caught it was catching half
> the defect** (`R45`).

**`README.md`** — I have no sentence for it. **Re-run before copying any count.** At `4726416` it
is `:api:test` **125** and `:seed:test` **15**, 140 together. Name both modules.

**`docs/decisions/open.md`** — two rows needing a judgement, argued in the reports rather than here:

> **Step 4 is inert on the shipped seed and nothing says so.** `R44` §5 compares four options and
> takes none; three would invalidate every published number or change what *recent* means.

> **Is CHECK 5's 172-block exclusion permanent or provisional?** `R43` §8. Widening to body
> comments re-admits `R17` §5's false positive and nothing structural distinguishes narration.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution you performed?**
No. Every run used `--rerun-tasks` and reported `16 actionable tasks: 16 executed`.
**Three runs were discarded and none is quoted:**
1. and 2. — I started a second Gradle run in the same worktree while the first was executing. They
   fought over `api/build/test-results/` and produced `java.nio.file.NoSuchFileException`.
3. — completed, but overlapped commit `2c7f16d`, which edited two `.kt` files mid-run.
The `135dbe0` baseline is the fourth attempt and is clean **by evidence rather than by assertion**:
`seed/build/classes/kotlin/test` has mtime `12:32:23`, the two files I added afterwards have mtimes
`12:39:35` and `12:40:24`, and neither appears in that run's results. A fifth run was stopped
deliberately once I found I had never applied the service-wiring patch, so it was testing an
inconsistent tree. **`ADR-014` `16.6` is *"the measurement contaminated itself three times"*; this
round it was four more.**

**b. Does any number here cross machines, sessions, or a long time gap?**
No. Every figure is from this machine, this session, 2026-08-22, between 11:39 and 13:36 KST.
**§3.5 states what may not be compared anyway** — nothing here is a load number, nothing may sit
beside a `p99`, the 9.5% is an upper bound rather than a rate, and every figure predates any merge
of D, E or G.

**c. Did you loosen a threshold, a sample size, or an assertion to make something pass?**
**One assertion changed and I do not believe it is what this question is about — judge it.**
`QueryCountTest` went 1→2, 2→3, 2+n→3+n because step 4 is a real extra statement. The assertions
remain **exact in both directions**: three statements on `nextRows` still fails, and so does one.
It ships in the same commit as the report that measured it, which is the process
`BaselineMigrationTest` records for `V2`'s index. **Nothing was widened to green.**
Two narrowings, both counted rather than asserted: CHECK 5 reads KDoc only — **172 of 552 blocks**
excluded, `R43` §3.3 argues why that is structural and not tuned; and CHECK 5 counts `create index`
only, which `R43` §8 records as leaving unique-constraint and primary-key claims unchecked.

**d. Is there any claim in a code comment that your work has made false?**
Not that CHECK 5 can see, and it passes at the tip. I also caught one by hand: the first draft of
`RecommendationService`'s KDoc referenced `R45` for the time-zone question, and `R45` turned out to
be a different report — that sentence was rewritten before it was committed. **What I cannot rule
out is the class CHECK 5 does not read**: body comments, non-backticked table names, and every
schema claim that is not about index existence. Specifically flagged and **not** resolved:
`Attempt.kt`, `RecommendationQueries` and `RecommendationService` each assert *"three million
rows"* in prose and nothing verifies it against `Scale.FULL` — ledger `43.2`.

**e. Did you write any version number, default value, or API behaviour from memory?**
No. Docker `29.5.3` and Temurin `21.0.12+8` were read off the machine. `V2`'s date came from
`git log --diff-filter=A`. The dataset's newest attempt, the 12.3-day gap and the clock were
printed by the run. The claim that no date-boundary function exists was **re-run by me** rather
than taken from the brief, and returned 0. `R7` §3.5's exclusion and the rule's failure text were
read out of the source and the JUnit XML, not recalled.

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**
No.
