# R24. Three instances, and the database is the only thing that refuses

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit / Green commit**: **neither, for §3.1 and §3.2** — nothing in the application
> changed and every arm is the same jar in one session, `R18`'s shape. **§3.3 has both**: red
> `cd3f34f`, green `20236d5`. **The instrument has three of its own**: `ae5401b`/`2be3443`,
> `f120a13`/`d49653f`, `e18f82a`/`53b3c54`.
> **Answers**: `ADR-004`'s *"the obvious next hole"*, one level up — `R2` and `R18` both sized a
> pool on **one** instance, and `R18` §8 recorded how close the ceiling came as 미측정 because
> measuring it meant querying inside a measurement window.
> **Supersedes**: `ADR-009`, on the condition `ADR-009` named itself. `ADR-013`.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 — bind-mounted into each container from the toolchain.
                   R23 §2 says why it is not pulled
  Base image     : ubuntu:24.04
                   sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517
  PostgreSQL     : postgres:16-alpine — server 16.15
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
                   max_connections=100, superuser_reserved_connections=3,
                   reserved_connections=0, all defaults, UNCHANGED
  Container      : memory=512m memory-swap=512m cpus=<unset>  PER APPLICATION INSTANCE
  Instances      : 1, 2 or 3 application containers — the variable. One database container,
                   unlimited
  Heap           : ergonomic, no -Xmx. 134217728 at this limit, measured in R23 §3.1
  Connection pool: HikariCP 7.0.2 — maximum-pool-size 10, 25 or 60, the other variable.
                   connection-timeout 30000 (default) except where §3.2 varies it
  App            : ONE jar, sha256 b8a1aac402b647a94f08b848b42674cf2a2688b596bf1e00bd670fa82a11ec81
  Dataset        : NOT the seeded 3,963,719 rows. ONE learner, concept, item and mastery row —
                   `load/ops/harness.sh fixture`. §2 says what that costs
  Load           : curl bursts and single large batches. NOT k6, no warm-up window, no
                   percentile claimed as a headline. §3.1's durations are observations
  Repetitions    : 1 per arm, with a drift control re-running arm A last. §3.4
  Session        : 2026-08-21, one WSL invocation per trap script, uninterrupted
