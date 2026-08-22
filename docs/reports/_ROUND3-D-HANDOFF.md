# Round 3, slice D — handoff

> Transient integration note. It deliberately carries no *last-updated* date line: it goes stale
> the moment the integrator edits around it, and a date on it would be a claim nobody maintains.

Branch `round3/pools`, base `77022a5`. **Not merged, not rebased, not pushed.**

---

## 1. WHAT I OPENED

| Trap | What it asked | Verdict |
| --- | --- | --- |
| **D1** — where the queue forms when workers outnumber connections | refusal, timeout, or silently slower | `REPRODUCED` |
| **D2** — when a thread pool's maximum size is ever used | core, maximum and queue capacity interacting | `REPRODUCED` |
| **D3** — what fails to cross an `@Async` boundary | transaction, request scope, MDC, `ThreadLocal` | `REPRODUCED` |
| **D4** — the fourth pool, the one nobody created | `parallelStream()` on the JVM-wide common pool | `REPRODUCED` |
| **D5** — virtual threads, and where they get pinned again | does D1's bottleneck disappear or move | `REPRODUCED` |

**D1's answer is the third incident shape**: silently slower. Not a refusal, not a timeout —
error rate `0.00 %`, every request `200`, and **each of the five arms' JVMs logged 54 lines, of
which 0 were `WARN` and 0 were `ERROR`** — all of them the startup banner — while roughly 90 to 115
of 200 worker threads were blocked waiting for a connection at any instant.

**D5's answer to the question as the brief asked it is "neither" — and the trap still reproduced,
just not where it was expected.** The bottleneck did not disappear and did not move: it was
already the connection pool, and a scheduler cannot manufacture an eleventh connection. **The
defect that did reproduce is an observability one**, and it is not the one I first wrote down.
Turning virtual threads on takes `executor.pool.core` and `executor.pool.max` away entirely —
**HTTP `404`** — and leaves `tomcat.threads.busy`, `.current` and `.config.max` answering
**HTTP `200` with the value `-1`**. ⭐ **The second half is the worse one**: `-1` is a number, a
dashboard plots it, and a rule that pages when `busy > 180` never fires. `R33` §3.1 has the table,
and §3.1.1 records that **my own harness reported the `-1` as `미측정` because its regex could not
read a minus sign** — the instrument's bug confirming the hypothesis I was carrying.

⚠️ **The latency half of D5 is a refusal, not a result.** Arm V's throughput median is 604.2/s
against arm A′'s **655.3/s**, and both arms' own spreads are wider than the difference between
them. **`R29` §3.3's rule applies and the comparison is declined** rather than reported as a
finding in either direction.

---

## 2. COMMITS

| Trap | red SHA | green SHA | what actually flipped |
| --- | --- | --- | --- |
| **D1** — `R29` | *(none)* | *(none)* | **Nothing in the application changed.** Five arms of one boot jar (`sha256 5b9e6892909d…`) differing only by a program argument, `R18` §1's shape. The commit carrying the report also carries `PoolCensusGateTest`, a trip-wire on the sizes rather than a fix |
| **D2** — `R30` | `3ab75d6` *(fixtures)*, `ee7d73c` *(the tests that observe it)* | **none — declined** | The red state is the tree's own configuration: `core=8 max=2147483647 queueCapacity=2147483647`, and the maximum unreachable by construction. **The remedy is measured and not adopted** (`ADR-018`): bounding the queue means choosing a rejection policy for a load that does not exist |
| **D3** — `R31` | `3ab75d6`, `ee7d73c` | `4f29288` | `proxima.ops.async-context`: `none` → `copy-mdc`. **The MDC crosses the boundary afterwards and nothing else does** — the transaction cannot cross and §5.1 argues why no decorator can carry it |
| **D4** — `R32` | `3ab75d6`, `ee7d73c` | **none for the application — declined** | The planted `parallelStream()` starves an unrelated caller. **The remedy is measured beside it and not adopted**, because there is no `parallelStream()` in this application to move; `ADR-018` keeps it out of every request path |
| **D5** — `R33` | *(none)* | *(none)* | **Nothing in the application changed.** Arm V is the same jar with `spring.threads.virtual.enabled=true`. `ADR-018` keeps it `false`, and the reason is `R33` §3.1 rather than any latency |
| *(shared)* — the instrument | `ee7d73c` | `4f29288` | `server.tomcat.mbeanregistry.enabled` unset → `true`. **`tomcat.threads.busy`, `.current` and `.config.max` go from `404` to reporting.** Without it half of `R29` §3 could not have been taken |

⛔ **Two traps have no green commit and one shared change is the only behavioural difference this
slice ships.** That is the honest count, and §5 of `R30` and `R32` each carry the measured remedy
they declined, with the reason. `R2` is the precedent: a report that establishes a mechanism and
refuses to choose a remedy on evidence that cannot support one.

### Why the commits are not one per trap

**Three of the five traps share one red state and one green state**, because what makes D2, D3 and
D4 observable is the same thing: a set of measurement fixtures under `api/src/main/kotlin/.../ops/`
and the tests that read them. Splitting that into three red commits would have produced three
commits none of which compiles on its own.

**Two of the five have no green commit at all, and the refusals are the decisions rather than
gaps** — `R30` §5 and `R32` §5 both measure a remedy and decline to adopt it, and `ADR-018` records
why. `R2` is this repository's precedent for a report that establishes a mechanism and refuses to
choose a remedy on evidence that cannot support one.

**And `R29` and `R33` have neither**, because nothing in the application changed for them: every
arm is the same boot jar with a different program argument. `R18` §1 carries the same line.

### Sequencing

The code commits land **after** the measurement session rather than before it, and the reason is
the round's measurement lock: compiling or running Gradle inside the window would have appeared to
my own quiet-guard as a foreign JVM and aborted the arm being measured. The ops sources were
compiled and committed before the window opened; the tests could not be run until it closed.

### Numbers I actually took, and why

`R29`–`R33` and `ADR-018` were assigned, and **I took exactly those.** Derived from the tree at
base `77022a5` before the first commit rather than trusted:

```
ls docs/reports/R*.md          -> highest R28     -> R29 free
ls docs/decisions/adr/ADR-*.md -> highest ADR-017 -> ADR-018 free
ls api/src/main/resources/db/migration/V*.sql -> highest V5; no migration is mine
```

No shift was needed and none was made. The pack's earlier edition assigned this slice
`R28`–`R32`/`ADR-015`; **both are consumed on `main`** — `R28` is *the remedy two reports
rejected against a database that could not pay*, `ADR-015` is *a race test proves its own
precondition*. No brief I was handed showed the stale range, so nothing had to be stopped for.

---

## 3. NUMBERS

### 3.0 The measurement environment every latency number below belongs to

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : server 16.15 — postgres@sha256:cf78e766…, read from the container
                   max_connections=100, superuser_reserved_connections=3,
                   shared_buffers=128MB, max_parallel_workers_per_gather=2 (defaults)
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  Application    : ONE boot jar, sha256 5b9e6892909da8323c93d58c29d0bd80669bc9669eff72099666f46933516063
                   one JVM per arm, on the HOST with no memory or CPU limit — rule 10's three
                   container lines do not apply and are omitted rather than filled in
  THE FIVE POOLS : 1 workers max=200 minSpare=10 (the arm variable) · 2 connections max=10
                   3 applicationTaskExecutor core=8 max=2147483647 queueCapacity=2147483647
                   4 ForkJoinPool.commonPool parallelism=7 · 5 virtual carriers unset
  Dataset        : seed 20260810 — 3,963,719 rows, ANALYZE at 2026-08-22 05:54 UTC
  Load           : k6 v2.2.0, 200 VU, 30s warm-up DISCARDED, 3min window, via load/run.sh
  Repetitions    : first run of every arm discarded, then 3 PUBLISHABLE; median, spread stated
  Session        : 2026-08-22 15:16:17 → 16:35:37 +0900, one uninterrupted session
  What else was running on the machine : NOTHING — checked at the start AND the end of every
                   run; see §3.9
