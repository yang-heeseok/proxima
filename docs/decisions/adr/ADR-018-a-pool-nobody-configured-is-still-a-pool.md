# ADR-018 — A pool nobody configured is still a pool this repository operates, so the defaults become assertions rather than settings

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: Accepted

## Context

This repository has measured a connection pool twice — `R2` and `R18` — and both times from the
database side. Neither could have said how many web server worker threads existed while it did.

There are five pools in this process. Read from a running JVM on 2026-08-22 rather than from
`application.yml`, which states exactly one of them:

| | pool | size | where the number comes from |
| --- | --- | --- | --- |
| 1 | web server workers | `max=200 minSpare=10` | Tomcat's defaults. **In no file here** |
| 2 | connection pool | `max=10 minIdle=10` | HikariCP's default. `measurement-discipline.md` names it |
| 3 | `applicationTaskExecutor` | `core=8 max=2147483647 queueCapacity=2147483647` | Spring Boot's defaults. **In no file here** |
| 4 | `ForkJoinPool.commonPool` | `parallelism=7` | `availableProcessors − 1`. **In no file here, and in no file anywhere** |
| 5 | virtual thread carriers | unset | not in use |

`R29` measured what pools 1 and 2 do to each other under load, `R30` what pool 3's numbers mean,
`R32` what pool 4 does to callers who have never heard of it, and `R33` what happens to all four
when pool 5 is switched on.

**Three of the five appear nowhere in this repository, and one of them cannot be configured by
this repository at all** — the common pool's size is a property of the box the JVM starts on, and
`R23` already found this JVM taking its heap ceiling from a cgroup rather than from anything a
document here had written down.

So the decision is not *what should these be set to*. It is **what does a repository owe a pool it
never chose.**

## Decision

**Enumerate all five. Assert their sizes. Change none of the sizes. Ship only the two changes that
have no trade-off to get wrong — the instrument whose absence made the measurement impossible, and
the one remedy that costs nothing to be right about.**

1. `PoolCensus` reads all five from the running JVM and produces the block every number in `R29`
   and `R33` carries. It is a measurement fixture and is not wired into any request path;
   `ADR-009` is why it is not an endpoint.
2. `PoolCensusGateTest` asserts the sizes, in `DeploymentBoundaryGateTest`'s idiom — *this
   assertion expects the defect*. The load-bearing one is the **inequality**,
   `webServerMaxThreads > connectionPoolMax`, presently 200 against 10.
3. **`server.tomcat.mbeanregistry.enabled: true` ships.** It is an instrument rather than a
   setting — see below — and it is the only change here that any measurement depended on.
4. **`proxima.ops.async-context: copy-mdc` ships, and it is inert today.** It makes the executor's
   `TaskDecorator` carry the MDC across an `@Async` hop (`R31` §5, option B). Nothing on a request
   path crosses that boundary, so it changes no behaviour anybody can observe; it ships because it
   is correct and free, and because shipping it puts the *wiring* — whether Boot actually applies a
   `TaskDecorator` bean to the executor it builds — under the default configuration rather than
   only under a test property.
   ⚠️ **This is not the same judgement as leaving pool 3's queue unbounded, and the difference is
   the point.** Bounding a queue means **choosing a rejection policy**, whose right answer depends
   on a load that does not exist here. Copying an MDC entry has no such trade: there is nothing to
   get wrong and nothing to tune. A remedy with no trade-off can ship into an unused component; one
   with a trade-off cannot.
5. **`spring.task.execution.*` stays unset**, and pool 3 keeps its unreachable maximum.
6. **`spring.threads.virtual.enabled` stays `false`, and the reason is not speed.** `R33` §3.2
   measured the latency difference at **1.08×** against a drift control of **1.09×** and refused to
   claim it in either direction. What decides this row is `R33` §1.1: turning it on takes
   `executor.pool.core` and `executor.pool.max` away entirely (`404`) and leaves
   `tomcat.threads.busy`, `.current` and `.config.max` **answering `HTTP 200` with the value `-1`**.
   ⭐ **The second half is the worse one.** A `404` is a gap somebody eventually notices; `-1` is a
   number that flows into a dashboard, a threshold and a capacity review without stopping any of
   them. **An absent metric is at least honest about being absent.**
7. `parallelStream()` stays out of every request path. The one in `SharedPoolWork` is a fixture.

## Why the values are not set

`application.yml`'s own note about `open-in-view` already settled this, one defect earlier:

> *Setting it explicitly also silences Spring's startup warning. That warning is about not having
> decided, not about the value: pinning it to `true` to quieten the log keeps the behaviour and
> loses the notice.*

Writing `server.tomcat.threads.max: 200` into `application.yml` would change nothing, look like a
decision, and remove the only signal that nobody has made one. **A gate is the opposite trade:**
it records the number, checks it on every build, and goes red if the framework moves it — which
is what `R5`, `R9` and `R10` are all about, and what `DeploymentBoundaryGateTest` was built for.

**And `R29` measured that the ratio is not a defect to fix here.** More workers than connections
is not waste in this application: a request spends roughly ten times longer waiting for a
connection than holding one, so the workers beyond the tenth are what keep throughput up rather
than what wastes it. A change made on the principle *"size the thread pool to the connection
pool"* would have made this system worse, and the arms in `R29` §3 are the evidence.

## Why `mbeanregistry` is the exception

