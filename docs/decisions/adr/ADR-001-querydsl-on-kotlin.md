# ADR-001 — How QueryDSL is generated on Kotlin

> **Created**: 2026-08-10
> **Updated**: 2026-08-10
> **Status**: **Proposed — not yet decided.** Closes `OPEN-2`.
> **Timebox**: 30 minutes. See *Fallback* below.

## Context

QueryDSL generates its `Q` classes with an annotation processor. On Kotlin that means
`kapt`, which Kotlin's own maintainers describe as in maintenance mode — it is not being
developed alongside K2, and it works by generating Java stubs from Kotlin sources, which is
where most of the friction lives.

Compounding it: the Jakarta migration split the artefact coordinates, and there is a
community fork maintained separately from the original project. A wrong combination
produces a build that either generates nothing or generates against the wrong persistence
API, and both failure modes present as an unhelpful "cannot resolve `QAttempt`".

This is a real, current, and slightly annoying piece of ecosystem state. It is exactly the
class of problem that does not appear in tutorials, because tutorials are written in Java.

## Options

| Option | Note |
| --- | --- |
| A — `kapt` + the original artefact with the Jakarta classifier | The conventional path. Verify the classifier and processor coordinates against the current release, not against a blog post |
| B — the community fork | Maintained separately; check whether it currently publishes what this project needs |
| C — **Fallback: no QueryDSL.** JPQL plus constructor projections, and the Criteria API where a query must be assembled dynamically | Loses type-safe query construction. Loses nothing this repository actually measures |

## Decision

**PENDING.**

## Fallback rule — written before starting, on purpose

**Thirty minutes.** If neither A nor B builds and generates within that window, take C and
record the attempt here: what was tried, what the error was, and what would make this worth
revisiting.

This rule is written down in advance because the failure mode it guards against is
predictable: a build-tooling problem is absorbing, it always feels like it is five minutes
from working, and half a day can go into it without a decision ever being made. A timebox
decided while the problem is interesting is not a timebox.

**Falling back is not a failure to record quietly.** A dependency that is expensive to
adopt and replaceable at low cost *should* be dropped, and the record of having measured
that cost is worth more than the dependency. If C is taken, this ADR is the deliverable.

## Consequences

*(To be written when the decision is made — including, if C is taken, which queries became
harder and whether that ever actually hurt.)*
