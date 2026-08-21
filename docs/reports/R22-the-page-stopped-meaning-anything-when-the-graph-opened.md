# R22. The page stopped meaning anything when the graph opened, and every page was perfect

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: `39f69ca` — the two pages, and the 182 items no page reaches
> **Green commit**: none, and §5 says why. There is no fix here, there is a choice, and
> `ADR-011` is where it is made

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 (JDK 21 toolchain, pinned in gradle.properties)
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14, default shared_buffers
  Dataset        : `concept_edge` at seed value 20260810, Scale.FULL — 3,000 concepts,
                   8,994 edges — plus one item per concept, difficulty 1 + (ordinal x 7) % 10,
                   installed by `SeedConceptGraph`
  Ordering       : every sort key below is numeric. NOTHING HERE ORDERS ON TEXT -- R9 §8
  Load           : none. These are set comparisons, not durations
  Repetitions    : not applicable. Every figure is a set, and a set does not have a median
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

At depth 1 a page over the recommendation is unremarkable. The set of concepts in scope is
fixed by the learner's mastery, `limit 20` takes twenty items, and there is nothing to say.

Open depth `d` and *the next 20* stops being one thing. Two queries, both `limit 20`, both
returning twenty rows, both ordered by difficulty then id, both correct SQL:

```
cut before expansion : [2930, 2893, 2886, 2976, 2986, 2839, 2889, 2909, 2932, 2865,
                        2935, 2965, 2975, 2995, 2948, 2881, 2921, 2994, 2887, 2917]
sort after expansion : [2690, 2730, 2740, 2750, 2760, 2790, 2800, 2810, 2820, 2830,
                        2840, 2850, 2860, 2870, 2880, 2890, 2900, 2910, 2930, 2940]
in common            : 1 of 20
```

**One item of twenty.** Nothing in either result says which of them is *the twenty easiest
problems this learner should see next* and which is *the twenty easiest problems among the
first twenty concepts the recursion happened to emit*.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.concept.ExpandedPagingTest
```

The two queries differ in **where `limit 20` sits**: inside the `target` CTE, or after the
join to `item`. Bounding the CTE is not a mistake anybody makes out of carelessness — `R21`
§3.5 measured that recursion carrying 797,160 rows, and putting a `limit` on it is the first
thing a reviewer would ask for.

## 3. 계측 / Measurement

Depth 6 from the last concept: **202 concepts, 202 items.**

### 3.1 The wrong twenty

| | items on page 1 | shared with the other |
| --- | --- | --- |
| `limit 20` inside the recursion, then join, then `limit 20` | 20 | **1** |
| join first, then `limit 20` over the whole expansion | 20 | **1** |

### 3.2 And the pages do not add up to the answer

Walking five pages of twenty with the limit inside the recursion:

```
five pages of 20, cut before expansion
  rows returned    : 20
  distinct rows    : 20
  the whole answer : 202 items
  walked but not in the answer : 0
  in the answer and never paged: 182
```

Page 1 is full. **Pages 2 to 5 are empty.** No row is repeated, no row is invented, no page
is malformed, nothing errors, and 182 of the answer's 202 items — **90%** — cannot be reached
by paging at all.

What a caller sees is a result set of exactly twenty items. What a reviewer sees is a query
with a `limit` in it. There is no symptom.

### 3.3 The natural ordering is not stable under the depth bound

`order by i.difficulty, i.id` is the domain's answer: easiest first. Comparing page 1 at
`maxDepth = 6` against page 1 at `maxDepth = 7` — the same learner, the same data, one
parameter:

| ordering | rows surviving on page 1 | stable |
| --- | --- | --- |
| `difficulty, id` | **15 of 20** | ✘ |
| `depth, difficulty, id` | **20 of 20** | ✔ |

A concept found one level deeper brings an item of some arbitrary difficulty, and it lands
wherever its difficulty puts it — including on page 1, displacing something. Five rows moved
off the first page because the *graph* was opened, not because the learner did anything.

The ordering that is stable puts `depth` first, and a concept found at depth 7 sorts after
every concept found at depth 6 and cannot displace anything. It is also no longer *easiest
first*. It is *nearest first*, and near is a property of the traversal.

### 3.4 The same defect, found by accident, in this repository's own suite

While the full `:api:test` suite ran with the fixtures above installed,
`CollectionPagingTest` failed:

```
the two return the same page; the difference is elsewhere
  ==> expected: <[41, 42, 43, 44, 45]> but was: <[48, 51, 52, 56, 60]>