With `server.tomcat.mbeanregistry.enabled` at its default of `false`, **`tomcat.threads.busy`,
`tomcat.threads.current` and `tomcat.threads.config.max` do not exist** — the actuator endpoint
answers `404` for all three, and **none of the three is even listed** in `/actuator/metrics`: zero
names match `tomcat.threads`. With the flag `true`: `200`, values `1.0`, `10.0`, `200.0`, and three
names listed.

⚠️ **This claim was re-taken deliberately.** Its first reading came from the same parser that
`R33` §3.1.1 caught turning a measured `-1` into `미측정`, so it was measured again with the **HTTP
status code and the metric listing** rather than with a regex over the value. It survives, with
better evidence than it had: a `404` and a value a parser cannot read look identical to that
parser, and they are opposite findings.

That is not a missing convenience. It means **the pool holding two hundred threads has no gauge at
all**, while the pool holding ten has six. Half of `R29` §3 could not have been taken without the
flag, and the incident `R29` describes — every worker alive, most of them blocked, nothing logged,
error rate `0.00 %` — is invisible to an operator by construction until it is on.

The cost is MBean registration on the connector. **Its latency cost is 미측정** and goes to
`ADR-014`'s ledger: every arm in `R29` ran with it on, so the arms are comparable with each other,
and none of them establishes what the flag itself costs against `false`.

## Why pool 3 keeps its unreachable maximum

`R30` measures the mechanism: `ThreadPoolExecutor` grows past core **only when the queue refuses a
task**, so an unbounded queue makes the maximum unreachable by construction. Bounding the queue
makes `max` reachable and starts producing `TaskRejectedException` — the same overload, delivered
as a refusal instead of as unbounded latency and heap.

**It is not bounded here, because nothing uses it.** No `@Async` call exists on any request path;
`AsyncBoundary` is a fixture. Configuring an executor that nothing exercises would be choosing a
rejection policy for a load that does not exist — and this repository has already paid for
building a guard before there was anything to guard. `.githooks/pre-commit` records the rule in
its own header: *"a guard that protects nothing is not free, it is unbanked."*

**What is recorded instead is the condition.** The first `@Async` call to enter a request path
must bound this queue in the same commit, and must say what it wants to happen to the task that
does not fit. `R30` §5 has the arms priced. `PoolCensusGateTest` asserts the present shape, so
that commit cannot land without turning the gate red and forcing the question.

## Is the unbounded queue a defect or a specification?

**A specification, in this tree, today — and a defect the moment anything uses it.** The decision
has to be recorded that way round rather than as a general verdict, because the same numbers mean
opposite things depending on whether the executor is on a request path:

| | unbounded queue |
| --- | --- |
| for an executor nothing calls | harmless; `max` is decoration and decoration costs nothing |
| for an executor a request path calls | **a defect**: overload becomes latency and heap with no refusal, and `max` is a number that will never be reached however alarming it looks |

`R30` §4 is the mechanism and it does not change between the two rows. What changes is whether
there is a caller to be hurt by it.

## Consequences

- Every number in `R29` and `R33` carries a five-line pool census, and a number missing one is
  refused by review rather than by a machine.
- `PoolCensusGateTest` goes red on a Boot or Tomcat upgrade that moves any of these defaults. That
  is the intended behaviour and the message on each assertion says which report to reread.
- The application gains three Tomcat gauges and an unmeasured amount of MBean overhead.
- **A `TaskDecorator` bean now exists and decorates every task the executor runs.** Today that is
  no tasks. The cost of the decoration itself is one map copy per task and is **미측정**, which is
  acceptable only while the count is zero — the first `@Async` caller inherits an unpriced wrapper
  along with an unbounded queue, and both belong in the same conversation.
- **Anybody adding `@Async` to a request path trips a gate and has to read `R30` and `R31`.**
- `parallelStream()` remains unused in production code, and `R32` is why rather than taste.

## What would flip this

- **Anything reaching a request path through `applicationTaskExecutor`.** Then pool 3 must be
  bounded, and the ledger entry becomes work rather than a note.
- **A measured cost for `mbeanregistry`.** If it is material at 200 VU the flag becomes a
  deployment-time property rather than a shipped default, the way `R24` §5 left the readiness
  group to whoever operates the fleet.
- **A second application instance changing what the ratio means.** `R24` established that
  `pool × instances` is the arithmetic that breaks; `workers × instances` has no ceiling to break
  against and therefore no gate. 미측정.
- **A managed database.** `R29` §5.4: `max_connections` stops being a constant and becomes
  `LEAST({DBInstanceClassMemory/9531392}, 5000)`, so the ceiling moves when somebody resizes the
  instance — and the recommended remedy, RDS Proxy, is a **sixth** pool that this process cannot
  see at all.

## What was not measured

- What `server.tomcat.mbeanregistry.enabled=true` costs. **미측정.**
- Whether pool 4's parallelism follows a cgroup CPU limit the way `R23` found the heap following a
  memory limit. It should, and nothing here checked. **미측정.**
- Pool 5's carrier count. `jdk.virtualThreadScheduler.parallelism` is unset and `R33` did not read
  the scheduler's actual size, only that the carriers are `ForkJoinPool-1-worker` threads.
- ⭐ **Whether any other number in this repository was taken through a parser that could not read
  it.** `R33` §3.1.1 found this slice's own harness turning a measured `-1` into `미측정` because
  its regex could not match a minus sign — **and failing in the direction that confirmed the
  hypothesis being carried.** No instrument here distinguishes *"the metric is missing"* from
  *"the metric is there and I could not read it"*, and both were treated as absence. **미측정**,
  and `ADR-014` entry 33.7 is the row.
