# Measurement discipline

> **Created**: 2026-08-10
> **Updated**: 2026-08-22

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
  JVM            : Temurin 21.0.12+8 -- RECORDED, not pinned. gradle.properties pins
                   language version 21 and nothing else; see below
  PostgreSQL     : server 16.14, and the DIGEST below is the identifier — the tag
                   `postgres:16-alpine` named this image until 2026-08-13 and now
                   resolves to 16.15. Pinned by digest since `8dec7e6`; `OPEN-10`
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

  > **Measured 2026-08-21, and the correction above stopped one sentence short.** `R23`
  > asserted the belief that survived it — that a container given 512 MB is a JVM with a
  > 512 MB heap, so the flag was writing down what would have happened anyway — and got
  > **134217728 bytes**, a quarter of the limit. `UseContainerSupport` defaults to `true` and
  > `MaxRAMPercentage` to 25, so the ceiling is a fraction of the **cgroup**, never of the
  > flag-less host.
  >
  > **The flag was not merely unsourced. Written beside a 512 MB limit it moves who refuses
  > from the JVM to the kernel**: the ergonomic ceiling throws `OutOfMemoryError: Java heap
  > space` and exits `1` with the container still up, and `-Xmx512m` at the same limit is
  > `SIGKILL`, exit `137`, no stack trace and no log line. `ContainerHeapErgonomicsTest` holds
  > both, and `R23` §3.2 carries the numbers.

- **It named a tag, and the tag moved.** The block above was written on 2026-08-10 and led
  with `postgres:16-alpine`. **That tag stopped naming this image on 2026-08-13**, when it was
  repointed at a build of PostgreSQL 16.15 — and nothing in this tree changed, so nothing here
  could notice. `R27` found it by asking the registry.

  > **What made it a defect rather than an untidiness.** `build.yml` has no image cache, so
  > every CI runner since that date pulled 16.15 while this machine's Docker cache still held
  > the July image. **Local and CI were running different servers, and every block described
  > the local one.** The digest was recorded here from the first day and *nothing in the build
  > ever used it* — `TestcontainersConfiguration.kt` pinned the tag. That is what `8dec7e6`
  > fixed, and `OPEN-10` is the row.
  >
  > **The blocks were already carrying the answer.** Every environment block that names 16.14
  > has the digest on the next line, so none of them was ever wrong about what it ran on. What
  > they did was **lead with the moving name**, which is the thing a reader copies. The
  > identifier line now leads with the version and points at the digest, and the tag is
  > written down as history.
  >
  > **And the scale of the correction is smaller than `R27` §5 implies.** That section speaks
  > of *"twenty documents"*, which counts every mention of `16.14` — 44 files, once
  > `.study` and round two's own reports are included. **Environment blocks carrying the
  > identifier line: eight, of which two already said 16.15.** The rest of the mentions are
  > prose *about* this finding and are correct as written. Correcting them all would have been
  > a sweep; correcting the identifier was six lines.
  >
  > **A digest is two numbers on a multi-architecture image**, and this block never said which
  > it recorded. `postgres:16-alpine` names an OCI **index**, and the index lists one manifest
  > per platform — `cf78e766…` against `075f7ba6…` for `linux/amd64` today. The recorded July
  > figure is an index digest and the pin is one too, so the two are comparable. `.study`
  > 12장 §6.3 is what happens when that is not said: two sessions reported different levels
  > and the integrator read them as two images.

