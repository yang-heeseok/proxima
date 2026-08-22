# R33. Virtual threads moved the instruments, not the bottleneck

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit / Green commit**: **neither.** Nothing in the application changed; arm V is the
> same boot jar as `R29`'s other arms with one property flipped, which is `R4` §2's argument.
> `ADR-018` records the decision that `spring.threads.virtual.enabled` stays `false`, and the
> reason is not performance.
> **Reads with**: `R29`, whose arms these are.

```
측정 환경 / Measurement environment
  Hardware       : Intel(R) Core(TM) Ultra 7 258V, 8 cores / 8 threads
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04,
                   kernel Linux 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 — JDK 21, and the JDK version is load-bearing here
  PostgreSQL     : server 16.15 — postgres@sha256:cf78e766…
                   max_connections=100, superuser_reserved_connections=3,
                   shared_buffers=128MB, max_parallel_workers_per_gather=2 (defaults)
  Framework      : Spring Boot 4.1.0, Kotlin 2.3.21
  Application    : ONE boot jar, sha256 5b9e6892909da8323c93d58c29d0bd80669bc9669eff72099666f46933516063
                   on the HOST, no container limit
  Dataset        : seed 20260810 — 3,963,719 rows, ANALYZE at 2026-08-22 05:54 UTC
  Load           : k6 v2.2.0, 200 VU, 30s warm-up DISCARDED, 3min window, via load/run.sh
  Repetitions    : first run of the arm discarded, then 3 publishable; median reported
  What else was running on the machine : NOTHING for the latency arms — `R29` §2.1 has the
                   guard. The pinning measurement in §3.3 is a COUNT and did not need it
```

> ⛔ **No number here may be placed beside one from `R2`, `R4`, `R16` or `R18`** — those are
> PostgreSQL 16.14 and this is 16.15, and nothing in this repository has calibrated the pair.
> `R29`'s header carries the full refusal.

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

This repository runs JDK 21 and uses virtual threads in **zero** places.

`R29` found the request queueing at the connection pool with two hundred platform workers alive
and roughly a hundred and fifteen of them blocked. Virtual threads are the standard answer to
that picture: a blocked virtual thread costs almost nothing, so the worker pool stops being a
scarce resource.

The question this report asks is the one that follows: **does `R29`'s bottleneck disappear, or
does it move?** The connection pool did not change.

**It does neither.** And the thing that does change is not on the latency axis at all.

### 1.1 ⭐ The gauges do not go away. Three of them start answering `-1`

Turning virtual threads on takes `executor.pool.core` and `executor.pool.max` away entirely — the
metrics endpoint answers **`404`**. That half is a nuisance: a query returns no series, a panel is
empty, and somebody eventually notices.

**The other half is the report.** `tomcat.threads.busy`, `tomcat.threads.current` and
`tomcat.threads.config.max` **keep answering `HTTP 200`, and the value is `-1`.**

> **An absent metric is at least honest about being absent. `-1` is a number.** A dashboard plots
> it. A rule that pages on `tomcat.threads.busy > 180` never fires. A capacity review reads a
> worker pool whose maximum is minus one and has no reason to look twice.

Tomcat's `ThreadPool` MBean has no `ThreadPoolExecutor` behind it once the connector runs a virtual
thread per request, so it reports `-1` for all three, and Micrometer publishes that faithfully.
**Nothing is broken and nothing is lying — every component is doing what it says.** The result is a
monitoring system reporting confidently and wrongly, which is what this repository is about.

§3.1 has the measurement. **§3.1.1 has how this report claimed the opposite for several hours**, and
that is the more useful half.

## 2. 재현 / Reproduction

Arm V of `R29`'s five, run in the same session on the same jar:

```bash
java -jar api/build/libs/api.jar \
  --server.port=8080 \
  --server.tomcat.mbeanregistry.enabled=true \
  --spring.datasource.hikari.maximum-pool-size=10 \
  --spring.threads.virtual.enabled=true
```

## 3. 계측 / Measurement

### 3.1 ⭐ The first thing that changes is that the instruments stop telling the truth