```

> **The database image moved under this repository while this report was being written.**
> `measurement-discipline.md` records `postgres:16-alpine` at
> `sha256:57c72fd2…`, server **16.14**. Pulled 2026-08-21 the same tag is `sha256:cf78e766…`,
> server **16.15**. The document predicted this in the sentence beside the digest — *"`16-alpine`
> is a moving tag… two people running it a month apart are not necessarily running the same
> server"* — and the digest above is the one these numbers were taken against. **No number here
> is compared with one from a report taken on 16.14.**

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Three things this repository has said about itself, none of which is wrong, all of which
describe one process:

- **`R2` and `R18` sized a connection pool.** Both measured one application instance. `R18` §8:
  *"`max_connections=100` was never reached but was never far… how close it came is 미측정."*
- **`/actuator/health` is the only management endpoint this application exposes on purpose**,
  and `R10` measured what the surface contains. Nothing measured what it *says* when the
  database is unreachable.
- **`ADR-009` refused a recording endpoint** and recorded the cost: *"every load number here is
  on the read path, the write path's concurrency was measured with JVM threads, and the write
  path under HTTP load is 미측정."*

Put a second instance beside the first and all three become different questions. A pool size is
per process; a database's connection ceiling is not. A health check that is right for one
instance is a fleet-wide switch when there are three. And a deployment is the act of removing an
instance while it is working.

## 2. 재현 / Reproduction

`load/ops/`. Three scripts over one harness, each printing its own environment block first:

```
export JAVA_HOME=~/.jdks/jdk-21.0.12+8
./load/ops/harness.sh build
./load/ops/trap-pool.sh        # §3.1
./load/ops/trap-health.sh      # §3.2
./load/ops/trap-shutdown.sh    # §3.3
```

Each application instance is `ubuntu:24.04` with the toolchain JDK and the boot jar bind-mounted
in, `--memory=512m --memory-swap=512m`, published on its own host port. One jar for every arm,
`R4` §2's argument: two builds would mean two JIT histories and two configurations sitting
beside the difference being measured.

**The fixture is one learner, not the seeded dataset, and that is a limit on everything below.**
`seed/` produces 3,963,719 rows from seed value 20260810 and every latency figure in this
repository is against those. Nothing here measures latency as a headline, and the questions —
connection counts, HTTP statuses, exit codes, row totals — do not move with the row count. What
it costs is real and is stated rather than hidden: **a read against this fixture returns in
single-digit milliseconds and therefore needs almost no connections**, which is why §3.1's
decisive arm drives writes at one contended row instead, and why **no figure in this report may
be compared with one from `load/recommendations.js`.**

`load/ops/README.md` carries the requirements. `run.sh` is deliberately not involved: it exists
to refuse a run whose steady-state verdict says `DO NOT PUBLISH`, and a run whose result is an
exit code has no measurement window to be steady over.

## 3. 계측 / Measurement

### 3.1 Pool size × instance count, against `max_connections`

Five arms plus a drift control, one jar, one session. The database is `max_connections=100`
with `superuser_reserved_connections=3` — **both read from the server, both left alone.** `R18`
declined to raise `max_connections` because it would have made its arms incomparable, and the
same holds here for a different reason: **the arithmetic is the subject.**

| Arm | instances × pool | requested | connections each instance filled | total | database `FATAL: sorry, too many clients already` | HTTP |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| **A** | 1 × 60 | 60 | 60 | 60 | **0** | 40/40 `200` |
| **B** | 2 × 60 | 120 | 60 + 40 | **100** | **15** | 80/80 `200` |
| **C** | 3 × 60 | 180 | 60 + 19 + 21 | **100** | **28** | 120/120 `200` |
| **D** | 3 × 25 | 75 | 25 + 25 + 25 | 75 | **0** | 120/120 `200` |
| **A′** | 1 × 60 | 60 | 60 | 60 | **0** | 40/40 `200` |

**What gets refused first is the database, and the application never hears about it.** Zero
`Connection is not available` across every instance in every arm. Every HTTP request answered
`200`. The entire evidence that a fleet is eighty connections over its ceiling is 28 lines in
the database's log.

Three things fell out of the arms that the design did not predict.

**The instance that starts last is the one that starves.** 60 / 19 / 21, not 60 / 60 / 60 and
not 33 / 33 / 33. `minimum-idle` defaults to `maximum-pool-size`, so each pool fills as fast as
it can from startup and whoever is first takes what it asked for. **The split is not stable
either** — the same arm across four runs of this script gave 53/32/15, 48/32/20, 59/25/16 and
60/19/21. The inequality reproduces; the shares are a race.

**The monitoring connection is refused before any user request is.** In arms B, C and E the
`psql` this script uses to read `pg_stat_activity` could not open a slot:

```
REFUSED: the monitoring connection could not be opened -- FATAL: sorry, too many
REFUSED: clients already. Per-instance counts below come from each instance's own
REFUSED: hikaricp.connections gauge, which is in-process and needs no slot.
```

So the per-instance figures in arms B and C come from each instance's own
`hikaricp.connections` gauge rather than from the server. **The two sources agree exactly where
both are available** — 60 in arm A, 25/25/25 in arm D — which is what licenses the substitution.
**The instrument was refused by the thing it was measuring**, and the first person to feel this
misconfiguration is whoever arrives to diagnose it.

**`superuser_reserved_connections=3` bought nothing, and the reason is the container image.**
`postgres:16-alpine` makes `POSTGRES_USER` a superuser — measured, `select usesuper from pg_user
where usename = current_user` returns `t`. The reserve exists so an administrator can still get
in when an application has taken everything, and here the application *is* the administrator.
Arm B's 60 + 40 = 100 is `max_connections` exactly, not the 97 the reserve was supposed to
protect.

**Arm E — does the starved instance serve worse?** Same fleet as C, pools 60 / 23 / 17, 80
concurrent single-recording writes at the winner and at the loser. Writes and not reads, because
the read path against this fixture is too cheap to need connections; every recording moves the
**same** `(learner, concept)` mastery row, so each request holds its connection while waiting for
that row's lock. This is the workload `ADR-009` named.

```
80 concurrent writes at instance 1 (60 connections)   recorded x80   p50 0.717s  p95 0.860s  max 0.925s
80 concurrent writes at instance 3 (17 connections)   recorded x80   p50 0.579s  p95 0.820s  max 0.868s
Connection is not available: 0 on both
attempt rows 160   mastery.attempts_count 160
```

**No.** Seventeen connections serve this workload as well as sixty, because the binding
constraint is one row's lock and not the pool. **The 1.24× between the two p50s is inside the
1.27× drift `R18` §3.3 measured between identical configurations, and is not claimed in either
direction.**

### 3.2 The health check and the database

Two instances, pool 10, database taken away two different ways. Every figure is one probe;
`SETTLE=5` seconds pass between stopping the database and probing, for the reason in §3.4.

| endpoint | database up | name gone (`docker stop`) | port closed (`pg_ctl stop -m immediate`) |
| --- | --- | --- | --- |
| `/actuator/health` | `200` in 0.015 s | **`503` in 30.013 s** | `503` in 30.010 s |
| `/actuator/health/liveness` | `200` in 0.003 s | **`200` in 0.003 s** | `200` in 0.004 s |
| `/actuator/health/readiness` | `200` in 0.003 s | **`200` in 0.003 s** | `200` in 0.004 s |
| `GET …/recommendations` | `200` in 0.265 s | **`500` in 30.021 s** | `500` in 30.015 s |

Both instances, identically, at the same instant.

**Both halves of the trap are live at once, on two different URLs.** A load balancer reading
`/actuator/health/readiness` keeps routing to an instance where every request `500`s. Anything
reading `/actuator/health` drops the entire fleet on one blip. **The split already exists** —
`management.endpoint.health.probes.enabled` defaults to `true` on Boot 4.1.0, read out of
`spring-boot-health-4.1.0.jar`'s own `spring-configuration-metadata.json` — and
`AvailabilityProbesHealthEndpointGroups`' entire constant pool is `liveness / livenessState /
readiness / readinessState`. **`db` is on neither side of a boundary that is already drawn.**

**The two ways of killing the database were expected to differ and did not.** An
`UnknownHostException` and a closed port both take 30.01 s, because HikariCP's
`connection-timeout` of 30000 ms dominates both. Spring says so about itself:

```
2026-08-21T07:58:32.767Z  WARN 1 --- [proxima] [io-8080-exec-10] o.s.b.h.a.e.HealthEndpointSupport
  : Health contributor org.springframework.boot.jdbc.health.DataSourceHealthIndicator (db)
    took 30007ms to respond