```

`LearnerPageQueries` carries no `order by`. `PageRequest.of(0, 5)` therefore asked for *five
learners*, and PostgreSQL was free to return any five. The test had been stable since
2026-08-10 and moved when a fixture in another package ran a database-wide `analyze` over the
shared container: the statistics changed, the plan changed, and the arbitrary five changed
with it.

**Both results were correct. Neither query promised anything else.** This is §3.1's finding
in a file written eleven days before it, found by a fixture rather than by a reader.

Two things were changed and neither is the query: `ExpandedPagingTest`'s `analyze` now names
its own tables, because a fixture may change its own tables and nothing else; and
`CollectionPagingTest`'s page requests carry `Sort.by("id")`, added in the test rather than
in `LearnerPageQueries` because those five queries are `R5`'s subject and
`CollectionPagingWarningTest` asserts against their generated text.

## 4. 원인 / Mechanism

**A `limit` is applied to the last thing in the pipeline, and there are now two pipelines.**
Before a transitive read there was one set — the learner's target concepts — and one place a
`limit` could go. A recursion introduces a second, larger, intermediate set, and a `limit` on
it is a *sample of the graph* rather than a *page of the answer*. SQL has no way to say which
one was meant, and the result of the wrong one is a well-formed page.

**Offset paging requires a total order, and a computed column is not stable under its own
parameters.** `min(depth)` per concept does not change when `maxDepth` rises — the shortest
path stays the shortest — but new concepts appear, and under any ordering that does not lead
with `depth` they interleave into pages already served.

**And a query with no `order by` has no page at all**, which is §3.4 and is the degenerate
case of the same statement.

## 5. 처방 / Remedy

There is no green commit here, and that is not an omission.

| Option | What the page means | Cost | Chosen |
| --- | --- | --- | --- |
| `limit` inside the recursion | twenty items of an arbitrary twenty concepts | 90% of the answer unreachable | ✘ |
| `limit` after the expansion, `order by difficulty, id` | the twenty easiest — **while `maxDepth` does not move** | 5 of 20 rows change when it does | ✘ as a *paged* API |
| `limit` after the expansion, `order by depth, difficulty, id` | the twenty nearest, easiest within a level | stable, and no longer what the domain asked for | ✔ **if paging is offered** |
| do not offer paging over an expanded closure | — | the caller gets the whole closure or a bound | ✔ **today** |

`ADR-011` makes that choice, and it turns on a fact this repository has already recorded:
`docs/explanation/domain-model.md` says the rule returns `N`, and `RecommendationService`
takes a `limit` and no offset. **There is no page-2 in this application.** A `limit` with no
offset over a stable-until-`maxDepth`-moves ordering is exactly as correct as the graph is
static, and `maxDepth` is not a request parameter today.

What §3 costs is therefore not a live defect. It is the price of the first feature that
offers *the next twenty* over a closure, and this report is what that feature reads before it
is written.

## 6. 재계측 / Re-measurement

| Metric | Before | After |
| --- | --- | --- |
| `CollectionPagingTest`, two arms over five learners | different pages, both correct | **identical, ordered by id** |
| `ExpandedPagingTest`'s effect on other test classes | a database-wide `analyze` | **four named tables** |
| everything in §3.1 to §3.3 | — | **unchanged, and pinned** |

Nothing in §3 is re-measured after a fix, because nothing in §3 was fixed. The rows are
assertions now, so the day somebody pages over an expanded closure, they fail.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/concept/ExpandedPagingTest.kt`:

- the two pages share exactly **1** of 20 items;
- pages 2 to 5 of the cut-before-expansion arm are empty, and exactly **182** items of the
  answer are unreachable by paging;
- `order by difficulty, id` loses rows off page 1 when `maxDepth` moves, and
  `order by depth, difficulty, id` loses none.

The third is the one that would catch a future change: it fails if `min(depth)` stops being
the shortest path, which is the only way the stable ordering could stop being stable.

`api/src/test/kotlin/net/gseek/proxima/domain/CollectionPagingTest.kt` now pages over a
defined order, so the *next* time a plan moves under it, it will not be this.

## 8. 남는 위험 / Remaining risk

