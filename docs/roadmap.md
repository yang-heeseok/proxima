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
| **T4** | **An index that exists and is not used.** Leading-column order; a column whose cardinality makes an index pointless; covering columns and an index-only scan; and **stale planner statistics after a bulk load**, measured deliberately before running `ANALYZE`, because that gap is a finding rather than a mistake. Then offset paging against keyset paging, at depth | **done** — `R3`, red `cceec6a` / green `V2__attempt_learner_time_index.sql`. 36.6 ms → 0.056 ms. Four of five strands reproduced: column order (the wrong one *is used*, and collapses only at depth), a useless index on a boolean, covering columns rejected on measurement, and offset-vs-keyset. **Stale statistics did not reproduce, on either attempt** — `CREATE INDEX` repairs `reltuples` as a side effect — and §3.5 records that rather than dropping it. §8 names the likely reason it cannot reproduce here: **the generated dataset has no skew**. **`R13` confirmed that on 2026-08-14** — give one learner 30 % of a table and the plan flips on a **60× underestimate**, 46.2 ms against 29.2 ms — **and found a second reason nobody had suspected: `R3` §3.5's query is served by the index at any selectivity, so no estimate however wrong could have changed its plan.** The question had been asked in a shape that could not hold the answer. **All five strands are now accounted for** |
| **T5** | **Updates lost under concurrency.** Count them first. Then compare optimistic locking, pessimistic locking, and a single atomic statement — on both correctness and throughput. Including the part that is easy to get wrong: **a retry placed inside the transaction it is retrying** | **done** — `R6`. 1,000 increments, 10 threads, one row: read-modify-write keeps **136** and loses 864 with zero exceptions. `@Version` keeps 180 and *rejects* 820 — it converts silence into noise, not loss into success. **The retry inside the transaction is worse than no retry** (135 against 180, and slower); outside it recovers 4.6×. Pessimistic and atomic both keep 1,000, and the atomic statement is **5.1× faster**. **The application still uses the second-worst arm** and §8 says why that was not changed here |
| **T6** | **A uniqueness check two requests both pass.** An application-level existence check, then an insert. Then the constraint that actually enforces it, and the database-specific consequence of hitting it — on PostgreSQL the whole transaction is aborted, so catching the exception and continuing does not work without a savepoint | **done** — `R7`, red `ad474d8` / green `V3__mastery_unique_learner_concept.sql`. **8 requests → 8 rows, 0 failures.** The constraint does not fix the code, it converts 7 silent duplicates into 7 exceptions. Catching the violation and re-reading **in the same transaction fails 7 of 8**, exactly as the roadmap predicted; isolating the insert or using `on conflict` gives 0. **`on conflict` turned out to require the constraint rather than replace it.** And the `T3` gate caught a self-invocation defect introduced *in this report's own remedy* — §3.4 |

## Tier 3 — keeping it fixed

| # | Item | State |
| --- | --- | --- |
| **T7** | **A test that counts queries.** Asserting the number of statements a request issues, so an N+1 reintroduced later fails CI instead of being noticed in production. Performance held by a test rather than by review | **done** — `R8`. The shipped read is **1 statement at any row count**; the entity path `R4` rejected is **2 + n**. Counts are exact rather than upper bounds, because a bound drifts upward one honest commit at a time. **The finding: the service does not contain the N+1, it hands one out** — measured alone it costs 2 statements whatever the size, and a gate scoped to it would have certified an N+1 as clean. §8: this covers **one** read path out of everything the application does |
| **T8** | **What an in-memory database does not tell you.** Cases that pass against H2 and fail against PostgreSQL — upsert syntax, types, collation-dependent ordering, reserved words, and identifier generation, which quietly decides whether inserts can be batched at all. Plus container reuse, with the CI time before and after | **done** — `R9`, **no red commit**: the classic form of this defect does not reproduce on Boot 4.1.0, because `@AutoConfigureTestDatabase.replace` now defaults to `NON_TEST` — read out of the bytecode, and §3.5 proves the mechanism is still live under it. H2 **cannot create the first table of `V1`**. Of 23 statements this repository issues: 11 refuse loudly, 11 agree, and **1 is silent — the transaction-abort semantics that `R7` is entirely about**, which H2 would have certified the broken remedy as passing. **The largest finding needed no H2**: under `replace = ANY`, Flyway migrates PostgreSQL while Hibernate runs on H2 *in one context*, with `missing table [attempt]` as the only symptom. **Collation-dependent ordering did not diverge, and the control explains why** — `postgres:16-alpine` is musl-built and sorts byte-wise, so §8 now carries a risk against every ordering number in this repository. Container reuse saves ~1.2 s against a test class costing 198–422 s; **CI time before and after is 미측정 and §3.6 argues the question does not apply** |
| **T9** | **Authorisation, exposure, tokens.** An endpoint that authenticates and does not authorise; management endpoints exposed wholesale, including the one that dumps the heap and everything that was in it; token expiry and clock skew | **done, in two reports** — `R10`, **no red commit**, and §5 says why: a public repository with a wide-open actuator surface in its history is a worked example for the wrong reader, and the finding reproduces entirely from a test property. **The premise is half wrong on Boot 4.1.0**: exposure and access are two gates and `include: "*"` opens one, so `heapdump` stays 404 while twelve other endpoints answer. Open the second gate — one line — and the dump is **156 MB containing the datasource password that `/actuator/env` masks**. `loggers` is not a view but a **control surface** (`POST` → 204, `null` → `TRACE`). And `application.yml` has been exposing `prometheus`, **an endpoint this build does not have**. **Strands two and three: `R11`, same day, also with no red commit and §5's reason.** A token is required, verified, and an invalid one refused — **and alice's token still returned bob's data with a 200 and bob's item code in the body.** The 401s are the control that makes that a leak rather than an application that never checked. Adding authentication to a repository that had none broke **1 test of 56**, measured before anything was fixed, because the filter was scoped under `/api/v1` instead of everywhere. On expiry: `ignored` means **no token can ever be outlived**; `strict` refuses a valid token whose issuer's clock is 10s ahead; and `skewed` fixes that **only by keeping a just-expired token trusted for the same 30 seconds** — a tolerance is symmetric, and on a 30-second token it is a **100% extension**. |