⚠️ **This section says something different from what its first draft said, and the correction is
the finding.** The draft claimed the worker-pool gauges *disappear*. They do not. Two of them do;
**three of them stay, answer `HTTP 200`, and report `-1`.**

Measured by starting the same jar twice and asking the metrics endpoint for **the status code as
well as the value** — which is the reading the first attempt did not take:

| metric | platform threads | virtual threads |
| --- | --- | --- |
| `tomcat.threads.config.max` | `200` → **`200.0`** | `200` → **`-1.0`** |
| `tomcat.threads.current` | `200` → **`10.0`** | `200` → **`-1.0`** |
| `tomcat.threads.busy` | `200` → **`1.0`** | `200` → **`-1.0`** |
| `executor.pool.core` | `200` → `8.0` | **`404`** |
| `executor.pool.max` | `200` → `2.147483647E9` | **`404`** |
| `hikaricp.connections.max` | `200` → `10.0` | `200` → `10.0` |

Identical before any traffic and after thirty requests, so this is not a gauge that populates
late. The thread dump confirms the configuration is in force: `http-nio-8080-exec-N` under platform
threads, `ForkJoinPool-1-worker-N` under virtual ones.

**So there are two different failures here and only one of them is the one people expect.**

1. **Pool 3's gauges genuinely vanish.** `executor.pool.core` and `executor.pool.max` answer `404`,
   because Boot replaces `applicationTaskExecutor` with a `SimpleAsyncTaskExecutor` that has no
   pool to measure. A query against them returns no series and a panel drawn from them is empty.
2. ⭐ **Pool 1's gauges do something worse: they answer.** Tomcat's `ThreadPool` MBean has no
   `ThreadPoolExecutor` behind it any more, so it reports `-1` for the maximum, the current count
   and the busy count — and Micrometer publishes that faithfully. **`-1` is a number.** A dashboard
   plots it. A rule that pages when `tomcat.threads.busy > 180` never fires. A capacity review
   reads a worker pool with a maximum of minus one and has no reason to look twice.

**An absent metric is at least honest about being absent.** A gauge that reports a sentinel as data
is the failure `R5` §3.3 and `R28` are both about — a missing thing rendered as a measured value —
except that here **the JVM itself is doing the rendering**, and no amount of care in this repository
would have prevented it.

### 3.1.1 And my own harness turned the `-1` into `미측정`, which is how the draft went wrong

The arm harness read every gauge with

```
sed -n 's/.*"value":\([0-9.E]*\).*//p'
```

**`[0-9.E]*` cannot match a leading minus sign.** Against `"value":-1.0` the capture group matched
the empty string, the sampler wrote an empty field, and the census printed
`미측정(gauge-absent)` — the word this repository uses for *nobody measured this*, applied to a
number that had been measured and was `-1`.

So `R29`'s arm-V census line and its `meanBusy` column say *gauge absent* where the truth is
**`-1.0`**, and both are corrected.

⭐ **The direction this failed in is the whole lesson.** I expected the gauges to be gone. The bug
said they were gone. **A measuring device whose defect agrees with the measurer has no natural
detector** — a reading that contradicts you gets investigated, and a reading that confirms you gets
published.

⛔ **And what caught it was luck plus attention, not an instrument.** A later run printed the raw
JSON for an unrelated reason, and `tomcat.threads.busy` answered — a gauge I had just written could
not answer. There was no check that would have caught this, there is none now, and the next one of
these will not have a stray printout next to it.

**The general form, for whoever reads this next:** the harness only ever asked *"did my parser
produce a number?"*. That question cannot distinguish **"the metric is missing"** from **"the metric
is there and I could not read it"** — and those are opposite findings. The fix is to read the
**status code** alongside the value, which is what §3.1's table now does and what the arms did not.
`ADR-014` entry 33.7.

### 3.2 What the numbers did

Arm V against **arm A′**, not arm A. `R29` §3.3 established that arm A's three publishable runs
vary by 1.83× with no single direction, and that **the cause of that spread is 미측정**. A′ is the
same configuration run last in the session, and the A→A′ difference is what bounds every
cross-arm comparison in both reports. Any difference smaller than that band belongs to the
session rather than to the scheduler.

