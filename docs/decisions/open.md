# Open decisions

> **Created**: 2026-08-10
> **Updated**: 2026-08-18

**Status:** Live. This file exists so that *undecided* is a recorded state rather than a
silence, and it discharges the `PUB-4` row that says so.

## What this document does not own

| Question | Owner |
| --- | --- |
| A decision that has been made | `docs/decisions/adr/` |
| What must be true before publication | `docs/decisions/publication-readiness.md` |
| The order the work happens in | `docs/roadmap.md` |

---

## Open

| # | Question | Why it is not decided yet | Deadline |
| --- | --- | --- | --- |
*(empty as of 2026-08-18 — see below)*

**An empty table here is a claim, not a default.** It says: everything undecided has been
decided, and nothing currently known is waiting on a judgement. **That claim stood from
2026-08-14 to 2026-08-17 and was false the whole time** — see below.

> **The table is empty again as of 2026-08-18, and that is a different kind of empty.** The
> three rows `R19` opened were closed the next day by `ADR-007`, `ADR-008` and `ADR-009`,
> because each of them said in its own `Deadline` column that it could name no honest deadline
> and should therefore be decided rather than parked. **Every one of the nine rows this file
> has ever held is now in the table below with an ADR beside it.**
>
> What makes this emptiness worth more than the last one is not that it was checked once. It
> is that the audit which established it — 145 bullets against one question — is written down
> in `R19` §3 and can be run again by somebody else. The previous empty table rested on nobody
> having looked.

**What would make it false**, and what to do about it:

- A *남는 위험* bullet that nobody can act on without a judgement belongs **here**, not there.
  `OPEN-6` came from `R12` §8 for exactly that reason. **A risk in a report is something
  someone chose to live with; a row here is something someone still has to decide.**
- A deadline of the form *"before X happens"* where `X` may never happen is not a deadline —
  `ADR-003` closed `OPEN-3` on that finding and `OPEN-6` was opened with the same shape the
  next day. If a row here cannot name a date or a change that will certainly arrive, it should
  be decided now instead.

### The empty table was a claim nobody had established — withdrawn 2026-08-17

**Kept here rather than deleted, because how the claim failed is more useful than the claim.**

It read *"(empty as of 2026-08-14)"*, and the paragraph under it said everything undecided had
been decided. Four rows had just closed inside thirty-six hours, so the sentence was written in
the middle of a run of closures and read as the end of one.

**Nothing had checked.** The test this document states — *does discharging this require a
judgement, or only work?* — had never been run across the place the last row came from.
`OPEN-6` was found in `R12` §8 because `R12`'s author was looking at `R12` §8. Nobody had asked
the same question of the other eighteen reports' §8 sections, and there were **145 bullets** in
them. An audit on 2026-08-17 asked it of all 145 and found **three**: `OPEN-7`, `OPEN-8`, and —
one level below the bullet that names it — `OPEN-9`. `R19` is that audit.

**Both sentences above the table were right; the evidence under them was the weaker kind.**
*"An empty table here is a claim, not a default"* asks for a claim to be established, and what
established this one was that no one had recently thought of a row. That is the same substitution
`PUB-4` exists to refuse, and `R17` is this repository's report on what it costs — its subject is
a guard whose only detector was a person, and **this table's only detector was a person too.**

`OPEN-6`'s own lesson is the one that was not carried forward. It said a decision left on the
wrong shelf means *nobody ever has to make it*. What it did not say, and what this withdrawal
adds, is that **the shelf has to be swept and not merely watched** — a row arrives on it whenever
a report is written, and no report has ever been asked to check.

### `OPEN-3` — closed 2026-08-13, and the reason it stayed open was wrong

**Kept here rather than deleted, because how this row failed is more useful than the row.**

The deadline was moved once, honestly: the original was *before the seed generator*, the
loader turned out to use `COPY` and bypass Hibernate, and the deadline was re-dated to
**before the first bulk insert path** rather than quietly dropped.

Then the row said: *there is no bulk insert path yet, so there is nothing to measure.*

**That was false, and it was the load-bearing sentence.** Whether `IDENTITY` defeats batching
is a property of the generator, the dialect, and the driver — three throwaway entities and a
container answer it, and the application never had to grow anything. So a re-dated deadline
became a deadline that could not arrive, `BaseEntity` carried the word *provisional* for three
days without moving, and `R8` §8 and then `R9` §8 each recorded the question as unmeasured.
**A provisional choice nobody can schedule the measurement for is a permanent choice with a
disclaimer on it.**

Measured on 2026-08-13: `IDENTITY` costs 1,000 statements for 1,000 rows and a sequence with
`allocationSize = 50` costs 40, about ten times faster — **and a sequence with the default
`allocationSize = 1` costs 1,020 and is indistinguishable from `IDENTITY`**. The hazard below
reproduced exactly. `ADR-003` keeps `IDENTITY` on the grounds that no path here inserts more
than one row per transaction, and names what flips it.

**The hazard, now a measured fact rather than a worry.** After the `COPY`, the loader calls
`setval` on each table's identity sequence so the next application insert continues past the
loaded rows. If a sequence generator is ever chosen, it must be **the same sequence the loader
realigns** — otherwise Hibernate allocates from a sequence starting at 1 and the first insert
collides with a row the seed already loaded. Reproduced in
`IdentifierGenerationTest.a sequence that was not realigned collides with seeded rows`:
`duplicate key value violates unique constraint`, then `INSERTED` after `setval`. **That
failure appears only against a seeded database and never against an empty test one**, which is
precisely the class of defect this repository exists to collect.

## Closed

*(Moved here with the ADR that closed them.)*