```

⛔ **The count-only numbers — `R30`, `R31`, `R32`, and `R33` §3.3 — do not belong to this block
and do not need it.** They are pool sizes, thread names, row counts and JFR event counts, taken
while other work was running on the machine because none of them is a duration. Each report says
so in its own header. **This is the distinction that decided what the round's measurement lock had
to cover**, and it is stated here so the integrator does not attach a quiet-machine claim to a
number that never needed one.

### 3.1 `R29` — the arms

Five arms, one jar (`sha256 5b9e6892909d…`), one JVM each, pool fixed at **10**, 200 VU
throughout. `server.tomcat.threads.max` is the only variable except in arm V.

```
arm  workers      p50/ms   p95/ms   p99/ms   err     thr/s   pending  busy    p50 spread
B    20           1547.6   1560.6   1629.1   0.00%   129.2     0.0    20.0      1.00x
C    50            627.8    677.7    729.6   0.00%   315.6     0.0    50.0      1.01x
A    200           252.4    521.7    639.9   0.00%   711.0    90.2   198.8      1.83x
A'   200 (last)    272.4    536.8    713.1   0.00%   655.3    90.5   198.8      1.27x
V    virtual       300.7    552.4    614.7   0.00%   604.2   104.2    -1.0      1.56x
```

Medians of **three publishable** runs each; twenty runs attempted, fifteen published, one refused
by the steady-state gate, four discarded by protocol as each arm's first.

⭐ **Drift control A → A′: 1.08× on p50, 1.09× on throughput, over seventy-nine minutes.** That is
the band. It is *narrower* than arm A's own within-arm spread of 1.83×, which is the median of
three doing what rule 5 says it does.

| comparison | ratio | verdict |
| --- | ---: | --- |
| B vs A′ — 20 workers against 200 | **5.07×** | claimed |
| B vs C | **2.44×** | claimed |
| C vs A′ | **2.08×** | claimed |
| **A vs A′ — the control, the same configuration against itself** | **1.09×** | *the band* |
| V vs A′ — virtual threads | **1.08×** | ⛔ **refused, smaller than the band** |

### 3.2 `R29` — how long a request waits for a connection

From the `hikaricp.connections.acquire` and `.usage` **timers**, sampled every ten seconds and
differenced per arm. **Measured, not derived** — the derivation this replaces is named in §3.10.

```
arm  workers   acquisitions   mean WAIT    mean HOLD   wait/hold   timeouts
B    20            108,109      0.026 ms    3.918 ms     0.007x        0
C    50            265,754      0.511 ms    6.281 ms      0.08x        0
A    200           632,706    133.7   ms   14.4   ms       9.3x        0   (cumulative to 15:32:55)
A'   200           664,942    143.0   ms   14.912 ms       9.6x        0
V    virtual       630,287     99.5   ms   12.793 ms       7.8x        0
```

⭐ **The wait rises 5,500× between twenty workers and two hundred while the connections never
change**, and **nothing timed out in any arm** across roughly 1.7 million acquisitions.

**The cross-check the orchestrator asked for, rebuilt against A′:**

```
worker-held time   50 workers  (DERIVED, workers/throughput)   158.4 ms
worker-held time  200 workers  (DERIVED)                       305.2 ms
rise                                                           146.8 ms

MEASURED rise in acquire   143.045 - 0.511  =  142.5 ms
MEASURED rise in usage      14.912 - 6.281  =    8.6 ms
                                               151.1 ms   -> 3% from the derived rise
```

**The measured timers carry the claim; the derivation corroborates them and is labelled.** Its
assumption — that the rise in worker-held time is connection wait — is stated, and the two measured
components account for the whole of it.

### 3.3 `R29` — what the application logged about any of it

**Nothing.** Counted rather than assumed, across all five arms:

```
app-A.log  app-B.log  app-C.log  app-V.log  app-Ap.log
total lines            : 54 each   -- all of them the startup banner
WARN                   :  0 each
ERROR                  :  0 each
"Connection is not available, request timed out" : 0 each
```

**Five JVMs, twenty runs, roughly 1.7 million connection acquisitions, and not one WARN or ERROR
line anywhere.** Error rate `0.00 %` in every published run. The worst single connection wait
observed was **1.081 s** against a `connection-timeout` of **30 s**, so nothing came near the
timeout that `R2` §3.3 was able to quote.

That absence is D1's answer: **no refusal, no timeout, no log line — only latency.**

### 3.4 `R30` — the thread pool's counters

```
R30 §3 core=2 max=8 queue=unbounded tasks=20 -> poolSize=2 queued=18 rejected=0
R30 §3 core=2 max=8 queue=2         tasks=20 -> poolSize=8 queued=2  rejected=10
R30 §3 core=2 max=8 queue=unbounded tasks=2  -> poolSize=2 queued=0  rejected=0
R30 §3 core=2 max=8 queue=2         tasks=2  -> poolSize=2 queued=0  rejected=0
```

⭐ **`max = 8` is written down in both twenty-task arms and only one of them ever gets there.** The
setting that decided it is the **queue**. With no bound: the pool never leaves core, eighteen tasks
queue, **nothing is refused and nothing is logged**. With a bound of two: the pool reaches max and
**ten of twenty tasks are refused outright** — the same overload delivered as a refusal instead of
as latency and heap.

**And the two-task control pair is identical in every column**, which is why no test written at a
load the pool can absorb could distinguish the configurations.

**The real bean, read from the running application by `PoolCensus`:**
`core=8 max=2147483647 queueCapacity=2147483647` — so this tree has the arm-A shape, and `max`
would be equally decorative if it read `200`.

### 3.5 `R31` — what crossed the `@Async` boundary

Counts and thread names; no duration, so no quiet machine needed.

```
R31 §3.2 caller = threadName=Test worker  transactionActive=true
                  transactionName=...TransactionalAsyncCaller.observeInsideTransaction
                  mdcValue=set-on-the-caller  threadLocalValue=set-on-the-caller
                  requestAttributesPresent=false
R31 §3.2 async  = threadName=task-3        transactionActive=false
                  transactionName=null
                  mdcValue=null              threadLocalValue=null
                  requestAttributesPresent=false
R31 §3.3 after rollback: sync rows=0  async rows=1
R31 §5   async  = threadName=task-1        mdcValue=green-arm  transactionActive=false
```

**Three of four contexts are established with a control that passes on the caller's side**: the
transaction is really active, the MDC really holds a value, the `ThreadLocal` really holds one —
and none of the three survives one method call.

⚠️ **The fourth — request attributes — reads `false` on BOTH sides and is therefore not
established.** The test runs on a JUnit thread, so there were never any request attributes to lose.
It is marked in `R31` §3.2 and entered as ledger `31.6` rather than counted as a finding. **That is
`R5` §3.3's shape a third time in this slice**, and the third time I caught it by looking rather
than by trusting a pass.

**`task-N` is Boot's `applicationTaskExecutor`** — pool 3, the one with the unreachable maximum.
Read off the thread rather than off the wiring, per §0 rule 9.

### 3.6 `R32` — the common pool

```
R32 §3.1 availableProcessors=8  commonPool.parallelism=7  property=unset
R32 §3.2 elements=16  threads=[7 commonPool workers + Test worker]
         peakConcurrent=8  callerThreadParticipated=true  commonPoolSizeAfter=7