| | arm A′ — platform, 200 workers | arm V — virtual threads |
| --- | ---: | ---: |
| p50 | 272.4 ms | 300.7 ms |
| p95 | 536.8 ms | 552.4 ms |
| p99 | 713.1 ms | 614.7 ms |
| error rate | 0.00 % | 0.00 % |
| **throughput** | **655.3/s** | **604.2/s** |
| mean `hikaricp.connections.pending` | 90.5 | 104.2 |
| mean `hikaricp.connections.acquire` | 143.0 ms | 99.5 ms |
| mean `hikaricp.connections.usage` | 14.912 ms | 12.793 ms |
| pool timeouts | 0 | 0 |
| mean `tomcat.threads.busy` | 198.8 | **`-1.0`** — see §3.1 |
| **p50 spread over its own three runs** | **1.27×** | **1.56×** |

**The queue is still at the connection pool.** `hikaricp.connections.pending` reads 90.5 and 104.2
— the same order — and the acquire timer says a request still waits about a hundred milliseconds
for a connection it will hold for about thirteen. Nothing moved to a different pool, because there
is no other pool that was ever the constraint.

### 3.3 Where a virtual thread gets pinned, checked rather than recalled

⚠️ The conditions were **measured on this JDK** rather than written from memory, and the
instrument is `VirtualThreadPinningTest`.

**The obvious instrument would have been a stopwatch** — block N virtual threads and see whether
the batch takes one unit of time or two. That is a duration, `ADR-004` rule 2 forbids CI asserting
one, and it would have needed the machine's measurement lock. So the instrument is instead the
JFR event `jdk.VirtualThreadPinned`, which the JVM emits when a virtual thread parks **while
pinned to its carrier**. Counting those events is a count: exact, indifferent to how fast this
machine is, and it names the condition directly instead of inferring it from elapsed time.

The event is enabled with `withThreshold(Duration.ZERO)`, because JFR's shipped settings carry a
threshold that would silently drop short pins — and a pinning test that sees nothing passes.

```
R33 §3.3 jdk.VirtualThreadPinned events: synchronized=7926  ReentrantLock=0
R33 §3.3 availableProcessors=8 attempted=16
         peak simultaneously inside: synchronized=8  ReentrantLock=16
```

| blocking inside… | pinned-park events | of 16 virtual threads, how many were inside at once |
| --- | ---: | ---: |
| a `synchronized` block | **7,926** | **8** — one per carrier |
| a `ReentrantLock` | **0** | **16** — all of them |

⚠️ **The event count is not deterministic and is not meant to be.** A second execution of the same
test reported **7,931**. The threads park in a poll loop, so the count is *how many times they
parked while pinned* before being released — a number that moves with scheduling. **What is
deterministic is the pair**: thousands against **zero**, and 8 against 16. Both runs are quoted
rather than the tidier one.

**On Temurin 21.0.12+8, blocking inside `synchronized` pins and blocking inside a `ReentrantLock`
does not.** Every thread has its own monitor and its own lock, so mutual exclusion is not the
variable — the only difference is which primitive is held while the thread parks.

⭐ **The second row is what pinning costs, as a count rather than as a clock.** Sixteen virtual
threads, eight carriers: with a monitor held, **eight of them can be inside and the other eight
cannot run at all**; with a lock held, the threads unmount and all sixteen are inside. The
scheduler did not compensate — it did not start a ninth carrier — and this report does not say how
far it *would* compensate, because that is a JDK internal nobody here has measured.

⛔ **The zero in the second row is only worth something because of the thousands beside it.** `R5` §3.3
concluded from an appender that had captured nothing and might never have been attached; the
positive arm here is what proves the recording was armed. A pinning test that found no pinning
anywhere would be indistinguishable from a pinning test that was not running.

**Then the search the brief asks for — this repository's own code, for the condition just
measured:**

```bash
grep -rn "synchronized" api/src/main seed/src/main
# (no matches -- zero uses of synchronized in api/src/main and seed/src/main)
```

**Zero.** Not "few" — none.