```

```
Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
  request timed out after 30001ms (total=0, active=0, idle=0, waiting=0)
Caused by: org.postgresql.util.PSQLException: The connection attempt failed.
Caused by: java.net.UnknownHostException: proxima-db
```

**Recovery is immediate and asymmetric with detection.** One second after the database is ready,
`/actuator/health` is `200`. Thirty seconds to notice; one to forgive.

**Arm 3 — the health check starves the application it is checking.** 210 concurrent
`/actuator/health` while the database is gone, one more than `server.tomcat.threads.max` (default
200, from the framework's configuration metadata):

```
liveness, during the storm            no answer within 20s
readiness, during the storm           200 in 6.685s
GET .../recommendations, during it    no answer within 20s
```

Each probe holds a Tomcat worker for thirty seconds. A load balancer polling faster than the
answer arrives stacks probes faster than they drain, and **the mechanism that exists to detect
the outage produces a second, larger one.**

**The boundary, measured rather than argued.** `management.endpoint.health.group.readiness.
include=readinessState,db`, one instance:

| endpoint | database up | database gone |
| --- | --- | --- |
| `/actuator/health/liveness` | `200` in 0.003 s | **`200` in 0.003 s** — no restart storm |
| `/actuator/health/readiness` | `200` in 0.004 s | **`503` in 30.005 s** — traffic drains |
| `/actuator/health` | `200` in 0.015 s | `503` in 30.021 s |

**And the second half, because the first is not enough.** Moving `db` into the readiness group
fixes *what* the probe says and not *how long it takes to say it* — so a readiness probe polled
more often than 30 s reproduces arm 3's storm on the endpoint the load balancer reads. Adding
`--spring.datasource.hikari.connection-timeout=2000`:

| endpoint | `connection-timeout` 30000 | `connection-timeout` 2000 |
| --- | --- | --- |
| `/actuator/health` | `503` in 30.021 s | **`503` in 2.011 s** |
| `/actuator/health/readiness` | `503` in 30.005 s | **`503` in 2.005 s** |
| `/actuator/health/liveness` | `200` in 0.003 s | `200` in 0.003 s |
| `GET …/recommendations` | `500` in 30.042 s | **`500` in 2.018 s** |

**The last row is the cost and not a bonus.** `connection-timeout` applies to every caller of
`getConnection`, so the change that makes the probe fail in two seconds makes a real request
under a momentarily-saturated pool give up in two seconds as well. §5.

**And one property in that arm could not be set the way a container platform sets properties.**
`SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` — the environment-variable spelling — is ambiguous
under relaxed binding: it can mean `connection-timeout`, or it can mean the `timeout` property of
`connection`, and `HikariDataSource` **has** a `connection` property because `getConnection()` is
one. The binder called it:

```
Failed to bind properties under 'spring.datasource.hikari.connection' to java.sql.Connection:
    Reason: java.net.UnknownHostException: proxima-db
