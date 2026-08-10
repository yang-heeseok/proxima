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
| `OPEN-3` | **Identifier generation strategy** | `IDENTITY` prevents Hibernate from batching inserts; a sequence with an allocation size does not, but changes what the ids look like. This interacts with how the seed is loaded and is worth measuring rather than assuming | Before the seed generator. `ADR-003` |
| `OPEN-4` | **Whether a cache layer is in scope at all** | Caching would improve every number in this repository, and would also hide which of them were bad for structural reasons. Deciding to leave it out is a real decision and should be recorded as one | Before any report claims a latency ceiling |
| `OPEN-5` | **How the measurement environment is pinned in CI** | A number taken on a developer machine and a number taken on a runner are not comparable, and this repository publishes numbers. Either CI stops publishing numbers, or the environment is stated per-run | Before CI runs a load lane |

## Closed

*(Moved here with the ADR that closed them.)*

| # | Question | Closed by |
| --- | --- | --- |
| `OPEN-1` | **Which Spring Boot line** | `ADR-000` — **Spring Boot 4.1.0 on JDK 21**, 2026-08-10. The near-miss was 3.5.x, the line most readers run in production; it lost because its OSS support ended 2026-06-30 and `start.spring.io` no longer offers it |
| `OPEN-2` | **How QueryDSL is generated on Kotlin** | `ADR-001` — **the community fork `io.github.openfeign.querydsl` 7.0 via `kapt`**, 2026-08-10. Timebox 30 min, used ~15. Both candidates were built and run against PostgreSQL and **both passed**; the predicted classifier friction did not occur. The fork won on maintenance, not on capability |
