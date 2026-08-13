# Open decisions

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

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
| `OPEN-4` | **Whether a cache layer is in scope at all** | Caching would improve every number in this repository, and would also hide which of them were bad for structural reasons. Deciding to leave it out is a real decision and should be recorded as one | Before any report claims a latency ceiling |
| `OPEN-5` | **How the measurement environment is pinned in CI** | A number taken on a developer machine and a number taken on a runner are not comparable, and this repository publishes numbers. Either CI stops publishing numbers, or the environment is stated per-run | Before CI runs a load lane |

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
| `OPEN-3` | **Identifier generation strategy** | `ADR-003` — **`IDENTITY` stays**, 2026-08-13. Measured rather than assumed: 1,000 inserts cost 1,000 statements under `IDENTITY` and 40 under `SEQUENCE(allocationSize = 50)`, about 10× — and `SEQUENCE(allocationSize = 1)` costs 1,020 and wins nothing, so the gain is the allocation size and not the sequence. No path here inserts more than one row per transaction, so the 10× is unclaimed rather than lost. `IdentifierGenerationTest` is the trip-wire |
| `OPEN-2` | **How QueryDSL is generated on Kotlin** | `ADR-001` — **the community fork `io.github.openfeign.querydsl` 7.0 via `kapt`**, 2026-08-10. Timebox 30 min, used ~15. Both candidates were built and run against PostgreSQL and **both passed**; the predicted classifier friction did not occur. The fork won on maintenance, not on capability |