...
Failed to bind properties under 'spring.datasource.hikari' to com.zaxxer.hikari.HikariDataSource:
    Property: spring.datasource.hikari.pool-name
    Value: "pool-1"
    Reason: java.lang.IllegalStateException: The configuration of the pool is sealed once
            started. Use HikariConfigMXBean for runtime changes.
```

Opening a connection starts the pool; starting the pool seals the configuration; the next
`spring.datasource.hikari.*` property to bind throws and the application does not start.
**Isolated across four runs: either variable alone starts fine, and the two together fail in
either order.** The fix is to pass the property as a program argument —
`--spring.datasource.hikari.connection-timeout=2000` — which spells the hyphen out and cannot be
read as navigation. `e18f82a` is the state in which it failed and `53b3c54` the state in which it
did not.

### 3.3 A deployment against a request in flight

One instance. A batch of 4000 recordings — 416001 bytes, 12101 ms uninterrupted — with the
signal sent at 3000 ms, so there is always something in flight.

| Arm | signal / configuration | what the client got | recordings landed | `attempt` vs `mastery.attempts_count` | exit |
| --- | --- | --- | ---: | --- | ---: |
| **A** | `SIGTERM`, `server.shutdown=graceful` (**the 4.1.0 default**) | `HTTP 200` in 13.297 s, full outcome list | **4000 / 4000** | 4000 = 4000 | 143 |
| **B** | `SIGTERM`, `server.shutdown=immediate` | **curl exit 52, empty reply** | **2163 / 4000** | 2163 = 2163 | 143 |
| **C** | `SIGKILL` (`docker kill`) | **curl exit 52, empty reply** | **657 / 4000** | 657 = 657 | 137 |
| **D** | `SIGTERM`, docker's default 10 s grace | `HTTP 200` in 8.504 s | 4000 / 4000 | 4000 = 4000 | 143 |
| **E** | as D, **8000 recordings** | **curl exit 52, empty reply** | **6942 / 8000** | 6942 = 6942 | **137** |

**The trap does not reproduce on the default.** `server.shutdown` is `graceful` on Boot 4.1.0 —
read out of `spring-boot-web-server-4.1.0.jar`'s own configuration metadata, and confirmed at
runtime by a log line arm A produces and arm B does not:

```
o.s.boot.tomcat.GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
o.s.boot.tomcat.GracefulShutdown : Graceful shutdown complete
```

Arm B is what the default *was* before Boot 3 and what most deployment documentation still
assumes.

**Nothing was ever torn.** `attempt` and `mastery.attempts_count` agree in all five arms,
including under `SIGKILL` with 657 of 4000 committed. `AttemptRecorder.record` is one
transaction per recording, and PostgreSQL rolls back the one that was open when the socket
died. **Two independent records of the same set of commits, and they never disagree** — that
comparison is the whole reason this is measurable, and a client whose connection was reset could
not have told us any of it.

**What IS half-applied is the batch, and the caller cannot tell which half.** 2163 of 4000, 657
of 4000, 6942 of 8000 — and in each case an empty reply carrying no outcome list. `R14` gave
every recording an outcome and **the transport can still take all of them away at once.**

**Arm E is the one that matters, and arm D had to nearly fail first.**
`spring.lifecycle.timeout-per-shutdown-phase` defaults to **30 s**; `docker stop` sends `SIGKILL`
after **10**. Both are defaults, neither knows the other exists. Arm D fits inside ten seconds —
by 456 ms in the first run of the script and by more in the second, which is a batch size and not
a design. Arm E doubles the batch:

```
signal          SIGTERM at +3004ms; container gone at +13296ms (docker waited 10292ms)
client saw      TRANSPORT FAILURE, curl exit 52
after           attempt=6942  mastery.attempts_count=6942  of 8000 sent
exit code       137
shutdown log    Commencing graceful shutdown. Waiting for active requests to complete
```

**Exit 137 from a `docker stop`, and a `Commencing graceful shutdown` line with no completion
line after it.** The framework was still politely waiting when the platform killed it. **The
application's graceful-shutdown guarantee is unreachable whenever a request needs longer than
the platform's patience**, and neither number is written down anywhere the other can see.

### 3.4 The drift control, and the three defects it and its neighbours found

`R18` §3.3 measured **1.27×** between identical configurations seventy minutes apart and used it
to kill one of its own four conclusions. Arm A′ is that control here: the same single instance
at pool 60, run last.

```
A   (first arm)   60 connections, 0 FATALs
A′  (last arm)    60 connections, 0 FATALs
```

**Identical.** Connection counts are a property of the configuration and not of when they were
taken, so §3.1's table is not drifting. The one figure in this report that *is* a duration
comparison — arm E's two p50s — is inside `R18`'s band and is not claimed.

**The control's first run did not find drift. It found the instrument leaking.** A′ came back
with **25** connections and 15 `too many clients already` against arm A's 60 and none, because
`harness_up` removed the instances it was about to start and not the ones already running, so
arm E's instances 2 and 3 survived into it and ate the slots. `ae5401b` → `2be3443`.

Two more of the same kind, because this report's instrument was written for it:

- **Eighty `200`s carried zero rows.** With three instances at pool 60 there is no slot left for
  `psql`, so a fixture inserted after the fleet came up inserted nothing — silently, because
  `on conflict do nothing` and a refused connection look identical to a caller that discarded
  stderr. Every POST then referenced a learner that does not exist and was answered `200` with a
  body full of `rejected`, because **a per-item outcome list is a `200` whatever it contains.**
  That is `R14`'s contract and it is correct; what was wrong is a probe asserting on the
  envelope after passing `-o /dev/null`. `f120a13` → `d49653f`. It is the same defect as `R16`'s
  `rate>=0.0` threshold and `R9` §7's vacuous substitution gate, in a load script this time.
- **Three arms agreed and the agreement looked like a result.** The first shutdown run sent 400
  recordings, which take 1.8–2.3 s, and signalled at 3.0 s — so every arm killed an idle
  container and graceful, immediate and `SIGKILL` were indistinguishable. `cd3f34f` → `20236d5`.
  The script now measures the uninterrupted batch time and **refuses to run** if it is not
  comfortably longer than the cut.

**And a control killed a claim, for the second time in this slice.** Probed with no settle, the
boundary arm's `/actuator/health` answered `503` in **0.011 s** beside a configuration change
that cannot affect latency. With `SETTLE=5` it is **30.021 s**, indistinguishable from the arms
before it (30.013 s and 30.010 s, all three inside the pool's 30 s `connection-timeout`):
the fast answer was a dead-but-not-yet-evicted connection being handed out, not the readiness
group. `R18` §9 is the report on attributing a timing difference to the wrong variable, and this
is what following it looks like.

## 4. 원인 / Mechanism

**§3.1.** A connection pool is a per-process reservation and `max_connections` is a per-server
ceiling, and no configuration file contains both. HikariCP's `minimum-idle` defaults to
`maximum-pool-size`, so a pool fills to its maximum from startup whether or not anything asks —
which means **the failure does not need load, only time.** PostgreSQL refuses a connection past
the ceiling with `FATAL: sorry, too many clients already` at the protocol level, before any
session exists; HikariCP treats a failed *creation* as a background housekeeping event and
retries, and only surfaces `Connection is not available` when a *borrower* waits past
`connection-timeout`. With 60 requested and 19 obtained, nothing borrows past the timeout,
because 19 connections are enough for the offered load. **So the pool absorbs the refusal
completely**, and the two sides of the system have no shared vocabulary for the fact that one of
them is being turned away.

**§3.2.** `DataSourceHealthIndicator` calls `getConnection()`. With the database gone the pool
is empty, so the call waits the full `connection-timeout` before failing — the indicator is not
slow, it is inheriting the pool's patience, and it holds a Tomcat worker for the whole of it.
The probe groups are a separate mechanism: `AvailabilityProbesHealthEndpointGroups` builds
`liveness` from `livenessState` and `readiness` from `readinessState`, which are
`ApplicationAvailability` signals about the *process*, not about anything it depends on. **Both
mechanisms are correct in isolation.** The incident is that the group a load balancer is
documented to read contains none of the contributors that know whether the instance can serve,
and the group that does contain them is the one an orchestrator is documented not to read.

**§3.3.** Graceful shutdown is a race between two independent timers. Spring's shutdown hook
stops accepting connections and then waits up to
`spring.lifecycle.timeout-per-shutdown-phase` for in-flight requests; `docker stop` waits its
`--time` and then sends `SIGKILL`. Neither is aware of the other, and the shorter one decides.
Underneath both, `AttemptRecorder.record` is a transaction per recording — so whatever the
timers do, the *unit* is intact: the connection dies, PostgreSQL aborts the open transaction,
and every earlier one is already committed. **The atomicity that survives is the one this
repository chose in `T3` for a completely different reason**, which is why §3.3's tables never
disagree.

## 5. 처방 / Remedy

Three questions, three remedies, and **only one of them ships.**

| Question | Option | Effect | Cost | Chosen |
| --- | --- | --- | --- | --- |
| §3.1 pool × instances | leave the pool at 10 and gate the arithmetic | 10 × 3 = 30 against 97. The gate fails the day somebody raises either | the fleet size becomes a constant in a test rather than in a deployment | **yes** |
| | raise `max_connections` | more headroom | it is the database's memory, and it makes every arm incomparable with `R2`, `R4`, `R16`, `R18` | no |
| | a connection proxy (PgBouncer, RDS Proxy) | decouples the two numbers properly | infrastructure for a fleet that does not exist | no — **and it is the right answer at real scale** |
| §3.2 health | add `db` to the readiness group | traffic drains, liveness stays up | the probe now costs `connection-timeout` | **no — see below** |
| | lower `connection-timeout` | the probe answers in 2 s | **real requests also give up in 2 s** | no |
| §3.3 shutdown | nothing | the default is already `graceful` | — | **yes, by measurement** |
| | align `docker stop --time` with `timeout-per-shutdown-phase` | arm E completes | there is no deployment here to align | no |

**Why the health remedy is measured and not shipped.** It is the only one of the three where the
right answer depends on something this repository does not have: a load balancer, its probe path
and its polling interval. Adding `db` to readiness with the default 30 s timeout converts a
routing bug into a thread-exhaustion bug the moment the probe interval is shorter than the
answer; lowering `connection-timeout` fixes that and makes every real request give up sooner.
**The two halves have to be chosen together, against a probe interval, by whoever operates the
fleet.** Shipping half of it because it is the half that fits in `application.yml` is how
`R2`'s failure mode reproduces — a requirement resting on a premise nobody checked.

So the decision is recorded rather than made: `ADR-012`, and `DeploymentBoundaryGateTest` asserts
the *current* state including the part that is a defect, so nothing drifts silently.

**What would have made a different option correct.** For §3.1, a fleet whose instance count is
recorded anywhere — then the arithmetic belongs in the deployment and not in a test. For §3.2, a
probe path this repository controls. For §3.3, a request that legitimately takes longer than ten
seconds; §3.3 arm E manufactures one and a real recommendation request does not come close.

### The managed equivalent

The parameter §3.1 is about is `max_connections`, and **on both major managed offerings it is
not a number anybody types — it is derived from the instance's memory**, which is exactly the
shape `R23` §5 found for the JVM heap.

- **Amazon RDS for PostgreSQL** — `max_connections`, allowed `6–262143`, default
  `LEAST({DBInstanceClassMemory/9531392}, 5000)`. Checked 2026-08-21 in *Quotas and constraints
  for Amazon RDS* → *Maximum number of database connections*,
  `https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html`. The same page notes
  that `DBInstanceClassMemory` "automatically subtracts the amounts reserved to the operating
  system and the RDS processes", so the ceiling is **not computable from the advertised GiB** —
  its worked MySQL example lands on ~630 where the arithmetic on the nominal figure gives 683.
  The page also recommends **RDS Proxy** for exactly the case §3.1 measures.
- **Cloud SQL for PostgreSQL** — `max_connections`, default "depends on the amount of memory of
  the largest instance in the chain of primaries", tabulated from **25** at ~0.5 GB through
  **100** at 3.75–6 GB to **1,000** at ≥120 GB. Checked 2026-08-21 at
  `https://docs.cloud.google.com/sql/docs/postgres/flags`.

