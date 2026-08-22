# R29. The queue forms where nobody was looking, and the client is never told

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit / Green commit**: **neither, and that is the honest answer.** Nothing in the
> application changed. Every arm below is the same boot jar with a different program argument,
> which is `R4` §2's argument applied to a thread pool — `R18` §1 has the same line for the
> same reason. The commit carrying this report also carries `PoolCensusGateTest`, which is a
> regression gate on the sizes rather than a fix for them.
> **Answers**: `measurement-discipline.md` §*Connection pool sizing*, which states the formula
> `pool = Tn × (Cm − 1) + 1` and then says the practical reading is that **`Cm` is usually the
> number nobody knows**. `Tn` was also the number nobody knew: it appears in no file in this
> repository. Both terms are measured here.
> Also `R18`'s title, from the other side — that report found the pool was not the explanation
> for latency; this one finds what **is** holding the request while the pool is not.

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 (OpenJDK Runtime Environment Temurin-21.0.12+8, LTS)
  PostgreSQL     : server 16.15 — postgres@sha256:cf78e766…
                   max_connections=100, superuser_reserved_connections=3,
                   shared_buffers=128MB, max_parallel_workers_per_gather=2 (defaults)
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  Application    : ONE boot jar, sha256 5b9e6892909da8323c93d58c29d0bd80669bc9669eff72099666f46933516063
                   one JVM per arm, run on the HOST with no memory or CPU limit — so
                   measurement-discipline rule 10's three container lines do not apply and
                   are omitted rather than filled with `unlimited, 1`
  Dataset        : seed 20260810 — 3,963,719 rows, loaded, ANALYZE at 2026-08-22 05:54 UTC
  Load           : k6 v2.2.0, 200 VU, 30s warm-up DISCARDED, 3min window, through load/run.sh
  Repetitions    : first run of every arm discarded, then 3 PUBLISHABLE runs; median reported,
                   spread stated. A run the steady-state gate refused is not a run
  Session        : 2026-08-22, one uninterrupted session, arms in the order A B C V A′
  What else was running on the machine : NOTHING — see §2.1. No Gradle daemon, no Kotlin
                   compile daemon, no other JVM, no container but the database under test and
                   a pre-existing buildkit idling at 0.00 % CPU. Checked at the start AND the
                   end of every single run by a guard that aborts the run otherwise
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

> ⛔ **No number in this report may be placed beside one from `R2`, `R4`, `R16` or `R18`.**
> Those were taken against **PostgreSQL 16.14**; this ran on **16.15**, because
> `TestcontainersConfiguration.kt` now pins the digest that `ADR-017` chose. `R27` §3.2
> compared 16.14-musl against 16.15-**glibc**, which is a different pair, so **nothing in this
> repository has ever calibrated the two images these numbers straddle.** The refusal is
> stated rather than left as an omission, the way `R28` refused its comparison. The arms below
> are internally comparable to each other and to nothing else.

---

## 1. 증상 / Symptom

`R2` and `R18` both measured the connection pool, and both looked at it from the database side.
Neither ever wrote down the number on the other side of it.

**There are twenty times more web server worker threads than there are connections.** Tomcat's
`maxThreads` defaults to 200 and HikariCP's `maximumPoolSize` to 10, nothing in this repository
sets either, and no document here names the first of them at all. A worker that cannot get a
connection is **alive, occupying a stack, and doing nothing.**

The question this report exists to answer is not throughput. It is **what the client sees** when
that happens, because a refusal, a timeout, and silently getting slower are three different
incidents in operations and only one of them pages anybody.

### 1.1 ⭐ And the answer that matters most is about the configuration that looks fixed

*"Do not have more threads than you have connections"* is the received advice, and arm B is that
advice measured: twenty workers against ten connections. It is **5.07× less throughput** than
leaving Tomcat at its default, with a p50 of **1547.6 ms** against 272.4 — and that ratio is
against arm A′, the drift control, whose whole job is to be the honest comparator.

**What makes it the finding rather than an anecdote is that every pool gauge in the process reads
healthy while it happens.**

```
hikaricp.connections.pending : 0        the connection queue is EMPTY
hikaricp.connections.active  : ~0.5     one connection of ten in use
tomcat.threads.busy / max    : 20 / 20  which reads as efficiency, not as saturation
measured_errors              : 0.00 %
```

The queue did not go away. **It moved into Tomcat's accept backlog, where this application exposes
no metric at all** — so the tidier configuration is the more dangerous one, because the change that
produced it removed the only instrument that would have found it.

### 1.2 The variance is evidence too

| arm | what limits it | p50 spread over three publishable runs |
| --- | --- | ---: |
| **B** — 20 workers | the **worker pool**; the database is idle | **1.00×** |
| **C** — 50 workers | the **worker pool**; the database is idle | **1.01×** |
| **A** — 200 workers | the **connection pool**, and the database behind it | **1.83×** |
| **A′** — 200 workers | the same | **1.27×** |
| **V** — virtual | the same, with no worker-pool gauges | **1.56×** |

**The two arms this repository's own configuration fully determines are reproducible to three
significant figures. The three that reach a saturated database are not.** `R18` §3.1 saw the same
shape on a different axis: *"when the query is a three-second scan, the scan is the latency; when
it is fast, the latency is everything else, and everything else varies."* §4.6 has the numbers.

## 2. 재현 / Reproduction

One jar, one JVM per arm, five arms, run in one session in the order below.

