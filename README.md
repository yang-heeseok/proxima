# proxima

**An API that chooses a learner's next problem — and a record of how it actually breaks
under load and concurrency.**

> **Created**: 2026-08-10 · **Updated**: 2026-08-18
> **Status: the nine traps on the roadmap are measured, in twelve reports.** Spring Boot
> 4.1.0 on JDK 21, a schema that applies to a real PostgreSQL under test, a generator that
> produces 3,963,719 rows from a fixed seed value, and **77 tests** — 36 classes, 0 failures,
> 9m43s on 2026-08-18 with `:api:test` actually executed rather than restored from cache, because a
> cached Gradle result is not a run. **It said `70` for four days and eight
> test-adding commits**, which is `R17`. Three of the nine turned out
> to be **already fixed by the framework** — those reports say so and measure what is holding
> them shut, rather than deleting the row. See `docs/explanation/measurement-discipline.md`
> for what makes any number below citable.

---

## Results

*This table is the point of the repository. Nothing in it is here that was not measured, and
the reports carry the environment each number was taken in.*

| What | Before | After | Report |
| --- | --- | --- | --- |
| Recommendation API p99 @ 200 VU — *2026-08-12* | 9064.1 ms | **5919.4 ms** | [`R4`](docs/reports/R4-the-fix-that-is-two-halves.md) |
| The same endpoint without `V3`'s unique constraint — *2026-08-14* | 11334.6 ms | **743.5 ms** | [`R16`](docs/reports/R16-the-constraint-that-was-also-an-index.md) |
| Attempt history within a learner, deep page | 36.6 ms | **0.056 ms** | [`R3`](docs/reports/R3-an-index-that-exists-and-is-not-used.md) |
| Recommendation read — statements per request | 2 + n | **1, at any row count** | [`R8`](docs/reports/R8-a-test-that-counts-queries.md) |
| 1,000 increments, 10 threads, one row | **864 lost, no exception** | 0 lost, and 5.1× faster | [`R6`](docs/reports/R6-updates-lost-under-concurrency.md) |
| 8 concurrent requests, one (learner, concept) | 8 rows, 0 failures | **1 row, 0 failures** | [`R7`](docs/reports/R7-a-uniqueness-check-two-requests-both-pass.md) |
| 1,000 concurrent recordings, one learner | **196 applied, 804 refused** | **1,000 applied, 0 refused** | [`R12`](docs/reports/R12-the-arm-the-application-kept.md) |
| A batch of 5 with one invalid recording | **2 of 4 valid ones landed** | **4 of 4, each outcome named** | [`R14`](docs/reports/R14-the-batch-that-discarded-what-it-was-told-to-keep.md) |
| One learner's token, another learner's data | 200, with their data | **403** | [`R11`](docs/reports/R11-authenticated-and-not-authorised.md) |
| Documents claiming a state the tree contradicts — *2026-08-17* | **18**, and a person was the only detector | **0**, on every push | [`R17`](docs/reports/R17-the-guard-that-was-a-person.md) |
| The same endpoint with 5× the connection pool and no index — *2026-08-17* | 8739.8 ms @ pool 10 | **4506.7 ms** @ pool 50 — **and still 8.7× worse than with the index** | [`R18`](docs/reports/R18-the-pool-was-not-the-explanation.md) |
| Decisions filed as risks, over 145 *남는 위험* bullets — *2026-08-17* | **3**, in a table claiming there could be none | **0** — three rows opened, and the claim withdrawn | [`R19`](docs/reports/R19-decisions-filed-where-nobody-had-to-make-them.md) |
| A migration that had never met a row — *2026-08-18* | `V3` **> 32 min, unfinished** on 600k rows, green in CI for four days | **`PopulatedMigrationTest`** — migrations run over 20,000 rows, and every DML statement's plan is checked for per-row evaluation | [`ADR-007`](docs/decisions/adr/ADR-007-migrations-meet-rows.md) |

**The two latency rows are dated and are not a progression.** They were taken two days apart
on a machine whose state changed in between, and `measurement-discipline.md` rule 3 says a
comparison across that gap is not made. `R16` §7 declines to print the flattering ratio and
shows why the caution was warranted — its own arm B, the closest thing to `R4`'s schema, lands
2× away from `R4`'s figure for reasons nothing has attributed.