⛔ **So the instruction "if present, measure with them, remove them, and measure again" has no
subject here, and that is the result rather than a step skipped.** There is nothing of this
repository's own to remove. What remains is the question the search cannot answer: **the code
underneath.** A JDBC driver, a connection pool, a servlet container and an ORM are all in the
request path, none of them was written here, and any of them may hold a monitor across a blocking
call.

That is not answerable by reading this tree, so it was answered by running it:

**First, by reading the bytecode**, which is a logical fact and needed no load at all:

```
postgresql-42.7.11   483 classes   14 monitorenter   6 synchronized signatures   1 ReentrantLock
HikariCP-7.0.2        81 classes    3 monitorenter   9 synchronized signatures   0 ReentrantLock

on this application's hot path:
  org.postgresql.jdbc.PgConnection            synchronized=0   monitorenter=0
  org.postgresql.core.v3.QueryExecutorImpl    synchronized=0   monitorenter=10
  org.postgresql.jdbc.PgStatement             synchronized=0   monitorenter=0
  org.postgresql.jdbc.PgPreparedStatement     synchronized=0   monitorenter=0
```

⭐ **`PgConnection` has no monitors left and `QueryExecutorImpl` has ten.** The driver has moved
away from `synchronized` *methods* — there are none on any of the four classes — while keeping ten
`synchronized` **blocks** inside the query executor. **So the pinning condition exists on this
application's hot path**, and whether it is ever held across a park is a different question from
whether it is there.

**Then by running it.** The application under `spring.threads.virtual.enabled=true` with
`-Djdk.tracePinnedThreads=full`:

```
requests completed            : 200
responses                     : 200 x HTTP 200
pinned-park traces (reason:)  : 0
app log lines                 : 49       WARN 0   ERROR 0
hikaricp.connections.acquire  : COUNT 201
```

**Two hundred requests through the JDBC path under virtual threads produced no pinned park.** The
`COUNT 201` on the acquire timer is the corroboration that the path was really exercised — 201
connections were taken out of the pool, so the driver really did run.

⚠️ **Three things keep this from being a strong result, and they are all stated rather than one of
them being buried.**

1. **Two hundred requests is a small sample**, and it is smaller than intended: the drive was
   written for 4,000 and stopped early because 200 concurrent `curl` **processes** plus
   `tracePinnedThreads` turned out to be bound by process creation rather than by the application.
2. **It took three attempts and the first two measured nothing.** §9 has both. The first reported
   `0 pinned events` from 6,000 requests that were all `401`s; the second was refused by the
   precondition check the first one's failure caused to be written.
3. ⛔ **`-Djdk.tracePinnedThreads` reports a pinned *park*, not a pinned *section*.** A monitor held
   across work that never blocks produces no event and no problem. So this measures *"nothing
   blocked while pinned"*, which is the operationally interesting question — but it is **not**
   *"the driver holds no monitors"*, and the bytecode above says it does.

**The honest summary is that the condition is present in the dependency and was not observed to
fire under this load.** Not "the driver is safe". `ADR-014` entry 33.4 carries the difference.

## 4. 원인 / Mechanism

### 4.1 Virtual threads make waiting cheap. Nothing here was short of threads

`R29` §4.1 measured the request holding a worker for about 155 ms and a connection for 4–14 ms of
it. Two hundred platform workers were enough to keep two hundred virtual users in flight, so
**arm A was never thread-starved** — `tomcat.threads.busy` reads 200 of 200 because 200 requests
are in the server, not because a 201st is waiting.

A virtual thread costs less to block. That matters when the number of *concurrent requests* is the
constraint. Here the constraint is ten connections, and **a scheduler cannot manufacture an
eleventh.**

So the queue does not move. It is at the connection pool in both arms, and
`hikaricp.connections.pending` says so in both arms — that gauge is one of the three that survive
the switch.

### 4.2 What the switch does change, and it is not on the latency axis

