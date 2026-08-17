# Measurement discipline

> **Created**: 2026-08-10
> **Updated**: 2026-08-13

**Status:** Settled before the first measurement, deliberately. Rules written after a
number is inconvenient are not rules.

This document owns **what makes a number in this repository citable.**

## What this document does not own

| Question | Owner |
| --- | --- |
| What the numbers are | `docs/reports/` |
| What is being measured and why | `docs/roadmap.md` |
| What the data looks like | `docs/explanation/domain-model.md` |
| Whether a number may be published at all | `docs/decisions/publication-readiness.md` — `PUB-4` |

---

## The short version

> **A number without its environment is not a measurement. It is a memory.**

Every number published here carries the block below. A number that was never taken is
called **미측정 (not measured)** — never estimated, never inferred from a similar run.

---

## The environment block

Copied verbatim into the header of every report, with the values of the run that produced
its numbers.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 (JDK 21 toolchain, pinned in gradle.properties)
  PostgreSQL     : postgres:16-alpine — server 16.14
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
                   default shared_buffers
  Connection pool: HikariCP 7.0.2, maximum-pool-size=10, connection-timeout=30000 (defaults)
  Dataset        : seed value 20260810 — see domain-model.md for row counts
  Load           : k6, 30s warm-up DISCARDED, 3min measurement window
  Repetitions    : 3 runs, median reported; spread stated if >10%
```

Filled in from the machine on 2026-08-10 rather than described. Two of those lines were
wrong in the draft written before anything ran, and both were wrong in a way that would
have cost a reader time:

- **It said Docker Desktop.** There is no Docker Desktop here. The engine runs natively
  inside WSL2, which is not a detail of taste — it means **Windows cannot reach the Docker
  daemon at all**, so the entire build and test lane runs inside WSL2. A reader who
  reproduced this on Docker Desktop would get different filesystem and network behaviour
  than these numbers were taken with.
- **It said `-Xmx512m`.** Nothing sets that. The heap flag is 미측정 as a property of these
  runs, and the field is left out until a report actually pins it — a stated JVM flag that
  no run used is worse than no flag, because it looks checkable and is not.

The image digest is recorded alongside the tag because `16-alpine` is a moving tag. Two
people running `postgres:16-alpine` a month apart are not necessarily running the same
server, and the digest is what makes the row citable.

---

## Why the warm-up is discarded, and why it is stated first

**A JVM lies to you for the first few seconds of every run.**

Code starts interpreted. The JIT promotes hot methods to C1, then to C2, and the compiled
form can be an order of magnitude faster than what ran a second earlier. Class loading is
still happening. The connection pool has not reached steady size. Hibernate has not
populated its query plan cache. The OS page cache holds none of the table yet.

A benchmark that includes that window is not measuring the system; it is measuring the
system's first ten seconds, which no user experiences and no capacity plan cares about.
Numbers taken that way have been observed to differ from steady state by a factor of
several — enough to reverse the ranking of two options.

**So: 30 seconds of load, discarded, before the measurement window opens.** Every time,
including for the runs that "obviously don't need it".

The same reasoning applies to the database. A `COPY` of several million rows leaves the
planner's statistics stale, and a stale planner picks different plans. `ANALYZE` runs
before any query measurement — and the one report where it deliberately does **not** is
labelled as such, because that gap is itself a finding.

## Why percentiles, and which ones

An average hides the failure. If 95 requests take 20ms and 5 requests take 30 seconds
because they were waiting for a connection, the average is a comfortable 1.5 seconds and
every one of those 5 users saw a timeout.

- **p50** — what a typical request feels like.
- **p95** — where the shape of the system starts to show.
- **p99** — where the failures live. **This is the number that decides whether a change
  worked.**
- **Error rate** — reported alongside, always. A p99 that improved because requests
  started failing early is not an improvement, and only the error rate reveals it.

Percentiles are read at a stated concurrency. "p99 = 210ms" is meaningless without "at 200
virtual users", because the interesting behaviour of every system in this repository is
what happens as concurrency rises.

## The knee, and why one data point is not a result

Latency against concurrency is not a line. It is flat, then it bends, then it goes
vertical. The bend — the **knee** — is the capacity of the system, and it is the only point
on the curve worth designing around.

A report that measures one concurrency level has found a point, not a curve. Where a report
claims a system got faster, it states at which concurrency levels that held, and where the
knee moved to.

## Connection pool sizing

The pool is left at HikariCP's default of 10 for every measurement unless the report says
otherwise, because a pool sized for the experiment hides what the experiment is about.

When pool size is the variable, the reference point is the formula HikariCP's own
documentation derives for a pool that cannot deadlock:

```
pool size = Tn × (Cm − 1) + 1
    Tn = number of threads
    Cm = maximum simultaneous connections a single thread holds
```

The practical reading is that **`Cm` is usually the number nobody knows** — and a request
that holds a connection while calling something else has an `Cm` larger than its author
believes. That is the mechanism behind the first report in this repository.

## Rules

1. **Measured, or 미측정.** No estimate is published as a number.
2. **Environment block or no number.** Including in a commit message.
3. **Before and after come from the same run conditions.** Different machine, different
   dataset, different day without re-baselining — the comparison is not made.
4. **Logs and query plans are quoted verbatim.** A summarised `EXPLAIN` is an opinion.
5. **Three runs, median, spread stated when it is wide.** A single run that looked good is
   a single run that looked good.
6. **Error rate is always reported with latency.**
7. **The concurrency level is part of the number.** Never a bare percentile.
8. **What was not measured is written down** — in the report's *남는 위험* section, which
   is why that section is not optional.
9. **A number taken on CI carries that run's environment block and its run id**, and CI
   asserts nothing that is a duration. Added 2026-08-13 by `ADR-004`, after rule 3 was broken
   in `R9` §3.6 — a container-start figure from this machine divided by a step timing read off
   the workflow API. Rule 3 already forbade it. **Rule 9 exists because rule 3 was not enough
   to stop somebody who had read it**, and because the lane it concerns had no way to say what
   it ran on until now.