**Roughly four requests in five in every load run here answer `200` with an empty list** — the
rule yields items for 210 of 1,000 generated learners. So a p50 above measures the query
ending early and a p99 measures it doing the work. `R16` §3.4.

**미측정 means not measured.** It does not mean "about the same". It still appears throughout
the reports, and deliberately: `R9` §3.6 quotes a range across two machines rather than the
flattering end of it, and `R10` §8 records that a claim about `loggers` was mechanism rather
than measurement until it was measured.

### The report that scores the rest

[**`R0`**](docs/reports/R0-the-scorecard.md) asks, for each trap: *did the draft step into the
defect it was documenting, and what caught it?* **Six of nine.** What caught them was a
deliberate measurement seven times, CI three times, a control planted inside an instrument
twice, the compiler once — and **a regression gate exactly once**, when rules written at the
end of one report refused the author's own remedy three reports later. **`R0` counted nine test
classes written to refuse a future edit and one that had ever been paid**, and said so instead
of counting gates as evidence.

**That count was a snapshot and this line used to state it in the present tense.** Six more gate
classes have landed since `R0` — **fifteen by the same classification, which is a judgement and
not a derived fact.** The ledger has moved in the other direction too: `docs-consistency.yml`
has caught **two** commits since 2026-08-17, both of them the very commit that was editing
documents, and `load-harness.yml` caught a non-executable wrapper on its first run.

**That last figure said *twice* for an hour, and it was wrong** — the lane's second defect sat
after the line that failed and was never reached, so it was found by reading the code, not by
running it. **The correction is worth more than the number**: it is an overstated gate ledger,
in the paragraph about overstated gate ledgers, written by the person correcting one. `R0`'s
own figures belong to `R0`'s tree and are not re-scored here.

---

## What this is

Given what a learner has done, which problem should they see next? Not the hardest one, and
not the next one in the book — the one at the edge of what they can currently do. That edge
has a name in the education literature, the **zone of proximal development**, and it is
where this repository gets its name. Step 2 of the recommendation is that idea written as a
`WHERE` clause.

The recommendation rule itself is deliberately simple, and a better one would not change a
single question this repository asks. The questions are about the layer underneath: what
happens to a data layer holding three million attempts when two hundred people ask at once.

## What makes it different from a demo

**Features can be invented. Failures cannot.**

Most of the work here is spent reproducing defects on purpose, measuring them, fixing them,
and then writing down what the fix did not solve:

- a connection pool exhausted by a setting that is on by default,
- a paginated query that silently paginates in memory,
- a transaction annotation that does nothing at all, in a way nothing reports,
- an index that exists and is not used,
- an update that loses writes under concurrency,
- a uniqueness check that two concurrent requests both pass.

Each one is a pair of commits — the state in which it was observed, and the state in which
it was not — plus a report carrying the numbers, the alternatives that were compared, the
gate that keeps it from coming back, and **what is still wrong**.

A report with an empty *남는 위험 / Remaining risk* section fails this repository's own
publication rules. That is not paperwork: a report that found nothing left to worry about
has usually stopped looking.

## Layout

```
api/          Spring Boot application, Flyway migrations
seed/         dataset generator — the data is code, never a committed file
load/         k6 scenarios; warm-up is discarded by construction
docs/
  roadmap.md            what is measured, in what order, and what is done
  reports/              the numbers, one file per defect — plus R0, which scores the rest
  decisions/            ADRs, open questions, publication requirements
  explanation/          domain model, measurement discipline
.study/                 Korean study notes — the only Korean in the tree, and the only
                        directory here whose purpose is learning rather than evidence
.github/workflows/      guards that are self-tested against planted violations
```

## Reproducing anything here

The dataset is not in this repository — it is generated by `seed/` from a fixed seed value,
so the rows are reproducible without anyone having to publish rows. That is a hard
requirement (`PUB-7`: this domain's records describe minors, and history cannot be erased),
and it is also the only reason the numbers mean anything to a reader. **A benchmark against
data you cannot obtain is an anecdote.**

Every number carries the environment it was taken in. The rules are in
`docs/explanation/measurement-discipline.md`, written before the first measurement on
purpose — rules written after a number is inconvenient are not rules.

## A note on the author

I have not worked with Spring in production. I have run production systems for several
years on NestJS/TypeORM and FastAPI, and this repository is me taking the same problems
into Spring Boot and JPA and writing down exactly where I fall over.

That is what it is for. Not a claim of expertise — a record of the route.

## Licence

Apache-2.0.