| | platform threads | virtual threads |
| --- | --- | --- |
| what limits concurrency in the web tier | `maxThreads`, a number | nothing — a thread per request |
| where a blocked request's cost lands | a platform thread's stack | a heap-allocated continuation |
| **what an operator can see about it** | `tomcat.threads.busy`, `current`, `config.max` | **`-1`, and `executor.*` gone entirely** |
| what limits throughput here | the connection pool | **the connection pool** |

**Three of those four rows change and the last one does not.** The row that decides the numbers is
the row that stayed the same, and the row that changed most is the one nobody is looking at until
an incident.

### 4.3 Why the difference that did appear is not claimed

Arm V's throughput median is **604.2/s** and arm A′'s is **655.3/s** — a ratio of **1.08×**.

⛔ **That is smaller than this session's own drift control.** `R29` §3.4 ran arm A's configuration
twice, seventy-nine minutes apart, and the same configuration moved **1.09×** against itself.

| | ratio |
| --- | ---: |
| **A vs A′** — one configuration against itself, across the session | **1.09×** |
| **V vs A′** — the scheduler change this report is about | **1.08×** |

**The effect is smaller than the instrument's demonstrated ability to move on its own, so this
report claims nothing from it in either direction.** Not "virtual threads made no difference" —
**"this experiment cannot tell"**, which is a different sentence.

⭐ **And that refusal is worth more than a number would have been.** `R18` §3.3 built a drift
control because `R4` §8 wished it had one; the first time it ran it deleted one of the four
conclusions it was brought in to check. This is the same thing happening again, on a different
axis, and it is the reason §5's decision is argued from §3.1 rather than from this table.

Both arms' own spreads are wider still — 1.27× for A′ and **1.56×** for V — so even the direction
of the 1.08× is not established.

## 5. 처방 / Remedy

| Option | Effect measured | Cost | Chosen |
| --- | --- | --- | --- |
| A — leave `spring.threads.virtual.enabled` at `false` | baseline | zero | **yes** |
| B — turn it on | §3.2: **1.08×, refused.** §3.1: **`executor.*` gone, `tomcat.threads.*` answering `-1`** | the instruments `R29` needed | **no** |
| C — turn it on *and* raise the connection pool | **미측정.** The pool is what binds; a scheduler change without one is §3.2 | `R24`'s `pool × instances` ceiling | no |
| D — turn it on and add a carrier-pool gauge by hand | would restore some of §3.1 | a custom metric nobody else's dashboards know | no |
| E — move the slow call off the request thread | **미측정**, and it is the change that would make B worth re-asking | a design change | no — but see §5.1 |

**Option B is not refused on latency.** §3.2 declines to claim the latency difference at all. It is
refused on §3.1: the configuration takes `executor.pool.core` and `executor.pool.max` away
entirely, and leaves `tomcat.threads.busy`, `.current` and `.config.max` **answering `-1`** — and
those are the instruments the incident in `R29` is only visible through. ⭐ **The second half is
worse than the first**, because a `404` is a gap somebody eventually notices and `-1` is a reading
that flows into a dashboard, a threshold and a capacity review without stopping any of them.

**Option C is the honest next experiment and this report does not run it.** If the connection pool
were large enough that it stopped binding, the worker tier might become the constraint and virtual
threads would have something to move. That is a different experiment with a different arm set, and
`R18` already measured that enlarging this pool buys at most 1.94× and only when a scan is present.

### 5.1 The decision, and the reason it is not about speed

`ADR-018` keeps `spring.threads.virtual.enabled` at `false`. **The reason is §3.1, not §3.2.**

A change that leaves the numbers where they were, removes two of the gauges that would show them
moving and makes three more report `-1`, is not neutral — it is worse than neutral, because what it
damages is the thing that would tell you when it stopped being neutral. `R24` §5 made the same call about the readiness
group and phrased it as a decision for whoever operates the fleet; this one is narrower, because
here the repository *is* the operator and the instruments are the ones `R29` needed.

**What would change the decision** is not a faster arm. It is a bottleneck that virtual threads
can actually move — a request that blocks on something other than a connection this application
already limits to ten. `R29` §4.1 measured that the worker is held roughly forty times longer than
the connection, and **all of that extra holding is a `Thread.sleep` standing in for a call this
repository has never made to a real dependency.** A real one, with a real client, is where this
question deserves to be asked again.