R32 §3.3 starved: elements=28  threads=[Test worker]  peakConcurrent=1
R32 §5   dedicated: elements=32  threads=[ForkJoinPool-1-worker-1, -worker-3]
         commonPoolWorkersUsed=0  ranOnDedicatedPool=true
```

⭐ **Twenty-eight elements, one thread, no parallelism.** The second caller shares nothing with the
first; all seven common-pool workers are parked inside the first caller's stream and a parked
ForkJoin worker is not replaced. **No exception, no log, correct answer, silently sequential.**

**Peak in flight is `parallelism + 1`, not `parallelism`** — the submitting thread executes elements
itself, so a request thread calling `parallelStream()` is inside the pool rather than waiting on it.
`commonPoolSizeAfter=7` with eight elements parked: **blocking does not grow the pool.**

⚠️ **`peakConcurrent=1` in the remedy arm is not a limit** — that arm's work is an empty lambda, so
nothing overlaps. It establishes *where* the work ran, not how much ran at once.

### 3.7 `R33` — virtual threads

**The census, re-taken with HTTP status codes rather than only parsed values** — which is the
reading that corrected this report's central claim:

```
metric                        platform threads     virtual threads
tomcat.threads.config.max     200 -> 200.0         200 -> -1.0
tomcat.threads.current        200 -> 10.0          200 -> -1.0
tomcat.threads.busy           200 -> 1.0           200 -> -1.0
executor.pool.core            200 -> 8.0           404
executor.pool.max             200 -> 2147483647    404
hikaricp.connections.max      200 -> 10.0          200 -> 10.0
```

Identical before traffic and after thirty requests, so this is not a gauge that populates late.
Thread dump confirms the configuration: `http-nio-8080-exec-N` versus `ForkJoinPool-1-worker-N`.

**Pinning, from `VirtualThreadPinningTest` (JFR event counts, no duration):**

```
jdk.VirtualThreadPinned events : synchronized=7926   ReentrantLock=0     (green run)
                                 synchronized=7931   ReentrantLock=0     (red run)
peak simultaneously inside     : synchronized=8      ReentrantLock=16    (both runs)
                                 (availableProcessors=8, 16 threads attempted)
```

⚠️ **The event count is not deterministic** — the threads park in a poll loop, so it counts *how
many times they parked while pinned* before release. **The pair is what is deterministic**:
thousands against zero, and 8 against 16. Both runs are quoted rather than the tidier one.

⭐ **A pinned virtual thread costs a carrier**: only 8 of 16 could be inside at once on an
8-carrier machine, against all 16 for the lock. **And the negative arm is a real zero** — the
positive arm proves the recording was armed, which is `R5` §3.3's construction.

**This repository's own code contains zero `synchronized`**, so the trap's *"remove them and
measure again"* has no subject here. The question moves to the dependencies, read out of bytecode:

```
postgresql-42.7.11 : 483 classes, 14 monitorenter opcodes, 6 synchronized signatures, 1 ReentrantLock
HikariCP-7.0.2     :  81 classes,  3 monitorenter opcodes, 9 synchronized signatures, 0 ReentrantLock
  PgConnection          synchronized=0  monitorenter=0
  QueryExecutorImpl     synchronized=0  monitorenter=10
  PgStatement           synchronized=0  monitorenter=0
  PgPreparedStatement   synchronized=0  monitorenter=0
```

**So the condition exists in the driver** — ten `monitorenter` opcodes on the hot query path — and
under load it did not fire: **200 requests, all HTTP 200, zero pinned-park traces.**

⚠️ **That last figure is small and its history is worse than its size.** It took three attempts;
the first two measured nothing and are in §6. The drive was stopped at 200 requests rather than the
planned 4,000 because 200 concurrent curl processes plus `-Djdk.tracePinnedThreads=full` turned out
to be bound by process creation. **200 requests is a weak sample and it is reported as one.**

### 3.8 The test run

**Two full runs, both `./gradlew :api:test :seed:test --rerun-tasks`, neither from a cache.**

⚠️ **The count is a property of the commit it was taken at, and this branch's base is `77022a5`.**
Stated because it will otherwise look like a discrepancy: **slice G's `:seed:test` count is 15 and
mine is 14, and both are right.** `77022a5` carries no RecencyDefinitionTest (deliberately unbackticked: it does not exist on this branch and CHECK 1 resolves every backticked artefact against the tree); G descends from H,
which adds it. ⛔ **Neither number should be "reconciled" against the other** — a count is only
meaningful beside the tree it was counted on, and `R17` exists because one sat unchanged across
eight test-adding commits.

```
                      run 1 (red, ee7d73c)        run 2 (green, 4f29288)
BUILD                 SUCCESSFUL in 23m 23s       SUCCESSFUL in 20m 1s
:api:test             140 tests / 54 classes      142 tests / 54 classes
                      0 failures 0 errors 0 skipped   0 failures 0 errors 0 skipped
:seed:test             14 tests /  4 classes       14 tests /  4 classes
                      0 failures 0 errors 0 skipped   0 failures 0 errors 0 skipped
```

**142 against 140** is `PoolCensusGateTest` gaining the two assertions on the shipped green state.
Sixteen of the 142 are this slice's, across six classes:

```
AsyncBoundaryTest 3   AsyncBoundaryWithContextCopyTest 1   PoolCensusGateTest 5
SharedPoolTest 3      ThreadPoolBoundsTest 3               VirtualThreadPinningTest 2
```

**Reported per module, never as one number.** `R17` is why: this repository's count once read
`77 tests` when that was `:api:test` alone and said so nowhere.

### 3.9 What else was running on the machine

**For every latency number: nothing.** That is a checked claim rather than an assertion, and here
is what checked it.

**The quiet-guard.** Every run was bracketed by a snapshot of the process table and the container
list, taken **before and after**, so a daemon waking mid-window is caught even when it was absent
at the start. A JVM that is not the arm's own `api.jar`, or a container that is not the database
under test or the machine's pre-existing buildkit, **aborts the run**.

```
runs bracketed by the guard                    : 20  (every run of every arm)
runs the guard ABORTED                         :  0
foreign processes or containers seen, ever     :  0
SUT presence samples, 15:20:13 -> 16:35:37     : 301 at 15-second intervals
samples where proxima-d-db was not `running`   :  0
```

The whitelist was agreed with the orchestrator before the window opened: `proxima-d-db` (the
database under test) and `buildx_buildkit_g-agent-builder0` (pre-existing infrastructure, 0.00 %
CPU, created six weeks ago). **Everything else aborts the run.**

⭐ **A guard that has never refused anything is a claim, not an instrument** — `R17` §7 counts five
in this repository that have reported into nothing. Two things show this one is capable of firing:

1. **It reported a false positive, on every call, before any run counted.** The first version
   matched `bin/java` against `ps -eo args=` output — and `ps` prints grep's own command line, so
   `foreign()` returned a hit every time. **It would have aborted all fifteen arms and looked
   exactly like a contaminated machine.** Fixed with the bracket trick (`[b]in/java`) before the
   session started. ⚠️ **A guard's false positives are not the harmless direction:** I would have
   believed it.
2. **It had a hole that a false negative walked through, and the hole fired.** The guard watched
   for something foreign **arriving**. It did not check the system under test was **still there** —
   and at 15:06:13 the database container stopped. The guard reported a clean machine while the
   subject was gone; the failure surfaced as `Connection refused` at Flyway and cost two attempts.
   **The symmetric check — assert the required thing is still running — is the half that was
   missing**, and it is the better sentence of the two. It is built into the `R33` pinning run, and
   for the arms it was retrofitted as a session-long sampler because editing a running bash script
   corrupts it.

⛔ **Two things this section does not claim.** The machine was quiet **as measured by the process
table and the container list** — no CPU, memory-bandwidth or thermal measurement was taken, so
"nothing was running" means "no process or container I did not put there", not "the machine was in
an identical physical state throughout". And the **count-only** numbers in §3.4–§3.7 were
deliberately taken while other work *was* running, because none of them is a duration.

### 3.9a ⭐ One instrument failed in the direction that confirmed my hypothesis

This is separate from the five count errors in §8.4 and it is a different class, so it is here
rather than buried with them.

```
sed -n 's/.*"value":\([0-9.E]*\).*//p'      <-- [0-9.E]* cannot match a leading minus
against "value":-1.0  ->  captures the empty string
                      ->  the census prints 미측정(gauge-absent)
