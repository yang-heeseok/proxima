# Roadmap

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Nothing below is done. The state column is the truth of this document and is
updated as each item lands, not in advance.

This document owns **what gets measured, in what order, and how far along it is.**

## What this document does not own

| Question | Owner |
| --- | --- |
| The numbers | `docs/reports/` |
| What makes a number citable | `docs/explanation/measurement-discipline.md` |
| Why a technical choice was made | `docs/decisions/adr/` |
| What must be true to publish | `docs/decisions/publication-readiness.md` |

---

## The premise

Every item below is a defect that is **reproduced on purpose**, measured, fixed, and then
written up including what the fix failed to solve.

The selection rule: each one is something that **passes every unit test and appears only
under load, concurrency, or scale.** That is a specific class of defect, and the reason it
is the interesting class is that it is exactly the class a compiler, a type system, a
linter, and a generated test suite all miss.

## Order, and why this order

Tier 1 before Tier 2 is not difficulty ordering. It is *what the next experiment needs*:
the connection-pool work establishes the load harness, and the transaction work establishes
that the tests can tell truth from silence. Indexing is more interesting and comes second
because measuring it without those two would produce numbers nobody could trust.

> **Amended 2026-08-12, by measurement.** That argument is true and it is only half of one.
> `T1` was attempted first, established its mechanism, and then could not choose a remedy:
> the effect it measures is 150 ms per request, and on an unindexed schema the query beside
> it moves between 140 ms and 555 ms depending on contention. **Indexing has to exist before
> pool numbers can be trusted, exactly as much as the harness had to exist before indexing
> numbers could be.** The two items need each other rather than ordering cleanly, and the
> harness — which is what `T1` was placed first to build — now exists either way. `T4` runs
> before `T1` resumes. See `R2` §5.

---

## Tier 1 — the foundation

| # | Defect | State |
| --- | --- | --- |
| **T3** | **A transaction annotation that does nothing.** Self-invocation through `this`; a `final` class where the proxy cannot be created at all; an entity declared as a `data class`, whose generated `equals` meets a lazy proxy; an exception swallowed inside the boundary, so the outer commit fails with a rollback-only marker instead. Nothing in a passing test run reports any of it | **partly done** — `R1`, red `21e7162` / green `9388743` / gate `4141a65`. **Self-invocation is reproduced, fixed, and gated.** The other three are gated structurally but **not reproduced**: the `final` class and `data class` traps are prevented by ArchUnit rules watched refusing planted violations, and the **swallowed exception / rollback-only case is not done at all** — it belongs with `T6`, since the same PostgreSQL transaction-abort behaviour surfaced while writing `R1` |
| **T1** | **A connection pool exhausted by a default.** The session stays open past the transaction, so a request that also calls something slow holds a database connection while doing so. ~~The database is idle; the application times out~~ — **that last clause is false on this system and the measurement says so**: 5–9 of 10 pooled connections are executing a query at any instant | **done** — `R4`, red `cceec6a` / green the commit carrying `R4`. p99 9064 ms → 5919 ms at 200 VU with the pool unchanged at ten. `R2` is the first attempt, kept: it established the mechanism, correctly refused to choose a remedy on an unindexed schema, and sent `T4` first. **The finding: the fix is two edits and each alone does nothing** — fetching inside the transaction changes nothing while `open-in-view` is on, and turning `open-in-view` off without it raises `LazyInitializationException`. **No regression gate exists**, and `R4` §7 says so rather than implying one |
| **T2** | **A page that is paginated in memory.** ~~A collection join with paging, where the framework fetches the whole result and slices it in the heap. There is a warning in the log and no error anywhere~~ — **false on Hibernate 7.4.1, measured.** Also: two collection joins at once | **half done, and the half that is missing is missing because it no longer exists** — `R5`, **no red commit and no green commit**. Hibernate rewrites the page into a derived table over the roots, so it reaches the database; the generated SQL is quoted in `R5` §3.1 and there is no warning because there is nothing to warn about. The second strand does reproduce — `MultipleBagFetchException`, raised at query-build time, loudly. **This row is the clearest example in the repository of a performance claim with no version attached**, and it was this document making it |

## Tier 2 — the data layer

| # | Defect | State |
| --- | --- | --- |
| **T4** | **An index that exists and is not used.** Leading-column order; a column whose cardinality makes an index pointless; covering columns and an index-only scan; and **stale planner statistics after a bulk load**, measured deliberately before running `ANALYZE`, because that gap is a finding rather than a mistake. Then offset paging against keyset paging, at depth | **done** — `R3`, red `cceec6a` / green `V2__attempt_learner_time_index.sql`. 36.6 ms → 0.056 ms. Four of five strands reproduced: column order (the wrong one *is used*, and collapses only at depth), a useless index on a boolean, covering columns rejected on measurement, and offset-vs-keyset. **Stale statistics did not reproduce, on either attempt** — `CREATE INDEX` repairs `reltuples` as a side effect — and §3.5 records that rather than dropping it. §8 names the likely reason it cannot reproduce here: **the generated dataset has no skew** |
| **T5** | **Updates lost under concurrency.** Count them first. Then compare optimistic locking, pessimistic locking, and a single atomic statement — on both correctness and throughput. Including the part that is easy to get wrong: **a retry placed inside the transaction it is retrying** | ☐ |
| **T6** | **A uniqueness check two requests both pass.** An application-level existence check, then an insert. Then the constraint that actually enforces it, and the database-specific consequence of hitting it — on PostgreSQL the whole transaction is aborted, so catching the exception and continuing does not work without a savepoint | ☐ |

## Tier 3 — keeping it fixed

| # | Item | State |
| --- | --- | --- |
| **T7** | **A test that counts queries.** Asserting the number of statements a request issues, so an N+1 reintroduced later fails CI instead of being noticed in production. Performance held by a test rather than by review | ☐ |
| **T8** | **What an in-memory database does not tell you.** Cases that pass against H2 and fail against PostgreSQL — upsert syntax, types, collation-dependent ordering, reserved words, and identifier generation, which quietly decides whether inserts can be batched at all. Plus container reuse, with the CI time before and after | ☐ |
| **T9** | **Authorisation, exposure, tokens.** An endpoint that authenticates and does not authorise; management endpoints exposed wholesale, including the one that dumps the heap and everything that was in it; token expiry and clock skew | ☐ |

## Deferred, deliberately

| Item | Why not |
| --- | --- |
| A learned recommendation model (BKT, DKT, …) | It would produce better recommendations and would not change one question this repository asks. A recommendation policy cannot be validated without learners, content, and teachers |
| A cache layer | It would improve every number here and hide which of them were bad for structural reasons. Recorded as `OPEN-4` rather than silently skipped |
| Full frontend | One back-office screen, for looking at what the API returns |
| Container orchestration | Out of proportion to the system |
| Coverage percentage | A number that is easy to raise without making anything safer. The regression gates in §Tier 3 are the claim being made instead |

---

## Definition of done, per item

An item is **done** when all of these hold. Not when the code works.

1. A commit exists in which the defect is observable, and its number was recorded.
2. A commit exists in which it is not, under the same measurement conditions.
3. A report exists carrying both numbers, the alternatives compared, and the mechanism.
4. Something in CI turns red if the defect returns, named in the report by file.
5. The report's *남는 위험 / Remaining risk* section is non-empty and specific — including
   anything the fix traded away and anything that went unmeasured.

Item 5 is the one that decides whether the rest was honest.