### 5.2 What is not a remedy

**Turning virtual threads on to fix `R29`.** It is the obvious move from `R29` §3 — two hundred
threads, most of them blocked, and virtual threads make blocked threads cheap. But the threads
were never the scarce resource: the connections were, and there are still ten of them. Making the
waiting cheaper does not make the queue shorter, and §3.2 is what that looks like when it is
measured instead of assumed.

## 6. 재계측 / Re-measurement

**None, and none is owed.** Nothing in the application changed — `spring.threads.virtual.enabled`
was `false` before this report and is `false` after it, so there is no after-state to measure.
Arm V is an arm, not an after, and §3.2 is where it sits beside the arm it is comparable with.

`R18` §1 carries the same line for the same reason: *"neither, for the 2×2 — nothing in the
application changed and all four arms are the same jar."*

⚠️ **What this report did change is a test, not the application.** `VirtualThreadPinningTest`
arrives with it, and its value is entirely in the day it goes red: if a future JDK stops pinning
inside `synchronized`, §3.3's conclusion about where the pinning comes from is describing a
runtime that no longer exists, and the assertion says so in its own failure message rather than
passing quietly.

## 7. 회귀 게이트 / Regression gate

`PoolCensusGateTest`'s third test asserts `jdk.virtualThreadScheduler.parallelism` is unset, which
is a trip-wire on somebody turning pool 5 on without saying so.

**There is deliberately no gate asserting `spring.threads.virtual.enabled=false`**, and the reason
is `ADR-018`'s: the interesting property is not the flag's value but that turning it on removes
the instruments in §3.1. A gate on the flag would be enforcing a decision; what needs watching is
whether the *observability* survives, and no test in this repository can assert the absence of a
Grafana panel.

## 8. 남는 위험 / Remaining risk

- ⭐ **Arm V is the least reproducible arm in the session, and it is the one whose instruments are
  missing.** Its three publishable runs spread **1.56× on p50 and 1.66× on throughput**, and they
  move in one direction — 943.9 → 604.2 → 567.6 req/s — which is *degradation*, the opposite of
  arm A's warming. **Why is 미측정.** The candidates are a filling page cache being outweighed by
  something else, heap pressure from virtual-thread continuations, or a scheduler effect; nothing
  here distinguishes them, **and the gauges that would have narrowed it down are the ones §3.1
  shows report `-1` in this configuration.** That is the report's own thesis landing on the
  report: the arm that most needed the worker-pool instruments is the arm whose instruments were
  answering with a sentinel.
- ⭐ **The most important claim in this report is a negative one and negatives are weak.** §3.1
  says two of the worker-pool gauges answer `404` and three answer `-1`, and both are checkable.
  What it implies — that an operator would not notice the incident `R29` describes — is **not
  measured**: nobody ran a dashboard, nobody was paged, and no alerting rule was tested against
  either configuration. The measurement is a set of status codes and values; the consequence is an
  argument.
- **This arm shares every one of `R29`'s limitations.** One concurrency level (200 VU), one
  instance, no container limit, a `Thread.sleep` standing in for a network call, host CPU not
  sampled, and PostgreSQL 16.15 against a repository whose earlier latency numbers are 16.14.
  `R29` §8 is the full list and it is not repeated here.
- ⚠️ **`Thread.sleep` behaves differently under virtual threads and that changes what this arm
  measures.** On a platform thread the gateway's 150 ms `sleep` parks the thread and holds it. On a
  virtual thread it **unmounts**, freeing the carrier. So the arm is not "the same load on a
  different scheduler" — the largest single component of the request became cheaper to wait on.
  A real HTTP call through a blocking client would not necessarily unmount the same way, and
  **nothing here measured one.** `R2` §8's `Thread.sleep` caveat is inherited and made sharper.
- ⚠️ **The application pinning run is 200 requests, and the zero must never be quoted without it.**
  The drive was written for 4,000 and stopped early because 200 concurrent `curl` **processes** plus
  `-Djdk.tracePinnedThreads=full` is bound by process creation rather than by the application. It
  also took **three attempts**: the first reported `0 pinned events` from 6,000 requests that were
  all `401`s, and the second was refused by the precondition check the first one's failure caused
  to be written — **a guard earning its place within an hour of being added.** Ledger 33.8.
