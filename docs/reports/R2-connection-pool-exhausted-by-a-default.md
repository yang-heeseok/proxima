# R2. A connection pool exhausted by a default — and why this is not concluded

> **Created**: 2026-08-11
> **Updated**: 2026-08-22
> **Red commit**: `cceec6a` — the state in which the mechanism was observed
> **Green commit**: **none. This report does not have one.**
> **Status**: **Superseded by `R4`**, which concluded `T1` after `T4` shipped the index.
> This report is kept rather than rewritten: it established the mechanism, refused to choose
> a remedy on evidence that could not support one, and its §5 is why `T4` ran first. It also
> contains an arm comparison that `R4` showed was **measuring two arms that both held the
> connection** — see `R4` §3.2. Reading the two together is the point.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : server 16.14, and the DIGEST below is the identifier — the tag
                   `postgres:16-alpine` named this image until 2026-08-13 and now
                   resolves to 16.15. Pinned by digest since `8dec7e6`; `OPEN-10`
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
                   default shared_buffers, NOT a container limited to fewer cores
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Connection pool: HikariCP 7.0.2, maximum-pool-size=10, connection-timeout=30000 (defaults)
                   except arm C, which sets maximum-pool-size=50
  Dataset        : seed value 20260810 — 3,963,719 rows, loaded, ANALYZE run
  Load           : k6 v2.2.0, 200 VU, 30s warm-up DISCARDED, 3min measurement window
  Repetitions    : 3 runs per arm, median reported, spread stated
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

At 200 concurrent users the pool is fully checked out and 189 of the 200 requests are
queued for a connection. Median response time is between 7 and 11 seconds. Requests fail
with a pool timeout after 30 seconds.

The application is configured entirely with defaults. Nobody chose the pool size, and
nobody chose `spring.jpa.open-in-view`.

## 2. 재현 / Reproduction

```bash
# a seeded database (see seed/README.md), then:
export JAVA_HOME=$(echo ~/.jdks/jdk-21*)
export PROXIMA_DB_URL=jdbc:postgresql://localhost:55432/proxima
export PROXIMA_DB_USER=postgres PROXIMA_DB_PASSWORD=...

./gradlew :api:bootRun --args='--proxima.content-gateway.delay-ms=150'
BASE_URL=http://localhost:8080 VUS=200 k6 run load/recommendations.js
```

> **The command above is what this report ran, and it is not what to run today.** The harness
> has changed twice since: `R15` made `PROXIMA_TOKEN_SECRET` mandatory, and `ADR-008` made
> `./load/run.sh` the entry point because `k6 run` exits `0` on a run the scenario has itself
> declared unpublishable. **Following this line verbatim now bypasses that check.** The
> reproduction is left as it was, because rewriting it would claim conditions this report did
> not have.

Docker runs natively inside WSL2 here, so all of this runs there; Windows cannot reach the
daemon.

## 3. 계측 / Measurement

### 3.1 The mechanism — one request, not load

Load makes a known thing bigger; it does not identify a cause. So the first measurement is
a single request with the slow call stretched to 5 s and `hikaricp_connections_active`
sampled every 0.5 s.

```
open-in-view = true  (the default)
  active: 1 1 1 1 1 1 1 1 1 1 0 0 0 0 0 0
  HTTP 200 in 5.17s
```

**A connection is checked out for the entire slow call.** PostgreSQL does nothing during
it.

The control — the same request with the setting off — did not merely release the
connection. It failed:

```
open-in-view = false
  HTTP 500 in 0.15s
  org.hibernate.LazyInitializationException:
    Could not initialize proxy [net.gseek.proxima.domain.Concept#1015] - no session
```

**So `open-in-view` is not a performance switch with a bad default.** It is the reason this
code works at all, and the price of it working is a held connection. Any remedy is a code
change.

### 3.2 What the query costs, warm