**Two consequences this report's arms make concrete.** A Cloud SQL instance in the 3.75–6 GB band
has the same `100` this measurement used, so arm B's arithmetic — two instances at pool 60 — is
over the ceiling of a real small production database, not only of a container. And on either
provider **resizing the instance moves the ceiling under an application that never mentioned
it**, in both directions: scaling *down* to save money silently narrows the room a fleet's pools
already occupy, and nothing in the application will report it, because §3.1 measured that
nothing in the application reports it.

`superuser_reserved_connections` is worth one line: it is `3` here and bought nothing because the
image made the application a superuser. On RDS and Cloud SQL the application role is **not** a
superuser — neither provider grants it — so the reserve behaves as designed and an operator
retains a slot. **That is a difference that makes the local measurement pessimistic by three
connections and the local diagnosis experience pessimistic by a lot more.**

## 6. 재계측 / Re-measurement

Only §3.3 has a before and after in the application's own behaviour; §3.1 and §3.2 are
measurements of arms that all ship. The instrument's three are in §3.4.

| | Before — `cd3f34f` | After — `20236d5` |
| --- | --- | --- |
| Batch size against signal | 400 recordings, 1.8–2.3 s, signalled at 3.0 s — **nothing ever in flight** | 4000 recordings, 12.101 s, signalled at 3.0 s, and the script **refuses to run** if the margin is not there |
| What the arms reported | graceful, immediate and `SIGKILL` **all identical**: 4000/4000, `HTTP 200` | 4000 / 2163 / 657, and the client's view differs in each |
| Request body | `curl -d "$BODY"` — dies at `MAX_ARG_STRLEN`, 131072 bytes | `-d @file` |
| The two-timeout finding | not reachable | arm E: 6942/8000, exit 137 from a `docker stop` |

