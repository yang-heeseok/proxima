# Open decisions

> **Created**: 2026-08-10
> **Updated**: 2026-08-17

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
| `OPEN-7` | **Should a check refuse a migration that contains a correlated subquery?** | `R15` §8 names it — *"No rule looks for correlated subqueries in migrations, and one could"* — and nobody has weighed the two sides. **For it:** `V3` passed four days of green CI and could not run on a database with rows in it, and nothing structural stops the next migration using the same shape. **Against it:** `V3` is fixed and gated by `MigrationDeduplicationTest`, so the rule would protect **no migration that exists** — `AGENTS.md` §Scope calls that *a guard that protects nothing yet*, which *"is not free, it is unbanked"*. And the cost of a rule that fires on correct text is measured here rather than feared: the `T3` self-invocation rule fired on Kotlin's `$default` bridges (`R7` §3.5 — *"a rule that is routinely wrong is a rule nobody reads"*), and a prose check was built, measured and discarded for the same reason (`R17` §5). **Both sides are real and no one has chosen** | **None that is honest, and this row says so rather than inventing one.** Its natural trigger — *the next migration that deduplicates* — is exactly the shape `ADR-003` condemned. **By the rule below, this should be decided now rather than parked** |
| `OPEN-8` | **What enforces the load lane's steady-state verdict, other than a person remembering to look?** | `R18` §7 states the position: the verdict goes to `steady-state.txt`, `load/README.md` step 2 is a `grep`, and *"nothing fails a build if the runner ignores the file"*. `R18` §8 then puts it in the category `R17` is a whole report about. **`R18` §5 chose between four *instrument* designs; whether enforcement should be machine-held was never on that table.** Three routes cost differently: a CI load lane (needs the seeded 3,963,719-row database, and `publication-readiness.md` records that a switch to private meters the Actions minutes and that *"this project's CI will grow a Testcontainers lane that is not cheap"*); a local wrapper that exits non-zero on a `DO NOT PUBLISH` verdict (cheap, **never priced**, and still needs the operator to invoke the wrapper); or accepting procedure permanently and saying so in one place instead of two. **`ADR-004` constrains the design without choosing it** — it forbids CI asserting a duration and explicitly lists *verdicts* among the machine-independent assertions CI may make | **Decidable today.** No artefact is missing and no measurement is owed — only the local-wrapper option is unpriced, and pricing it is not a precondition for choosing. **By the rule below, this should be decided now rather than parked** |
| `OPEN-9` | **Does a recording endpoint get built, or is `recordAll` permanently a library method with no caller?** | `R14` §8 says *"That is the next decision and it needs the endpoint that does not exist"* — a decision conditioned on something that may never arrive. **The condition is recorded nowhere:** `docs/roadmap.md` *Deferred, deliberately* does not name a recording endpoint, and `docs/explanation/domain-model.md` places **recommendation policy** out of scope while saying explicitly that the layer underneath it is *"**not** out of scope"*. So the thing `R14` defers to is undecided rather than closed, and **the decidable question is one level down: whether the endpoint exists at all, not what status code it returns.** Three of `R14` §8's six bullets — §8's *"still no endpoint"*, *"the outcomes are returned, not acted on"*, and *"order is not part of the contract"* — are parked behind it, and `R14` §5 rejected idempotency keys on the same ground (*"a contract with an absent party"*) | **Decidable today.** It needs a judgement about scope, not an artefact — **which is why it is here and `R14` §8's version is not.** By the rule below, this should be decided now rather than parked |

**An empty table here is a claim, not a default.** It says: everything undecided has been
decided, and nothing currently known is waiting on a judgement. **That claim stood from
2026-08-14 to 2026-08-17 and was false the whole time** — see below.

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
| `OPEN-6` | **Where the `0..1` band on `mastery.score` is defined** | `ADR-006` — **both places stay; the gate is on their ordering**, 2026-08-14. `ck_mastery_score` is authoritative and outlives any one code path; the predicate in `RecordingQueries` is what makes a refusal *zero rows* instead of an aborted transaction (`R12` §3.4). They are not supposed to be **equal**, they are supposed to be **ordered**: a stricter predicate refuses valid work and is harmless to data, a laxer one puts `R1` §9's abort back through the code built to prevent it. `ScoreBandGateTest` drives seven boundary deltas and requires every refusal to arrive from the guard and none from the constraint. **Decided the morning after being opened**, because its deadline had the shape `ADR-003` had just condemned |
| `OPEN-4` | **Whether a cache layer is in scope at all** | `ADR-005` — **no cache layer**, 2026-08-13, and **the only one of these rows closed before its hazard arrived** rather than after. Closed with measurements rather than the argument it was opened with: PostgreSQL's buffer cache had already moved a conclusion here — the same statement at **576.8 ms cold and 140 ms warm**, with the cold figure reported as fact in `R2` — so a cache changing what the instruments read is observed rather than predicted. It also reaches at most half of the recommendation request (`R2`: query 140 ms against gateway 150 ms) and none of `R12`'s write-side defect |
| `OPEN-5` | **How the measurement environment is pinned in CI** | `ADR-004` — **the lane states its environment per run**, 2026-08-13. Closed by the hazard arriving rather than by the deadline: this row guarded *CI publishing numbers*, and what actually happened is that a **report reached into CI and took one** — `R9` §3.6 divided a container-start figure from this machine by a step timing read off the workflow API. That breaks `measurement-discipline.md` **rule 3**, which predates every measurement here, and the report's own §8 was uneasy about the sentence without noticing which rule it broke. `measurement-discipline.md` gains rule 9, and `R9` §3.6 is annotated rather than corrected |
| `OPEN-3` | **Identifier generation strategy** | `ADR-003` — **`IDENTITY` stays**, 2026-08-13. Measured rather than assumed: 1,000 inserts cost 1,000 statements under `IDENTITY` and 40 under `SEQUENCE(allocationSize = 50)`, about 10× — and `SEQUENCE(allocationSize = 1)` costs 1,020 and wins nothing, so the gain is the allocation size and not the sequence. No path here inserts more than one row per transaction, so the 10× is unclaimed rather than lost. `IdentifierGenerationTest` is the trip-wire |
| `OPEN-2` | **How QueryDSL is generated on Kotlin** | `ADR-001` — **the community fork `io.github.openfeign.querydsl` 7.0 via `kapt`**, 2026-08-10. Timebox 30 min, used ~15. Both candidates were built and run against PostgreSQL and **both passed**; the predicted classifier friction did not occur. The fork won on maintenance, not on capability |