| # | Question | Closed by |
| --- | --- | --- |
| `OPEN-1` | **Which Spring Boot line** | `ADR-000` — **Spring Boot 4.1.0 on JDK 21**, 2026-08-10. The near-miss was 3.5.x, the line most readers run in production; it lost because its OSS support ended 2026-06-30 and `start.spring.io` no longer offers it |
| `OPEN-7` | **Should a check refuse a migration that contains a correlated subquery?** | `ADR-007` — **no rule; the coverage moves instead**, 2026-08-18. `PopulatedMigrationTest` runs the migrations against 20,000 `mastery` rows with 5,000 planted duplicates, and `EXPLAIN`s every DML statement they contain against that populated schema, failing on a `SubPlan`. **Three reasons the rule lost.** It would have protected no migration that exists — `AGENTS.md` §Scope's *unbanked* — while `MigrationDeduplicationTest` already gates the one that does. The syntax is not the defect: the defect is that **migrations are only ever tested on empty tables**, and a correlated subquery is one shape that took; the next could be an unindexed bulk `UPDATE`, which a rule spelled for subqueries passes. And a rule that fires on correct text is a rule nobody reads — paid twice here, `R7` §3.5 and `R17` §5. **The assertion is a plan and not a clock** because rule 9 forbids CI asserting a duration, and because the red statement would have finished in seconds at any row count a test can afford. `ADR-007` records what stays uncovered: DDL, which `EXPLAIN` cannot reach |
| `OPEN-8` | **What enforces the load lane's steady-state verdict, other than a person remembering to look?** | `ADR-008` — **`load/run.sh`, the only documented way to run a scenario**, 2026-08-18. It exits **1** on `FAIL` and **2** when no verdict was written at all, because a run with no verdict is not a passing run. Three planted scenarios and `load-harness.yml` require exactly those three exits, with `selftest-ok` returning 0 as the negative control. **The CI load lane was rejected for a reason beyond cost**: `ADR-004` forbids CI asserting a duration, so such a lane could never produce a citable latency and would exist only to enforce the verdict of a measurement it may not take. **It does not remove the person** — someone still types `./run.sh`. What it removes is the second step, so the failure is loud where it happens instead of two hours later in a log, which is where `R18` actually found it |
| `OPEN-9` | **Does a recording endpoint get built, or is `recordAll` permanently a library method with no caller?** | `ADR-009` — **no endpoint, and the gap is named**, 2026-08-18. An endpoint adds surface and no defect class; `R14` reproduced, measured and fixed its failure through the service layer without one, and the three `R14` §8 bullets waiting on a consumer are answered by there never being one. **The cost is recorded rather than implied: every load number in this repository is on the read path**, and the write path's concurrency was measured with JVM threads, never over HTTP — so **the write path under HTTP load is 미측정** and this decision keeps it that way. The one real argument for an endpoint is that it would let k6 reach the write path; that is a question about the load harness, and building an API first to enable an unscheduled measurement is `ADR-007`'s unbanked guard with a different name |
| `OPEN-6` | **Where the `0..1` band on `mastery.score` is defined** | `ADR-006` — **both places stay; the gate is on their ordering**, 2026-08-14. `ck_mastery_score` is authoritative and outlives any one code path; the predicate in `RecordingQueries` is what makes a refusal *zero rows* instead of an aborted transaction (`R12` §3.4). They are not supposed to be **equal**, they are supposed to be **ordered**: a stricter predicate refuses valid work and is harmless to data, a laxer one puts `R1` §9's abort back through the code built to prevent it. `ScoreBandGateTest` drives seven boundary deltas and requires every refusal to arrive from the guard and none from the constraint. **Decided the morning after being opened**, because its deadline had the shape `ADR-003` had just condemned |
| `OPEN-4` | **Whether a cache layer is in scope at all** | `ADR-005` — **no cache layer**, 2026-08-13, and **the only one of these rows closed before its hazard arrived** rather than after. Closed with measurements rather than the argument it was opened with: PostgreSQL's buffer cache had already moved a conclusion here — the same statement at **576.8 ms cold and 140 ms warm**, with the cold figure reported as fact in `R2` — so a cache changing what the instruments read is observed rather than predicted. It also reaches at most half of the recommendation request (`R2`: query 140 ms against gateway 150 ms) and none of `R12`'s write-side defect |
| `OPEN-5` | **How the measurement environment is pinned in CI** | `ADR-004` — **the lane states its environment per run**, 2026-08-13. Closed by the hazard arriving rather than by the deadline: this row guarded *CI publishing numbers*, and what actually happened is that a **report reached into CI and took one** — `R9` §3.6 divided a container-start figure from this machine by a step timing read off the workflow API. That breaks `measurement-discipline.md` **rule 3**, which predates every measurement here, and the report's own §8 was uneasy about the sentence without noticing which rule it broke. `measurement-discipline.md` gains rule 9, and `R9` §3.6 is annotated rather than corrected |
| `OPEN-3` | **Identifier generation strategy** | `ADR-003` — **`IDENTITY` stays**, 2026-08-13. Measured rather than assumed: 1,000 inserts cost 1,000 statements under `IDENTITY` and 40 under `SEQUENCE(allocationSize = 50)`, about 10× — and `SEQUENCE(allocationSize = 1)` costs 1,020 and wins nothing, so the gain is the allocation size and not the sequence. No path here inserts more than one row per transaction, so the 10× is unclaimed rather than lost. `IdentifierGenerationTest` is the trip-wire |
| `OPEN-2` | **How QueryDSL is generated on Kotlin** | `ADR-001` — **the community fork `io.github.openfeign.querydsl` 7.0 via `kapt`**, 2026-08-10. Timebox 30 min, used ~15. Both candidates were built and run against PostgreSQL and **both passed**; the predicted classifier friction did not occur. The fork won on maintenance, not on capability |
