# Domain model

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Baseline schema settled (`V1__baseline.sql`) and verified to apply against
PostgreSQL 16.14 under test. The scale table below is **generated and loaded** as of
2026-08-10 — see `seed/README.md`. No domain entities exist yet.

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
| `concept_edge` | *This concept must come before that one.* A DAG, not a tree — most concepts have several prerequisites |
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

**What this deliberately is not.** No BKT, no DKT, no learned model. A stronger
recommender would produce better recommendations and would not change a single one of the
questions this repository is actually asking, which are about what happens to the data
layer underneath it when 200 people ask at once.

An honest statement of the limit: **a recommendation policy cannot be validated without
learners, content, and teachers.** What can be built alone is the layer it would run on,
and whether that layer survives load, concurrency, and a dataset of realistic size.

## Scale

Fixed, so that every report in this repository is comparing like with like.

| Table | Rows | Note |
| --- | --- | --- |
| `learner` | 1,000 | |
| `concept` | 3,000 | |
| `concept_edge` | ~9,000 | average 3 prerequisites per concept |
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