| | Before this report | After |
| --- | --- | --- |
| Write path under HTTP load | **미측정** — `ADR-009` | measured for **shutdown**. Latency and pool behaviour under concurrent writes still 미측정, §8 |
| How close `max_connections` came | **미측정** — `R18` §8 | reached, 28 refusals, and the application logged nothing |
| What `/actuator/health` says with the database gone | never asked | §3.2, four endpoints × three states × two instances |

## 7. 회귀 게이트 / Regression gate

`DeploymentBoundaryGateTest` — four assertions, one Spring context shared with the other gates,
none of them a duration (`ADR-004` rule 2):

- the probe groups are exactly `{liveness, readiness}`, `db` is in **neither**, and the primary
  group **does** include it;
- `server.shutdown` resolves to `GRACEFUL`;
- `spring.lifecycle.timeout-per-shutdown-phase` (30 s) is **longer** than `docker stop`'s grace
  (10 s) — an assertion that passes and is bad news;
- `maximumPoolSize × PLANNED_MAX_INSTANCES ≤ max_connections − superuser_reserved_connections`,
  with both server values read from the container: **10 × 3 = 30 against 97**.

`RecordingEndpointTest` gates the endpoint §3.3 needed: four of five recordings land over HTTP
with every outcome named, a rejection is `200` and not a `4xx`, and a cross-learner write is
`403` **before** the write.

