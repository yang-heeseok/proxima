# R17. The guard that was a person

> **Created**: 2026-08-17
> **Updated**: 2026-08-18
> **Red commit**: `36de28f` — the tree the PO's question landed on. **Eighteen findings**, of
> which the question found two.
> **Green commit**: this one — the four checks, and the eighteen discharged.
> **Found by**: being asked twice. §1

```
증거 / What the evidence here is
  Not durations. This report measures a repository against itself, so the hardware block
  the template carries would be hardware none of these numbers came from — R0 does the
  same and for the same reason.

  Instrument   : .github/workflows/docs-consistency.yml, run locally against detached
                 worktrees of each commit named below
  Corpus       : every tracked *.md — 33 files at HEAD, of which 18 carry an Updated date
  History      : full clone. Every number here depends on `git log` per file, and a
                 shallow clone answers those questions wrongly rather than not at all
  Repetitions  : the checks are deterministic over a fixed tree. One run per commit
  Dates        : read from the machine clock, not from memory. §9 says why that line is here
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

On 2026-08-14 the PO asked three questions, one of which was *관련 문서 모두 업데이트?* —
are all the related documents up to date.

The answer was no. `docs/roadmap.md` said **"Nothing below is done"** with nine items done in
seventeen reports, and `docs/explanation/domain-model.md` said **"No domain entities exist
yet"** with five in the tree. Both had been true on 2026-08-10 and nobody had read them since.

Both were corrected. **The correcting pass left a third:** `roadmap.md`'s `T1` row still said
*"No regression gate exists, and `R4` §7 says so rather than implying one"* — while `R4` §7
named `ConnectionHoldingGateTest.kt`, which landed in `d30e326` on 2026-08-12, the commit
after the one that wrote the sentence. It was found because the PO asked a second time.

That is the symptom, and it is not "two stale documents":

> **The only instrument that has ever detected this class of defect in this repository is a
> person being suspicious.** `publication-readiness.md` records the same failure on
> 2026-08-10 — three documents stale in the hour the build landed — and its row for it has
> been `reviewed`, never `observed`, since the day it was written.

## 2. 재현 / Reproduction

```bash
git worktree add -q --detach /tmp/red 36de28f
bash .github/workflows/…            # the four checks, extracted; see §7 for the workflow
```

`36de28f` is the tip at the moment the question was asked. Every commit before it is also red;
this one is chosen because it is the one a human audited.

The checks need **full history**. Two of them ask when a file last changed, and
`actions/checkout` defaults to `fetch-depth: 1`, on which `git log -- <file>` returns the one
commit it has. The checks would then pass on every document forever — so the workflow sets
`fetch-depth: 0` **and asserts the clone is deep enough**, because a guard that silently
becomes vacuous is the thing this repository keeps finding.

## 3. 계측 / Measurement

### 3.1 The four checks

| | What it refuses |
| --- | --- |
| **1** | A document names an artefact — a file, a migration, a test class — that is not in the tree. A line that marks the artefact as unwritten (`(to come)`, `미작성`) is exempt, and the marker must be on the **same line** so it cannot be established once in a paragraph and relied on for a whole table |
| **2** | An `Updated` date that does not equal the document's **last substantive change** — in both directions. *BEHIND*: edited without dating. *AHEAD*: a date moved forward over content that did not change |
| **3** | A report exists that `docs/roadmap.md` never names |
| **4** | A report whose §8 *남는 위험* is empty |

Commits whose only change to a file is the `Updated` line are **excluded** from "last
substantive change". Without that exclusion the commit that *fixes* a stale date would count
as the change it is dating, and check 2 could never be satisfied by anything.

### 3.2 The count, at four points in this repository's history

| Tree | check 1 | check 2 | check 3 | check 4 | **total** |
| --- | ---: | ---: | ---: | ---: | ---: |
| `36de28f` — **the tree the question landed on** | 0 | **16** | **2** | 0 | **18** |
| `973865e` — after the first correcting pass | 0 | 10 | 1 | 0 | 11 |
| `2b3e1b1` — after the second correcting pass | 0 | 10 | 0 | 0 | 10 |
| `211680c` — after publishing `.study/` | 0 | 10 | 0 | 0 | **10** |
| this commit | **0** | **0** | **0** | **0** | **0** |

> **Two rounds of human auditing, both prompted by the PO, moved 18 to 10.** Everything in
> that remaining 10 was in the tree the whole time, in files anyone could open.

### 3.3 What the question found, and what it did not

Both documents the PO caught are inside check 2's sixteen, verbatim from the run:

```
BEHIND docs/roadmap.md                      Updated=2026-08-10  last substantive change=2026-08-14
BEHIND docs/explanation/domain-model.md     Updated=2026-08-10  last substantive change=2026-08-14
```

The fourteen it did not find, on the same tree, same run:

```
BEHIND AGENTS.md                            Updated=2026-08-10  changed=2026-08-11
BEHIND README.md                            Updated=2026-08-13  changed=2026-08-14
BEHIND docs/decisions/open.md               Updated=2026-08-10  changed=2026-08-14
BEHIND docs/decisions/publication-readiness.md   Updated=2026-08-10  changed=2026-08-11
BEHIND docs/decisions/adr/ADR-004-numbers-that-cross-machines.md  Updated=2026-08-13  changed=2026-08-14
BEHIND docs/explanation/measurement-discipline.md  Updated=2026-08-10  changed=2026-08-13
BEHIND seed/README.md                       Updated=2026-08-10  changed=2026-08-14
BEHIND docs/reports/R3, R4, R6, R7, R8, R11, R12
```

### 3.4 The AHEAD finding is this session's own

Check 2's other direction fired exactly once, and on a commit written while auditing for this
very defect:

```
AHEAD  docs/explanation/measurement-discipline.md   Updated=2026-08-14  changed=2026-08-13
```

`973865e` bumped `Updated` on **four** documents that it did not otherwise edit. Two were
noticed by hand that day — `AGENTS.md` was reverted, `publication-readiness.md` was given a
real edit that earned its date — **and two were not, and stayed wrong for three days.**

`AGENTS.md` is worse than that. The revert put it back to `2026-08-10`, and its last real edit
was `2026-08-11`. **The correction overshot into the other failure**, and it is in the BEHIND
list above as a result. Both errors were made by the same pass that was looking for them.

## 4. 원인 / Mechanism

**A repository can see a diff. It cannot see a sentence stop being true.**

Every other guard here refuses something that arrives *in* a commit — a secret, a `.csv`, a
personal identifier, an `@Transactional` self-invocation. Each is decidable by looking at the
commit that introduces it.

This defect has no such commit. The commit that made `"Nothing below is done"` false was the
one that finished `T1`, and it touched no document. **The change and the falsehood are in
different files, and nothing joins them** — so there is no diff at which review could have
caught it, and no reviewer was negligent.

That is why the `PUB-4` row for it has been `reviewed` rather than `observed` since 2026-08-10,
and why that word was doing more work than it looked like:

> `publication-readiness.md`: *"Rows marked **reviewed** are established by a person looking,
> and are the weaker kind — a row that stays 'reviewed' for long is a candidate for being made
> observable."*

The row said so about itself, for seven days, in the document it discharges.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Keep reviewing, more carefully | Nothing. The record is three failures in seven days, two of them found by the PO rather than by the author, and one *introduced by the review itself* | zero | no |
| **Read the prose** — look for phrases like "nothing is done" and compare against the State column | **Built and measured. Fires correctly on the red tree — and also on the corrected one** | — | **no, see below** |
| **Check structure** — artefact existence, date arithmetic, inventory completeness | Catches 18 of 18 on the red tree, including both documents the PO found | a workflow and its self-test | **yes** |
| Require every document to declare machine-checkable claims inline | Would catch the same-day case §8 names | invasive; every document gains a syntax, and a claim nobody registers is invisible | no — recorded, not done |

### The prose check was written, measured, and discarded

It compared a `**Status:**` block against the State column, and on `36de28f` it fired: thirteen
rows marked `done`, one phrase saying nothing was.

**It also fired on the corrected tree**, twice, on these two lines:

```
> *"Nothing below is done"* for four days after the first item landed. Corrected 2026-08-14 —
> **and the same pass left `T1`'s cell claiming no regression gate existed**, two days after
```

Both are *descriptions of a past claim*. A keyword check cannot tell one from an actual claim,
and there is no amount of tuning — excluding blockquotes, excluding italics — that fixes that
rather than hiding it on this particular corpus. Lowering it until it went green is exactly
the move this repository forbids elsewhere.

**Nothing was lost.** Check 2 flags `roadmap.md` on the same tree for the same underlying
reason, by arithmetic instead of by reading. The prose check was the only one of the five that
read a sentence, and the only one that produced a false positive.

## 6. 재계측 / Re-measurement

| | Red `36de28f` | Green — this commit |
| --- | ---: | ---: |
| Documents naming an artefact that does not exist | 0 | 0 |
| `Updated` dates that do not describe their document | **16** | **0** |
| Reports with no roadmap row | **2** | **0** |
| Reports with an empty §8 | 0 | 0 |
| **Total** | **18** | **0** |
| What detects a recurrence | **a person, if they think to look** | `docs-consistency.yml`, every push |

The 18 were discharged by fixing them, not by narrowing the checks: ten dates corrected to the
date of each document's last real edit, and `R15`/`R16` given the roadmap rows they never had
(`2b3e1b1`).

## 7. 회귀 게이트 / Regression gate

`.github/workflows/docs-consistency.yml`, two jobs.

The **guard** job runs the four checks of §3.1 on every push and pull request, on a full clone,
after asserting the clone is deep enough for the history checks to mean anything.

The **self-test** job plants violations in a scratch repository and requires each check to fire
— and requires the clean counterparts *not* to fire, which is the half that matters:

| Planted | Must fire | Must **not** fire |
| --- | --- | --- |
| `NoSuchThing.kt`, `AlsoMissing.yml` named in a document *(planted)* | check 1 | — |
| the same shape on a line marked `(to come)` | — | check 1. **The exemption is the point** |
| a file edited on 2020-06-01 carrying `Updated: 2020-01-01` | check 2 BEHIND | — |
| a later commit that changes **only** the `Updated` line | — | check 2. Without this the check is unsatisfiable and would fail forever after its own first fix |
| two reports, neither named by the roadmap | check 3 | — |
| a report with an empty §8, beside one with a real §8 | check 4 | check 4 on the real one |

The negative half exists because this repository has now found five instruments that reported
into nothing — `R5`'s appender, `R8`'s statistics, `R10`'s canary, `R15`'s threshold
placeholder, `R16`'s `body is not empty` with a `rate>=0.0` threshold. A sixth would be this
one.

## 8. 남는 위험 / Remaining risk

- **The largest hole: a document edited on the same day it becomes false.** Check 2 compares
  dates at day resolution, so a document touched today and made false today passes. Every
  failure in §1 happened to span days, and that is luck rather than design.
- **No check reads a claim.** §5 explains why the one that tried was discarded. The class of
  defect that started this — *a sentence asserting a world-state* — is caught **only**
  through the proxy of a date. A document that is genuinely up to date and simply says
  something false about the tree passes all four checks. **The word `done` in the roadmap's
  State column is not verified against anything.**

  > **This bullet acquired an example within the hour.** With all four checks green,
  > `publication-readiness.md`'s own Status line still read *"No defect has been reproduced
  > and no report exists yet"* — in the document that owns `PUB-4`, in the commit that added
  > the guard, with eighteen reports in the tree. Its `Updated` date was correct, so check 2
  > had nothing to say; it names no missing artefact, so check 1 had nothing to say. **The
  > gate is green and the sentence is false, and both of those are true at once.** That is
  > the shape of what remains, stated with a case rather than as a caveat.
- **Check 1 has never fired on this repository.** 0 findings at every commit measured. It has
  been watched refusing planted violations and nothing else — the same status the four
  ArchUnit rules in `R1`'s appendix ② carry, and the same caveat applies: blocking and
  reproducing are different claims.
- **`.study/` is excluded from check 2** because those notes carry `작성`/`개정` rather than
  `Updated`. That is 3,720 lines outside the date check, and the exclusion is a decision, not
  an oversight — but it is untested by any planted violation.
- **Check 4 sees a heading, not honesty.** `publication-readiness.md` already said so; making
  the heading observable does not make it true, and a report can satisfy it with three lines
  of nothing.
- **Check 3 verifies the roadmap names every report. It does not verify the row is correct** —
  `2b3e1b1` fixed a roadmap cell that was false while the row existed, and check 3 would have
  passed on it.
- ~~**미측정: CI cost.** The workflow has not run on a GitHub runner yet. `fetch-depth: 0` on a
  30-commit repository is cheap and the per-file `git log` walk is O(files × commits); neither
  has been timed there, and `ADR-004` forbids quoting a local number as a CI one.~~

  **Measured 2026-08-17. This bullet made three claims and each was wrong differently.**

  **It had already run.** `docs-consistency.yml` has run on `ubuntu-latest` three times, all
  six jobs `success`, read from the Actions REST API:

  | run | commit | started (UTC) | guard | self-test |
  | --- | --- | --- | --- | --- |
  | `31997246739` | `01e16af` | 05:15:16Z | **7 s** | **3 s** |
  | `32006472600` | `b1c1b95` | 07:35:16Z | **5 s** | **5 s** |
  | `32022191057` | `1761fcb` | 10:52:12Z | **7 s** | **5 s** |

  The first of those is the push carrying `01e16af` — **the commit that added the annotation
  two bullets above this one.** The claim cannot be written in a state where it stays true: the
  next push after it falsifies it, and here that was the push it travelled in. Three pushes
  later nothing had said so.

  **The walk is cheap, and the step timings say where the time is not.** Whole seconds, per
  step, from those same three runs:

  | step | `01e16af` | `b1c1b95` | `1761fcb` |
  | --- | --- | --- | --- |
  | `actions/checkout`, `fetch-depth: 0` | 2 | 1 | 1 |
  | check 1 — every named artefact exists | 0 | 1 | 1 |
  | **check 2 — the per-file `git log` walk** | **0** | **0** | **0** |
  | check 3 — every report has a roadmap row | 0 | 0 | 0 |
  | check 4 — §8 non-empty | 1 | 0 | 0 |

  **The four checks together account for 1 s of a 5–7 s job.** The rest is job setup, the
  checkout, and teardown. The O(files × commits) term the bullet worried about is below
  whole-second resolution at **36 documents × 62 commits** — where 36 is check 2's actual
  working set, `*.md` outside `.study/` carrying an `Updated` date.

  **And this repository was never 30 commits.** It held **58** at `30ec1e9`, the commit where
  that sentence was written, and 62 now; it last held 30 on 2026-08-12. The figure appears
  nowhere else in the tree and nothing produced it. It was an estimate in a sentence whose
  subject is that estimates are not numbers.

  **What is still 미측정 is not the durations — it is whether they may be cited.** `ADR-004`
  requires a report quoting a CI number to carry **that run's environment block**: *"Not
  'GitHub Actions'. The block."* The step that prints one is in `build.yml` alone.
  `docs-consistency.yml` has never printed one, and the job logs that would carry the runner
  image answer `403` without authentication. So the seconds above carry a run id, a job id, a
  runner name, the label `ubuntu-latest` and UTC timestamps, **and not the block the rule asks
  for.** They are quoted as themselves and combined with nothing — what `ADR-004` was written
  after was a local number divided by a remote one, and there is no division here. **The gap
  belongs to the lane, not to this bullet**, and no report can close it by trying harder.

  > **Closed at the lane, 2026-08-18 — and not retroactively.** `docs-consistency.yml` now
  > prints the block, copied from `build.yml`'s step. **The three runs above still have none**,
  > because they ran before the step existed, and their seconds stay labelled as they are
  > rather than acquiring a block from a later run they were not taken in. Rule 3 forbids that
  > substitution as surely as it forbids the division. **What changed is the next citation, not
  > this one.**

  Also 미측정: how any of this scales. Three runs on a shared runner at one repository size say
  nothing about where the walk stops being free, and a term that is currently a fraction of a
  second is invisible to an instrument whose resolution is a second.
- **What would break this conclusion:** the checks are only as good as the corpus they scan.
  A document moved outside `*.md`, or a claim moved out of prose into a diagram or a code
  comment, leaves the corpus entirely — and code comments are where several of this
  repository's load-bearing claims live (`R14` is a report about two of them contradicting
  each other three inches apart, in a file no check here reads).

## 9. 배운 것 / What I learned

`PUB-4`는 이 저장소에서 가장 엄격한 조항이고, 그걸 지키는 장치가 **사람 한 명**이었다는 걸
일곱 날 동안 몰랐습니다. 더 정확히는, `publication-readiness.md`가 그 행을 `reviewed`라고
적어놓고 *"오래 `reviewed`로 남아 있는 행은 관측 가능하게 만들 후보"* 라고까지 써놨는데,
그 문장을 제가 여러 번 읽으면서 그게 **저를 가리키고 있다는 걸** 못 봤습니다.

그리고 두 번의 감사가 18건 중 2건을 잡았다는 숫자보다 더 아픈 게 있습니다. **감사 자체가
결함을 만들었습니다.** `973865e`에서 네 문서의 날짜를 내용도 안 고치고 올렸고, 그중 둘만
알아채고 되돌렸고, `AGENTS.md`는 되돌리다가 **반대 방향으로 지나쳐서** 지금 BEHIND 목록에
있습니다. 낡은 주장을 찾는 작업이 낡은 주장을 세 개 남기고 나왔습니다.

오늘 하나 더 했습니다. **시계를 읽지 않고 날짜를 썼습니다.** 세션 중간에 온 시스템 메시지가
`2026-08-15`라고 했고 저는 그걸 그대로 `.study/`와 `.gitignore`에 박아 넣었습니다. 실제
기계 시각은 `2026-08-17`이었고, `date -Is` 한 번이면 끝날 일이었습니다. 규칙 9 — *버전을
기억으로 쓰지 않는다* — 는 버전에 대한 규칙이지만 **날짜도 정확히 같은 물건**입니다. 확인
비용이 명령어 하나인 것을 확인 없이 쓰는 실패는 `.study/T1 예측` §8.1이 이미 적어둔 것과
같은 모양이고, 그 문서를 오늘 발행하면서 그 옆에서 다시 했습니다.

제일 놀란 건 산문 검사를 버리기로 한 순간입니다. 그건 **가장 똑똑한 검사였고 유일하게
틀린 검사**였습니다 — 고쳐진 문서가 옛 문장을 인용한다는 이유로 걸렸으니까요. blockquote를
빼면 통과했을 겁니다. 그런데 그건 이 코퍼스에서만 통하는 조정이고, 통과시키려고 임계값을
내리는 것과 구분이 안 됩니다. **버리고 나니 커버리지가 그대로였습니다.** 날짜 산술이 문장을
읽지 않고도 같은 두 문서를 잡고 있었습니다. 문장을 읽는 검사가 필요하다고 믿은 게 제
가정이었고, 그 가정이 틀린 게 이 리포트에서 제일 값싼 발견이었습니다.
