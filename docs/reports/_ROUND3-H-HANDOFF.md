# Round 3 — Slice H handoff

> **Branch**: `round3/recency` · **Worktree**: `../proxima-h` · **Base**: `77022a5`
> **Written**: 2026-08-22

---

## 1. WHAT I OPENED

| Trap | Verdict |
| --- | --- |
| **H1** — the stale comment is itself the first defect | **REPRODUCED** |
| **H2** — implement `recent`, both ways, same learner different bands | **REPRODUCED** |
| **H3** — if you chose last N days, whose day is it | **NOT-REPRODUCED** |
| **H4** — the window moves, so the same request answers twice differently | **ABANDONED** |

**H1** found **two** false claims, not one, and a third specimen that is true and that the check
fired on anyway until it was fixed.

**H2** reproduced with a number — 158 of 1,000 learners get a different band — **but not by the
mechanism the trap predicted.** The trap predicts divergence from differing learner cadence; the
seed has *no* cadence variation (spread 0) and the divergence is caused by sample size alone. A
third finding fell out of it: on the shipped dataset the implemented step 4 is **inert**.

**H3** is NOT-REPRODUCED and what holds it shut is measured: there is **no day boundary anywhere
in this repository** — zero occurrences of `date_trunc`, `::date`, `current_date`,
`atStartOfDay`, `truncatedTo`, `LocalDate`, `ZoneId` or `ZonedDateTime` in `api/src/main` or
`seed/src/main` — and `ADR-021` chose a **rolling** window, which has no midnight to disagree
about. `learner` has no time-zone column (`id`, `external_ref`, `created_at` only) and no
timezone or offset column exists anywhere in the schema. H3's defect needs a day boundary to
exist first, and choosing the rolling window is what kept it shut. **Neither the cost of adding a
learner time zone nor the error from not having one was measured** — see §6.

**H4** was not attempted. §6.

---

## 2. COMMITS

| Trap | red | green | what flipped |
| --- | --- | --- | --- |
| H1 | `5f03310` | `2c7f16d` | CHECK 5 reads KDoc against the migrations. Guard job **2 findings → 0** |
| H1 | — | `7ee5552` | the check was firing on a **true** claim one backtick away; it now judges a claim as of the migration it scopes itself to |
| H1 | — | `135dbe0` | `R43` |
| H2 | *see note* | `PENDING-H2` | step 4 implemented; `QueryCountTest` re-baselined 1→2, 2→3, 2+n→3+n |
| H3 | **no red commit** | — | held shut by there being no date boundary in the tree and by `ADR-021`'s rolling window. Measured, §1 |
| H4 | **no red commit** | — | not attempted. §6 |

**Note on H2's red.** There is no red commit and the report says so in its header. Step 4 not
being implemented was a *documented deviation with a stated reason*, not a defect — `R43` is the
report about the reason having expired. The defect H2 *did* find (§3, step 4 is inert on the
shipped seed) **also has no green**, because the remedy is a judgement `ADR-021` declines to make
and which belongs in `open.md`. Both are stated in `R44`'s header rather than dressed as a pair.

---

## 3. NUMBERS

### 3.1 The test suite — from a run I executed, both modules named separately

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

**Baseline, at `135dbe0` (H1 complete, before step 4):**

| | classes | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `:api:test` | 48 | **125** | 0 | 0 | 0 |
| `:seed:test` | 4 | **14** | 0 | 0 | 0 |
| **both modules** | **52** | **139** | **0** | **0** | **0** |

`BUILD SUCCESSFUL in 10m 16s`, `16 actionable tasks: 16 executed`, window 12:31:50 → 12:42:07
(+09:00).

**Final, at the branch tip:** `PENDING-FINAL`

> **Two earlier runs were discarded and are not quoted anywhere.** The first two collided — I
> started a second Gradle run in the same worktree while the first was still executing, and they
> fought over `api/build/test-results/`, producing `NoSuchFileException`. A third completed but
> overlapped a commit that edited two `.kt` files mid-run. **The baseline above is the fourth
> attempt**, and it is clean by evidence rather than by assertion: `seed/build/classes/kotlin/test`
> has mtime 12:32:23, the two source files I added afterwards have mtimes 12:39:35 and 12:40:24,
> and neither appears in the results. `ADR-014` entry 16.6 is *"the measurement contaminated
> itself three times"*; this is the fourth and fifth.

### 3.2 H1 — the comment corpus