| | runs | median |
| --- | --- | --- |
| arm A statement 1 of 2 — ids only | 184.2 / 131.5 / 149.3 ms | **149 ms** |
| arm B single statement — projection | 154.9 / 137.7 / 139.7 ms | **140 ms** |
| arm A statement 2 — primary-key lookups | 0.139 ms, 0.090 ms | negligible |

**The two arms cost the same in the database.** This was measured because the latency
algebra in §3.4 suggested arm B did roughly twice the work; it does not. The inference was
wrong and the measurement caught it.

Cold, the same statement takes **576.8 ms** — four times the warm figure. That number was
quoted in an earlier decision before being recognised as a cold-buffer artefact.

### 3.3 200 VU, three arms, three runs each

| arm | strategy | pool | p50 | p95 | p99 | error | pool timeouts |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **A** | entities | 10 | 8257.7 / 7649.5 / **7258.5** | 16319.1 / **16128.0** / 15363.3 | 23419.1 / **22222.3** / 20490.6 | 0.09–0.28 % | 8 / 26 / 28 |
| **B** | projection | 10 | 10919.7 / **10959.9** / 8668.5 | 24217.0 / **24299.8** / 20447.6 | 37043.4 / **28893.5** / 28252.4 | 0.11–0.71 % | 34 / 58 / 106 |
| **C** | entities | 50 | 5820.0 / **6448.9** / 6504.6 | 10348.7 / **12133.4** / 12652.8 | 13545.9 / **16516.9** / 17283.0 | **0.00 %** | **0** |

Medians in bold. **Spread exceeds 10 % in every arm** and reaches 31 % on arm B's p99, which
is stated rather than smoothed: at this concurrency the system is far past its knee and
small differences amplify.

Verbatim, the pool timeout:

```
WARN 96973 --- [proxima] [io-8080-exec-75] org.hibernate.orm.jdbc.error :
  HikariPool-1 - Connection is not available, request timed out after 30000ms
  (total=10, active=10, idle=0, waiting=189)
```

### 3.4 Where the connections actually were

Sampled every 5 s through the measurement window, separating client backends from parallel
workers — the distinction matters and is the reason the first reading of this was wrong.

```
ARM A   10.0 0.0 189.0 | client backend:active=6 client backend:idle=4 parallel worker:active=5
        10.0 0.0 189.0 | client backend:active=8 client backend:idle=2 parallel worker:active=3
        10.0 0.0 189.0 | client backend:active=5 client backend:idle=5 parallel worker:active=6
         9.0 1.0 190.0 | client backend:active=5 client backend:idle in transaction=1 client backend:idle=4 parallel worker:active=4

ARM B   10.0 0.0 189.0 | client backend:active=8 client backend:idle=2 parallel worker:active=5
        10.0 0.0 189.0 | client backend:active=9 client backend:idle=1 parallel worker:active=5
        10.0 0.0 189.0 | client backend:active=9 client backend:idle=1 parallel worker:active=7

ARM C   50.0 0.0 149.0 | client backend:active=47 client backend:idle=3 parallel worker:active=6
        49.0 1.0 150.0 | client backend:active=42 client backend:idle=8 parallel worker:active=7
```

**The remedy did what it was designed to do.** Idle held connections fall from ~40 % of the
pool in arm A to ~15 % in arm B.

**And the roadmap's description of this defect is wrong for this system.** `T1` says *"The
database is idle; the application times out."* It is not idle. Five to nine of ten pooled
connections are executing a query at any instant, plus four to seven parallel workers
beside them.

Derived from the measured medians:

| arm | throughput | connection held | of which database | concurrent queries |
| --- | --- | --- | --- | --- |
| A | 26 req/s | 385 ms | 235 ms | ~6 |
| B | 18 req/s | 555 ms | 555 ms | ~8.5 |
| C | 31 req/s | 1610 ms | 1460 ms | ~46 |