- ⭐ **No instrument here distinguishes "the metric is missing" from "the metric is there and I
  could not read it."** §3.1.1 is what that cost, and the fix — read the status code beside the
  value — is applied in §3.1 and nowhere else in this repository. Ledger 33.7.
- **The pinning search covered this repository's source, not its classpath.** Zero `synchronized`
  in `api/src/main` and `seed/src/main` is a fact about the code this repository wrote. Everything
  underneath it — the JDBC driver, the connection pool, the servlet container, Hibernate — was
  measured only through whether the running application emitted pinning events under load, which
  is a weaker instrument than reading the source would be.
- **The carrier pool's actual size was never read.** `jdk.virtualThreadScheduler.parallelism` is
  unset, and this report records that it is unset rather than what the scheduler resolved it to.
  How far the scheduler compensates when carriers are pinned is **미측정** and
  `VirtualThreadPinningTest`'s second test deliberately prints rather than asserts it.
- **`-Djdk.tracePinnedThreads` was used for the application arm and it is not free.** It prints a
  stack trace on every pinned park, so **no latency figure was taken from that run** and none is
  offered. The event counts are what it produced.
- **Nothing was measured about `@Async` under virtual threads.** Boot replaces
  `applicationTaskExecutor` in this configuration, and whether the `TaskDecorator` from `R31` §5
  survives the swap is **미측정** — ledger entry 31.4.
- **A single JDK.** Everything here is Temurin 21.0.12+8. The pinning conditions are a property of
  the runtime and they move between releases; a JDK that no longer pins inside `synchronized`
  would change §3.3's conclusion entirely, and `VirtualThreadPinningTest`'s first assertion is
  written to go red rather than quietly pass if that day arrives.

## 9. 배운 것 / What I learned

**계측기가 빨간불이 되는 게 아니라 사라진다는 것.**

`R29`를 재고 나서 가장 자연스러운 다음 수는 가상 스레드였다. 워커 200개 중 115개가 커넥션을
기다리며 놀고 있는데, 가상 스레드는 정확히 그 낭비를 없애라고 만든 것이다. 켜보기 전까지 내가
기대한 건 "좋아지거나, 아니면 병목이 다른 데로 옮겨가거나" 둘 중 하나였다.

실제로 일어난 건 셋째였다. **병목은 그 자리에 그대로 있고, 그걸 보던 계기가 없어졌다.**
`executor.*`는 404다 — 존재하지 않는다. 그런데 `tomcat.threads.*` 세 개는 **200을 주고 `-1`을
돌려준다.** 이게 더 나쁘다. **-1은 숫자다.** 대시보드는 그걸 그리고, `busy > 180`으로 걸어둔
알림은 영원히 안 울리고, 용량 리뷰는 최대 스레드 수가 마이너스 1인 워커 풀을 보고도 다시 안
본다. 없는 계기는 최소한 없다고 정직하게 말한다.

이게 `R5`가 쓴 이야기의 다른 판본이다. 없는 것을 0으로 적으면 측정한 적 없는 값을 발표하는
것이고, `R28`이 `Heap Fetches`에서 잡은 것도 같은 치환이었다. 그래서 하네스가 그 자리에
`미측정(gauge-absent)`을 찍게 만들었다. **그리고 그게 틀렸다** — §3.1.1. 내 sed의
`[0-9.E]*`는 마이너스를 못 읽어서, 측정된 `-1.0`을 "아무도 안 쟀음"으로 바꿔 찍었다.
**계측기의 버그가 하필 내가 갖고 있던 가설을 확인해주는 방향으로 틀렸다.**