Deterministic over a fixed tree; the check was extracted from the YAML by a parser rather than
retyped. No durations, so no hardware block — `R17` and `R0` do the same.

| | |
| --- | ---: |
| tracked `*.kt` + `*.java` | 93 |
| comment blocks / of which KDoc / out of scope | 552 / **380** / **172** |
| migrations / tables given a `create index` | 5 / 2 |
| findings at **full comment scope** | **3, of which 1 FALSE** |
| findings at **KDoc scope** (shipped) | **2, of which 0 false** |
| the two claims were false for | **10 days** (`V2` = `8bbe8b9`, 2026-08-12) |
| self-test cases / of which required-silent | **7 / 5** |

### 3.3 H2 — the two definitions of *recent*

No database. Every figure is a single-threaded computation over the shipped `Generator`'s output
at `Scale.FULL`, seed value 20260810, 1,000 learners × 3,000 attempts = 3,000,000 rows.
**No figure here may be placed beside a `p99`.**

| | |
| --- | ---: |
| span of the last 20 attempts, min / max over 1,000 learners | 81 h / 83 h |
| attempts in the last 7 days, min / max over 1,000 learners | **39 / 39 — spread 0** |
| learners whose **band disagrees** between the two definitions | **158 / 1,000 = 15.8%** |
| newest attempt in the dataset | `2026-08-09T20:40:41Z` |
| dataset older than the clock, at `2026-08-22T03:45:20Z` | **12.3 days** |
| learners with an empty 7-day window **measured from now** | **1,000 / 1,000** |
| statements per `nextRows` / `nextItems` | 1 → **2** / 2 → **3** |

### 3.4 What may not be compared

- **Nothing in §3.3 went through PostgreSQL.** It is computed over the generator's bytes. The
  loader `COPY`s exactly those bytes so it *should* be identical, and *should be* is not measured.
- **No latency figure for step 4 exists.** The slice brief required H2's performance to be
  measured under `R3`'s conditions or the comparison refused. **It is refused.** In particular the
  plan cost of `recentOutcomesByCount`'s `order by attempted_at desc, id desc` tie-break — whether
  `V2`'s index still serves it without a sort — is **미측정**.
- **§3.2 and §3.3 are not comparable with each other** and neither is comparable with any load
  number in this repository. Slice D changes the runtime environment; **every number here was
  taken before any of D, E or G merged**, on `77022a5` plus my own commits.

---

## 4. REPORTS WRITTEN

| | Title | §8 non-empty |
| --- | --- | --- |
| `R43` | A comment is a document, and no check here read one | **yes** — 9 bullets |
| `R44` | Step 4, implemented, and the window was empty for all one thousand | **yes** — 8 bullets |

`R45` and `R46` were **not written**. §6.

`ADR-021` — *Recent is a span of days, and on the shipped seed that window is empty for
everybody.* Records the choice, what the rejected reading did better, and the fact that the
choice cannot be validated on this dataset.

**Numbering.** The slice was assigned `R43`–`R46` and `ADR-021`. Derived from the tree before the
first commit as §0 requires: the tip carried `R28` and `ADR-017`, so the assigned range was free
and nothing was shifted. `R45`, `R46` are unused and remain free.

---

## 5. GATES AND CI

**I changed a workflow.** `.github/workflows/docs-consistency.yml` — one new check in the
`guard` job and one new step in the `self-test` job.

| | |
| --- | --- |
| file | `.github/workflows/docs-consistency.yml` |
| new guard check | **CHECK 5** — *no code comment denies an index a migration creates* |
| new self-test step | *check 5 fires on a denied index, and on nothing else it was narrowed away from* |
| checks 1–4 | **untouched** |

No other workflow was modified. `build.yml`, `image-pin.yml`, `load-harness.yml`,
`no-learner-data.yml`, `secret-scan.yml` and `study-consistency.yml` are byte-identical to
`77022a5`.

**Run locally, script extracted from the YAML by a parser:**

| check | verdict on the branch tip |
| --- | --- |
| CHECK 1 artefacts | **OK** |
| CHECK 2 dates | **OK** |
| CHECK 3 roadmap rows | **FAIL — `R43 R44`, and it is expected.** See below |
| CHECK 4 §8 non-empty | **OK** |
| CHECK 5 comments | **OK** |
| CHECK 5 self-test | **OK** — 2 fire, 5 silent |

> ⚠️ **CHECK 3 IS RED ON THIS BRANCH AND THE INTEGRATOR MUST CLEAR IT.** The check requires every
> `docs/reports/R*.md` to be named by `docs/roadmap.md`, and my slice brief forbids me from
> touching `docs/roadmap.md`. The rows are in §8 below. **This branch cannot go green until they
> are added.**

