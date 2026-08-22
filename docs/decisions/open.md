# Open decisions

> **Created**: 2026-08-10
> **Updated**: 2026-08-22

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
| `OPEN-13` | **Should the toolchain pin a vendor, so that the JVM every number here was taken on is requested rather than merely recorded?** | **There is a trade, which is why this is a row and not an errand.** `jvmToolchain(21)` pins a language version; `vendor` and `JvmVendorSpec` appear nowhere in the build, so any JDK 21 on the machine satisfies it and Gradle reports the one it found as `Detected by: Current JVM`. Adding `vendor = JvmVendorSpec.ADOPTIUM` is one line and would make `measurement-discipline.md`'s block true — **and `./gradlew javaToolchains` reports `Auto-download: Enabled`, so it would also turn "you have the wrong JDK" into a silent download**, putting a network fetch of a JVM inside a build that currently has none. Against that: **every number in this repository is compared against every other under rule 3, and nothing enforces the vendor they share.** CI already pins `distribution: temurin`, so pinning would cost CI nothing and cost a contributor on Corretto or Zulu a second JDK. **What would settle it: a decision on whether this repository wants contributors at all** — if its builds are only ever the author's and CI's, pinning is free and rule 3 gets its guarantee; if not, the auto-download behaviour has to be settled first | **None that can be named honestly, and that is deliberate.** The hazard has already arrived once — 2026-08-22, an Ubuntu 21.0.11 selected in place of the Temurin, unreported by anything — so a deadline of *"before it happens"* is spent. `ADR-003` closed `OPEN-3` on exactly that shape. This should be decided on its merits rather than parked |

> **The table is not empty as of 2026-08-22.** `OPEN-13` is in it, and every paragraph below
> that speaks of an *empty* table was written while it was — they are dated and left standing
> rather than edited, because how this table fills and empties is the record it exists to keep.
> **`OPEN-13` arrived the way the paragraphs below ask a row to arrive**: not from a sweep of
> documents, but from a build resolving to the wrong JVM for an hour and nothing reporting it.
> Like `OPEN-12`, no report contains it — it is a property of the toolchain, and only running
> the build could have shown it.

**The table was filled and emptied inside forty-eight hours, and this time the claim below is
established rather than assumed.** `OPEN-10`, `OPEN-11` and `OPEN-12` opened on 2026-08-21 and
all three closed on 2026-08-22, by `ADR-015`, `ADR-016` and `ADR-017`. `OPEN-10` was the first
row opened since `R19` filled and `ADR-007`–`ADR-009` cleared it on 2026-08-18, and it arrived
the way the paragraph below asks for: out of a *남는 위험* bullet — `R27` §8's first — that
nobody can act on without a judgement.

**What makes the empty table a claim today rather than a default.** Every one of the three was
closed with an ADR naming what would flip it, and the sweep that would fill it again has been
run and written down: `ADR-014` classified 168 measurement gaps and left **66 marked (a) —
measurable here and not done.** Those are **work, not judgements**, which is precisely the
distinction `R19` built this table around. If one of the 66 turns out to need a trade rather
than an errand, it belongs here, and `OPEN-11` is the worked example of getting that
classification wrong in the safe direction.

> **And 66 is not the whole of it, which this paragraph implied on 2026-08-22 and does not
> now.** `ADR-014` was written in a parallel slice that forked from `a417ce3`, so **it swept a
> tree on which none of `R20`–`R27` existed** — the ledger contains no entry from any round-two
> report, and `R28` adds a ninth §8 section it has never seen either. The figure is exact about
> what it counted and it is **not** the repository's current backlog. Re-running the sweep over
> the round-two reports is itself an (a)-shaped errand, and nobody has done it.
>
> Found while checking an audit finding that was **raised and then withdrawn** — the auditor's
> arithmetic against 66 was wrong and the retraction was correct, and looking at why sent both
> of us to a scope nobody had stated.

**Three rows opened, and only one of them came from a report.** `OPEN-11` came from `R20` §3.6
the same way `OPEN-10` came from `R27` §8. `OPEN-12` did not: it came from **running the merged
tree**, where a round-one gate failed once and passed twice on a tree byte-identical to the
branch it had been green on. No report contains it, because no slice owned it — it is a
property of the three together, and integration is the only place it could have been seen.
`R19` swept *남는 위험* bullets for decisions filed as risks and found three; **this is the
first row that a sweep of the documents could not have produced at all.**