**Arm B releases its connection 150 ms earlier and is slower anyway**, because a statement
that takes 140 ms alone takes ~555 ms when 8.5 of them run at once on eight cores.

## 4. 원인 / Mechanism

`open-in-view` binds an `EntityManager` to the thread for the whole request. A lazy
association touched after the transaction ends therefore *works* — and acquires a
connection which, having no transaction to end, is held until the request finishes.
`RecommendationController` touches one, then calls something slow. §3.1 measures the
consequence directly.

**That is real, and on this system it is a second-order effect.** The database is the
bottleneck. Pool configuration mostly decides how much concurrency is pushed at a saturated
PostgreSQL:

- arm A's idle holding was accidentally **throttling** the database to ~6 concurrent queries
- arm B removed the throttle, ran ~8.5 concurrently, and each query slowed by more than the
  saving
- arm C pushed ~46 concurrent and got the best throughput of the three

## 5. 처방 / Remedy — **not concluded**

| Option | Effect measured | Chosen |
| --- | --- | --- |
| A — leave the default | baseline | |
| B — fetch inside the transaction (projection) | idle holding 40 % → 15 %, **latency worse** | |
| C — enlarge the pool to 50 | best latency and zero timeouts here | |
| D — move the slow call out of the request | **미측정** | |

**No option is chosen, and picking one from this table would be wrong.**

The signal this report is about — a connection held across a call that does not use the
database — is 150 ms per request. The noise it sits in — a query that takes 140 ms alone and
555 ms under contention — is the same size or larger, and it exists because
`attempt (learner_id, attempted_at)` has no index. That absence is deliberate (`ADR-002`)
and is `T4`'s subject.

```
query 140 ms  vs  gateway 150 ms   →  the held connection is half the story
query   1 ms  vs  gateway 150 ms   →  the held connection is the whole story
```

**So `T1` cannot be separated from `T4` on this schema, and the roadmap's ordering argument
runs both ways.** It says the load harness must exist before indexing numbers can be
trusted, which is true and has now been done. It did not say that indexing must exist before
pool numbers can be trusted, which is equally true and is what today measured.

`T4` goes first. `T1` returns afterwards, at which point arms A–D are re-run against an
indexed schema in one continuous session.

**Arm C is the most dangerous row in this table.** It is the answer everyone reaches for, it
won on every metric measured here, and it won by pushing 46 concurrent queries at an
eight-core machine — which is a throughput win bought with per-query latency and no headroom
left. Recording it as "best" without that sentence would be the most misleading true
statement in this repository.

## 6. 재계측 / Re-measurement

**Pending `T4`.** No before-and-after is claimed.

## 7. 회귀 게이트 / Regression gate

**None.** Nothing in CI would catch this returning.

That is not an oversight to fix quickly — there is nothing yet to protect, because no
remedy has been chosen. A gate asserting a property that has not been established would be
worse than none. It arrives with the green commit.

## 8. 남는 위험 / Remaining risk

- **The whole comparison in §3.3 is inconclusive and one arm is worse than that.** Arms A
  and B ran between 18:41 and 19:01. **Arm C ran after a 93-minute gap in which the machine
  was idle or suspended.** Measurement rule 3 forbids comparing across that boundary
  without re-baselining, and no re-baseline was done. **Arm C's numbers may reflect machine
  state rather than pool size.** A vs B is internally valid; anything involving C is not.
- **The raw measurement artefacts were lost.** k6 output and the pool samples were written
  to `/tmp`, and WSL restarted. Every number in §3.3 and §3.4 is transcribed from what was
  read at measurement time, which is weaker evidence than a file. `load/out/` is gitignored
  and exists for this; it should have been used and will be.
- **A pooled connection is not one PostgreSQL process.** The recommendation query plans with
  `Workers Planned: 2`, and four to seven parallel workers were live throughout. The pool
  sizing formula in `measurement-discipline.md` counts connections a thread holds; the
  database is sized in processes. **A `max_connections` chosen to match a pool size can be
  wrong by a factor of three**, and nothing in this repository said so before today.
