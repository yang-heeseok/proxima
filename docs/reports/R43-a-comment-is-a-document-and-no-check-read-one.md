# R43. A comment is a document, and no check here read one

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `5f03310` — CHECK 5 added, comments untouched. The guard job fails with two findings
> **Green commit**: `2c7f16d` — both claims corrected. Two findings to zero
> **Strengthened**: `7ee5552` — the check was refusing correct text one backtick away, and now does not
> **Found by**: `R17` §8's last bullet, which filed this as a limitation while it was already a defect

```
증거 / What the evidence here is
  Not durations. This report measures a repository against itself, so the hardware block
  the template carries would be hardware none of these numbers came from -- R0 and R17 do
  the same and for the same reason.

  Instrument   : .github/workflows/docs-consistency.yml CHECK 5, extracted from the YAML
                 by a parser rather than retyped, and run against the worktree
  Corpus       : every tracked *.kt and *.java -- 93 files, 552 comment blocks
  Schema       : the five migrations on the classpath, V1 through V5
  Repetitions  : deterministic over a fixed tree. One run per state
  Dates        : read from `git log`, not from memory. R17 section 9 is why that line is here
  NOT measured : this check has never run on a GitHub runner. Section 8
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Two comments in this repository said, in the present tense, that a table had no index that had
been in the schema for ten days.

`RecommendationQueries` — the KDoc a reader of the recommendation query actually reads:

> *Computing that means a second pass over `attempt`, which is three million rows with **no
> index on `(learner_id, attempted_at)`** — absent on purpose, see `ADR-002`.*

`RecommendationService`, four inches from the constant it justifies:

> *That is a second pass over three million `attempt` rows on a schema with no index for it,
> so the band is fixed for now.*

`V2__attempt_learner_time_index.sql` creates `ix_attempt_learner_attempted_at (learner_id,
attempted_at)`. It landed in `8bbe8b9` on **2026-08-12**. Both sentences were false from that
morning and were read as current until **2026-08-22**.

**And the false sentence was not decoration.** It is the stated justification for step 4 of the
documented recommendation rule not being implemented — the deviation `RecommendationQueries`
itself calls *a real deviation from the documented rule*. The reason expired, the deviation did
not, and the comment went on supplying a reason that no longer existed.

That is the symptom. The defect is one level up:

> **Four checks guard the documents in this repository and none of them has ever read a code
> comment.** CHECK 1 opens every `.kt` in the tree — and reads it **only** to harvest
> `class|object|interface` names, so that a `.md` naming a type resolves. The prose in those
> files was never a document to any check here.

## 2. 재현 / Reproduction

```bash
git checkout 5f03310
python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/docs-consistency.yml')); open('/tmp/c5.sh','w').write(d['jobs']['guard']['steps'][-1]['run'])"
bash /tmp/c5.sh; echo "exit=$?"
```

The script is extracted by a parser rather than retyped, because a check quoted by hand into a
report is a check the report is no longer measuring.

**It must be run where `git ls-files` works.** §3.5 is what happens when it is not.

## 3. 계측 / Measurement

### 3.1 The corpus

| | count | |
| --- | ---: | --- |
| tracked `*.kt` + `*.java` | **93** | |
| comment blocks in them | **552** | every line-comment run and every block comment |
| of those, KDoc | **380** | what CHECK 5 reads |
| outside the scope | **172** | §8's first bullet |
| migrations | **5** | `V1`–`V5` |
| tables a migration gives a `create index` | **2** | `attempt`, `concept_edge` |
| KDoc blocks naming one of those two tables | **22** | the check's live surface |
| KDoc blocks containing a denial phrase | **3** | of which two were findings |

### 3.2 The findings, verbatim from the red tree

```
api/src/main/kotlin/net/gseek/proxima/recommendation/RecommendationQueries.kt:9 denies an index on `attempt`, and a migration creates one:
    ix_attempt_learner_attempted_at (learner_id,attempted_at) created by api/src/main/resources/db/migration/V2__attempt_learner_time_index.sql
api/src/main/kotlin/net/gseek/proxima/recommendation/RecommendationService.kt:69 denies an index on `attempt`, and a migration creates one:
    ix_attempt_learner_attempted_at (learner_id,attempted_at) created by api/src/main/resources/db/migration/V2__attempt_learner_time_index.sql

