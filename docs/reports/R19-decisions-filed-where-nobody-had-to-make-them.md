# R19. Decisions filed where nobody had to make them

> **Created**: 2026-08-17
> **Updated**: 2026-08-17
> **Red commit**: `b1c1b95` — three decisions filed as risks, three falsified §8 bullets
> saying so nowhere, and an Open table claiming there could be none of the first
> **Green commit**: this one — three rows, four annotations, and the claim withdrawn
> **Changes no code and gates nothing.** It measures the other nineteen reports, as `R0` and
> `R17` do, and §7 is the argument for why the gate is absent rather than a promise.
> **Found by**: sweeping a shelf that had only ever been watched. §1

```
증거 / What the evidence here is
  Not durations. This report measures this repository's own §8 sections against the
  commits that came after them, so the hardware block the template carries would be
  hardware none of these numbers came from -- R0 and R17 do the same, for the same reason.

  Corpus       : docs/reports/R*.md at b1c1b95 -- 19 reports, 145 top-level bullets in
                 their section 8. Section 8 only; nothing here read code comments
  Question     : the one docs/decisions/open.md states -- does discharging this bullet
                 require a JUDGEMENT, or only WORK?
  Instrument   : a person reading all 145, once. Section 7 records a mechanical check
                 that was built, measured on this corpus, and discarded -- with its numbers
  Cross-check  : git log per artefact, and the working tree, for every bullet that asserts
                 a state. A bullet's own claim was never taken on trust
  References   : open.md's two rules; AGENTS.md section Scope; ADR-003; ADR-004
  Dates        : read from the machine clock -- `date -Is` gave 2026-08-17T19:30+09:00.
                 R17 section 9 says why this line is here
  Repetitions  : one pass, one reader. Section 8 says what that is worth
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`docs/decisions/open.md`'s Open table was empty, and the document says of itself:

> **An empty table here is a claim, not a default.** It says: everything undecided has been
> decided, and nothing currently known is waiting on a judgement.

The claim had stood since 2026-08-14. It was false when it was written.

The precedent for how it goes wrong is in the same file. `R12` §8 carried a bullet about the
`0..1` band being defined in two places; that was not a risk somebody had chosen to live with,
it was a judgement nobody had made, so it moved to `OPEN-6` and `ADR-006` closed it the next
morning. `open.md` records why that mattered: *leaving it in a 남는 위험 section would have
meant nobody ever had to decide it.*

**`OPEN-6` was found because `R12`'s author was reading `R12` §8.** Nobody had ever asked the
same question of the other eighteen reports, and there were 145 bullets in them. The table was
not empty because the shelf was clear. It was empty because **the shelf was watched and never
swept**, and watching only reaches the file already open.

## 2. 재현 / Reproduction

```bash
cd /c/project/airtown/proxima
for f in $(ls docs/reports/R*.md | sort -V); do
  echo "===== $f"
  awk '/^## *8\./{p=1;next} /^## *9\./{p=0} p' "$f" | grep -E '^[-*] '