## After the traps

| # | Item | State |
| --- | --- | --- |
| **R14** | **The batch that discarded what it was told to keep.** `AttemptRecorder`'s KDoc said the unit of work is one recording *because a learner's invalid submission is not a reason to discard the valid ones beside it* — and, three inches lower in the same file, that `recordAll` stops at the first failure | **done** — `R14`. **Of four valid recordings in a batch of five, two landed.** Recordings after the invalid one were not rejected, they were **never attempted**, and the caller could not tell that from any other outcome. Now: every recording attempted, every outcome returned, **4 of 4**. The blocker was the sentence *"it needs a requirement, not a refactor"* — true about **which shape to choose**, and used as a reason not to measure what was already happening |
| **R13** | **The dataset was hiding a strand.** Whether this repository's own generator removed `T4`'s stale-statistics strand from view | **done** — `R13`. It did. One learner holding 30 % of a table flips the plan on a **60× underestimate**; uniform data cannot, because a wrong estimate never reaches a decision boundary. **A second reason nobody had suspected**: `R3` §3.5's query is served by the index at any selectivity, so no estimate however wrong could have changed its plan. The shipped dataset was not touched — the variable was isolated instead |
| **R12** | **The arm the application kept.** `R6` §8 and `R7` §8 both measured a defect in `AttemptRecorder` and both deferred the fix. This is that deferral discharged | **done** — `R12`. **196 of 1,000 concurrent recordings applied, against 1,000 of 1,000 after.** The blocker was an argument of three true premises with a conclusion that does not follow: a business rule does not have to be a **constraint**, it can be a **`WHERE` predicate** — so a recording outside the `0..1` band matches no row instead of aborting the transaction (`R1` §9). **The defect was availability, not corruption**: `@Version` had already converted silent loss into loud rejection, and being loud is what kept it in place. A green test also went **red on a change that broke nothing** — the one whose KDoc opens *"This is the test that proves nothing"* |
| **R0** | **The scorecard.** For each of `T1`–`T9`: did the draft step on the trap it was documenting, did the author notice, and **what actually caught it**. Written last because it could not be written earlier | **done** — `R0`. **The draft stepped into six of the nine traps it was writing about.** What caught them: a deliberate measurement **7 times**, CI **3**, a planted instrument control **2**, the compiler **1** — and **a regression gate exactly once**, when `T3`'s ArchUnit rules refused `T6`'s remedy three reports after they were written. **Nine test classes here exist to refuse a future edit; one has ever been paid**, and §4 says so rather than counting gates as evidence |

## Deferred, deliberately

| Item | Why not |
| --- | --- |
| A learned recommendation model (BKT, DKT, …) | It would produce better recommendations and would not change one question this repository asks. A recommendation policy cannot be validated without learners, content, and teachers |
| A cache layer | **Decided by `ADR-005`, with measurements rather than the argument.** PostgreSQL's buffer cache had already moved a conclusion here — 576.8 ms cold against 140 ms warm on the same statement, and `R2` reported the cold figure as fact. A cache would have hidden `R3`'s 36.6 ms scan entirely, reaches at most half of the recommendation request, and none of `R12`'s write path |
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