- **This is a report about a feature that does not exist.** No endpoint pages over a
  prerequisite closure. `AGENTS.md` §Scope is explicit that building ahead of the code a guard
  protects is how this repository has lost a day — the defence is that §3.4 found the same
  defect in code that *does* exist, which makes this a measurement of a live shape rather than
  a rehearsal.
- **Keyset paging is 미측정.** `R3` compared offset against keyset on `attempt` and keyset
  won by a wide margin. Whether a keyset cursor over `(depth, difficulty, id)` survives a
  change of `maxDepth` — the mechanism says it does, since the tuple only grows at the end —
  **was not run**, and the mechanism is not a measurement.
- **No text is ordered on and therefore nothing was learned about collation.** `R9` §8's
  standing risk is not deepened here and is not discharged either. `concept.name` and
  `concept.grade_band` are the obvious keys for *which concept next* and both are text; the
  day one is used, this report's numbers say nothing about it. **Slice C is measuring that
  gap; this report deliberately stays out of it.**
- **One learner-shaped fixture, one item per concept.** The real dataset has 100,000 items
  over 3,000 concepts and `item_concept` is many-to-many, so a concept entering the closure
  brings ~33 items rather than 1. Every count in §3.2 scales with that and the *ratio* is what
  this report claims. The counts at the shipped item scale are **미측정**.
- **The `1 of 20` in §3.1 is a property of this difficulty distribution.** `1 + (ordinal ×
  7) % 10` is deliberately uncorrelated with concept id; a curriculum where difficulty rises
  with depth would make the two pages agree far more, and the finding would be quieter without
  being less true.
- **§3.4 was found by accident and nothing looks for it.** A test that pages without an
  `order by` is invisible to every check in this repository. It survived eleven days and was
  exposed by an unrelated fixture; how many others exist is **unknown, not 미측정** — nothing
  has counted.
- **What would break the conclusion:** a `maxDepth` that is fixed by policy rather than
  passed. §3.3's instability is entirely about that parameter moving. If `ADR-011` had pinned
  a constant, the ordering question would collapse to §3.1 alone.

## 9. 배운 것 / What I learned

**페이지가 완벽하면서 답이 아닐 수 있다.**

`limit 20`을 재귀 안쪽에 넣은 쿼리는 20행을 돌려줬다. 중복도 없고, 없는 행도 없고, 순서도 맞고,
에러도 없다. 페이지 2는 비어 있다 — 그것도 정상적인 응답이다. 그런데 답의 202개 중 182개, **90%가
어떤 페이지로도 도달할 수 없다.** 리뷰에서 볼 수 있는 건 `limit`이 하나 있다는 것뿐이고, 그건
누구라도 요구했을 최적화다. R21에서 그 재귀가 797,160행을 나른다는 걸 재고 나면 특히.

두 번째로, **정렬 키를 고르는 게 도메인 결정이라는 걸 몰랐다.** `order by difficulty, id`는
"쉬운 것부터"이고 그게 도메인이 원하는 답이다. 그런데 `maxDepth`를 6에서 7로 올리면 1페이지의
20개 중 5개가 바뀐다. 학습자는 아무것도 안 했다. 안정적인 정렬은 `depth`를 앞에 두는 것인데,
그러면 "가까운 것부터"가 되고 그건 그래프 탐색의 성질이지 학습자가 신경 쓰는 성질이 아니다.
**둘 다 가질 수 없다**는 걸 숫자로 본 게 처음이다.

그리고 제일 값싼 발견은 내가 만든 게 아니었다. 전체 스위트를 돌렸더니 열하루 된
`CollectionPagingTest`가 `[41,42,43,44,45]` 대신 `[48,51,52,56,60]`을 받고 깨졌다. 그 쿼리에는
`order by`가 없다. **"학습자 5명"을 요청했고 PostgreSQL은 아무 5명이나 줄 자유가 있었다.** 열하루
동안 초록이었던 이유는 계획이 안 바뀌었기 때문이고, 내 fixture가 컨테이너 전체에 `analyze`를
돌리자 바뀌었다. 내가 만들려던 결함이 이미 저장소 안에 있었고, 나는 그걸 **우연히 건드려서** 찾았다.
`R0`가 세는 "무엇이 잡았는가" 표에 이건 어느 칸에 들어가는지 잘 모르겠다. 측정도 아니고 게이트도
아니다. 굳이 쓰면 **다른 것을 재려고 만든 fixture**다.
