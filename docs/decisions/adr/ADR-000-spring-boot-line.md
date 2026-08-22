# ADR-000 — Which Spring Boot line

> **Created**: 2026-08-10
> **Updated**: 2026-08-22
> **Status**: **Accepted** — 2026-08-10. Closes `OPEN-1`.

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

## What the options actually were, on the day

Queried 2026-08-10, from `api.spring.io/projects/spring-boot/releases` and
`/generations`, and from the `start.spring.io` metadata endpoint. Not from memory, and not
from a tutorial.

| Line | Latest GA | OSS support ends | Offered by start.spring.io |
| --- | --- | --- | --- |
| 3.5.x | 3.5.16 | **2026-06-30 — already past** | **no** |
| 4.0.x | 4.0.7 | 2026-12-31 — 143 days away | yes |
| 4.1.x | **4.1.0** — marked `current` | **2027-07-31** | yes, and it is the default |

Two facts from that table did more work than any argument:

1. **3.5.x left OSS support 41 days ago.** Its commercial support runs to 2032-06-30, which
   is why so many production systems will sit on it for years — but commercial support is a
   purchase, not a property of the artefact. A new repository baselined there gets no
   further free patches, on day one.
2. **The initializr no longer offers 3.5.x at all.** The "familiar to readers" argument
   was, in practice, an argument for a line the project's own tooling has stopped emitting.

## Decision

**Spring Boot 4.1.0, on JDK 21.**

### Why not 3.5.x, the line more readers are running

This was the genuinely close call, and the context above was written expecting to land on
it. It lost on the support date. Choosing a baseline whose free security patches stopped
six weeks earlier would mean either paying for support on a repository with no users, or
publishing measurements taken on an unpatched stack — and the second is the kind of quiet
compromise `PUB-4` exists to prevent.

The familiarity cost is real and is not being waved away. A reader on 3.5.x pays the
Boot 3 → 4 translation. For the surface this repository actually touches — JPA, HikariCP,
Flyway, Actuator, Micrometer — that translation is small, and the mechanisms behind every
trap in `docs/roadmap.md` predate both lines. Where a report's mechanism turns out to be
version-sensitive, the report says so at the point of the number rather than here.

### Why not 4.0.x

It is the worst of both: 143 days of OSS support left, and no familiarity advantage over
4.1 that 3.5 would not have had more of. Nothing selects it.

### Why JDK 21 and not 17, 25, or 26

The initializr offers 17, 21, 25, 26 and defaults to 17. 21 is chosen because it is an LTS
with a wide production installed base, and because the toolchain is pinned identically in
`gradle.properties` and in CI — a measurement taken on a different JVM than the one CI runs
is not comparable to it, and this repository publishes numbers
(`docs/explanation/measurement-discipline.md`, rule 3).

Recorded exactly, because the environment block demands it: **Temurin 21.0.12+8**.

## Consequences

**The trap that this decision could have silently killed, and did not.** `T1` depends on
`spring.jpa.open-in-view` defaulting to `true` — that default is the whole mechanism by
which a request holds a database connection while doing something slow. A version bump that
flipped it would not break the build; it would make `T1` fail to reproduce, and the failure
would look like the author being wrong rather than the default having moved.

Checked against the 4.1 property appendix before accepting this ADR:

```
spring.jpa.open-in-view   Register OpenEntityManagerInViewInterceptor. Binds a JPA
                          EntityManager to the thread for the entire processing of
                          the request.                                          true
```

Still `true`. `T1` stands as written.

**What this costs.** Boot 4 is recent enough that some of the ecosystem around it is not
finished moving — which is precisely the subject of `ADR-001`, decided immediately after
this one and under a timebox for that reason.

**What is now pinned.** Boot 4.1.0 and JDK 21 go into `gradle.properties` as a toolchain,
not into a developer's `JAVA_HOME`. CI uses the same two numbers.

> **Annotated 2026-08-22 rather than rewritten. The decision stands; the second clause does
> not.** *"not into a developer's `JAVA_HOME`"* is true about **where the number is written**
> and false about **what decides the JVM**. `jvmToolchain(21)` is a language version; no
> `vendor` or `JvmVendorSpec` appears anywhere in this build, so **Temurin 21.0.12+8 is
> recorded, never requested**. `./gradlew javaToolchains` lists it as `Detected by: Current
> JVM` — that is, because of `JAVA_HOME`. The reproduction commands in `R23`–`R27` all export
> `JAVA_HOME=~/.jdks/jdk-21.0.12+8` before running Gradle, which is the practice already
> disagreeing with this sentence.
>
> **And §*Why JDK 21* above says the toolchain is *"pinned identically in `gradle.properties`
> and in CI"*, which understates CI.** `build.yml` asks `actions/setup-java` for
> `distribution: temurin`; the build asks for nothing. The vendor is guaranteed on CI by the
> workflow and nowhere by the repository, so the two are not identical — CI is stricter, and
> a local build is the loose end.
>
> Both clauses are left standing because how they went wrong is worth more than a tidy file.
> `docs/explanation/measurement-discipline.md` owns the retraction and the evidence; whether
> the build should pin a vendor is `OPEN-13`, not something this annotation decides.

## What would cause this to be revisited

- **A trap stops reproducing on 4.1 because a default moved.** `open-in-view` is the one
  checked above; the others (`spring.jpa.properties.hibernate.jdbc.batch_size`, Hikari's
  `maximum-pool-size=10`) are re-checked at the report that depends on them, not assumed
  from here.
- **4.1.x approaches 2027-07-31** and this repository is still being measured against.
  Moving lines mid-repository invalidates cross-report comparison unless everything is
  re-baselined — see measurement rule 3. That is a re-baselining project, not an upgrade.
- **A reader reports that the Boot 3 → 4 translation was the thing that stopped them
  reproducing a number.** That would falsify the central claim of this ADR, which is that
  the translation is cheap for this surface.