- **It said the JVM was pinned, and nothing pins it.** The line read *"Temurin 21.0.12+8
  (JDK 21 toolchain, pinned in gradle.properties)"* until 2026-08-22. **Withdrawn: no file in
  this build pins that JVM, or any part of it beyond the number 21.**

  > **What the build actually contains**, read out of it rather than described.
  > `gradle.properties` holds `javaToolchainVersion=21`; `api/build.gradle.kts` and
  > `seed/build.gradle.kts` each consume it as `jvmToolchain(21)`. That is a **Java language
  > version**. The strings `vendor` and `JvmVendorSpec` do not occur anywhere in the build,
  > and `settings.gradle.kts` has no `toolchainManagement` block. **Temurin is not requested,
  > and neither is `21.0.12+8`.**
  >
  > `./gradlew javaToolchains` on 2026-08-22 reports `Auto-detection: Enabled` and
  > `Auto-download: Enabled`, and lists the Temurin with `Detected by: Current JVM` — it is a
  > candidate **because it is the JVM Gradle was launched on**, not because anything selected
  > it. So the JVM is a property of whoever is sitting in front of the machine, which is the
  > exact opposite of what the withdrawn clause promised.
  >
  > **The build file was right and this document generalised it wrong.** `gradle.properties`
  > says, in its own comment, *"The exact build in use when this was pinned is recorded in the
  > measurement environment block, because `21` is not precise enough to reproduce a number
  > with."* It knew. This block turned *recorded* into *pinned*, which is the same move as
  > `R10` §3.2 — sample a little, generalise it into a stronger word.
  >
  > **CI is stricter than the repository.** `.github/workflows/build.yml` asks
  > `actions/setup-java` for `distribution: temurin`, so the vendor is guaranteed *there* by
  > the workflow and nowhere by the build. A local build and a CI build are held to different
  > standards, and only CI's is written down.
  >
  > **It was observed, not deduced.** On 2026-08-22 a second JDK 21 — an Ubuntu-packaged
  > 21.0.11 — existed on this machine for about an hour, and a Gradle run resolved to it
  > instead of the Temurin. **Nothing in the build, the tooling or CI reported the
  > substitution**; it is known only because the session wrote the resolved JVM path into its
  > report. That JDK has since been removed, so the observation cannot be re-taken and no
  > number from that run is cited anywhere. It is dated evidence that the gap is reachable,
  > not a measurement.
  >
  > **What this does and does not change.** Every environment block naming `21.0.12+8` is
  > still correct about what it ran on — five of them additionally claimed the pin, and are
  > corrected. Whether the build *should* pin a vendor is a trade, not an errand, and is
  > `OPEN-13` rather than a change made here.

The image digest is recorded alongside the tag because `16-alpine` is a moving tag. Two
people running `postgres:16-alpine` a month apart are not necessarily running the same
server, and the digest is what makes the row citable.

### When the run is inside a container, the block grows three lines

Added 2026-08-21 by `ADR-012`. Every number taken before that date is on **one** application
process with **no** memory or CPU limit, so the fields below would have read *unlimited, 1*
on all of them and the block stays as it is above. The moment a report varies any of them
they become part of the number:

```
  Container      : memory=512m memory-swap=512m cpus=<unset>  (per APPLICATION instance)
  Instances      : 2 application containers, one database container
  Heap           : ergonomic — no -Xmx. MaxHeapSize 134217728 (measured, not derived)
```

**`memory-swap` is on that line and not omitted**, because Docker's default when only
`--memory` is given is to allow swap up to twice the limit. A limit with swap behind it is
not a limit; it is a latency cost that shows up as page faults and never as a failure, and a
report measuring what happens *at* a limit under that setting is measuring nothing.

**`Heap` is measured rather than stated** for the reason the `-Xmx512m` bullet above is
about. A container-aware JVM derives its ceiling from the cgroup, so a report that writes
down the flag it passed has written down an input, not the heap.

**`Instances` is there because a pool setting is per-process and a database's connection
ceiling is not.** `R2` and `R18` both sized a pool on one instance; the arithmetic that
breaks is `pool × instances`, and a pool number without an instance count beside it cannot be
checked against anything. `R24` is what that costs.

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
10. **A number taken inside a container carries the container's limits and the instance
    count**, in the three lines above. Added 2026-08-21 by `ADR-012`. Rule 2 already required
    "the environment", and rule 2 was not enough: every field in the block above describes the
    *machine*, and a container is a machine the run invented. **The heap is measured rather
    than stated** — `R23` found a JVM taking its ceiling from the cgroup and not from anything
    a report would otherwise have written down.
