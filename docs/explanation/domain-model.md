# Domain model

> **Created**: 2026-08-10
> **Updated**: 2026-08-21

**Status:** Schema at `V5`. `V1__baseline.sql` applies against PostgreSQL 16.14 under test;
`V2` adds the index `R3` measured; `V3` adds the `mastery` uniqueness `R7` measured — and,
unintentionally, the index `R16` measured at 15× on the read path; `V4` adds the index `R20`
measured on `concept_edge`; `V5` adds the row predicate that makes a prerequisite cycle
unrepresentable, which `V1`'s own table comment said a `CHECK` could not do — `R21` and
`ADR-010`. The scale table below is **generated and loaded** — see `seed/README.md`. **Five
entities exist** (`net.gseek.proxima.domain`: `Attempt`, `Concept`, `Item`, `Learner`,
`Mastery`), asserted as an exact set by `PersistenceUnitGateTest`. **`concept_edge`
deliberately has none** — it is read as values by `PrerequisiteQueries`, for the reason `R4`
and `R8` established about handing a caller a managed graph.

> This line said *"No domain entities exist yet"* until 2026-08-14, four days after they did.
> `PUB-4` requires that no prose claim a state that does not exist and the row is `reviewed`
> rather than `observed`; nothing enforces it, and this was found by being asked.

This document owns **what the data means, how much of it there is, and where it comes
from.**

## What this document does not own

| Question | Owner |
| --- | --- |
| The schema itself | `api/src/main/resources/db/migration/` |
| Why `V1` omits indexes and one constraint | `docs/decisions/adr/ADR-002-schema-tells-the-story.md` |
| Identifier generation strategy | `docs/decisions/open.md` — `OPEN-3` |
| Why no real data is here | `docs/decisions/publication-readiness.md` — `PUB-7` |

---

## The question this models

> Given what a learner has done, **which problem should they see next?**

Not *which problem is hardest*, and not *which problem comes next in the book*. Which one
sits at the edge of what they can currently do — near enough to be reachable, far enough to
be worth doing.

That edge has a name in the education literature: the **zone of proximal development**. It
is where this repository's name comes from, and step 2 of the recommendation below is that
idea expressed as a `WHERE` clause.

## Entities

```
Learner ──< Attempt >── Item ──< ItemConcept >── Concept ──< ConceptEdge >── Concept
   │                                                │
   └──────────────< Mastery >───────────────────────┘
```

| Table | Meaning |
| --- | --- |
| `learner` | A person learning. Identified by a generated reference, nothing more |
| `concept` | One mathematical idea |
| `concept_edge` | *This concept must come before that one.* A DAG, not a tree — most concepts have several prerequisites, and **the difference from a tree is measurable rather than decorative**: see §How deep the prerequisite graph is |
| `item` | One problem |
| `item_concept` | A problem exercises several concepts, with different weight |
| `attempt` | One learner meeting one item once. **The hot table** |
| `mastery` | What a learner is currently believed to know about one concept |

`attempt` is the table everything interesting happens on. It is append-only, it is by far
the largest, every read of it is scoped to one learner, and it is ordered by time. Those
four facts together decide most of the indexing work in this repository.

## Recommendation

Deliberately simple. The rule is not the subject of this repository — the query underneath
it is.

```
1. concepts where this learner's mastery.score < 0.7
2. AND every prerequisite of that concept has mastery.score >= 0.7   ← the proximal zone
3. items on those concepts that this learner has not attempted in 30 days
4. filtered to a difficulty band matched to their recent accuracy
5. return N
```

**Step 2 is read one level deep, and that is a choice rather than the rule.** *Every
prerequisite of that concept* means the immediate prerequisites: `RecommendationQueries`
expresses it as a `NOT EXISTS` over `concept_edge` and stops there. A transitive reading —
*everything below this concept, to depth `d`* — is a different question with different costs,
and `PrerequisiteQueries.closure` answers it in one statement. **Nothing in the recommendation
path calls it.** `ADR-011` says why the closure is computed rather than stored, and why its
depth bound is a liveness bound rather than a claim about learning.

**What this deliberately is not.** No BKT, no DKT, no learned model. A stronger
recommender would produce better recommendations and would not change a single one of the
questions this repository is actually asking, which are about what happens to the data
layer underneath it when 200 people ask at once.

