# ADR-012 — The health-check boundary is measured and not shipped

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Status**: Accepted

## Context

`R24` §3.2 measured what this application's three health URLs say while the database is
unreachable. Both halves of the trap the roadmap describes are live at once, on two different
URLs, in the shipped configuration:

| endpoint | database up | database gone |
| --- | --- | --- |
| `/actuator/health` | `200` in 0.015 s | `503` in **30.013 s** |
| `/actuator/health/liveness` | `200` in 0.003 s | `200` in 0.003 s |
| `/actuator/health/readiness` | `200` in 0.003 s | **`200` in 0.003 s** |
| `GET /api/v1/learners/{id}/recommendations` | `200` in 0.265 s | `500` in 30.021 s |

A load balancer reading `readiness` keeps routing to an instance where every request fails.
Anything reading `/actuator/health` drops the whole fleet on one blip — and takes thirty seconds
per probe to do it, each probe holding a Tomcat worker. At 210 concurrent probes, one more than
`server.tomcat.threads.max`, the instance answers nothing at all.

**The liveness/readiness split already exists.** `management.endpoint.health.probes.enabled`
defaults to `true` on Spring Boot 4.1.0 — read out of `spring-boot-health-4.1.0.jar`'s own
`spring-configuration-metadata.json` — and `AvailabilityProbesHealthEndpointGroups`' entire
constant pool is `liveness / livenessState / readiness / readinessState`. **`db` is on neither
side of a boundary that is already drawn.**

`R24` §3.2 also measured the remedy, in two halves:

```
db added to the readiness group          readiness 503, liveness 200 -- traffic drains,
                                         no restart storm. Probe now costs 30.006s
+ connection-timeout lowered to 2000ms   readiness 503 in 2.005s
                                         AND every real request gives up in 2.018s
```

## Decision

**Neither half ships. The current state — including the part of it that is a defect — is
asserted by `DeploymentBoundaryGateTest`, and the choice is recorded here for whoever operates
a fleet.**

## Why

**The right answer depends on a number this repository does not have: the probe interval.**

Adding `db` to readiness with the default `connection-timeout` converts a routing bug into a
thread-exhaustion bug the moment a load balancer polls faster than the answer arrives —
`R24` §3.2's 210-probe arm is that failure, on the endpoint the balancer reads rather than on a
side one. Lowering `connection-timeout` to 2 s fixes the arrival time and **makes every real
request give up in 2 s as well**, because `connection-timeout` applies to every caller of
`getConnection` and not only to the health indicator. A pool that is momentarily saturated under
legitimate load now fails requests it would have served.

The two halves have to be chosen **together**, against a probe interval and a tolerance for
failing a request that would have succeeded. This repository has no load balancer, no probe
path, and no polling interval.

**Shipping the half that fits in `application.yml` is `R2`'s failure mode.** That report's §9
records a requirement resting on a premise nobody checked; `application.yml` can hold
`management.endpoint.health.group.readiness.include` today and cannot hold the reason it is
correct, and a future reader would find a setting whose justification is a probe interval that
appears nowhere.

**And the gate asserts the defect deliberately.** `DeploymentBoundaryGateTest` asserts that `db`
is **not** a member of `readiness`, with a message saying that this expects the defect. A
framework default is what produces it; if the default moves, the gate goes red and `R24` §3.2's
boundary arm is describing a fix that shipped itself. That is the same move `ManagementSurfaceTest`
makes on `heapdump`'s 404.

## The alternative that was not taken

**Ship both halves with the values `R24` measured.** `readiness.include=readinessState,db` and
`connection-timeout=2000` is a coherent pair and would make this application behave correctly
behind a typical load balancer.

It loses on the same ground `ADR-005` rejected a cache: the setting would be **untestable here**.
Nothing in this repository could go red if `connection-timeout=2000` were wrong for the eventual
deployment, and `R16`'s `rate>=0.0` threshold is what a value nobody can falsify becomes. Worse,
2000 ms is a number derived from one probe against a one-row fixture on a laptop, and
`measurement-discipline.md` rule 3 forbids carrying it to a machine that has not been
re-baselined.

## Consequences

**What this buys.** The measurement is available and the configuration is honest: the
application's shipped state is documented, gated, and known to contain a routing defect under a
load balancer that does not exist yet. Whoever adds one has both arms already measured.

**What this costs.** If this application were deployed behind a load balancer tomorrow with a
readiness probe, it would keep receiving traffic during a database outage. That is a real
defect and this decision leaves it in place. It is left in place *with a number beside it*
rather than fixed by a setting whose correctness nobody could check.

**What this rules out.** Adding `db` to the readiness group without also choosing a
`connection-timeout`, and choosing a `connection-timeout` without stating the probe interval it
was chosen against. Either alone is measured to make something worse.

## What would flip this

A deployment target — anything with a probe path and an interval. At that point both halves are
chosen together against that interval, `R24` §3.2's two tables are the inputs, and this decision
is replaced rather than amended.

## What was not measured

- **The probe interval at which the readiness storm begins.** `R24` §3.2 fired 210 probes at
  once because that is one more than `server.tomcat.threads.max`. The realistic shape is a
  steady poll, and **미측정**.
- **What `connection-timeout=2000` costs a real request under load.** It was measured against a
  database that was *gone*, where 2 s is a straight improvement. Against a saturated pool it is
  a request that fails instead of waiting, and that arm was not run.
- **Any value between 2000 and 30000.** Two points, both chosen for legibility.