| Arm | `server.tomcat.threads.max` | `spring.threads.virtual.enabled` | pool |
| --- | --- | --- | --- |
| **A** | 200 — Tomcat's default, stated | false | 10 |
| **B** | 20 | false | 10 |
| **C** | 50 | false | 10 |
| **V** | not applicable | **true** | 10 |
| **A′** | 200 | false | 10 — **drift control, run last** |

```bash
export JAVA_HOME=~/.jdks/jdk-21.0.12+8
export PROXIMA_DB_URL=jdbc:postgresql://localhost:55432/proxima
export PROXIMA_DB_USER=postgres PROXIMA_DB_PASSWORD=... PROXIMA_TOKEN_SECRET=...

java -jar api/build/libs/api.jar \
  --server.port=8080 \
  --server.tomcat.mbeanregistry.enabled=true \
  --spring.datasource.hikari.maximum-pool-size=10 \
  --server.tomcat.threads.max=200

BASE_URL=http://localhost:8080 ./load/run.sh recommendations.js -- --env VUS=200
```

**`server.tomcat.mbeanregistry.enabled=true` is on in every arm, and it is not cosmetic.**
Measured on this build by starting the same jar twice and reading **the HTTP status**, not just the
value: with the flag at its default of `false` the metrics endpoint answers **`404`** for
`tomcat.threads.busy`, `.current` and `.config.max`, and **none of the three is even listed** in
`/actuator/metrics` — zero names match `tomcat.threads`. With it `true`: `200`, values `1.0`,
`10.0`, `200.0`, and three names listed.

Half of this report is the occupancy of the worker pool, and without that flag there is no
instrument for it at all. It is on in every arm, so it cannot be the difference between them.

Program arguments rather than environment variables, for the reason `load/ops/harness.sh`
records: `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` is ambiguous under relaxed binding and a
hyphenated program argument cannot be read the other way.

⚠️ **The driver that sequenced the five arms is not in this repository, and that is a real weakness
of this report.** It looped the commands above, sampled the actuator gauges, and ran the quiet
guard in §2.1 — and it stayed in a scratch directory because this slice's file scope covers
`api/src/**/ops/**`, configuration and `.github/workflows/load-harness.yml`, and `load/ops/` is
none of those. **So these arms are reproducible from the commands printed here by hand, and not by
running a committed file** — which is weaker than `R24`, whose `trap-pool.sh` is in the tree. The
gap is declared rather than papered over, and adding the script is a decision for whoever owns
`load/`.

### 2.1 What else was running, and how that is a checked claim rather than an assertion

Every run is bracketed by a snapshot of the process table and the container list. A JVM that is
not this arm's `api.jar`, or a container that is not the database under test or the machine's
pre-existing buildkit, **aborts the run**; the two snapshots are compared so a daemon that wakes
mid-window is caught even when it was absent at the start.

**The guard aborted 0 runs, and a guard that has never refused anything is a claim rather than
an instrument** — `R17` §7 lists five in this repository that reported into nothing. So its
capability is shown rather than asserted: **the first version of it reported a false positive on
every call.** It matched `bin/java` against `ps -eo args=` output, and `ps` prints grep's own
command line, so `foreign()` returned a hit every time and would have aborted all fifteen runs
while the machine was in fact clean. It was fixed before any run counted, by matching
`[b]in/java` instead.

**And it had a hole that a false negative walked through.** The guard watched for something
foreign **arriving**. It did not check that the system under test was **still there** — and on
this machine, at 15:06:13, the database container stopped. The guard reported a clean machine,
the application failed at Flyway with `Connection to localhost:55432 refused`, and two attempts
were lost before anybody looked at the container rather than at the script. The symmetric check
— *assert the required thing is still running* — is the half that was missing, and a session-long
sampler recorded `proxima-d-db` running at every 15-second sample for the whole of the session
these numbers come from.

## 3. 계측 / Measurement

### 3.0 The five pools, every arm

**A number that names one pool is not reproducible**, so all five are read from the running JVM
rather than from `application.yml` — which states exactly one of them.

```
  1 web server workers      : max=200  current=10 at idle   (Tomcat's maxThreads / minSpareThreads)
  2 connection pool         : max=10   minIdle=10           (HikariCP's default)
  3 applicationTaskExecutor : core=8   max=2147483647  queueCapacity=2147483647
  4 ForkJoinPool.commonPool : parallelism=7   (availableProcessors 8 − 1, property unset)
  5 virtual thread carriers : jdk.virtualThreadScheduler.parallelism unset
```

**Three of those five numbers appear in no file in this repository.** Pools 3 and 4 are `R30`
and `R32`'s subjects; pool 5 is `R33`'s.

### 3.1 The gate refused a run, and it refused the flattering one

Arm A's third run was rejected by `load/run.sh` before any of these tables were built:

```
  p50 : 258.9 ms   p95 : 471.1 ms   p99 : 619.2 ms   err : 0.00 %   vus : 200
  steady state: first half 335 ms, second half 223 ms  (ratio 1.50, skew 1.50x)

  *** NOT STEADY STATE. The SECOND half of the measurement window was 1.50x slower,
  *** so the system DEGRADED while being measured -- a cache filling, a plan changing,
  *** something else on the machine. This direction went unwatched until R18.
  *** DO NOT PUBLISH THIS RUN.

steady-state verdict: FAIL skew=1.499
FAIL: this run is NOT STEADY STATE and its numbers may not be cited.
```

**The refused run was the best-looking one available at that moment** — the lowest p50 of the
three taken so far and the highest throughput, 709 req/s against 519. Admitting it would have
improved arm A's median in every column. `R17` §7 lists five instruments in this repository that
have never reported anything; this is the opposite case and it is the rarer one — a gate observed
refusing a number it had every incentive to accept, on a run nobody would have looked at twice.