```

**My harness printed this repository's phrase for *nobody measured this* over a number that had
been measured and was `-1`.** `R33`'s central claim was wrong for several hours because of it, and
the corrected finding turned out to be a worse defect than the one I had claimed.

⛔ **It failed in the direction that agreed with me.** I expected the gauges to be gone; the bug
said they were gone. **A reading that contradicts you gets investigated. A reading that confirms
you gets published.** There is no natural detector for that.

⛔ **And what caught it was luck plus attention, not a check.** A later run printed raw JSON for an
unrelated reason and a gauge answered that I had just written could not answer. **No instrument in
this repository would have caught it, none does now, and the next one will not have a stray
printout beside it.** Ledger entry 33.7.

**The general form:** the harness only ever asked *"did my parser produce a number?"*, and that
cannot distinguish **"the metric is missing"** from **"the metric is there and I could not read
it"**. Reading the status code beside the value is the fix, and it is what re-took every gauge
figure in `R33` §3.1 and re-verified `R29` §6.

### 3.10 Comparisons this slice declines to make

⛔ Three, and each is refused rather than omitted:

1. **Against `R2`, `R4`, `R16` or `R18`.** Those are PostgreSQL **16.14**; every arm here is
   **16.15**. `R27` compared 16.14-musl against 16.15-**glibc**, a different pair, so **the pair
   these numbers straddle has never been calibrated in this repository.** Ledger entry 29.6 prices
   removing the refusal.
2. **Arm V against arm A′ on latency.** Both arms' own spreads exceed the difference between them.
   `R29` §3.3's rule applies: a difference smaller than the drift is not an effect.
3. **Anything against arm A finer than 1.83×.** Arm A's three publishable runs vary by that much,
   each having passed the within-run steady-state gate. ⚠️ **The cause is 미측정**: a page cache
   filling after the 15:06 container restart is a candidate, but the sequence falls and then
   rises, which warming alone does not explain. Arm A′ is the same configuration run last, and
   the A→A′ difference is what bounds the cross-arm comparisons.

---

## 4. REPORTS WRITTEN

| # | Title | One line | §8 non-empty |
| --- | --- | --- | --- |
| `R29` | The queue forms where nobody was looking, and the client is never told | 200 workers against 10 connections at 200 VU: the client gets no refusal, no timeout and no log line — only latency, most of which is a worker waiting rather than working | **yes — 48 lines** |
| `R30` | A maximum that cannot be reached, and the queue that makes it so | `max` and `queue-capacity` are one setting with two names; an unbounded queue makes the maximum unreachable by construction, and this tree's executor has both | **yes — 20 lines** |
| `R31` | The thread changed and the transaction did not follow | `R1`'s sibling: there the proxy was never applied, here it is applied and the context is on the wrong thread — and both fail into the same silent per-statement commit | **yes — 21 lines** |
| `R32` | A slice titled three pools had five, and the fourth belongs to the whole JVM | `ForkJoinPool.commonPool` is per-JVM, sized by the box, named in no file — and one caller blocking in it serialises an unrelated caller with no exception and no log | **yes — 21 lines** |
| `R33` | Virtual threads moved the instruments, not the bottleneck | turning them on leaves the queue exactly where it was, takes `executor.*` away entirely and leaves `tomcat.threads.*` **answering `-1`** | **yes — 53 lines** |

**All five §8 sections are non-empty and were checked with `docs-consistency.yml`'s own CHECK 4
logic re-run locally** — the line counts above are that check's own counts, not an assertion.

`ADR-018` — *A pool nobody configured is still a pool this repository operates, so the defaults
become assertions rather than settings*. It carries the decision for all five pools, the
defect-or-specification verdict `R30` asks for, and its own *what was not measured* section.

---

## 5. GATES AND CI

⛔ **No workflow ran, and none could have: I was instructed not to push, so nothing on
`round3/pools` has ever reached a runner.** Saying "green" about a job that never executed is the
vacuous-pass shape this repository keeps collecting, so the table below reports what was **run
locally as a proxy** and names the gap.

| Workflow | Ran on a runner | Reproduced locally | Result | Changed by me |
| --- | --- | --- | --- | --- |
| `build.yml` | **no — not pushed** | yes: `./gradlew :api:test :seed:test --rerun-tasks`, which is the substance of its four Gradle steps | **both runs green**, locally: `BUILD SUCCESSFUL`, `:api:test` 140 then 142, `:seed:test` 14, zero failures either time. ⚠️ **This is not a runner result** — see the note below the table | **no** |
| `docs-consistency.yml` | **no — not pushed** | CHECK 1 and 4 re-implemented as shell against the working tree; CHECK 3 by inspection; CHECK 2 not run, see the next column | **CHECK 1 green** (re-run locally: every backticked artefact in all five reports, `ADR-018` and this handoff resolves to a tracked file or a declared type). **CHECK 4 green** (§8 line counts 48/20/21/21/53, all ≥ 3). **CHECK 3 RED and expected** — five reports, no roadmap rows, and I am forbidden to add them. **CHECK 2 green**, re-run after committing — it needs git history, so it could not be evaluated while the files were untracked. All seven documents report `ok`, their dated line matching their last substantive commit. ⚠️ **This handoff nearly poisoned that check with its own prose**: an earlier version of this cell quoted the literal dated-line marker, CHECK 2 greps for that marker anywhere in a file, and it matched here — passing only because the date happened to be right. Reworded so the token does not appear; a document that is not dated should not accidentally claim a date | **no** |
| `secret-scan.yml` | **no — not pushed** | no — `gitleaks` was not run | **미측정.** Nothing in this slice adds a literal credential; the only new property values are `true` and `copy-mdc` | **no** |
| `no-learner-data.yml` | **no — not pushed** | the `pre-commit` hook enforcing the same file class ran on **every** commit | no data file is committed; `load/out/` is gitignored | **no** |
| `image-pin.yml` | **no — not pushed** | no | not touched by this slice; ⚠️ but see §8.6 | **no** |
| `load-harness.yml` | **no — not pushed** | no | **it has no reason to run: `load/` is unmodified** | **no** |
| `study-consistency.yml` | **no — not pushed** | no | `.study/` is unmodified | **no** |

⚠️ **The honest summary is that this slice's CI status is 미측정 and the integrator must not read
this section as green.** The local proxy for `build.yml` is strong — same Gradle tasks, same
toolchain, a real PostgreSQL through Testcontainers — and it is still not a runner. `R9` §3.6 and
`ADR-004` rule 9 are this repository on exactly that distinction.

**I changed no workflow file.** `load-harness.yml` was the one I was permitted to touch and it
did not need touching: it already loops over `git ls-files 'load/**.sh' 'load/*.sh'` for the
executable bit and over `load/ops/*.sh` for parse errors, so it would have covered a new script
automatically — and I added no script to `load/`, for the scope reason in §6.

**`docs-consistency.yml` CHECK 3 fails on this branch and that is expected and correct.** It
requires a `docs/roadmap.md` row per report; I added five reports and am forbidden to touch
`docs/roadmap.md`. ⛔ It must not be closed by editing the roadmap from this branch — §8 carries
the exact rows for the integrator to paste in one pass. CHECK 1 and CHECK 4 are green, re-run
locally; **CHECK 2 was not run** for the reason in the table above.

**New test classes:** `PoolCensusGateTest`, `ThreadPoolBoundsTest`, `SharedPoolTest`,
`AsyncBoundaryTest`, `AsyncBoundaryWithContextCopyTest` and `VirtualThreadPinningTest` — all in
`api/src/test/kotlin/net/gseek/proxima/ops/`. **None asserts a duration**, per `ADR-004` rule 2:
every assertion is a pool size, a thread name, a row count, a rejection count or a JFR event count.

⛔ **None of them has ever refused an edit, and they should be counted as promises rather than as
paid gates.** `R0` §4 keeps that distinction and `README.md` has already blurred it once.

---

## 6. WHAT I DID NOT DO

**Declared reductions, in order of how much they cost the slice.**

1. **I added no script to `load/` and the reproduction lives in the reports instead.** The brief
   scoped me to `api/src/**/ops/**`, configuration and `.github/workflows/load-harness.yml`.
   `load/ops/` is none of those. So `R29` §2 carries the literal `java -jar` and `./load/run.sh`
   commands, and the driver that ran the five arms stayed in a scratch directory and is not in
   the tree. **The consequence is real: `R29`'s arms are reproducible from the report by hand and
   not by running a committed file**, which is weaker than `R24`'s `trap-pool.sh`. The integrator
   may want that script; it is not mine to add.
2. **I did not fix `load/recommendations.js`'s inverted banner**, found in `R29` §3.1. When the
   steady-state check fails because the *first* half was slower, the banner says the *second*
   half was. The verdict is correct and the sentence naming which half is not. Same scope reason.
   `OPEN-D3`.
3. **I did not add an across-run steady-state check**, which is `R29` §3.3's finding and the
   larger of the two: three runs each passed the within-run gate while the arm's medians varied by 1.83×.
   `load/` again. `OPEN-D2`.
4. **I did not sweep concurrency.** Every latency number is at **200 VU**, so this slice has found
   points and not a curve — exactly the hole `ADR-014`'s ledger entry 2 already names for `R2`,
   `R4`, `R16` and `R18`. Adding a sweep would have multiplied a measurement window that two other
   slices were waiting on.
5. **I did not measure what `server.tomcat.mbeanregistry.enabled=true` costs.** It is on in every
   arm, so the arms are comparable with each other and none of them prices the flag against
   `false`. It ships in the green commit on an unmeasured cost, which is stated in `ADR-018` and
   in `R29` §8 rather than glossed.
6. **I did not run any arm inside a container.** Every number is one process on the host with no
   memory or CPU limit, so `measurement-discipline.md` rule 10's three container lines are
   **omitted rather than filled with `unlimited, 1`**. `R23` and `R24` are the reports that vary
   those, and `R32` §4.1 names what a CPU limit would do to pool 4 without measuring it.
7. **I did not migrate anything to virtual threads.** The brief scoped D5 to turning it on and off
   under the same load and that is all that was done.
8. **I added no endpoint.** `ADR-009` stands. `PoolCensus` is reachable from tests only, which is
   why `R29`'s pool occupancy comes from actuator gauges and a JVM-side census rather than from a
   diagnostic route.
9. **No migration.** `V5` is still the highest.

### Two measurements I had to take twice, and why that is here rather than hidden

⭐ **`R33` §3.3's application arm was run twice, and the first run's result was a zero that measured
nothing.** The load driver built curl command lines as text and passed them through
`xargs -I{} sh -c "{}"` — and **xargs processes quotes in its input**, so the single quotes around
`Authorization: Bearer <token>` were stripped before `sh` ever saw them. curl received an empty
`-H Authorization:` and treated the token and the URL as separate URLs. **All 6,000 requests came
back `{"error":"missing-token"}`.** The application answered 401 without touching JDBC, so
"0 pinned events" was an instrument placed where nothing passed.

**It was caught by reading the response bodies rather than trusting the zero**, and the re-run
**asserts its precondition before it measures**: one authenticated request must answer 200 with a
body, and 90 % of the batch must answer 200, or it refuses to report. That is `ADR-015`'s rule —
a race test proves its own precondition — applied to a load driver.

⛔ **`R5` §3.3 is the identical failure, and I had quoted it in `R33` §3.1 before making it.**
Quoting a lesson does not confer immunity to it.

**And the JDK-level probe was wrong the first time too.** Its sixteen virtual threads shared **one
static monitor**, so they excluded each other and the batch serialised whether or not anything was
pinned — sixteen waves for `synchronized` *and* sixteen for `ReentrantLock`. **Two arms returning
the same number is a signal that the instrument is measuring something other than the variable**,
and that is what it was: mutual exclusion, not carrier occupancy. `VirtualThreadPinningTest`, the
version that is in the tree, gives every thread its own monitor and counts JFR events instead.

---

## 7. NEW UNMEASURED

Entries for `ADR-014`'s ledger, in that ledger's own format. Class `a` = measurable and not done,
`b` = needs a second party, `c` = not a quantity. Cost in minutes, Flip is H/M/L.

### `R29` — the queue forms where nobody was looking

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 29.1 | the knee: every arm is at 200 VU | **a** | 180 | M | duplicates ledger entry 2 from a fourth direction. **The drift band here is 1.83×**, wider than at `R18`'s 1.27×, so this machine may not be able to answer it |
| 29.2 | what `server.tomcat.mbeanregistry.enabled` costs | **a** | 45 | M | it ships in the green commit on an unmeasured cost. One arm with it `false` and pool occupancy taken from `pg_stat_activity` instead |
| 29.3 | no across-run steady-state check exists | **a** | 60 | **H** | `R29` §3.3: three runs each passed the within-run gate and the arm slid 1.83×. `R18` fixed the within-run check and nobody added the other. `OPEN-D2` |
| 29.4 | `recommendations.js`'s failure banner names the wrong half | **a** | 15 | L | verdict correct, sentence inverted. `R29` §3.1. `OPEN-D3` |
| 29.5 | `workers × instances` has no ceiling to break against | **a** | 90 | M | `R24` established `pool × instances`; nothing bounds worker threads across a fleet, so there is no arithmetic to gate |
| 29.6 | the arms straddle PostgreSQL 16.14 → 16.15 and the pair is uncalibrated | **a** | 120 | M | `R27` compared 16.14-musl against 16.15-**glibc**, a different pair. Until somebody runs one arm on each image, **no number of this slice's may sit beside `R2`, `R4`, `R16` or `R18`** |

### `R30` — a maximum that cannot be reached

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 30.1 | no task has ever gone through the **real** `applicationTaskExecutor` | **a** | 60 | M | the mechanism is measured on an executor the test built. The real bean's three numbers are asserted and its behaviour is not |
| 30.2 | `RejectedExecutionHandler` behaviour is unmeasured | **a** | 90 | **H** | ⭐ `CallerRunsPolicy` on a bounded queue would run async work **on the request thread**, deleting the `@Async` boundary with no annotation changing — and making the transaction cross *intermittently*. `R30` §8 and `R31` §8 found this from opposite sides without measuring it |
| 30.3 | whether Boot's `core-size` follows `availableProcessors` in a container | **a** | 45 | L | `R23` found the heap doing so; nothing checked this |

### `R31` — the thread changed and the transaction did not follow

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 31.1 | §5.1's "a transaction cannot cross" is a construction proof, not a measurement | c | — | — | no arm tried to defeat it |
| 31.2 | partial failure across several async writes | **a** | 90 | M | one async call and one row were measured. A real feature has several, some committed and some not |
| 31.3 | `@Async` under load, against pool 3's unbounded queue | **a** | 120 | M | the boundary was crossed by a test, never by 200 concurrent requests. `R30` §8 records the same hole |
| 31.4 | whether the `TaskDecorator` is applied to the **virtual-thread** executor | **a** | 30 | M | `spring.threads.virtual.enabled=true` replaces the executor (`R33` §3.1); nothing checked the decorator survives the swap |
| 31.5 | no log line was read | **a** | 20 | L | the MDC is asserted through `MDC.get`, not by reading what the appender wrote. A correlation id in the MDC and absent from the pattern is unchecked |

### `R32` — a slice titled three pools had five

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 32.1 | `parallelStream()` contention against a **request path** | **a** | 90 | M | §3.3 starves one test thread with another. 200 concurrent requests were not tried |
| 32.2 | pool 4's parallelism under a CPU quota | **a** | 45 | M | `R23` found the heap following a cgroup. Inside a 2-CPU container `parallelism` would be 1 and `parallelStream()` a slower sequential stream |
| 32.3 | ⭐ `CompletableFuture`'s no-executor overloads use the same pool | **a** | 60 | **H** | `supplyAsync(fn)` with no executor is the common pool, and it appears in code with no stream in it. A far more common entry into this defect than `parallelStream()`, and completely unmeasured |
| 32.4 | no dependency's use of the common pool was checked | **a** | 60 | M | the search covered this repository's source, not its classpath. A library holding the pool reproduces §3.3 with nothing in the tree to find |
| 32.5 | `ManagedBlocker` compensation was avoided, not measured | **a** | 45 | L | §2.1 explains the choice. How far the pool grows through a synchroniser, and whether it shrinks back, is unmeasured — §5 option D is rejected on reasoning |

### `R33` — virtual threads moved the instruments

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 33.1 | ⭐ the observability claim's *consequence* is unmeasured | b | — | — | that the gauges return 404 is checked. That an operator would therefore miss the incident is an argument: no dashboard was built, no alert rule tested, nobody paged |
| 33.2 | arm V degrades across its own runs and why is unmeasured | **a** | 90 | M | 943.9 → 604.2 → 567.6 req/s, spread 1.66×. Candidates: heap pressure from continuations, a scheduler effect, something else. ⭐ **The gauges that would narrow it down are the ones this report shows report `-1` in that configuration** |
| 33.3 | `Thread.sleep` unmounts a virtual thread and a blocking client call may not | **a** | 120 | **H** | the gateway's 150 ms is the largest component of the request and it became cheap to wait on under virtual threads. A real HTTP client would not necessarily behave the same, so arm V may flatter the configuration |
| 33.4 | the pinning search covered this repository's source, not its classpath | **a** | 60 | M | zero `synchronized` in `api/src/main` and `seed/src/main` is a fact about code written here. The JDBC driver, the pool, the container and the ORM were probed only by running them |
| 33.5 | the carrier pool's actual size was never read | **a** | 20 | L | `jdk.virtualThreadScheduler.parallelism` is recorded as *unset*, which is not the same as reading what the scheduler resolved it to |
| 33.6 | how far the virtual-thread scheduler compensates for a pinned carrier | **a** | 60 | M | `VirtualThreadPinningTest` prints this rather than asserting it, because asserting it would be writing a JDK internal from memory |
| 33.7 | ⭐ **no instrument in this repository distinguishes "metric missing" from "metric unreadable"** | **a** | 45 | **H** | every harness here asks *did my parser produce a number*. That cannot tell a `404` from a value the regex failed on, and they are opposite findings. `R33` §3.1.1 is what it cost. The fix is to read the status code beside the value; nothing does yet |
| 33.8 | the pinning sample is 200 requests, not the 4,000 intended | **a** | 45 | M | the drive was bound by curl process creation. Wherever the zero appears the 200 appears with it, but a proper sample would use one client process |

### Ledger entries this slice CLOSED

**None.** ⛔ Nothing in `ADR-014`'s ledger was closed by this work, and no entry of it should be
marked closed on account of this slice. Entry **2** (*"the knee is unmeasured; every load number
is one point"*) is **reinforced, not discharged** — this slice adds a fourth family of numbers at
a single concurrency level, and 29.1 says so.

---

## 8. FOR THE INTEGRATOR

⛔ I edited none of `README.md`, `docs/roadmap.md` or `R0`. The sentences below are the ones to
place, and they are written to be pasted rather than paraphrased — a row the integrator composes
is a claim no worker checked.

### 8.1 `docs/roadmap.md` — five rows, in the round-three table

⭐ **`docs consistency` CHECK 3 is red on this branch until all five are added.** It requires a row
per report and I am forbidden to touch the file. Adding four of five leaves it red.

Paste verbatim. Each row is `| **R{n}** | **Title.** what it was about | state and the numbers |`,
matching the rows already in the file.

| **R29** | **The queue forms where nobody was looking, and the client is never told.** `R2` and `R18` both measured the connection pool from the database side; neither wrote down the number on the other side of it. Tomcat's `maxThreads` is 200, HikariCP's pool is 10, and **no file in this repository names the first of them** | **done** — `R29`, no red/green: one jar, one session, five arms and a drift control. At 200 VU with 200 workers against 10 connections, **~90–115 workers are blocked waiting for a connection at any instant, the error rate is 0.00 %, every request answers 200, and each arm's JVM logs 54 lines of which zero are WARN or ERROR.** Refusal, timeout or silently-slower: it is the third, evidenced by absence. **`hikaricp.connections.acquire` measures the wait at 143.0 ms against a hold of 14.9 ms — nine times longer waiting than working** — and nothing timed out in ~1.7M acquisitions. ⭐ **The received remedy is the trap**: 20 workers against 10 connections is **5.07× less throughput** and p50 1547.6 ms, with **every pool gauge reading healthy** — `pending` 0, one connection of ten in use, workers 20/20 which reads as efficiency. The queue moved into Tomcat's accept backlog where this application exposes no metric. The drift control moved **1.09×**, which is what lets 2.08× be claimed and 1.08× be refused. Ships `server.tomcat.mbeanregistry.enabled=true`, without which three of those gauges **do not exist** — cost 미측정 |
| **R30** | **A maximum that cannot be reached, and the queue that makes it so.** This tree's `applicationTaskExecutor` is `core=8 max=2147483647 queueCapacity=2147483647`, and nothing sets `spring.task.execution.*` | **done** — `R30`, **no green commit and the refusal is the decision** (`ADR-018`). `ThreadPoolExecutor` grows past core **only when the queue refuses a task**, so an unbounded queue makes the maximum unreachable by construction — **`max` would be equally decorative if it read 200**, which is what a person writes. Measured on the same class Boot builds pool 3 from: unbounded queue → pool stays at **core**, 18 of 20 tasks queued, **0 rejected**; queue bounded at 2 → pool reaches **max**, and **10 of 20 tasks are refused**. The same overload delivered as a refusal instead of as latency and heap. Not bounded here because **nothing calls the pool** — the condition is recorded instead, and `PoolCensusGateTest` makes the first `@Async` caller trip it |
| **R31** | **The thread changed and the transaction did not follow.** `R1`'s sibling, reached from the opposite direction | **done** — `R31`. In `R1` the annotation did nothing because **the proxy was never applied**; here the proxy is applied, the interceptor runs, and the work still leaves the transaction behind because **the thread changed**. Neither is visible to a test that asserts on a return value. Measured across one `@Async` hop: **no transaction, no request attributes, no MDC, no `ThreadLocal`** — and a `@Transactional` caller that writes two rows and throws **loses one and keeps the other**, because the async write commits through `SimpleJpaRepository`'s own transaction. ⭐ **Both reports fail into the same silent per-statement commit**, which is `R1` §4's sentence arrived at a second way. Ships the `TaskDecorator` that carries the MDC and **deliberately not** the one that would carry request attributes — the request they belong to may already be recycled, which replaces a loud null with a quiet wrong answer. **The transaction cannot cross and no decorator can carry it** |
| **R32** | **A slice titled three pools had five, and the fourth belongs to the whole JVM.** `ForkJoinPool.commonPool` is one per JVM, sized `availableProcessors − 1`, configured in no file here and in no file anywhere | **done** — `R32`, **no green commit for the application**: there is no `parallelStream()` to remove, and `ADR-018` keeps it out of every request path. Every figure is a **count**, so it needed no measurement lock and was taken while the machine was busy. `parallelStream()` runs on the common pool **and on the calling thread**, so peak concurrency is `parallelism + 1` and a request thread that calls one is *inside* the pool rather than waiting on it. Blocking does not grow it — a `ForkJoinPool` compensates for a `ManagedBlocker` and nothing else. ⭐ **One caller blocking in it collapses an unrelated caller's parallel stream to a single thread — its own — with no exception, no log and nothing in any configuration to explain it.** The remedy arm is measured while the contention is still present. §8 names `CompletableFuture`'s no-executor overloads as the commoner door into the same defect, unmeasured |
| **R33** | **Virtual threads moved the instruments, not the bottleneck.** This repository runs JDK 21 and used virtual threads in zero places; `R29` left 200 workers alive with most of them blocked | **done** — `R33`, no red/green: arm V is `R29`'s jar with one property flipped. ⭐ **The bottleneck neither disappeared nor moved** — it was already the connection pool and a scheduler cannot manufacture an eleventh connection. **The latency difference is 1.08× against a drift control of 1.09× and is REFUSED in both directions**, the way `R18` §3.3 deleted one of its own four conclusions. What reproduced instead is an **observability** defect, and **not the one the report's first draft claimed**: under `spring.threads.virtual.enabled=true`, `executor.pool.core` and `executor.pool.max` **answer 404**, while `tomcat.threads.busy`, `.current` and `.config.max` **answer HTTP 200 with the value `-1`**. ⭐ **The second half is the worse one** — `-1` is a number, a dashboard plots it, and a rule that pages above a threshold never fires. **An absent metric is at least honest about being absent.** §3.1.1 of that report records that the slice's own harness reported the `-1` as `미측정` because its regex could not match a minus sign, and that the bug failed in the direction that confirmed the hypothesis being carried. Pinning conditions were measured on this JDK rather than recalled, by counting `jdk.VirtualThreadPinned` JFR events; this repository's own code contains **zero** `synchronized`, so the search the trap asks for has no subject and the question moves to the dependencies |

### 8.2 `docs/roadmap.md` — the Status line

The Status line currently reads *"…twenty-nine in total (`R0`–`R28`)"*. With this slice alone it
becomes `R0`–`R33`; with the other three slices of the round it will be higher, so **do not paste a
total from this handoff** — count the tree once, in the single pass that adds every row.

### 8.3 `README.md`

⚠️ **I did not read `README.md` for this purpose and I am not proposing a diff to it.** What
follows is the content a `README` sentence would have to carry; the integrator should place it in
whatever section already exists rather than take my wording as a location.

> **The connection pool was measured twice from the database side, and the number on the other
> side of it was in no file.** There are 200 web server worker threads against 10 connections, and
> at 200 concurrent users roughly 100 of those workers are blocked waiting — with a 0.00 % error
> rate, every request answering 200, and nothing in the log. Sizing the worker pool down to match
> the connection pool is **5.07× less throughput**, and it makes every pool gauge read healthy
> while it does it.

⛔ **One thing the `README` must not gain from this slice: a claim that a gate fired.** `R0` §4
counts guards that have ever refused anything, and `README.md` already asserts three later firings
with no report behind them (`ADR-014` ledger `0.3`). **None of my five new test classes has ever
refused an edit.** They assert the present state so that a future one trips them; that is a
promise, not a payment, and it should be counted as one.

### 8.4 `R0` — the scorecard

`R0` §*Round two* carries a per-slice table with the columns
`slice | reports | self-inflicted traps recorded | caught by`. This slice's row, for the round-three
equivalent:

> | **D** — the five pools | `R29` `R30` `R31` `R32` `R33` | **6** | a quiet-guard reporting a false positive on every call before any run counted; a steady-state gate refusing the *fastest* run of an arm; a load driver whose 6,000 requests were all 401s while reporting "0 pinned events"; a second driver refused by the precondition the first one's failure caused to be written; a pinning probe whose sixteen threads shared one monitor and so measured mutual exclusion; **a metrics parser that could not read a minus sign and turned a measured `-1` into `미측정`** |

**The six, expanded** — each is a mistake of mine that measurement caught, not a defect in the
application:

1. **The guard's false positive.** `ps -eo args=` includes grep's own command line, so `foreign()`
   matched `bin/java` on every call. It would have aborted all fifteen arms and looked exactly like
   a contaminated machine. Caught before any run counted.
2. **The guard's blind side.** It watched for something foreign arriving and never checked the
   system under test was still there — which is what actually happened at 15:06:13.
3. ⭐ **A vacuous measurement reported as a zero.** `R33`'s first pinning run drove 6,000 requests
   that were **all `{"error":"missing-token"}`** — `xargs` strips quotes from its input, so curl
   got an empty `-H Authorization:`. The application answered 401 without touching JDBC, and
   "0 pinned events" measured nothing. **Caught by reading the response bodies instead of trusting
   the zero** — and `R5` §3.3, which is the same failure, is *quoted in the same report*.
4. **Two arms agreeing is a signal, not a result.** The pinning probe gave 16 waves for
   `synchronized` and 16 for `ReentrantLock`; both threads shared one static monitor, so it
   measured exclusion. Identical arms meant the instrument was measuring something else.
5. **A number that was true once and carried forward.** I described arm A as sliding
   "monotonically" and it does not — `115.4 → 52.5 → 90.2`. Corrected to 미측정 with warming named
   as a candidate.
6. ⭐ **A parser that could not read a minus sign, failing in the direction that confirmed my
   hypothesis.** The arm harness read gauges with `sed -n 's/.*"value":\([0-9.E]*\).*//p'`, and
   `[0-9.E]*` cannot match a leading `-`. Against `"value":-1.0` it captured the empty string and
   the census printed `미측정(gauge-absent)` — over a number that had been measured and was `-1`.
   **`R33`'s central claim was wrong because of it**, and the corrected finding is a worse defect
   than the one I claimed: the gauges do not vanish, they report a sentinel as data. Caught only
   because a later run printed raw JSON and the metric answered when I had just written that it
   could not.