- **Arm B improves two things at once and this measurement cannot separate them.** It removes
  the lazy load *and* collapses two statements into one. §3.2 shows the statements cost the
  same, which weakens the second effect, but does not eliminate it.
- **Only one concurrency level was measured.** 200 VU is far past the knee — p50 of 7–11 s
  is not a system anyone would run. Where the knee actually is, and whether the arms rank
  differently below it, is **미측정**. Measurement discipline calls this finding a point
  rather than a curve, and it is right.
- **The gateway delay of 150 ms is a choice, not a measurement.** No real dependency was
  timed. A different value moves the balance between signal and noise, and therefore
  potentially the ranking.
- **`Thread.sleep` stands in for a network call.** It parks the thread as a blocking call
  would, but it consumes no sockets, no TLS, and no second process.
- **CPU saturation is inferred, not measured.** §4 argues the database is CPU-bound from
  query times under contention. **Host CPU was not sampled during any run.** 미측정.
- **What would break the conclusion:** an index that makes the query cost negligible — which
  is precisely what `T4` will add. This report expects its own §4 to be overturned in part,
  and says so before the fact rather than after.

## 9. 배운 것 / What I learned

오늘 두 번 틀렸고 **두 번 다 측정이 잡았다.**

첫 번째는 추천 쿼리가 577ms라고 보고한 것. 콜드 버퍼에서 잰 값이었고 웜은 140ms였다. 내가 쓴
measurement-discipline 문서에 *"JVM은 첫 몇 초 동안 거짓말을 한다"* 고 적어놓고, 데이터베이스도
똑같이 거짓말한다는 걸 잊었다. 그리고 그 잘못된 숫자를 근거로 T1의 설계를 바꾸겠다고 PO에게
보고했다.

두 번째가 더 위험했다. B가 느린 이유를 지연시간에서 역산해서 *"B가 DB 작업을 2배 한다"* 는
결론을 냈다. 계산이 깔끔했고 숫자가 맞아떨어졌다. **그럴듯했기 때문에 재볼 생각을 거의 안 했다.**
재보니 두 쿼리는 149ms와 140ms로 사실상 같았다. 그럴듯한 산수는 관측이 아니다.

그런데 오늘 제일 중요한 건 내가 틀린 게 아니라 **처방이 틀린 것**이다. 교과서적인 수정 — 트랜잭션
안에서 필요한 걸 다 가져오기 — 은 설계대로 정확히 동작했다. 유휴 커넥션 점유가 40%에서 15%로
줄었다. **그리고 시스템은 더 느려졌다.** 조여놓은 것을 풀었더니 그 뒤에 있던 진짜 병목이 드러났고,
A의 낭비는 사실 우연한 throttle이었다.

이게 이 저장소가 존재하는 이유라고 생각한다. *"OSIV를 끄고 필요한 걸 미리 가져와라"* 는 조언은
어디에나 있고 옳다. 다만 **어떤 조건에서 옳은지는 아무도 안 쓴다.** 인덱스가 없는 스키마에서는
그 조언이 숫자를 악화시킨다는 걸, 나는 재보기 전까지 몰랐고 앞으로도 몰랐을 것이다.

그리고 T1을 T4 뒤로 미루기로 한 것 — 이건 후퇴가 아니라 오늘 잰 것 중 제일 쓸모 있는 결과다.
로드맵은 *"인덱싱을 나중에 하는 이유는 부하 하네스가 먼저 있어야 그 숫자를 믿을 수 있기 때문"*
이라고 썼다. 맞는 말인데 **역방향도 참이라는 걸 안 썼다.** 인덱스가 없으면 풀 숫자도 못 믿는다.
두 항목은 순서가 있는 게 아니라 서로를 필요로 한다.