`load-harness.yml` now asserts the committed mode of **every** script under `load/`, not one
named file, and parses the `ops/` scripts.

**Three things are honestly weak and are named.** The first assertion expects the defect rather
than the fix, so it is a trip-wire and not a guard. The `bash -n` parse check would have caught
none of the three instrument defects in §3.4. And **nothing here runs a second instance** — the
gate checks the arithmetic, not the fleet, because `:api:test` has one application context and
`ADR-004` would not let CI publish what a real fleet measured anyway.

## 8. 남는 위험 / Remaining risk

- **Three of the four traps in this slice do not reproduce on the shipped defaults, and defaults
  move.** `server.shutdown=graceful`, the probe groups' membership, and the pool being left at
  10 are what hold §3.2 and §3.3 shut. §7's gate exists for that and is a trip-wire, not a fix.
- **§3.1's misconfiguration has no application-visible symptom at all**, at the load measured.
  120 of 120 requests answered `200` with a fleet 80 connections over the ceiling. **What load
  would make it visible is 미측정** — arm E tried and found the row lock binding before the pool
  was, so the question is open rather than answered.
- **The fixture is one learner, and the seeded 3,963,719 rows were never loaded.** Every read in
  §3.1 and §3.2 returns in single-digit milliseconds and needs almost no connections. **No figure
  in this report may be compared with one from `load/recommendations.js`**, and whether a fleet
  running the *real* query would starve differently is 미측정.
- **One run per arm, and rule 5 asks for three.** The drift control (§3.4) establishes that
  connection counts do not move; it establishes nothing about the durations in §3.2 and §3.3,
  which are single probes. The 30.0 s figures are `connection-timeout` and are structural; the
  0.003 s and 0.265 s figures are not, and are quoted as observations.
- **Arm E's two p50s differ by 1.24× and that is inside `R18`'s 1.27× drift band.** Not claimed.
  Whether a starved pool costs latency at a concurrency this fixture cannot reach is **미측정**.
- **The share each instance wins is not deterministic** — 53/32/15, 48/32/20, 59/25/16, 60/19/21
  across four runs. The inequality reproduces every time; **nothing here explains the
  distribution**, and no number in §3.1's table beyond the totals should be read as a constant.
- **CPU limits were never set.** `cpus` is unset on every container in this report, so
  `ActiveProcessorCount` is the host's 8 in all three instances at once — which is not what a
  three-instance deployment on one box looks like. **미측정, and it is the largest single gap in
  the environment block.**
- **`docker stop`'s 10 s is Docker's default and not a platform constant.** Kubernetes'
  `terminationGracePeriodSeconds` is a different number with different semantics and **was not
  measured** — there is no Kubernetes here, deliberately, and arm E's finding is about *two
  independent timers*, not about the value 10.
- **The database image moved during this work**: `postgres:16-alpine` is `sha256:cf78e766…`,
  server 16.15, where `measurement-discipline.md` records `sha256:57c72fd2…`, server 16.14.
  Nothing here is compared across that, but **every earlier report's digest line is now stale
  relative to what the tag resolves to**, and this report does not fix them.