⚠️ **`R0` §4's count of gates that have ever fired does not change on my account.** Five new test
classes, zero firings. The steady-state gate in `load/run.sh` *did* fire — on arm A's run 2, and it
refused the flattering one — but that gate is `R18`'s and `ADR-008`'s, not this slice's.

### 8.5 `ADR-014` — the ledger

Add §7's five groups as new `### R29` … `### R33` sections. ⛔ **Close nothing.** Entry **2**
(*"the knee is unmeasured; every load number is one point"*) is **reinforced by this slice, not
discharged** — 29.1 is the fourth family of numbers at a single concurrency level.

### 8.6 `measurement-discipline.md` — ⚠️ one correction that is not mine to make

The document says *"Pinned by digest since `8dec7e6`"* and then names
`sha256:57c72fd2…` as **server 16.14**. `TestcontainersConfiguration.kt` pins
`postgres@sha256:cf78e766…`, which answers **16.15**, and `git show 8dec7e6` pins `cf78e766`.
**The governing document names a digest that commit did not pin.** One document, four branches —
it belongs to the integration pass, not to a slice. Every environment block in `R29`–`R33` says
16.15 / `cf78e766…`, read from the running container.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution I performed?**

**No.** Both runs used `--rerun-tasks` and both executed. The evidence rather than the assertion:

- Gradle reported `BUILD SUCCESSFUL in 23m 23s` and `BUILD SUCCESSFUL in 20m 1s`. **A cache-restored
  `:api:test` does not take twenty minutes**, and `R9` §3.6 is why it takes that long here at all.
- The two runs disagree — 140 tests then 142 — which a restored cache could not produce.
- The counts are read from `api/build/test-results/test/TEST-*.xml` and
  `seed/build/test-results/test/TEST-*.xml`, written by the runs themselves, and the per-class
  breakdown is quoted from those files.
- Both modules are named separately everywhere the count appears. `R17` is why: this repository's
  count once read `77 tests` when that was `:api:test` alone and said so nowhere.

⚠️ **`R33`'s pinning figure came from a third execution and the first two are discarded, not
cached.** §6 has both failures. Nothing from them is in any published number.

**b. Does any number here cross machines, sessions, or a long time gap?**

**No across machines. No across sessions. One gap inside the session, and it is named.** Every
latency figure comes from one uninterrupted session on 2026-08-22 starting 15:16:17 +0900, on one
boot jar (`sha256 5b9e6892909d…`), one host, one database container.

⚠️ **Two boundaries are declared rather than glossed:**

1. **The session began on a cold page cache** because the database container stopped at 15:06:13
   and was restarted at 15:16:02. Arm A's runs vary by 1.83× and the cause is 미측정 — `hikariPending`
   115.4 → 52.5 → 90.2 across its three publishable runs, p50 spread **1.83×**. This is why arm A′
   exists. **The band that governs cross-arm comparisons is the A→A′ drift of 1.09×**, not arm A's
   internal 1.83×, because the medians are what is being compared and the control compares medians.