*(The banner's wording is `R18`'s and it names the second-half-slower direction. Read literally
it is inverted for this run — the first half was the slow one — because the skew is symmetric and
the message picks its branch from the raw ratio. The verdict is correct; the sentence naming
which half is not. Recorded here rather than fixed: `load/` is outside this slice's file scope,
and it is `OPEN-D3` in §8.)*

### 3.2 How long a request waits for a connection — measured, not derived

**This did not have to be inferred.** `micrometer-registry-prometheus` is on the runtime
classpath (`api/build.gradle.kts`), so Boot wires HikariCP's metrics tracker, and
`hikaricp.connections.acquire` is a **timer over exactly this quantity**: the time a caller spent
waiting to be handed a connection. `hikaricp.connections.usage` is its counterpart — how long the
caller then held it.

Sampled every ten seconds through the whole session and differenced per arm — the counters reset
when each arm's JVM restarts, which is what separates them. Arm A's row is cumulative to 15:32:55
because the sampler was started mid-arm; every other row is that arm's whole JVM.

| arm | workers | window | acquisitions | **mean wait** | **mean hold** | wait ÷ hold | timeouts |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| **B** | 20 | whole arm | 108,109 | **0.026 ms** | 3.918 ms | 0.007× | **0** |
| **C** | 50 | whole arm | 265,754 | **0.511 ms** | 6.281 ms | 0.08× | **0** |
| **A** | 200 | JVM start → 15:32:55 | 632,706 | **133.7 ms** | 14.4 ms | 9.3× | **0** |
| **A′** | 200 | whole arm | 664,942 | **143.0 ms** | 14.912 ms | **9.6×** | **0** |
| **V** | virtual | whole arm | 630,287 | **99.5 ms** | 12.793 ms | 7.8× | **0** |

⭐ **The wait for a connection rises by a factor of 5,500 between twenty workers and two hundred**
— 0.026 ms to 143.0 ms — while the connections themselves never change. **Nothing timed out in any
arm**, across roughly 1.7 million acquisitions.

**At 200 workers a request waits nine to ten times longer for a connection than it uses one.** At
20 workers it waits essentially not at all, and is four times slower to answer. That inversion is
§4's whole subject.

**The hold time is not a constant either**: 3.918 ms at 20 workers, 6.281 at 50, 14.912 at 200.
Same query, same data, 3.8× apart — because ten of them run concurrently on eight cores at the top
of the table and about half of one runs at the bottom. §4.4.

Verbatim, arm A's raw reading at 15:32:55:

```
hikaricp.connections.acquire : COUNT 632706   TOTAL_TIME 84612.87 s   MAX 1.0811 s
hikaricp.connections.usage   : COUNT 632706   TOTAL_TIME  9119.77 s   MAX 0.1080 s
hikaricp.connections.timeout : COUNT 0
```