- **Write-path latency under HTTP load is still 미측정**, and so is connection-pool behaviour
  under concurrent writes at a concurrency the pool binds at. `ADR-009`'s cost paragraph narrows
  and does not close; `ADR-013` says exactly how much.
- **The endpoint §3.3 needed is public surface this repository did not have.** It authorises and
  `RecordingEndpointTest` proves it refuses a cross-learner write, but `R10` §5 and `R11` §5 both
  declined to add surface for a measurement, and this report did. `ADR-013` argues it; **that
  argument is a judgement and it is filed as a decision rather than as a risk**, which is what
  `R19` §3.2 asks for.
- **Which earlier §8 bullet this falsifies, and one of the two annotations is missing.**
  `ADR-009`'s *"the write path under HTTP load is 미측정"* narrows to shutdown behaviour only,
  and **is** annotated in `ADR-009` beside the sentence. `R18` §8's *"`max_connections=100` was
  never reached but was never far… how close it came is 미측정"* is falsified — reached, 28
  times — **and is not annotated there**, because `R18` was outside this work's file contract.
  `_TEMPLATE.md` §8 asks for the annotation to be beside the sentence and `R19` §3.4 measured
  what happens when it is only in the new report: twelve bullets falsified by later work, three
  of them saying so nowhere. **This is knowingly the fourth**, and it is recorded here and in
  `docs/reports/_ROUND2-B-HANDOFF.md` §3 so that it is discharged deliberately rather than
  discovered by the next sweep.

## 9. 배운 것 / What I learned

세 개의 함정을 한 리포트에 넣은 이유는 셋 다 같은 문장에서 나왔기 때문입니다: **이 저장소의
모든 숫자는 프로세스 하나에서 나왔다.** 인스턴스를 하나 더 붙이는 순간 풀 크기도, 헬스체크도,
배포도 전부 다른 질문이 됩니다.

제일 놀란 건 §3.1입니다. 저는 애플리케이션이 `Connection is not available`을 뱉을 거라고
예상했고, **한 번도 안 나왔습니다.** 거절한 건 데이터베이스뿐이고, 120개 요청은 전부 200이었고,
증거는 DB 로그 28줄이 전부였습니다. 그리고 제일 먼저 거절당한 건 사용자가 아니라 **제
모니터링 psql**이었습니다. 문제를 보러 온 사람이 첫 번째 피해자라는 게, 이 설정 오류의 성격을
그대로 말해줍니다. `superuser_reserved_connections=3`이 아무것도 못 산 이유도 — 이미지가
애플리케이션 롤을 슈퍼유저로 만들어놨더군요 — 재보기 전엔 몰랐습니다.

§3.2는 반대로 예상이 반만 맞았습니다. *"헬스체크가 DB를 안 본다"* 도 아니고 *"본다"* 도
아니고, **URL마다 다릅니다.** readiness는 3ms에 200을 주면서 같은 순간 모든 요청은 500이고,
`/actuator/health`는 503을 주는데 **30초** 걸립니다. 그리고 210개를 동시에 던졌더니 그
인스턴스에서 아무것도 응답을 못 했습니다. **장애를 감지하라고 만든 게 더 큰 장애를 만듭니다.**

§3.3에서 제일 오래 기억할 건 arm E입니다. Spring은 30초를 기다리고 `docker stop`은 10초 뒤에
죽입니다. 둘 다 기본값이고, 서로의 존재를 모릅니다. arm D가 456ms 차이로 **통과**했을 때
그냥 넘어갈 뻔했는데, 배치를 두 배로 늘리니까 `Commencing graceful shutdown` 다음 줄이 없고
`docker stop`인데 exit 137이 나왔습니다. **"오늘은 들어맞는다"와 "들어맞는다"는 같은 관찰이고,
구분되는 건 누가 요청을 더 길게 만든 다음뿐입니다.**

계기가 세 번 틀렸고 세 번 다 대조군이나 테이블이 잡았습니다. 특히 **80개 POST가 전부 200인데
행은 0개**였던 건 제 실수 중 제일 부끄럽습니다. per-item outcome은 전부 rejected여도 200이고,
저는 `-o /dev/null`로 본문을 버리고 상태 코드만 보고 있었습니다. `R16`의 `rate>=0.0`을 비웃는
문단을 읽은 사람이 같은 짓을 로드 스크립트에서 했습니다. 그리고 A′ 대조군은 드리프트를 찾으러
갔다가 **계기가 팔 사이에 상태를 흘리고 있는 걸** 찾았습니다 — `R18`이 대조군을 넣으라고 한
이유는 드리프트였는데, 정작 처음 잡은 건 그게 아니었습니다.