2. ⛔ **Every number crosses a PostgreSQL version boundary against this repository's earlier
   work.** These arms are **16.15**; `R2`, `R4`, `R16` and `R18` are **16.14**. `R27` compared
   16.14-musl against 16.15-**glibc**, a different pair, so the pair these numbers straddle has
   never been calibrated. `R29` and `R33` **refuse the comparison in their headers** rather than
   omitting it, and ledger entry 29.6 records what it would cost to remove the refusal.

**c. Did I loosen a threshold, a sample size, or an assertion to make something pass?**

**No — and the opposite happened twice, in my favour and against it.**

- `load/run.sh` refused arm A's run 2 at skew 1.50, and **the refused run was the flattering one**:
  lowest p50 and highest throughput of the three taken to that point. Admitting it would have
  improved arm A's median in every column. It was discarded and `R29` §3.1 quotes it verbatim.
- `STEADY_STATE_RATIO` was not touched. No k6 threshold was touched. No arm's run count was
  reduced: every arm ran its discard-first run plus **three publishable** runs, and where a run was
  refused another was taken rather than the count being lowered.
- The only assertion I weakened, I weakened **against** myself:
  `VirtualThreadPinningTest`'s second test prints the pinned-carrier count rather than asserting a
  number for it, because how far the virtual-thread scheduler compensates is a JDK internal this
  repository has not established, and asserting it would have been writing a default from memory.