**What this rule yields on the shipped seed, measured 2026-08-14.** Step 2 is strict — *every*
prerequisite mastered — and on 1,000 generated learners it leaves **210 with anything to
recommend**. The other 790 get `200` and an empty list. That is not a defect and it is not
tuned for: judging it would be judging the policy, which the paragraph below places out of
scope.

It is recorded here because it is **not** out of scope for the layer underneath. Every load
number in this repository is taken against traffic that is **four-fifths empty-path**, so a
median measures the query ending early and a 99th percentile measures it doing the work.
`R16` §3.4 separates them; `R4`'s table predates the distinction and is annotated.

An honest statement of the limit: **a recommendation policy cannot be validated without
learners, content, and teachers.** What can be built alone is the layer it would run on,
and whether that layer survives load, concurrency, and a dataset of realistic size.

## Scale

Fixed, so that every report in this repository is comparing like with like.

| Table | Rows | Note |
| --- | --- | --- |
| `learner` | 1,000 | |
| `concept` | 3,000 | |
| `concept_edge` | ~9,000 | average 3 prerequisites per concept. **Depth measured 2026-08-21**, below |
| `item` | **100,000** | |
| `item_concept` | ~250,000 | |
| `attempt` | **3,000,000** | ~3,000 per learner, spread over 18 months |
| `mastery` | ~600,000 | |

**Realised 2026-08-10**, counted in the database rather than in the generator: `learner`
1,000 · `concept` 3,000 · `concept_edge` 8,994 · `item` 100,000 · `item_concept` 249,725 ·
`attempt` 3,000,000 · `mastery` 600,000 — **3,963,719 rows**. The two approximate rows are
approximate by construction: prerequisite edges and item-concept links are drawn per row,
so the totals land near the figure above rather than on it.

Three million rows is chosen because it is the smallest size at which the difference
between a good plan and a bad plan is unambiguous on a developer machine. Below roughly a
million rows PostgreSQL will often choose a sequential scan and be right to, which makes
every indexing experiment inconclusive.

### How deep the prerequisite graph is

**A row count says nothing about a graph.** `concept_edge` had 8,994 rows on 2026-08-10 and
nothing measured its shape until 2026-08-21, because the only thing that read it —
`RecommendationQueries` — read it exactly one level deep. Measured by
`PrerequisiteDepthTest` at `Scale.FULL`:

| | |
| --- | --- |
| longest prerequisite chain | **294 edges** |
| concepts with at least one prerequisite | 2,999 of 3,000 |
| mean prerequisites, of those | 2.999 |

From the last concept, which is the deepest place to stand:

| depth | concepts reachable | distinct walks to reach them |
| --- | --- | --- |
| 1 | 3 | 3 |
| 6 | 202 | 1,092 |
| 9 | 365 | 29,523 |
| 12 | 511 | 797,160 |
| 14 | **606** | **7,174,452** |

**The two columns are the same number at depth 1 and 11,839× apart at depth 14**, because
this is a DAG whose concepts share ancestors rather than a tree. That distinction is what
`R20` and `R21` are about, and it is invisible from where the recommendation rule stands.

The graph is **acyclic by construction and now also by constraint**: every edge runs from a
lower concept id to a higher one, so a prerequisite walk strictly decreases. `V5` states that
as `ck_concept_edge_forward`. `ADR-010` records what that buys and what it forbids — a new
concept can never become a prerequisite of an older one, because it always gets a higher id.

## The seed is code

**No dataset is committed.** `seed/` generates it from a fixed seed value — `20260810` —
so any reader reproduces the same rows by running the generator.

That is a `PUB-7` requirement first: a committed dataset is a dataset whose provenance
nobody can verify, and this domain's records describe minors. But it is also the only way
the numbers in `docs/reports/` mean anything to someone who is not the author. A benchmark
against data you cannot obtain is an anecdote.

Loading is by `COPY`, not by row-at-a-time insert. The difference at three million rows is
minutes against tens of minutes, and the reason is worth stating rather than assuming — see
`OPEN-3`, where the identifier strategy turns out to be entangled with it.

**Generated identifiers are shaped so they cannot be mistaken for real ones**
(`learner-000001`, not a name, an email, or a phone number). The CI guard checks the
shapes it can check; the shape of a generated reference is a design choice made here.