**A request spends nine times longer waiting for a connection than using one.** That is a
measured timer, not an arithmetic identity — and the derivation it replaces (`pending ÷
throughput`, Little's law on the two sampled gauges) is the shape `R9` §3.6 got wrong by
publishing a quotient of two measured things as though it were a reading. The derived figure is
kept nowhere in this report.

`MAX 1.0811 s` is worth its own line: **the worst wait in six hundred thousand acquisitions was
one second, and the pool's `connection-timeout` is thirty.** Nothing was ever close to timing
out, which is why §3.4's log is empty and why this incident has no error rate to find it by.

### 3.3 Arm A, and why its median cannot carry a fine comparison

| run | p50 | p95 | p99 | err | throughput | mean `hikariPending` | mean `tomcatBusy` | verdict |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| run 0 | 379.2 | 628.8 | 879.3 | 0.00 % | 503.4/s | 119.5 | 200.0 | **discarded by protocol** |
| run 1 | 364.1 | 609.3 | 854.1 | 0.00 % | 519.6/s | 115.4 | 198.8 | OK |
| run 2 | 258.9 | 471.1 | 619.2 | 0.00 % | 709.0/s | 101.5 | 199.0 | **FAIL, refused** |
| run 3 | 199.3 | 330.4 | 511.4 | 0.00 % | 913.3/s | 52.5 | 198.9 | OK |
| run 4 | 252.4 | 521.7 | 639.9 | 0.00 % | 711.0/s | 90.2 | 197.0 | OK |
| **median of the three publishable** | **252.4** | **521.7** | **639.9** | 0.00 % | **711.0/s** | 90.2 | 197.0 | |
| **spread** | **1.83×** | **1.84×** | **1.67×** | — | **1.76×** | 2.20× | 1.01× | |

⭐ **Every one of those three runs passed the steady-state gate, and together they slide by
1.83×.** The gate compares the two halves of **one** measurement window. Nothing compares one run
against the next, so three runs can each be internally steady while the arm as a whole is on a
wandering by 1.83× — which is what `hikariPending` at 115.4 → 52.5 → 90.2 shows.

⚠️ **And that sequence is not a monotonic trend, which matters for how much of it can be blamed
on the cache.** It falls and then rises. A page cache filling after the 15:06 container restart
explains the fall; it does not explain the return. **So the cause of arm A's spread is 미측정** —
warming is a candidate and not a conclusion, and the only thing this report claims from it is the
*size* of the band, which is what §8's refusals are built on.

**That is a second defect in the instrument, of exactly `R18` §3.5's family**, and this report
does not fix it because `load/` is outside this slice's file scope. It is `OPEN-D2` in §8.

The consequence is stated rather than worked around. **Arm A′ — the same configuration, run last
in the session — is the baseline the other arms are read against**, and §3.4 shows the two medians
landing 1.08× apart. That number, not arm A's internal 1.83×, is the band a cross-arm comparison
has to clear: the median of three is what is being compared, and the control compares medians.

### 3.4 Every arm, medians of three publishable runs

| arm | workers | p50 | p95 | p99 | error | throughput | mean `hikariPending` | mean `tomcatBusy` | p50 spread |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **B** | 20 | **1547.6** | 1560.6 | 1629.1 | 0.00 % | **129.2/s** | **0.0** | 20.0 | **1.00×** |
| **C** | 50 | **627.8** | 677.7 | 729.6 | 0.00 % | **315.6/s** | **0.0** | 50.0 | **1.01×** |
| **A** | 200 | **252.4** | 521.7 | 639.9 | 0.00 % | **711.0/s** | 90.2 | 198.8 | **1.83×** |
| **A′** | 200 | **272.4** | 536.8 | 713.1 | 0.00 % | **655.3/s** | 90.5 | 198.8 | **1.27×** |
| **V** | virtual, unbounded | **300.7** | 552.4 | 614.7 | 0.00 % | **604.2/s** | 104.2 | **`-1.0`** | **1.56×** |

All values in ms at 200 VU; medians of three publishable runs; throughput derived from k6's own
iteration counter between 0m29s and 3m29s, which is the same window the percentiles use.

⭐ **The drift control landed at 1.08×.** Arm A′ is arm A's configuration run last, seventy-nine
minutes later: p50 252.4 → 272.4 (**1.08×**), throughput 711.0 → 655.3 (**1.09×**), p99 639.9 →
713.1 (1.11×). `R18` measured 1.27× on this machine over seventy minutes and deleted a conclusion
over it; this session's band is tighter.

**And it is tighter than the spread *within* arm A, which is 1.83×.** That is the median of three
doing what rule 5 says it does: individual runs wander much further than the medians they compose.

**So the band that governs a comparison between arms is ~1.09×**, and it decides these:

| comparison | ratio on throughput | verdict |
| --- | ---: | --- |
| **B vs A′** — 20 workers against 200 | **5.07×** | **claimed** |
| **B vs C** — 20 against 50 | **2.44×** | **claimed** |
| **C vs A′** — 50 against 200 | **2.08×** | **claimed** |
| ⭐ **A vs A′ — the drift control. The same configuration against itself** | **1.09×** | *this is the band, not a finding* |
| **V vs A′** — virtual threads against platform | **1.08×** | ⛔ **refused: smaller than the row above it** |

**The control is in this table on purpose, one row above the comparison it kills.** A reader should
be able to see the thing being refused sitting beside the thing that refuses it, without following
a reference. The bottom row asks whether a scheduler change moved the system by 1.08×; the row
above says this configuration moves by 1.09× against *itself* across a session.

⛔ **The last row is a conclusion this report declines to draw in either direction.** It is not
"virtual threads made no difference"; it is **"this experiment cannot tell"**, which is a
different sentence and the only one the numbers support. `R33` §3.2 carries it.

### 3.5 What the application said about any of it

`R29`'s whole question is what an operator would have to go on. So the application's own log was
counted rather than assumed, across arm A's four measured windows at the occupancy in §3.4:

```
                        app-A  app-B  app-C  app-V  app-Ap
total lines                54     54     54     54      54    -- all the startup banner
WARN                        0      0      0      0       0
ERROR                       0      0      0      0       0
"Connection is not available"  0   0      0      0       0
```

**Fifty-four lines per JVM, none of them about the incident, in every one of the five arms.** `R2` §3.3 could at least quote a Hikari
timeout — that report ran with `open-in-view` on, held connections across a slow call, and drove
the pool to a thirty-second timeout. This configuration never gets near it: §3.2's worst single
wait in 632,706 acquisitions is **1.081 s** against a `connection-timeout` of 30 s.

So there is no exception, no log line, and an error rate of `0.00 %`. **The only observable is
latency, and the only instrument that decomposes it is a metric that does not exist by default**
— §5's option D.

## 4. 원인 / Mechanism

### 4.1 The request holds a worker forty times longer than it holds a connection

Per-arm figures from the `hikaricp.connections.acquire` and `.usage` **timers**, taken as deltas
between the first and last sample of each arm's JVM — the counters reset when the arm restarts,
which is what separates them:

| | arm A′ — 200 workers | arm B — 20 workers |
| --- | ---: | ---: |
| window | the whole arm | the whole arm |
| acquisitions measured | 664,942 | 108,109 |
| **mean wait for a connection** | **143.0 ms** | **0.026 ms** |
| **mean hold of a connection** | **14.912 ms** | **3.918 ms** |
| wait ÷ hold | **9.6×** | **0.007×** |
| pool timeouts | **0** | **0** |

**A′ rather than A is the 200-worker row here**, because A′ covers a whole JVM and arm A's sampler
was started mid-arm. Arm A's cumulative figure is 133.7 ms, 7 % below A′'s — and the choice between
them changes nothing, because the difference separating them is a fraction of the **5,500×**
separating twenty workers from two hundred.

**The connection is held for single-digit milliseconds and the worker for about 155.** The
request's life is roughly `4 ms of database + 150 ms of the content gateway + overhead`, and the
gateway is a `Thread.sleep` standing in for a call that does not touch PostgreSQL — so **97 % of
the time a worker is occupied, it is holding no connection at all.**

That is the whole mechanism, and the arithmetic closes on it. In arm B nothing queues for a
connection, so throughput is exactly workers ÷ service time:

```
20 workers ÷ 0.155 s  =  129.0 req/s          measured: 129.1 req/s
```

### 4.2 So sizing the worker pool to the connection pool throws capacity away

Arm B is what *"do not have more threads than connections"* looks like when it is measured. Twenty
workers against ten connections is the closest arm to that advice, and it is **5.07× slower in
throughput** than leaving Tomcat at its default — a difference far outside the **1.09×** band the
drift control measured, so it survives where the virtual-thread comparison does not.

The advice is not stupid; it is **answering a different question.** It protects a database from
being asked for more concurrency than it can serve. It says nothing about a request that spends
most of its life not asking the database for anything, and this endpoint does — because `R4` moved
the gateway call outside the transaction, which was correct and is exactly what makes the worker
and the connection two different resources with two different holding times.

### 4.3 The queue did not shrink. It moved somewhere with no gauge

This is the finding that matters operationally, and it is why §3's occupancy table is in the
report at all.

| | arm A | arm B |
| --- | --- | --- |
| where the queue is | the connection pool | Tomcat's connector, ahead of the workers |
| `hikaricp.connections.pending` | ~90–115 | **0** |
| `tomcat.threads.busy` / `config.max` | 197 / 200 | **20 / 20** |
| what a dashboard shows | a visible connection queue | **every pool at 100 % and nothing pending** |
| what the client gets | 252 ms | **1548 ms** |

⭐ **In arm B every gauge in the process reads healthy.** The connection pool is idle — nothing
pending, one connection in use out of ten. The worker pool is fully utilised, which reads as
efficiency rather than as saturation. And the client waits a second and a half, because the queue
is in the connector's accept backlog where **this application exposes no metric at all.**

Arm A at least leaves evidence: `hikaricp.connections.acquire` records a 125 ms mean wait, and
`pending` sits near 100. **Arm B is the more dangerous configuration precisely because it is the
tidier one** — the instrument that would have found it is the one the change removed.

### 4.4 And the pool's own service time is not a constant

`usage` is 14.0 ms in arm A and 3.9 ms in arm B — **3.5× apart for the same query on the same
data.** Arm A runs ten of them concurrently on eight cores; arm B runs about half of one.

That is `R18` §4's mechanism measured directly rather than inferred: *"`W` grows as the pool
grows"*. It also means the two arms' `wait ÷ hold` ratios are not comparable as pure ratios — the
denominator moved — which is why §4.1 prints both terms rather than only the quotient.

⚠️ **Arm A's 14.0 ms spans its cache-warming period and arm B's 3.9 ms does not.** Part of that
3.5× is contention and part is a warmer page cache, and **this measurement cannot separate them.**
The A′ arm is what bounds the second part.

### 4.5 Three worker counts, and where the bottleneck changes hands

Pool fixed at 10, 200 VU throughout, `server.tomcat.threads.max` the only variable.

| arm | workers | throughput | **per worker** | p50 | mean `hikariPending` | worker-held ms *(derived)* |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **B** | 20 | 129.2/s | **6.46/s** | 1547.6 | **0.0** | 154.8 |
| **C** | 50 | 315.6/s | **6.31/s** | 627.8 | **0.0** | 158.4 |
| **A′** | 200 | 655.3/s | **3.28/s** | 272.4 | 90.5 | 305.2 |
| *(A, the same configuration earlier in the session)* | 200 | 711.0/s | *3.56/s* | 252.4 | 90.2 | *281.3* |

**Per-worker throughput is flat from 20 to 50 and has halved by 200.** Below the crossover each
added worker buys a full share of throughput and the connection pool sits idle — `hikariPending`
is a clean zero and roughly one connection of ten is in use. Above it, added workers buy queue.

**The 200-worker row is A′ rather than A**, because A′ is the arm measured last, closest in time to
nothing in particular but bounded by a drift control against A. Both are shown; the conclusion does
not move between them.

**The right-hand column is a derivation and is labelled as one.** `workers ÷ throughput` is
Little's law applied to the **worker** pool, not a reading. It is worth printing because of what
it agrees with:

```
worker-held time, 50 workers   (derived)   158.4 ms
worker-held time, 200 workers  (derived)   305.2 ms
                                          ---------
rise                                       146.8 ms

measured rise in hikaricp.connections.acquire   143.045 - 0.511  =  142.5 ms
measured rise in hikaricp.connections.usage      14.912 - 6.281  =    8.6 ms
                                                                  ----------
                                                                    151.1 ms
```

**A derived 146.8 ms against 151.1 ms of measured components — 3 % apart.** The derivation and the
two timers are independent instruments, and the timers between them account for the whole of the
rise. That is what makes the derivation worth printing: not because it is a number, but because it
agrees with two that were read.

⚠️ **The derivation assumes the whole rise in worker-held time between the 50 and 200 arms is
connection wait** — that nothing else grew at the same time. The agreement with an independently
measured timer is evidence *for* that assumption, which is exactly why it is reported; it is not a
substitute for stating it. §4.4 names one thing that did also grow — the connection hold itself,
from 3.9 ms to 14.0 ms — and that is inside the same 123 ms, so the assumption is approximate
rather than exact.

⛔ **No knee is published.** Three points do not resolve one. Extending the flat per-worker slope
from the 20 and 50 arms until it meets the 200 arm's throughput would put the crossover somewhere
near a hundred workers, and **that is an extrapolation between two measured points, not a
measurement.** What is established is that the crossover lies between 50 and 200, and where it
lies is `ADR-014` ledger entry 29.1.

### 4.6 The reproducible arm is the one bottlenecked on the application

An accident of the design worth recording, because it will decide where the next measurement is
taken:

| arm | bottleneck | p50 spread | p99 spread | throughput spread |
| --- | --- | ---: | ---: | ---: |
| **B** — 20 workers | the worker pool | **1.00×** | 1.03× | **1.00×** |
| **C** — 50 workers | the worker pool | **1.01×** | 1.05× | **1.02×** |
| **A** — 200 workers | the connection pool | **1.83×** | 1.67× | **1.76×** |
| **A′** — 200 workers | the connection pool | **1.27×** | 1.35× | **1.27×** |
| **V** — virtual | the connection pool | **1.56×** | 1.24× | **1.66×** |

Arms B and C barely touch the database — mean `hikariPending` is a clean `0.0` in both — so nothing
about the page cache reaches them and three runs agree to three significant figures. The other
three arms' latency **is** the database's, and it varies.

`R18` §3.1 saw the same shape from the other side and said it plainly: *"when the query is a
three-second scan, the scan is the latency; when it is fast, the latency is everything else, and
everything else varies."* Here the axis is different and the rule holds — **the arm whose
bottleneck this repository owns is the reproducible one.**

## 5. 처방 / Remedy

### 5.1 The options, and what each was measured to do

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — leave the pool sizes at their defaults | baseline: 200 workers, 10 connections | zero | **yes** |
| B — reduce workers to match connections | **measured, arm B: 5.07× less throughput and the queue moves somewhere with no gauge** | worse on every axis | **no** |
| C — an intermediate worker count | measured, arm C | — | **measured, arm C: 2.08× less throughput than the default, and still no connection queue at all** | one third of the workers, less than half the throughput | **no** |
| D — **turn on `server.tomcat.mbeanregistry.enabled`** | the worker pool acquires gauges at all; without it the incident is invisible | MBean registration, **cost 미측정** | **yes — the only change that ships** |
| E — enlarge the connection pool | **미측정 here.** `R18` measured 1.94× at best from 5× the pool, and only when a scan was present | headroom, and `R24`'s `pool × instances` ceiling | no |
| F — move the gateway call off the request thread | would decouple worker holding from the 150 ms | **미측정** | no |
| G — assert the sizes in a gate | the next default that moves goes red | one test | **yes** |

**Option B is the remedy this report exists to refuse.** It is the received advice, it is what a
reviewer asks for on seeing 200 against 10, and arm B measures it making the system 5.07× slower
while every pool gauge in the process reads healthy. §4.2 is why: the advice protects a database
from excess concurrency and says nothing about a request that spends 97 % of its life not touching
the database.

**Option D is the only behavioural change in this slice, and it is an instrument rather than a
setting.** With `mbeanregistry` at its default of `false`, `tomcat.threads.busy`,
`tomcat.threads.current` and `tomcat.threads.config.max` do not exist — the actuator endpoint
answers `404` for all three, measured both ways. **Half of §3 could not have been taken without
it**, which means the incident this report describes is invisible to an operator by construction
until the flag is on. `ADR-018` records that its own cost is unmeasured and that it ships anyway,
with the reason.

**Option E is deliberately not attempted and `R18` is why.** That report measured five times the
pool buying at most 1.94×, and only when a sequential scan was present; with the index in place it
found the difference **inside its own drift band** and withdrew the claim. This session's drift
band is wider still, at 1.83×. Re-running that experiment here would have produced a number this
machine cannot resolve, which is a worse outcome than not running it.

### 5.2 What is not a remedy, and would look like one

**Raising `maximumPoolSize` until `hikaricp.connections.pending` reads zero.** It is the obvious
move from §3, it is achievable, and arm B is what the same-shaped success looks like when it is
measured properly: a gauge reading zero because the queue left, not because it drained.

### 5.3 The five pools are not five decisions

`ADR-018` records the whole of it: **the values stay at their defaults and the defaults become
assertions.** `application.yml`'s own note about `open-in-view` already argued this one defect
earlier — pinning a default to quieten a notice keeps the behaviour and loses the notice — and
`PoolCensusGateTest` is the opposite trade.


### 5.4 What this bottleneck is called on a managed database

Read on **2026-08-22** from AWS's own documentation, not written from memory:
`https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html`, section *"Maximum
number of database connections"*. The URL and the date are here for the same reason an image
digest is in the environment block — so a reader can go and disagree with it.

The parameter is **`max_connections`** — the same name as the one this machine reports as `100`.
What changes on RDS for PostgreSQL is that it stops being a fixed number and becomes a formula:

```
allowed values : 6 – 262143
default        : LEAST({DBInstanceClassMemory/9531392}, 5000)
```

`DBInstanceClassMemory` is **in bytes, after RDS subtracts what the operating system and its own
management processes reserve**, so the effective figure is smaller than the instance class's
advertised memory would suggest — AWS's own worked example lands at ~630 where the arithmetic on
total memory gives 683.

**Three things follow, and only the first is obvious.**

1. The ceiling `R24` §3.1 measured — `pool × instances` against `max_connections` — **moves when
   somebody resizes the instance**, in a direction nobody announces. `DeploymentBoundaryGateTest`
   asserts that arithmetic against `show max_connections`, which is the right shape *because* the
   right-hand side is not a constant: on a managed database it is a function of instance memory,
   so a scale-*down* made for cost reasons, by somebody who has never heard of the pool size,
   silently lowers the ceiling that `R24` §3.1 measured requests being refused above.
   **A measured ceiling that changes without a commit is the same defect class as a document
   going false without a diff** — which is `R27`'s subject, and `ADR-017`'s answer there was to
   watch the moving thing on a schedule rather than to record it once.
2. **The number this report is about is not that one.** `max_connections` bounds pool 2. The
   thing that queued in §3 was pool 1, and no managed database has an opinion about it: the
   worker pool lives in the application and RDS cannot see it. **Raising `max_connections`
   would not have moved any number in this report.**
3. AWS's documented recommendation for *"a large number of long-lived connections"* is **RDS
   Proxy**, which is a connection pool in front of the connection pool. On a managed database
   this slice's title is wrong by one: there are **six** pools, and the sixth is operated by
   somebody else, sized by somebody else, and has its own queue that the application cannot see
   at all.

## 6. 재계측 / Re-measurement

**Only option D shipped, so only option D has a before and an after — and it is an observability
change, so the before-and-after is what the instrument reports, not a latency.**

| | before — `mbeanregistry` at its default `false` | after — the green commit |
| --- | --- | --- |
| `GET /actuator/metrics/tomcat.threads.config.max` | **`404`** | **`200`** → `200.0` |
| `GET /actuator/metrics/tomcat.threads.current` | **`404`** | **`200`** → `10.0` at idle |
| `GET /actuator/metrics/tomcat.threads.busy` | **`404`** | **`200`** → `1.0` at idle, `200.0` under §3's load |
| names matching `tomcat.threads` in `/actuator/metrics` | **0** | **3** |
| `hikaricp.connections.pending` | `0.0` | `0.0` — this one was never missing |

**Both readings are from this machine, taken by starting the same jar twice, and the status code is
recorded rather than only the value.** That distinction is not pedantry: `R33` §3.1.1 is the same
check catching a *different* metric reporting `-1` where this report's first draft said it was
absent, because the parser could not read a minus sign. **A `404` and a value you failed to parse
look identical to a regex and are opposite findings.**

The `404` here is the real thing: the metric does not exist, it is not listed, a dashboard drawing
it gets no series.

⛔ **No latency re-measurement is claimed for the green commit, and one would be dishonest to
offer.** Every arm in §3 already ran with the flag on, so this session contains no `false` arm to
compare against — which means **the flag's own cost is 미측정** and it ships on that basis.
`ADR-018` and ledger entry 29.2 both say so.

**The other measured alternative, arm B, is not re-measured because it is not adopted.** §3's arms
are the measurement of it, taken in the same session on the same jar, which is what makes them
comparable to each other at all.

## 7. 회귀 게이트 / Regression gate

`PoolCensusGateTest`, in the commit carrying this report.

It asserts the five sizes above, and the assertion that matters is the **inequality**:
`webServerMaxThreads > connectionPoolMax`, currently 200 against 10. `ADR-004` rule 2 forbids a
CI assertion that is a duration and nothing here is one — every assertion is a pool size or an
inequality between two integers, neither of which depends on how fast the machine is.

**It is a trip-wire, not a fix**, and it is written the way `DeploymentBoundaryGateTest`'s
assertions are: *this assertion expects the defect.* If Tomcat's default moves under a version
bump, or if somebody sets one of these without setting the other, the gate goes red and the
arms above are describing a server that no longer exists.

**What it deliberately does not assert is a ratio.** §4 shows the useful ratio is not a constant
— it depends on what fraction of the request holds a connection, which is a property of the code
path and not of the configuration. A gate asserting `workers ≤ N × connections` would be
enforcing a number this report cannot justify.

## 8. 남는 위험 / Remaining risk

- ⭐ **Arm A never reached steady state, and the gate could not see it.** Its three publishable
  runs slide 1.83× while each one passes the within-run check. **No comparison finer than 1.83×
  may be drawn against arm A**, which is why arm A′ exists and why some of the arm-to-arm
  differences below the drift band are refused in §3 rather than reported. `R18` §3.3 measured
  1.27× on this machine and deleted one of its four conclusions; this band is wider.
- ⭐ **`load/run.sh` has no across-run check and nothing in this repository does.** `R18` fixed
  the within-run one. Three runs can each be certified publishable and their median can still be
  meaningless, which is what §3.3 shows. **`OPEN-D2`**, not fixed here because `load/` is outside
  this slice's file scope.
- **`load/recommendations.js`'s failure banner names the wrong half** when the *first* half is the
  slow one — the verdict is right and the explanatory sentence is inverted. Someone reading only
  the banner would look for a degrading system when the system was warming. **`OPEN-D3`**, same
  scope reason.
- **The database container stopped at 15:06 and the page cache was cold when the session began.**
  That is a condition, not an accident of protocol: arm A's warming is *because of a restart*, and
  a session begun on a warm cache would have produced a different arm A. The cause of the stop is
  **unestablished** — exit 0, no OOM, no Testcontainers label, and no mechanism identified.
- **The quiet-guard watched one direction for most of the session.** It aborted on foreign
  processes appearing and had nothing to say about the system under test departing, which is
  exactly what happened. The symmetric check exists now; **the arms in §3 were run under a
  session-long sampler rather than under the check itself**, and the sampler is evidence after the
  fact rather than a refusal at the time.
- **Only one concurrency level.** 200 VU, every arm. This is a set of points and not a curve, and
  `measurement-discipline.md` §*The knee* is explicit that the knee is the only interesting place
  on it. **미측정.**
- **`server.tomcat.mbeanregistry.enabled=true` is on in every arm and its own cost is 미측정.**
  The arms are comparable with each other and none of them prices the flag against `false`. It
  ships in the green commit on that basis, which `ADR-018` records.
- **The gateway delay of 150 ms is a choice, not a measurement**, and §4 shows the whole result
  turns on the ratio between it and the 4–14 ms the database costs. A dependency that answered in
  15 ms would move the worker-to-connection arithmetic by an order of magnitude and could reverse
  §4.2. `R2` §8 recorded the same limitation and it has never been closed.
- **`Thread.sleep` stands in for a network call.** It parks the thread as a blocking call would,
  and it consumes no sockets, no TLS and no second process. Under virtual threads it also
  *unmounts*, which is not what every blocking call does — `R33` §3.3 is where that matters.
- **Host CPU was not sampled during any run.** §4.4 attributes part of the `usage` difference to
  contention on eight cores and that attribution is **inferred, not measured**. `R2` §8 carries
  the identical gap and it is still open.
- **One instance.** `R24` established that `pool × instances` is the arithmetic that breaks
  against a database; there is no equivalent ceiling for worker threads, so nothing here says what
  three instances of this configuration would do to the same database. **미측정.**
- **Every number here is PostgreSQL 16.15** and every latency number this repository published
  before this round is 16.14. Nothing has calibrated the pair. The refusal to compare is stated at
  the top of this report rather than left for a reader to notice.
- **What would break the conclusion:** an endpoint whose request is mostly database. §4.2's whole
  argument is that the worker is held forty times longer than the connection, and that ratio is a
  property of *this* handler. The recording path is not shaped like this one, and this report says
  nothing about it.

## 9. 배운 것 / What I learned

**제일 무서웠던 건 arm B였고, 그게 "고친" 쪽이라는 것이다.**

*"스레드 풀을 커넥션 풀보다 크게 잡지 마라"* 는 조언은 어디에나 있고, arm B가 그걸 재본
것이다. 워커를 200에서 20으로 줄였더니 처리량이 5.07배 떨어졌다 (A′ 기준). 그런데 숫자보다 무서운 건
**계기판이 전부 정상으로 읽힌다는 것**이었다. `hikaricp.connections.pending`이 0이다. 커넥션
10개 중 1개도 안 쓰인다. 워커 풀은 20/20으로 꽉 차 있는데, 이건 포화가 아니라 **효율**처럼
보인다. 그리고 클라이언트는 1.5초를 기다린다. 큐가 사라진 게 아니라 **게이지가 없는 곳으로
옮겨간 것**이고, 옮긴 건 내가 한 "수정"이었다.

`R2`와 `R18`이 커넥션 풀을 두 번 쟀는데 둘 다 데이터베이스 쪽에서 봤다. 반대쪽 숫자 — 워커가
몇 개인지 — 는 이 저장소 어느 문서에도 없었다. 다섯 개 풀 중 **세 개가 어느 파일에도 안
적혀 있었다.**

**두 번째로 배운 것: 유도한 값을 표에 넣을 뻔했다.** 처음엔 게이지 두 개로 Little's law를 써서
"커넥션 대기가 221ms"라고 계산했다. 깔끔했고 p50과 맞아떨어졌다. 그런데 `hikaricp.connections.acquire`가
**정확히 그 값을 재고 있는 타이머**로 이미 거기 있었다 — micrometer가 클래스패스에 있으니까.
재본 값은 143.0ms였다 (A′ 팔 전체). 유도한 값과 크게 다르진 않았지만 **다른 게 요점이 아니다.** `R9` §3.6이
바로 측정값 두 개를 나눠서 몫을 발표한 보고서고, 나는 그 규칙을 읽은 상태로 같은 걸 하려고 했다.
계산이 맞아떨어질 때가 제일 위험하다.

**세 번째 — 게이트가 거절한 run이 제일 좋아 보이는 run이었다.** arm A run2는 p50이 제일 낮고
처리량이 제일 높았다. 그걸 넣었으면 arm A의 중앙값이 모든 칸에서 좋아졌다. 게이트가 그걸
거절했고, 나는 그 순간 게이트가 틀린 게 아닌지 확인하고 싶어졌다. `R17` §7이 세는 건
*"한 번도 아무것도 거절한 적 없는 계기 다섯 개"* 인데, 이건 반대 경우고 더 드물다.

**그리고 게이트가 못 본 것.** run 1, 3, 4가 전부 within-run 검사를 통과했는데 셋이 서로 1.83배
차이가 났다. `R18`이 창 안의 두 반쪽을 비교하게 만들었고, **연속된 run끼리 비교하는 검사는
아무도 안 만들었다.** 세 번 재서 중앙값을 내라는 규칙이 있는데, 그 세 번이 서로 못 믿을 값이면
중앙값도 못 믿는다. 이건 `R18` §3.5와 정확히 같은 모양의 결함이고, 같은 파일에 있다.

마지막으로 — 이번에 제일 오래 배운 것은 **어떤 질문이 지속시간을 요구하는가**였다. 이 슬라이스의
다섯 개 함정 중 락이 필요했던 건 두 개뿐이다. `R30`, `R31`, `R32`는 전부 개수로 답할 수 있었고,
그래서 다른 슬라이스가 기다리는 동안 시끄러운 머신에서 잴 수 있었다. 처음엔 전부 부하를 걸어야
한다고 생각했다. **지속시간으로 물어야만 하는 질문은 생각보다 적다.**