FAIL: a KDoc comment denies an index that a migration creates.
```

`exit=1`.

### 3.3 Run against every comment it finds three, and the third is `R17` §5 again

| scope | findings | false | true |
| --- | ---: | ---: | ---: |
| every comment block | **3** | **1** | 2 |
| KDoc only — **shipped** | **2** | **0** | 2 |

The third is `BaselineMigrationTest.kt:97`:

> *This assertion started as "there must be NO performance index", which was correct for as
> long as V1 was the whole story. V2 added one, and the test changed in the same commit as the
> report that measured it.*

That is a **description of a past claim**, and it is word for word the failure `R17` §5
discarded its prose check for:

> `R17` §5: *"It **also fired on the CORRECTED tree**, twice… Both are descriptions of a past
> claim. A keyword check cannot tell one from an actual claim."*

The axis reproduced that failure at **1 site in 3** on its first run.

**The narrowing is structural rather than a tuned exclusion**, and that distinction is the whole
defence. KDoc documents the declaration it is attached to and is rendered to a reader as what
that declaration *is*; a body comment narrates to a maintainer. No phrase was added to an
exclusion list and no threshold moved. What it costs is 172 blocks and is §8's first bullet.

**Honest about the order: the principle was found *by* the false positive, not before it.** §9.

### 3.4 The check fired on a sentence that is true, and was silent only by luck

`Attempt.kt`'s KDoc says:

> *`V1` carries no index on `(learner_id, attempted_at)` on purpose so that the first `EXPLAIN`
> shows a sequential scan.*

**That is true.** `V1` carries none; `V2` added it. The check was silent on it at `5f03310` —
and silent for the wrong reason: because that KDoc happens not to write the word `attempt` in
backticks. Backtick it, change nothing else:

| tree | the `Attempt.kt` claim | verdict |
| --- | --- | ---: |
| `5f03310`, table name backticked | true, scoped to `V1` | **FAIL** — refusing correct text |
| `7ee5552`, table name backticked | true, scoped to `V1` | OK |
| `7ee5552`, same line rewritten to `V5` | false | **FAIL** |

`ADR-007`'s third reason for rejecting a text rule — *a rule that fires on correct text is a
rule nobody reads* — was sitting one word inside the guard written in the round that quotes it.
`7ee5552` makes a block that names migrations judged **as of the highest one it names**, which
is the scope the claim declares in its own text. An unscoped claim is still judged against the
whole tree, which is the safe direction.

### 3.5 The green was vacuous once before it was real

Verifying CHECK 5 from WSL2, it printed `OK` on the tree that has two findings in it.

`git ls-files` had failed — a git worktree's `.git` is a pointer to a Windows absolute path that
WSL cannot resolve — so both harvested lists came back empty and the check refused nothing:

```
fatal: not a git repository: /mnt/c/project/airtown/proxima-h/C:/project/airtown/proxima/.git/worktrees/proxima-h
OK: no KDoc denies an index a migration creates.
```

It was caught **only because that green was expected and arrived too easily.** That is not an
instrument, it is a mood. `2c7f16d` asserts both inputs are non-empty and fails if either is —
`ADR-017`'s rule for `S3`, *a guard that stops finding its input and reports OK*, applied to the
guard written in the round that quotes it. This is the **sixth** instrument in this repository
found reporting into nothing; `R17` §7 lists the first five.

## 4. 원인 / Mechanism

**A code comment is a document that no tool in this repository treated as one.**

`docs-consistency.yml` exists because *a repository can see a diff and cannot see a sentence stop
being true* (`R17` §4). Its four checks defined "document" as `*.md`. That definition was never
argued for — it was the file extension the first defect happened to be in.

The commit that made these two sentences false was `8bbe8b9`, which added `V2` and its report. It
touched neither `.kt` file. **The change and the falsehood are in different files and nothing
joined them** — `R17`'s mechanism exactly, one corpus over.

And the corpus gap was written down before it was a defect. `R17` §8, last bullet:

> *"the checks are only as good as the corpus they scan. A document moved outside `*.md`, or a
> claim moved out of prose into a diagram or **a code comment**, leaves the corpus entirely — and
> code comments are where several of this repository's load-bearing claims live (`R14` is a
> report about two of them contradicting each other three inches apart, in a file no check here
> reads)."*

It was filed as a limitation on 2026-08-17. `V2` had made it a live defect on 2026-08-12, five
days earlier. **The bullet describing the hole was written after the hole had already swallowed
something**, by an author who had both files available.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Correct the two comments | The two sentences become true. Nothing detects the next one | zero | **no — the remedy `R17` §5 already rejected.** `R17` §3.2 measured what reviewing harder is worth: two human audits moved 18 findings to 10 |
| A general natural-language checker over comments | Would reach claims no structural rule can | `R17` §5 built one, measured it firing on a corrected tree, and discarded it. `ADR-007` reason 3 refuses it again | no |
| Register machine-checkable claims inline in every comment | Would reach every claim | `R17` §5: *"invasive; every document gains a syntax, and a claim nobody registers is invisible"* | no |
| **One narrow axis: an index-existence claim, resolved against the migrations** | Catches both live findings and everything of that shape. A claim nobody marks is **caught**, not invisible — the default is FAIL and registration is the escape | one workflow step, one self-test, and 172 comment blocks out of scope | **yes** |

**Why the exemption marker is not the objection `R17` §5 raised.** That objection was against
*enrolment* — a claim nobody registers is never checked. Here the direction is inverted: every
denial is a finding unless a human writes `INDEX-CLAIM-EXEMPT` on the block. The unmarked case is
the caught case. The marker is still abusable and §8 says so.

**What would have made a different option correct.** If the load-bearing claims in this
repository's comments were mostly *not* about resolvable schema objects, a structural axis would
reach almost nothing and the discarded prose check would be the only option that reached
anything. §8's sixth bullet is the count that would settle that, and it is 미측정.

## 6. 재계측 / Re-measurement

Identical conditions to §3 — same extraction, same worktree, same corpus.

| | Red `5f03310` | Green `2c7f16d` | Strengthened `7ee5552` |
| --- | ---: | ---: | ---: |
| KDoc blocks denying an index a migration creates | **2** | **0** | **0** |
| guard exit code | **1** | 0 | 0 |
| findings on a *true* `V1`-scoped claim, table backticked | **1** | 1 | **0** |
| findings on the same claim rewritten to `V5` | 1 | 1 | **1** |
| behaviour on empty input | **`OK`** | `FAIL` | `FAIL` |
| self-test cases / of which required-silent | — | 5 / 4 | **7 / 5** |
| What detects a recurrence | nothing had ever read a comment | CHECK 5, every push | same |

## 7. 회귀 게이트 / Regression gate

`.github/workflows/docs-consistency.yml`, CHECK 5 in the **guard** job, with its own step in the
**self-test** job.

The self-test plants seven fixtures and requires **two** to fire and **five** to stay silent. The
silent half is the larger half on purpose: this check is a narrowing of a check that produced a
false positive, so what needs watching is the silence.

| Planted | Must fire | Must **not** fire |
| --- | --- | --- |
| KDoc denying an index a migration creates | ✔ | — |
| the same sentence about a table whose only index is a **unique constraint** | — | ✔ — `R16`'s distinction; that claim is true |
| the same sentence in a **body comment** | — | ✔ — the scope that removed §3.3's false positive |
| a block marked `INDEX-CLAIM-EXEMPT` | — | ✔ |
| KDoc naming an indexed table and denying nothing | — | ✔ |
| a claim **scoped to `V1`**, where the index arrives in `V2` | — | ✔ — `Attempt.kt`'s shape, §3.4 |
| the same claim **scoped to `V2`** | ✔ | — |

And the guard asserts **both of its inputs are non-empty** before it reports anything, because
§3.5 is what it did without that.

## 8. 남는 위험 / Remaining risk

- **172 of 552 comment blocks are outside the scope, and the exclusion is measured rather than
  estimated.** A false index claim written in a body comment escapes CHECK 5 entirely. This is
  not hypothetical: the check *found* `BaselineMigrationTest.kt:97` at full scope and **cannot
  see it now**. That block is currently honest, and nothing here would notice if it stopped
  being.
- **The denial phrase set is closed and short** — four patterns. A KDoc saying *"the planner has
  nothing to use here"* or *"this is a sequential scan by construction"* makes the same claim and
  escapes. **미측정: how many index claims in this tree are phrased outside the set.** The set was
  written from the two findings, which is the smallest possible sample.
- **The table name must be in backticks.** `Attempt.kt` states a true claim and is silent for two
  independent reasons — the `V1` scope, and no backticks. Only the first is the check working. A
  false claim that names its table in plain prose is invisible.
- **`create index` only, so "no index" and "no index that serves this query" are the same sentence
  to this check.** `attempt` has carried a primary key since `V1`. Counting constraint-backed
  indexes would fire on every comment written during the months when *no index for this query*
  was correct — `R16` is the report about that distinction — so the check ignores them, and
  therefore **a claim that a table has no unique constraint, or no primary key, is not checked at
  all.**
- **The exemption marker is abusable and nothing checks it is honest.** One token silences a block
  permanently, including a block whose claim is false. This is CHECK 4's shape — `R17` §7:
  *"Check 4 sees a heading, not honesty"* — and it is a deliberate trade, not an oversight. What
  limits the damage is that the marker is a visible line in a diff.
- **Index existence is the only claim checked. Every other schema claim a KDoc makes is not.**
  Column types, nullability, constraint names, and row counts are all unverified — and
  **`Attempt.kt`, `RecommendationQueries` and `RecommendationService` each assert "three million
  rows" in prose, which nothing in this repository checks against `Scale.FULL`.** That is the same
  class of defect this report is about, one field over, and it is open.
- **미측정: what this costs on a runner.** CHECK 5 has never executed on GitHub Actions. It is a
  per-file `awk` over 93 files plus a `grep` over five migrations, and `R17` §8 measured the four
  existing checks at 1 s of a 5–7 s job — **but that is a different check on a different corpus,
  and `ADR-004` forbids quoting a local figure as a CI one.** No number is offered.
- **What would break this conclusion.** The version harvest reads a `V<n>__` prefix from the
  migration filename; a migration named outside that convention yields a non-numeric version and
  its indexes silently stop counting. `PopulatedMigrationTest` would not notice — it reads
  statements, not names. And an index created anywhere other than a migration file — by hand, by a
  test fixture, by schema generation — is invisible to the whole check.
- **Which earlier §8 bullet this report falsifies.** `R17` §8's last bullet, quoted in §4. It is no
  longer true that a claim in a code comment leaves the corpus entirely — **for index claims in
  KDoc, and for nothing else.** The bullet needs annotating in place rather than deleting, and this
  report does not do it: `R17` is edited by the integrator, and the exact sentence is in
  `_ROUND3-H-HANDOFF.md` §8.
- **Does anything here need a judgement rather than work?** Yes, one thing, named rather than
  decided: **whether the 172-block exclusion is permanent or provisional.** Widening to body
  comments re-admits `R17` §5's false positive unless something else distinguishes narration, and
  nothing structural does. That is a trade, not an errand, so it belongs in
  `docs/decisions/open.md` if anyone wants it moved.

## 9. 배운 것 / What I learned

제일 불편한 건 순서였습니다. 저는 KDoc만 읽는다는 원칙을 **먼저 정하고** 검사를 만든 게
아닙니다. 전체 주석에 돌려서 3건이 나왔고, 그중 하나가 `BaselineMigrationTest`의 "예전엔
이랬다"는 서술이었고, 그걸 보고 나서야 범위를 좁혔습니다. 원칙은 실패가 가르쳐준 것이지
제가 가져온 게 아닙니다. `R17` §5가 산문 검사를 버릴 때 쓴 문장을 제가 그대로 다시
만들었고, 다른 점은 그 실패가 이미 문서에 적혀 있어서 제가 알아볼 수 있었다는 것뿐입니다.
**리포트를 쓰는 것이 면역이 아니라는 게 `R9`의 주제인데, 여기서는 반대로 리포트가 실제로
작동했습니다.** 제가 똑똑해서가 아니라 `R17`이 그 문장을 남겨놨기 때문입니다.

두 번째로 놀란 건 `Attempt.kt`였습니다. 검사가 그 문장에 대해 조용했고 저는 잠깐 그게 검사가
잘 작동하는 증거라고 생각했습니다. 아니었습니다. 그 KDoc이 `attempt`를 백틱으로 감싸지
않았기 때문에 조용했던 것뿐이고, 백틱 하나 넣으니 **참인 문장에 대고 FAIL을 냈습니다.**
`ADR-007`이 텍스트 규칙을 거절한 세 번째 이유가 정확히 그것이고, 저는 그 ADR을 읽고 인용까지
하면서 같은 물건을 만들고 있었습니다. 운으로 초록이던 걸 실력으로 착각할 뻔했습니다.

그리고 초록이 한 번 가짜였습니다. WSL2에서 돌렸더니 `git ls-files`가 실패해서 목록이 비었고,
검사는 findings 2건이 있는 트리에 대고 `OK`를 찍었습니다. 그걸 잡은 건 계측이 아니라 **"이게
이렇게 쉽게 초록일 리가 없는데"** 라는 기분이었습니다. 다음 사람은 그 기분이 없을 겁니다.
그래서 입력이 비면 실패하게 만들었는데, 이건 `ADR-017`이 `S3`에 대해 쓴 문장을 그대로 옮긴
것이고, 그 문장이 쓰인 라운드 안에서 제가 같은 실수를 했습니다.

마지막으로, 고친 주석을 어떻게 쓸지가 제약이었습니다. **대체한 문장을 인용하면 검사에
걸립니다.** `R17` §5가 세 번째로 나타난 자리인데, 이번엔 검사를 고치는 대신 문장을 다르게
썼습니다. 그게 맞는 선택이었는지는 아직 모르겠고, 최소한 §8에 비용으로 적었습니다.