**두 번째로 배운 것: JDK가 무엇을 pin하는지는 기억으로 쓸 수 있는 종류의 사실이 아니다.**
pinning 조건은 릴리스마다 움직인다. 그래서 `VirtualThreadPinningTest`는 조건을 주장하지 않고
**이 JDK에서 재고**, 첫 단언이 *"synchronized 안에서 막으면 pin된다"* 가 거짓이 되는 날
초록으로 조용히 지나가는 대신 빨개지도록 썼다. 그리고 이 저장소 코드에는 `synchronized`가
**하나도 없다.** 그러니 pin이 일어난다면 그건 내가 쓴 코드가 아니라 그 아래층이고, 그건 내가
고칠 수 있는 종류의 것이 아니다.

**세 번째 — 계기를 시계 대신 계수기로 바꾼 것.** pinning을 보여주는 제일 자연스러운 방법은
시간이다: "막힌 스레드 N개가 두 배 걸렸다." 그런데 그건 지속시간이고, `ADR-004` 규칙 2가 CI에서
금지하고, 다른 슬라이스 둘이 기다리는 측정 락이 필요했다. `jdk.VirtualThreadPinned` JFR 이벤트를
세면 같은 사실이 **개수**가 된다. 정확하고, 머신 속도와 무관하고, CI에서 돈다. 이번 라운드에서
반복해서 배운 게 이거다 — **어떤 질문이 정말로 시계를 요구하는지 먼저 묻는 것.**

**네 번째 — 그리고 이게 오늘 제일 값비싼 교훈이다. 나는 §3.3을 한 번 재고 "pin 이벤트 0개"라는
결과를 얻었다. 그리고 그 0은 아무것도 재지 않은 0이었다.**

부하 드라이버가 curl 명령을 **문자열로 만들어서** `xargs -I{} sh -c "{}"` 에 넘겼는데,
**xargs는 입력의 따옴표를 처리해서 벗겨낸다.** `'Authorization: Bearer <token>'` 의 작은따옴표가
sh에 닿기 전에 사라졌고, curl은 빈 헤더 `-H Authorization:` 을 받고 토큰과 URL을 서로 다른
URL로 취급했다. **6,000개 요청 전부 `{"error":"missing-token"}` 이었다.** 애플리케이션은 401을
돌려주고 JDBC를 건드리지도 않았다. 그러니 "pin 이벤트 0개"는 *pin이 없었다*가 아니라
**계측기가 아무것도 지나가지 않는 자리에 놓여 있었다**는 뜻이다.

`R5` §3.3이 정확히 이 실패다 — 붙어 있지도 않은 appender에서 이벤트 0개를 보고 "Hibernate가
아무 말도 안 한다"고 결론 내린 것. 나는 그 문단을 **이 보고서 §3.1에 인용해놓고** 같은 실수를
했다. 인용이 면역을 주지 않는다.

**잡은 이유는 0을 믿지 않고 응답 본문을 봤기 때문이다.** 재실행판은 측정 전에 **전제를
단언한다**: 인증된 요청 하나가 200과 본문을 돌려주지 않으면, 그리고 배치의 90%가 200이 아니면,
아무것도 보고하지 않고 중단한다. `ADR-015`가 race test에 대해 정한 규칙 — 자기 전제를 스스로
증명하라 — 을 부하 드라이버에 적용한 것이다.

**같은 세션에서 두 번째 같은 실수도 있었다.** `PinProbe`의 첫 판은 열여섯 개 가상 스레드가
**하나의 static 모니터**를 공유했다. 그래서 서로를 배제했고, pin이 있든 없든 16 웨이브가 나왔다.
`synchronized`와 `ReentrantLock` 둘 다 16이 나온 걸 보고서야 알았다 — **두 팔이 같은 값을 내면
그건 결과가 아니라 계측기가 다른 걸 재고 있다는 신호다.**

그리고 제일 불편한 것: **§8의 첫 항목이 이 보고서에서 제일 중요한 주장인데 그게 부정문이다.**
게이지가 404를 주거나 `-1`을 준다는 건 확인했다. 그것 때문에 운영자가 사고를 놓친다는 건
**안 재봤다** —
대시보드를 띄운 사람도, 알림 규칙을 건 사람도 없다. 측정한 것과 그로부터 주장하고 싶은 것
사이의 간격을 §8에 그대로 적어두는 것 말고는 할 수 있는 게 없었다.
