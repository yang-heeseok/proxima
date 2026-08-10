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

---

## Tier 1 — the foundation

| # | Defect | State |
| --- | --- | --- |
| **T3** | **A transaction annotation that does nothing.** Self-invocation through `this`; a `final` class where the proxy cannot be created at all; an entity declared as a `data class`, whose generated `equals` meets a lazy proxy; an exception swallowed inside the boundary, so the outer commit fails with a rollback-only marker instead. Nothing in a passing test run reports any of it | ☐ |
| **T1** | **A connection pool exhausted by a default.** The session stays open past the transaction, so a request that also calls something slow holds a database connection while doing so. The database is idle; the application times out | ☐ |
| **T2** | **A page that is paginated in memory.** A collection join with paging, where the framework fetches the whole result and slices it in the heap. There is a warning in the log and no error anywhere. Also: two collection joins at once, and what happens to the row count when the obvious fix is applied | ☐ |

## Tier 2 — the data layer

| # | Defect | State |
| --- | --- | --- |
| **T4** | **An index that exists and is not used.** Leading-column order; a column whose cardinality makes an index pointless; covering columns and an index-only scan; and **stale planner statistics after a bulk load**, measured deliberately before running `ANALYZE`, because that gap is a finding rather than a mistake. Then offset paging against keyset paging, at depth | ☐ |
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