done
```

For every bullet, `open.md`'s question: **does discharging this require a judgement, or only
work?** Then, separately, for every bullet that asserts a state of the tree: check the tree.

Bullets already carrying a `> **Closed / Discharged / Answered / Written …**` annotation, or
struck through with a replacement, are not open and were counted separately.

## 3. 계측 / Measurement

### 3.1 The corpus, and the split

| Report | §8 bullets | | Report | §8 bullets |
| --- | --- | --- | --- | --- |
| `R0` | 7 | | `R10` | 8 |
| `R1` | 9 | | `R11` | 8 |
| `R2` | 9 | | `R12` | 8 |
| `R3` | 7 | | `R13` | 6 |
| `R4` | 10 | | `R14` | 6 |
| `R5` | 6 | | `R15` | 7 |
| `R6` | 7 | | `R16` | 7 |
| `R7` | 7 | | `R17` | 8 |
| `R8` | 6 | | `R18` | 9 |
| `R9` | 10 | | **total** | **145** |

| | count |
| --- | --- |
| bullets classified | **145** |
| **risks** — discharging needs work, or nothing, and the report chose to live with it | **142** |
| **decisions** — discharging needs a judgement nobody has made | **2** |
| **a decision wearing a condition that may never arrive** | **1** |

Three rows follow: `OPEN-7`, `OPEN-8`, `OPEN-9`.

### 3.2 The two decisions, and the one underneath a condition

**`R15` §8 — *"No rule looks for correlated subqueries in migrations, and one could."*** Writing
it is possible today and `V3` is already fixed and gated by `MigrationDeduplicationTest`, so the
rule would protect **no migration that exists.** `AGENTS.md` §Scope names that case directly —
*a guard that protects nothing yet is not free, it is unbanked* — and the cost of a rule that
fires on correct text is measured here rather than feared, twice: `R7` §3.5 (the self-invocation
rule on Kotlin's `$default` bridges — *"a rule that is routinely wrong is a rule nobody reads"*)
and `R17` §5 (a prose check built, measured, discarded). Both sides are real. **Nobody has
chosen.** → `OPEN-7`.

**`R18` §8 — *"The verdict file is enforcement by procedure."*** The bullet places itself in the
category `R17` is a whole report about. `R18` §5 chose between four **instrument** designs;
whether enforcement should be machine-held was never on that table, and §7 states the CI position
as a fact rather than as an option — *"the load lane does not run in CI at all."* Three routes
exist and cost differently, and one of them — a wrapper that exits non-zero on a `DO NOT PUBLISH`
verdict — has never been priced. `ADR-004` constrains the design without choosing it: it forbids
CI asserting a duration and explicitly lists **verdicts** among the machine-independent
assertions CI may make. → `OPEN-8`.

**`R14` §8 — *"That is the next decision and it needs the endpoint that does not exist."***
The report calls it a decision and then conditions it on something that may never arrive, which
is the shape `ADR-003` condemned when it closed `OPEN-3`. **The condition itself is recorded
nowhere:** `docs/roadmap.md` *Deferred, deliberately* does not name a recording endpoint, and
`docs/explanation/domain-model.md` places **recommendation policy** out of scope while saying
explicitly that the layer underneath it is *"**not** out of scope"*. So the question `R14` waits
on is undecided rather than closed, and **the decidable one is a level down: whether the endpoint
exists at all, not what status code it returns.** → `OPEN-9`, worded as that question rather than
as `R14`'s.

### 3.3 What separated a decision from work, and it is not size

Four bullets have the identical surface shape — *X could be checked and nothing checks it*:

| Bullet | What the check would protect | |
| --- | --- | --- |
| `R3` §8 — *"Nothing asserts the plan… the most valuable test this report did not write"* | `V2`'s column order, which ships | work |
| `R11` §8 — *"A structural rule in the `T3` style… Not written."* | every handler taking a path variable, which ship | work |
| `R12` §8 — *"A structural rule could catch it; none is written."* | the native statement in `RecordingQueries`, which ships | work |
| `R15` §8 — *"No rule looks for correlated subqueries in migrations, and one could."* | **a migration that does not exist** | **judgement** |

**`R11`'s is the control, and it is the only one of the four that has been discharged.**
`AuthorisationRules` was written on 2026-08-14, four days after the bullet, by doing it — no
decision was required and none was recorded. That is what *work* looks like when it lands.

The line that separates the fourth is one sentence of `AGENTS.md`, and it is a rule about
sequence rather than about effort: a guard for code that exists is work, a guard for code that
does not is a judgement about whether to spend now. **Size, difficulty and value do not
separate these four.** `R3`'s missing test guards a measured 660× regression and is still only
work.

### 3.4 The second finding: a §8 bullet is corrected where the author happened to be looking

Twelve of the 145 have been falsified by later work. Where the correction went:

| | count | |
| --- | --- | --- |
| **annotated beside the bullet** — this repository's established shape | **7** | `R3`←`R13`, `R6`←`R12`, `R8`←`ADR-003`, `R9`←`ADR-003`, `R11`←`4c9f517`, `R12`←`ADR-006`, `R16`←`R18` |
| **answered elsewhere in the same file**, so the reader must find it | **2** | `R10` §8:233, `R4` §8:248 |
| **corrected nowhere** | **3** | `R1` §8:199, `R7` §8:209, `R12` §8:244 |

> **Amended 2026-08-17 — thirteen, not twelve, and the last row is four rather than three.**
> `R17` §8:256 said *"the workflow has not run on a GitHub runner yet"*, and at `b1c1b95`, the
> tree this table was counted on, that was already false: `docs-consistency.yml` had run three
> times, all green. §8 of this report named it as a candidate it could not check, because the
> Actions API needed a tool that was not on this machine. It was checked the same day and it
> had gone stale. **The corpus was right, the bullet was read, and the count was short by one.**
>
> **It is also the sharpest instance of §3.5's pattern in the repository, and it is one degree
> worse than any below.** `01e16af` edited `R17` §8 — it added the annotation to that section's
> *second* bullet — and **the push carrying that commit produced the run that falsified the
> seventh.** Not the same file open: the same section, in the same push. And the claim has no
> state in which it survives, because the next push after it writes is what makes it false.
>
> Corrected in `R17` §8 on 2026-08-17, with the measurement it had been waiting for. The
> original numbers are left standing above, for the reason this whole section is about.

The three counted at the time, verified against the working tree rather than against the
reports:

| Bullet | What it says | What is true | False since |
| --- | --- | --- | --- |
| `R7` §8:209 | *"`AttemptRecorder` still uses the naive pattern"* | `proxima.recording.mastery-update` defaults to **`atomic-guarded`**; the naive path is the red arm kept in the binary | **2026-08-13**, `4222b39` (`R12`) |
| `R1` §8:199 | *"`recordAll` stops at the first failure … is **not fixed**"* | `proxima.recording.batch` defaults to **`per-item-outcomes`**; every recording attempted, every outcome returned | **2026-08-14**, `b47b370` (`R14`) |
| `R12` §8:244 | *"Batch partiality is untouched … still stops at the first failure"* | the same | **2026-08-14**, `b47b370` (`R14`) |

**A fourth is half-corrected, in one commit.** `R16` §8's last bullet was struck through by
`b1c1b95` and replaced with `R18`'s measurement; `R16` §8's **second** bullet, four bullets
above it in the same file, still says the pool size *"was not varied"* about the variable that
commit had just varied. Both carried the same 미측정 in different words.

### 3.5 In two of the four, the correcting commit had the stale file open

`4222b39` is the sharpest. Its message enumerates what it annotated:

```
R6 §8 and R7 §5 are annotated in place rather than edited: the argument that
kept a measured defect alive for three days is worth more than a tidy file.
```

It did both, and the diff confirms it: seven lines added to `R7`, all of them in §5. **`R7` §8
was three sections further down the same open file, and it is the bullet naming the exact class
the commit changed.** The author was working from a list and §8 was not on it.

`b1c1b95` is the same shape inside one section: seventeen lines to `R16`, striking through the
last bullet of §8 and leaving the second.

`b47b370` is the other kind. It rewrote `AttemptRecorder`'s KDoc — the paragraph `R1` §8 is
quoted in — and opened **no report but its own.** `R1` and `R12` are not named anywhere in it,
and neither report names `R14` anywhere in it. Nothing was overlooked in a file; the files were
never opened.

> **Amended 2026-08-17 — three of five, and the one added is worse than either above.**
> `R17` §8:256, §3.4. `01e16af` had the file open **and the section open**: it edited that §8's
> second bullet while the push carrying it produced the run that falsified the seventh.

### 3.6 The two that were answered, and one of them is the counter-example

`R10` §8:233 said *"Two of three strands are not done … this application currently has no
authentication of any kind"*. `R11` landed the same day and `8762453` edited `R10`'s Status
block to say so:

> *token expiry and clock skew* — landed the same day in **`R11`**. §8's first bullet was
> written while they were still outstanding and is left as it stood; `R11` is the answer to it.

**That is the failure prevented, by a different mechanism**: the bullet is left standing and the
header names it. It costs the reader one jump and it is not a stale claim. Counting it with the
three would have been counting a case that worked.

`R4` §8:248 is the weaker half of the pair. `36de28f` added fifteen lines to `R4` §7 carrying
`R16`'s substance — *"`R16` also measures what `V3`'s unique constraint … is worth to this
endpoint: **15× on p99**"* — and never named §8, where the sentence **`mastery` is still
sequentially scanned** stands in the present tense. `R16` §3 quotes the plan both ways:
`Index Scan using uk_mastery_learner_concept` with the constraint, `Parallel Seq Scan` without.
The shipped schema has had it since `f3c03f6`, the day after `R4`.

### 3.7 What was checked and did not reproduce

`README.md`'s Status line says the nine traps are measured *"in twelve reports"* and there are
nineteen. It was written at `5b11926` on 2026-08-13, when twelve was also the total, which is
why it reads as one.

**It survives.** `docs/roadmap.md` places `R12`–`R18` under *After the traps* — seven reports —
and `R0`–`R11` are exactly the twelve that measured `T1`–`T9`. The sentence is scoped to the
traps and the scoped reading is true. It is left alone, and it is recorded here because a
finding that does not reproduce is also a result, and because `roadmap.md`'s own Status says
*"`T1`–`T9` are all done, in nineteen reports"* — the two documents count the same thing
differently and each is defensible.

## 4. 원인 / Mechanism

Two mechanisms, and they are not the same one twice.

**The shelf.** `open.md`'s table gains a row whenever a report is written, because a report is
where the judgement surfaces. Nothing has ever asked a report to check. Every one of the six
rows this document has held was found by whoever happened to be holding the relevant file —
`OPEN-6` by `R12`'s author reading `R12` §8, `OPEN-3` by `ADR-003` re-reading its own deadline.
That works exactly as far as the open file reaches, and the claim under the empty table was
about the whole tree.

**The bullet.** A §8 bullet is written **about the tree at one instant** and read **as a
standing risk**, and nothing marks the difference. *"`AttemptRecorder` still uses the naive
pattern"* is a true sentence on 2026-08-12 and a false one on 2026-08-14, and the text does not
change. §3 is a measurement and stays true because it is history. §7 names a file and check 1
of `docs-consistency.yml` verifies the file exists. **§8 is the only section of the template
that makes claims about a live tree, and nothing verifies any of them.**

Underneath both is a third thing, and it is the one worth carrying forward. Two of the three
uncorrected bullets are the **same sentence copied forward**: `R1` §8 wrote *"a requirement,
not a refactor"*, `R12` §8 repeated it a report later without retesting it, and `R14` §5 then
found a third option that needed no requirement at all. This repository has met that before and
named it — `R9` §8, on a bullet repeated verbatim from `R8` §8: **a risk that survives two
reports unchanged is not being carried, it is being avoided.** The sentence was right and it
was filed under measurement discipline; it is a rule about §8 in general.

## 5. 처방 / Remedy

Nothing in the application changes. Three things follow, and only the third is new text.

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave the three decisions in §8 | They stay somewhere nobody has to act on them. `open.md` says what that means and `OPEN-6` is the worked example | zero | no |
| **Open a row for each, and do not decide them** | The judgement is in front of the person whose judgement it is | three rows; the decisions are still owed | **yes** |
| Decide them here | Faster, and wrong: `AGENTS.md` says the session doing the work is not the one that calls it done, and two of the three have a stated cost the PO owns | — | **no** |
| **Annotate the falsified bullets** | Four reports stop asserting a tree that no longer exists | four annotations | **yes** |
| Delete the falsified sentences | Tidier, and it destroys the evidence. `R6` §8's annotation exists because *the argument that kept a measured defect in place for three days* is worth more than a tidy file | — | no |
| **A line in the report template** | The sweep has a home instead of depending on whoever is holding the file | procedure, and §7 says what that is worth here | **yes** |
| A check that reads §8 and finds the stale bullets | Would generalise all of it | **built, measured, discarded — §7** | no |

`docs/reports/_TEMPLATE.md` §8 gains two lines: which earlier §8 bullet this report falsifies,
and whether any bullet here needs a judgement rather than work. **Both are procedure and are
labelled as such** — this repository has a report about what procedure is worth (`R17`) and an
open row about the same gap in a different file (`OPEN-8`). It is put where a person is already
reading rather than in a document they would have to remember to open.

## 6. 재계측 / Re-measurement

Same corpus, same question, after this commit.

| Metric | Before (`b1c1b95`) | After |
| --- | --- | --- |
| §8 bullets classified | 145 | 145 |
| decisions filed as risks | **3** | **0** — `OPEN-7`, `OPEN-8`, `OPEN-9` |
| falsified bullets corrected nowhere | **3** | **0** |
| falsified bullets half-corrected | **1** | **0** |
| falsified bullets answered elsewhere in the file | 2 | 2 — left as they are, §3.6 |
| `open.md` Open table | empty, and the claim unestablished | 3 rows, and the claim withdrawn beside them |

> **Amended 2026-08-17.** The third row is wrong in both columns: `R17` §8:256 was falsified at
> `b1c1b95` too, so *before* was **4**, and this report's own commit left it standing, so
> *after* was **1** rather than 0. It reached **0** later the same day, in the commit carrying
> this annotation. §3.4 says why it was missed and §8 says what it cost.

The Open table is not the metric that improved. **`open.md`'s claim about it is** — it said an
empty table is a claim rather than a default, and what established it was that nobody had
recently thought of a row. The withdrawal is kept in the file in `OPEN-3`'s style, because how
it failed is more useful than the row.

## 7. 회귀 게이트 / Regression gate

**There is none, and this section is the argument for why — with the numbers from the one that
was built.**

A check for *"a §8 bullet a later commit falsified"* does not have to read prose. It can reuse
check 1's artefact extraction and check 2's date arithmetic: if a report's §8 names an artefact
whose file changed **after** the report's own last substantive change, flag it. That was
written and run over the corpus at `b1c1b95`.

**First shape, using check 1's token regex unchanged.** One finding — `R4` → `load/recommendations.js`
— and it is not one of the three. `AttemptRecorder`, `AttemptRecordingService` and `recordAll`
are all invisible to it: the regex matches only `*.kt`, `*Test`, `*Rules` and `*Queries`, and
**not one of the three falsified bullets names a token of that shape.** Recall 0 of 3.

**Second shape, widened to any backticked CamelCase identifier that resolves to a type declared
in the tree.** Four findings:

```
R1  -> RecordingFixture.kt        (2026-08-13)   FALSE POSITIVE -- R1 §8's REQUIRES_NEW bullet is still true
R6  -> AttemptRecorder.kt         (2026-08-14)   FALSE POSITIVE -- annotated 2026-08-13; a check cannot read an annotation
R7  -> AttemptRecorder.kt         (2026-08-14)   TRUE  POSITIVE
R8  -> AttemptRecorder.kt         (2026-08-14)   FALSE POSITIVE -- R8 §8's bullet is still true
```

**One of four correct, and it found one of the three.** The other two misses are worse than the
noise:

- `R1` §8:199 names `recordAll`, a method in lower case. The check flagged `R1` — **for the
  wrong bullet.** A finding that points at the right file and the wrong sentence costs a reader
  the same as no finding and looks like a hit.
- `R12` §8:244 names `AttemptRecordingService`, which **is** a declared type, and is invisible
  anyway: `fd27670` edited `R12` on 2026-08-14 and `b47b370` falsified it later the same day, so
  a comparison of *last changed* against *last changed* has nothing to compare. **`R17` §8 calls
  day resolution the largest hole in `docs-consistency.yml` and this is a second instance of it**,
  found by a different route in a different check.

So: precision 1 in 4, recall 1 in 3, and the miss that mattered most is hidden by a hole this
repository has already named and cannot close at this resolution. That is `R7` §3.5's rule
arrived at again — *a rule that is routinely wrong is a rule nobody reads* — and it is the same
verdict `R17` §5 reached on the prose check, by a different road. **Tuning it until it went
green would be lowering a threshold to get a green**, which is the move this repository forbids
in `R18` §5's second row and in `publication-readiness.md`'s negative control.

**And a gate here could not fail on the tree that ships**, for `R13` §7's reason stated one
layer up: after this commit there are no falsified-and-uncorrected bullets left, so any
assertion written now would pass because there is nothing to find rather than because the
repository is clean. `R13` §7 refused to write a test for that reason and this refuses for the
same one. Nothing about a passing check distinguishes those two states —
`publication-readiness.md` says so about the secret scanner and it is the general rule.

The script is not committed. It found one true finding in four and it would be a tenth test
class in a repository where **one gate of nine has ever fired** (`R0` §4); adding it would be
adding a promise, and this section is the record of what it measured instead.

## 8. 남는 위험 / Remaining risk

- **One reader, one pass, 145 judgement calls.** The bullet counts are countable and the split
  is not. `R0` §8's first bullet applies here unchanged — the author, the evidence and the
  scoring are one party — and this report is a second **session**, not a second party.
- **The line between judgement and work is one sentence of `AGENTS.md`.** §3.3 separates
  `R15`'s bullet from `R3`'s and `R12`'s on *does the guard protect code that exists*. A reader
  who drew it elsewhere would open one row or five. The line is argued rather than derived, and
  it is the load-bearing choice in this report.
- **`R0` §8's own example is stale and I left it.** It cites `R6` §8's *"the application still
  uses the second-worst arm"*, discharged by `R12` on the day `R0` landed. The bullet's claim —
  that nothing in `R0` measures the quality of the fixes — survives, and a reader following the
  citation lands on an annotated bullet and is corrected on arrival. **That is a judgement, not
  a rule**, and a fourth annotation would have been defensible.
- ~~**`R17` §8's CI-cost bullet may have gone stale and I could not check it.** It says *"the
  workflow has not run on a GitHub runner yet"*; three commits have been pushed since. Verifying
  it needs the Actions API, `gh` is not installed on this machine, and **미측정** is the honest
  output rather than an inference from the fact that pushes happened.~~

  **Checked 2026-08-17. It had. `docs-consistency.yml` had run three times on `ubuntu-latest`,
  all six jobs green, the first on the push carrying `01e16af`** — so it was already false at
  `b1c1b95`, and **§3.4's twelve should have been thirteen.** Amended there rather than here,
  and `R17` §8:256 is corrected in its own file with the timings it had been waiting for. The
  refusal to infer was right and the inference would have been right too; those are different
  things, and only one of them is a measurement.

  **§7 stays at the figures it was scored with, and one of them moves anyway.** Its precision —
  one correct finding in four — is over a fixed set at a named tree, and re-scoring it against
  a corpus it was not taken on is the move it exists to refuse. Its **recall** is a ratio to the
  ground truth, and the ground truth grew: **1 in 3 becomes 1 in 4**, against the check. It
  could not have caught this one under either shape — `R17` §8:256 names a workflow and a walk,
  and no `*.kt`, `*Test`, `*Rules`, `*Queries` token or declared type at all. **That is the
  third distinct way the check was blind**, after the wrong-bullet hit and the day resolution,
  and it is recorded here because §7 may not be re-scored.

  **What replaced the 미측정 is a smaller one, and it is the lane's.** `ADR-004` requires a
  quoted CI number to carry that run's environment block, and only `build.yml` prints one; the
  logs that would supply it for `docs-consistency.yml` need authentication. So `R17` §8 now
  carries seconds with a run id, a job id, a runner name and timestamps, and **without the block
  the rule asks for** — stated there rather than smoothed over. Nothing in this repository can
  fix that by writing more carefully.
- **§7's numbers are one tree and four findings.** Precision 1 in 4 at `b1c1b95` is not a rate,
  and a corpus with more falsified bullets could make the same check look better or worse.
- **Nothing here read code comments.** The corpus was §8 sections. `R17` §8's last bullet says
  several of this repository's load-bearing claims live in KDoc, and `R14` is an entire report
  about two of them contradicting each other three inches apart. **The same sweep over KDoc is
  미측정**, and `R14`'s existence is evidence it would find something.
- **The remedy in §5 is procedure and is enforced by nothing.** A line in a template is read by
  whoever opens the template. `R17` measured what that is worth over seven days — three failures,
  two found by the PO asking — and `OPEN-8` is the same gap in `load/README.md`. This report
  adds a third instance of the pattern it is about, knowingly, because §7 is why.
- **Three rows were opened and none was decided**, which is correct and is also a cost: two of
  the three say their own deadline is *now*, so the repository is carrying three questions whose
  honest deadline has already passed.
- **What would break this conclusion:** a second reader finding that one of `OPEN-7`, `OPEN-8`
  or `OPEN-9` is work rather than judgement — most likely `OPEN-8`, whose cheapest route is a
  wrapper script nobody has priced, and where a low enough price would turn the row into a task.
  `R0` §8 asked for exactly this kind of review and it is still owed: **this report reviews §8
  sections, not the measurements**, so it does not discharge that bullet and does not claim to.

## 9. 배운 것 / What I learned

제일 놀란 건 발견 자체가 아니라 **발견이 앉아 있던 자리**였습니다. `R12`는 커밋 메시지에
*"R6 §8과 R7 §5를 제자리에 주석했다"* 고 자기가 한 일을 열거해 놨습니다. 목록을 만들 만큼
의식하고 있었고, 그 목록이 `R7` §8에 닿지 않았습니다. **같은 파일이 열려 있었고 세 섹션
아래였습니다.** 저는 이런 종류의 누락이 파일을 안 열어서 생긴다고 생각했는데, 세 건 중 두 건은
파일이 열려 있었습니다. 열려 있는 것과 보고 있는 것은 다릅니다.

두 번째로 배운 건 **위험과 결정을 가르는 기준이 크기가 아니라는 것**입니다. 처음엔 "이건 큰
판단이고 저건 작은 작업"으로 나눌 수 있을 줄 알았는데, `R3` §8이 못 쓴 테스트는 660배 회귀를
막는 것이고 그냥 작업입니다. `R15` §8의 규칙은 훨씬 작고 결정입니다. 갈라놓은 건 **그 가드가
지금 존재하는 코드를 지키느냐**였고, 그건 `AGENTS.md` 한 문장이었습니다. 그리고 그 기준이
맞는지 확인해 준 건 `R11` §8이었습니다 — 같은 모양의 항목이 나흘 뒤에 **아무 결정 없이 그냥
작성돼서** 닫혔습니다. 대조군이 있어서 기준을 주장이 아니라 확인으로 쓸 수 있었습니다.

§7이 제일 불편했습니다. 검사를 만들면 이 리포트가 훨씬 그럴듯해집니다. 만들어서 돌렸더니 넷 중
하나 맞았고, 놓친 셋 중 하나는 **`R17` §8이 이미 "가장 큰 구멍"이라고 적어둔 날짜 해상도**에
가려져 있었습니다 — 같은 날 안에서 문서가 고쳐지고 몇 시간 뒤에 거짓이 됐습니다. 임계값을
낮춰서 초록으로 만들고 싶은 유혹이 실제로 있었고, 그게 `R18` §5가 두 번째 줄에서 거절한 바로
그 수였습니다. **못 만든 걸 적는 게 만든 척하는 것보다 이 저장소에서 더 값이 나갑니다.**

마지막으로, 빈 표가 거짓이었던 방식이 계속 남습니다. 그 문장은 *"빈 표는 기본값이 아니라
주장"* 이라고 스스로 더 높은 기준을 요구해 놓고, 그 주장을 뒷받침한 건 **최근에 아무도 행을
떠올리지 않았다는 사실**이었습니다. 규칙을 쓰는 것과 그 규칙을 자기한테 적용하는 것 사이의
거리가 사흘이었습니다. `R17`이 사람이 유일한 탐지기였던 가드에 대한 리포트인데, **이 표의
유일한 탐지기도 사람이었습니다.** 같은 결함을 다른 파일에서 두 번째로 만난 겁니다.