⚠️ **One sample IS smaller than planned, and it is declared rather than presented as sufficient.**
`R33`'s application pinning run completed **200 requests against the 4,000 intended**, because 200
concurrent `curl` processes plus `-Djdk.tracePinnedThreads=full` is bound by process creation. That
is a reduction, and it is reported everywhere the zero it produced is reported — never on its own.
**It was not reduced to make anything pass**: a larger sample could only have found *more* pinning,
which would have made the finding stronger rather than weaker.

⭐ **And the two runs before it were refused rather than reported.** Attempt 1 produced
`0 pinned events` from 6,000 requests that were all `401`s and I threw it away on reading the
bodies; attempt 2 was refused by the precondition check that attempt 1's failure caused me to write.
**Neither is in any published number.**

**d. Is there any claim in a code comment that my work has made false?**

**Yes — one, mine, caught and corrected before it was committed a second time.**

`SharedPoolWork.kt`'s KDoc said *"A repository-wide search on 2026-08-22 found **zero** uses of
`parallelStream()` in `api/src/main` and `seed/src/main`"*. **That sentence was true when I wrote
it and false the moment the file was saved**, because the file it is written in is the first use.
It shipped that way in `3ab75d6`. It now reads *"before this file existed"* and states the present
count out loud.

⭐ **That is the same shape as `R27`** — a claim that goes false while every file's date is right
and no diff shows it — and it is the round's recurring failure: `77 tests`, a stale commit count, a
block count in `measurement-discipline.md`, my "monotonic slide", and now this. **Five instances in
one round, all of them a number that was true once.**

**Nothing pre-existing was falsified.** Searched before answering: no document, comment, workflow
or test outside my own new files mentions `parallelStream`, `@Async` or virtual threads, so
planting all three made no existing claim wrong. `DeploymentBoundaryGateTest`'s *"the pool is left
at HikariCP's 10"* and `measurement-discipline.md`'s pool-sizing note both remain true — this slice
changed no pool size.

⚠️ **One comment I did not touch and could have made misleading.** `api/build.gradle.kts` says
`micrometer-registry-prometheus` is there because *"a report about a pool that cannot quote the
pool's gauges is a report about a guess."* That is still true and `R29` §3.2 is what it bought —
but the same file's reasoning would now also cover `server.tomcat.mbeanregistry.enabled`, and I
left the comment alone rather than editing a build file outside this slice's scope.

**e. Did I write any version number, default value, or API behaviour from memory?**

**No. Every one was read, and here is where each came from:**

| Claim | How it was established |
| --- | --- |
| PostgreSQL **16.15**, digest `cf78e766…` | `show server_version` and `docker inspect` on the running container |
| Tomcat `maxThreads=200`, `minSpareThreads=10` | read off the running connector by `PoolCensus`, not from `application.yml`, which does not mention them |
| HikariCP `maximumPoolSize=10` | `hikaricp.connections.max` and the `HikariDataSource` bean |
| `applicationTaskExecutor` `core=8 max=2147483647 queue=2147483647` | the live bean, via `PoolCensus` |
| `ForkJoinPool.commonPool().parallelism = 7` | `ForkJoinPool.commonPool().parallelism` at run time |
| `tomcat.threads.*` need `mbeanregistry.enabled` | probed both ways: the endpoint answers `404` with it off |
| which executor `@Async` uses | the **thread name** read on the executing thread, printed by the test |
| what pins a virtual thread on JDK 21 | `jdk.VirtualThreadPinned` JFR events counted on **Temurin 21.0.12+8** |
| `ThreadPoolExecutor`'s three-case ordering | measured in `ThreadPoolBoundsTest`, not quoted |
| RDS PostgreSQL `max_connections` default | read 2026-08-22 from `docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html`, URL and date in `R29` §5.4 |
| k6 **v2.2.0**, Temurin **21.0.12+8**, Docker Engine **29.5.3** | `k6 version`, `java -version`, `docker version` |
| Spring Boot **4.1.0**, Kotlin **2.3.21** | `gradle.properties` |

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**

**No.** Nothing of that class was written anywhere in this slice. The only external organisation
named is **AWS**, in `R29` §5.4, as the publisher of a documented parameter default — with the URL
and the date it was read, the way an image digest is recorded.