> **All three closed the day after they opened, and every closing found more than its row
> asked for.**
>
> `ADR-015` closed `OPEN-12`. The gate that failed asserts *the losers failed loudly*, so an
> unraced run raises a false alarm. **Three sibling arms assert `failures == 0`**, which a
> serialised run satisfies without exercising anything — so the flaky arm was the safe one and
> the quiet ones were the hazard. A row opened about one test was discharged across four.
>
> `ADR-016` closed `OPEN-11`, and the row turned out to be **undecidable rather than
> unanswered**: no two candidates had ever been measured in the same condition, so no argument
> could have settled it. The missing arm took four minutes. **Recognising that a row is short a
> measurement rather than short a judgement is worth doing before opening it** — this one was
> filed as a trade and was really an errand.
>
> `ADR-017` closed `OPEN-10`, and the row shrank on contact. It was filed as *"twenty
> documents against hours of re-measurement"*; the real count is **six identifier lines**,
> because every block naming 16.14 already carried the digest beneath it and the defect was
> leading with the moving name. The guard `R27` §5 rejected as *"red on arrival"* **starts
> green**, because the pin came first — which that section had itself predicted.


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
>
> **Stale since 2026-08-21 and annotated rather than edited.** *"Every one of the nine rows this
> file has ever held is now in the table below with an ADR beside it"* was true when written and
> is not now: `OPEN-10` is the tenth and has no ADR. **The sentence would have gone false on the
> next row whatever it was**, which is the shape `R19` §3.4 catalogues — a claim written about
> one instant and read as a standing one. It is left standing because how it went stale is worth
> more than a tidy file, and because `R17` §8's largest hole is a document edited on the day it
> becomes false: this one was.

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
| `OPEN-12` | **A regression gate that cannot tell "the defect did not occur" from "the defect was hidden"** | `ADR-015` — **every arm proves its own precondition**, 2026-08-22. `RaceOverlap.peak` measures how many calls were ever open at one instant and `assertRaced` requires two, so an unraced run now says *the harness failed* instead of *the losers did not fail*. **The arm that was flaky turned out to be the safe one**: it asserts `failures > 0` and raises a false alarm, while the other three assert `failures == 0`, which a serialised run satisfies **without exercising the remedy at all** — `R9` §7 and `R16`'s `rate >= 0.0` in three tests at once. Fixing only the one that failed would have left those untouched. The control was watched refusing: `peak` planted to return 1 always, the shape `study-consistency.yml`'s `S3` actually had, fails 2 of 5 and exits 1 |
| `OPEN-11` | **Does the load path run `VACUUM`, and if it does not, does this repository stop offering covering indexes as a remedy?** | `ADR-016` — **the loader is not changed, and the loader was never the reason**, 2026-08-22. The row was **undecidable rather than unanswered**: `R20` §3.6 measured the covering candidate either side of a vacuum and both candidates before one, which leaves only *covering after* against *single before* — two conditions, refused by rule 3. `R28` takes the missing arm. With both vacuumed the covering index is **1.03–1.16× across four runs while the single-column arm's own spread is 11.0–40.6%**, so the effect sits inside the variance and 85% more space still buys nothing. **The first run said 1.16× at 11.0% spread — *outside* — and stopping there would have produced the opposite finding**, which is `R18`'s lesson reached with four consecutive runs instead of two an hour apart. `R3`'s and `R20`'s verdicts stand with their reasons now known to be incomplete rather than wrong |
| `OPEN-10` | **Does the PostgreSQL image get pinned by digest — and if it does, do the documents saying `16.14` get corrected, or do their numbers get re-baselined on `16.15`?** | `ADR-017` — **pinned, corrected, not re-baselined, and the tag is watched**, 2026-08-22. `R27` §3.2 compared twelve facts across the two images and found three differences, all the same fact; alpine and musl are unchanged, which is what keeps `R25` and `R26` standing. **The correction was six identifier lines, not twenty documents** — 44 files mention `16.14`, eight carry an environment block, two of those already said 16.15, and **none was ever wrong about what it ran on**: every one carries the digest on the next line and the defect was leading with the moving name. `.github/workflows/image-pin.yml` is the guard `R27` §5 rejected as *"red on arrival"*, **and it starts green**, because the pin was taken from the tag's current value — the objection was correct when written and was spent by the pin, exactly as that section's *"pin first, then guard the pin"* predicts |
