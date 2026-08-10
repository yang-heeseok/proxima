# ADR-000 — Which Spring Boot line

> **Created**: 2026-08-10
> **Updated**: 2026-08-10
> **Status**: **Proposed — not yet decided.** Closes `OPEN-1`.

## Context

The choice is between the line most likely to match a production system a reader already
operates, and the newest available one.

This repository exists to be read by people who run Spring in production. A version they
cannot map onto their own system costs them the translation, and buys this repository
nothing — none of the defects it reproduces are version-specific in an interesting way.
They are properties of JPA, of connection pooling, and of proxy-based AOP, all of which
have behaved this way across many releases.

Against that: pinning to an older line to look familiar is its own kind of dishonesty if
the newer one is what a greenfield project would sensibly choose today.

## Decision

**PENDING.** To be made against the current GA list at the time of the first build file —
not from memory, and not from whatever a tutorial happened to use.

Whichever line is chosen, this ADR records:

- the version chosen, exactly
- what the current GA options were on the day of choosing
- why the alternative was not chosen
- what would cause this to be revisited

## Consequences

*(To be written when the decision is made.)*

## Note on why this ADR exists before its decision

An ADR written after the fact reconstructs a rationale; the honest ones are written while
the answer is still open. This file is committed empty on purpose, so that the version
choice cannot be made silently by a copy-pasted `build.gradle.kts`.