**No CI run has happened.** Nothing was pushed, so no workflow has executed on a GitHub runner.
Every verdict above is local. `ADR-004` forbids quoting a local figure as a CI one and no CI
figure is quoted.

---

## 6. WHAT I DID NOT DO

**H4 was not attempted at all.** The trap asks how often the sliding window makes the same request
answer differently, and asks for the count in the seed. I ran out of budget before building it. It
is **cheap for whoever picks it up** — the instrument already exists (`RecencyDefinitionTest`
already holds every learner's attempt stream in memory) and the measurement is: compare
`band(last 39)` against `band(last 38)` per learner, since the window advancing past one attempt is
exactly what drops the oldest. Expect it to be answerable in one 20-second run. **Nothing about
H4 is reported as measured, and the ADR does not decide whether the wobble is a defect or a
specification** — the brief asked for that decision and it is not made.

**H3 was investigated but not measured.** The verdict is NOT-REPRODUCED and §1 gives the evidence
for what holds it shut. What the brief asked for and I did **not** deliver:

- the **cost** of adding a per-learner time-zone column — 미측정
- the **error** from not having one — 미측정
- the reproduction of two learners in different zones with diverging "today's accuracy" — **not
  built**, and with a rolling window there is no boundary for it to diverge at
- whether the index is still used with a per-learner zone in the query — 미측정

**Neither `R45` nor `R46` exists.** The slice was scoped for four reports and produced two.

**H2's database arm is missing.** Every H2 number is over the generator's output rather than the
loaded database, and the step-4 latency comparison under `R3`'s conditions was refused rather than
approximated. That refusal is correct; its absence is still a gap.

**Migrations.** None were added. `V6` was available and unused — nothing H1 or H2 needed a schema
change for, and H3, which would have needed one, was not measured.

---

## 7. NEW UNMEASURED

For `ADR-014`'s ledger, in its format:

| id | claim | class | minutes | importance | note |
| --- | --- | --- | ---: | --- | --- |
| H.1 | how often the sliding 7-day window changes a learner's band from one second to the next | **a** | 30 | **H** | H4, not attempted. Instrument exists; `band(39)` vs `band(38)` per learner |
| H.2 | whether the 15.8% band divergence is identical against the loaded database rather than the generator's output | **a** | 45 | M | every H2 number is pre-database |
| H.3 | plan cost of `recentOutcomesByCount`'s `attempted_at desc, id desc` tie-break — does `V2`'s index still serve it without a sort | **a** | 40 | **H** | the comparison was refused rather than approximated |
| H.4 | cost of adding a per-learner time-zone column, and the aggregation error from not having one | **a** | 120 | M | H3. Needs a migration from `V6` |
| H.5 | what the 15.8% becomes on a population with real cadence variation | **b** | — | **H** | not measurable here: needs a generator this repository does not have, and changing it invalidates every published number |
| H.6 | the date on which step 3's 30-day window stops excluding anything on the shipped seed (arithmetic says ~2026-09-09) | **a** | 20 | **H** | derived from a measured maximum, never observed. No test asserts it |
| H.7 | how many index claims in this tree are phrased outside CHECK 5's four-pattern set | **a** | 30 | M | the set was written from a sample of two |
| H.8 | CHECK 5's cost on a GitHub runner | **a** | 15 | L | never executed in CI |
| H.9 | whether `RecentAccuracy.bandFor`'s thresholds and `RecencyDefinitionTest`'s restatement agree | **a** | 30 | **H** | `ADR-006`'s shape **without** `ADR-006`'s gate. Nothing fails if they drift |

**Ledger entries closed by this slice:** none. Nothing in `ADR-014`'s 168 entries covered the
recommendation rule's step 4 or the comment corpus — which is itself worth the integrator noting,
because the sweep that produced those 168 entries read `docs/**/*.md` and would not have seen
either.

---

## 8. FOR THE INTEGRATOR

**`docs/roadmap.md` — required, or CHECK 3 stays red:**

> `R43` — **A comment is a document, and no check here read one.** Two KDoc comments asserted, in
> the present tense, that `attempt` had no index on `(learner_id, attempted_at)`; `V2` created it
> ten days earlier. `docs-consistency.yml` gains CHECK 5, which resolves an index claim in a KDoc
> against the migrations, and closes `R17` §8's last bullet for that one claim shape.

> `R44` — **Step 4, implemented, and the window was empty for all one thousand.** The
> recommendation rule's step 4 is implemented for the first time. The two readings of *recent*
> band **158 of 1,000** learners differently — on a seed with **zero** cadence variation, so the
> cause is sample size, not cadence. Measured against the wall clock, all 1,000 learners have an
> empty window and every band falls back to the constant step 4 replaced.

**`R17` — annotate §8's last bullet in place, beside the sentence, not by deleting it:**

> **Partly closed 2026-08-22 — `R43`.** `docs-consistency.yml` CHECK 5 now reads KDoc and refuses a
> comment that denies an index a migration creates. It found **two** live false claims on the tree
> this bullet was written against, one of which had been false for five days when this sentence
> was written. **The bullet is otherwise intact**: 172 of the tree's 552 comment blocks are outside
> the KDoc scope, only index-existence claims are checked, and `R14`'s two contradicting comments
> are still in a file no check reads.

**`R0` — the round-three scoring line for this slice:**

> Slice H drafted a check that reproduced `R17` §5's discarded prose check at 1 site in 3, and a
> guard that fired on a true sentence one backtick away from where it was silent. **Neither was
> caught by review — both were caught by running the instrument in a second configuration.** The
> narrowing that fixed the first was found *by* the false positive rather than before it, and
> `R43` §9 says so in the first person. A third failure — a green that was vacuous because
> `git ls-files` returned nothing — was caught by suspicion rather than by any instrument, and is
> the **sixth** case in this repository of something reporting into nothing.

**`README.md`** — I have no sentence for it. **Do not copy my test counts into it without
re-running**; §3.1's numbers are for `135dbe0` and the branch tip figure is `PENDING-FINAL`. Count
both modules and name them separately.

**`docs/decisions/open.md`** — two rows that need a judgement rather than work, both argued in the
reports rather than here:

> **Step 4 is inert on the shipped seed and nothing says so.** `R44` §5 compares four options and
> takes none of them; three are refused because they would invalidate every published number or
> change what *recent* means. Needs a decision, not an errand.

> **Is CHECK 5's 172-block exclusion permanent or provisional?** `R43` §8's last bullet. Widening
> to body comments re-admits `R17` §5's false positive and nothing structural distinguishes
> narration from a claim.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution you performed?**
No. Every run used `--rerun-tasks` and reported `16 actionable tasks: 16 executed`. §3.1 names the
window and the tree for the baseline. Two runs were discarded for collision and one for overlapping
a commit; none of the three is quoted.

**b. Does any number here cross machines, sessions, or a long time gap?**
No. Every figure is from this machine, this session, 2026-08-22, between 11:39 and the branch tip.
**But §3.4 states what may not be compared** — nothing here is a load number, nothing may sit beside
a `p99`, and every figure predates any merge of slices D, E or G.

**c. Did you loosen a threshold, a sample size, or an assertion to make something pass?**
**One assertion changed, and I do not think it is what this question is about — judge it.**
`QueryCountTest` went 1→2, 2→3, 2+n→3+n because step 4 is a real extra statement. The assertions
stay **exact** in both directions: three statements on `nextRows` still fails, and so does one. The
change ships in the same commit as the report that measured it, which is the process
`BaselineMigrationTest` records for `V2`. **Nothing was widened to green.** Separately, CHECK 5's
KDoc scope narrows what it reads — `R43` §3.3 argues at length why that is structural rather than
tuned, and §8 counts what it costs: 172 blocks.

**d. Is there any claim in a code comment that your work has made false?**
Not that CHECK 5 can see — it passes on the tip, which is the whole point of it. I also checked by
hand the comments I touched. **What I cannot rule out** is the class CHECK 5 does not read: body
comments, non-backticked table names, and every schema claim that is not about index existence.
`R43` §8 lists them. Specifically flagged and **not** resolved: `Attempt.kt`,
`RecommendationQueries` and `RecommendationService` each assert *"three million rows"* in prose and
nothing verifies that against `Scale.FULL`.

**e. Did you write any version number, default value, or API behaviour from memory?**
No. Docker 29.5.3 and Temurin 21.0.12+8 were read off the machine. `V2`'s date came from
`git log --diff-filter=A`. The dataset's newest attempt, the 12.3-day gap and the clock were
printed by the run. The claim that no date-boundary function exists was **re-run by me** rather
than taken from the brief, and returned 0. The corpus counts came from one clean measurement pass.

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**
No.
