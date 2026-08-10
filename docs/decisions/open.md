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
| `OPEN-1` | **Which Spring Boot line** | The choice is between the line most likely to match a production system a reader already runs, and the newest one. It should be made against the current GA list rather than from memory | Before the first build file. `ADR-000` |
| `OPEN-2` | **How QueryDSL is generated on Kotlin** | `kapt` is in maintenance; the Jakarta classifier and the community fork are both plausible. A 30-minute timebox decides it, and the fallback — JPQL with constructor projections — is acceptable | Before the first repository class. `ADR-001` |
| `OPEN-3` | **Identifier generation strategy** | `IDENTITY` prevents Hibernate from batching inserts; a sequence with an allocation size does not, but changes what the ids look like. This interacts with how the seed is loaded and is worth measuring rather than assuming | Before the seed generator. `ADR-003` |
| `OPEN-4` | **Whether a cache layer is in scope at all** | Caching would improve every number in this repository, and would also hide which of them were bad for structural reasons. Deciding to leave it out is a real decision and should be recorded as one | Before any report claims a latency ceiling |
| `OPEN-5` | **How the measurement environment is pinned in CI** | A number taken on a developer machine and a number taken on a runner are not comparable, and this repository publishes numbers. Either CI stops publishing numbers, or the environment is stated per-run | Before CI runs a load lane |

## Closed

*(Moved here with the ADR that closed them.)*

| # | Question | Closed by |
| --- | --- | --- |
| — | — | — |
