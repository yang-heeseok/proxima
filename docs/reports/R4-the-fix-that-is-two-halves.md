# R4. A connection pool exhausted by a default — the fix is two halves, and one alone does nothing

> **Created**: 2026-08-12
> **Updated**: 2026-08-14
> **Red commit**: `cceec6a` — the default configuration
> **Green commit**: this one — `open-in-view: false` **and** `strategy=projection`
> **Supersedes**: `R2`, which established the mechanism and could not choose a remedy. `R2`
> is kept: its reason for stopping was correct, and §5 here is what unblocked it.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : postgres:16-alpine — server 16.14
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Connection pool: HikariCP 7.0.2, maximum-pool-size=10, connection-timeout=30000 (defaults)
                   except arm C, which sets 50
  Schema         : V1 + V2 — the index from R3 is present. R2 ran without it and could not
                   separate signal from noise as a result
  Dataset        : seed value 20260810 — 3,963,719 rows
  Load           : k6 v2.2.0, 200 VU, 30s warm-up DISCARDED, 3min measurement window
  Repetitions    : 3 valid runs per arm, median reported, spread stated
  Raw output     : load/out/t1c/
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

At 200 concurrent users, ten connections are checked out, 189 requests are queued for one,
and **two of the ten are doing any work.** Median response 4.1 s, p99 9.1 s.

Nothing is misconfigured. The pool size is HikariCP's default and `open-in-view` is
Spring's.

## 2. 재현 / Reproduction

```bash
./gradlew :api:bootRun --args='--proxima.content-gateway.delay-ms=150 \
  --proxima.recommendation.strategy=entities --spring.jpa.open-in-view=true'
BASE_URL=http://localhost:8080 VUS=200 k6 run load/recommendations.js
```

Those two flags are the red state. They are the defaults at `cceec6a`; they are flags now
because the green state is what ships.

## 3. 계측 / Measurement

### 3.1 One request, before any load

```
entities   + open-in-view=true     hikari active: 1 1 1 1 1 1 1 1 1 1 0 0 0 0
projection + open-in-view=true     hikari active: 1 1 1 1 1 1 1 1 1 1 0 0 0 0
projection + open-in-view=false    hikari active: 1 1 0 0 0 0 0 0 0 0 0 0 0 0
```

Sampled every 0.5 s across a 5 s call that does not touch the database.

### 3.2 The finding that reorganised this report

**Row two.** Fetching everything inside the transaction — the standard advice, and the fix
this repository had already written and measured once — **releases nothing while
`open-in-view` is on.** The `EntityManager` is bound to the request; the connection is held
whether or not anything lazy is ever touched.

And the reverse is worse: `open-in-view=false` with the entity-returning code raises
`LazyInitializationException` (`R2` §3.1). So:

| | alone | consequence |
| --- | --- | --- |
| fetch inside the transaction | **no measurable effect** | |
| turn `open-in-view` off | **HTTP 500** | `LazyInitializationException` |
| **both** | **the connection is released** | the only working combination |

**It is one decision with two edits, and each edit alone looks like a mistake.**

This was established with **one request**, after a 40-minute load run compared two arms that
were both holding the connection and unsurprisingly tied. The rule this repository already
had — *load makes a known thing bigger; it does not identify a mechanism* — was applied to
the defect and not to the remedy.

### 3.3 200 VU, three arms, three valid runs each

| arm | configuration | p50 | p95 | p99 | error | timeouts |
| --- | --- | --- | --- | --- | --- | --- |
| **A** | default — entities, OSIV on, pool 10 | 4138.9 | 7985.7 | 9064.1 | 0.00 % | 0 |
| **D** | **projection + OSIV off, pool 10** | **2986.3** | **3857.4** | **5919.4** | 0.00 % | 0 |
| **C** | entities, OSIV on, **pool 50** | 2801.1 | 5017.9 | 6766.0 | 0.00 % | 0 |

Medians of three. Spread on p50: A 7.5 %, **D 4.4 %**, C 12.4 %. On p99: A 50 %, **D 6.5 %**,
C 27.6 %.

Against the default:

| | p50 | p95 | p99 |
| --- | --- | --- | --- |
| D | **−28 %** | **−52 %** | **−35 %** |
| C | −32 % | −37 % | −25 % |

### 3.4 Where the connections were

```
ARM A   10.0 0.0 189.0 | client backend:active=2  client backend:idle=8
        10.0 0.0 189.0 | client backend:idle=10
ARM D   10.0 0.0 181.0 | client backend:active=10
        10.0 0.0 177.0 | client backend:active=9  client backend:idle in transaction=1
ARM C   50.0 0.0 149.0 | client backend:active=37 client backend:idle=12
```

**Arm A holds ten connections to do the work of two.** One sample has all ten idle while 189
requests wait. Arm D holds ten and uses ten.

The roadmap's description of `T1` — *"the database is idle; the application times out"* —
is **correct here**, and `R2` reported it as false. Both are right about what they measured:
`R2` ran on the unindexed schema where the query took 140–555 ms and the database genuinely
was busy. The index changed which statement was true.

## 4. 원인 / Mechanism

`open-in-view` binds an `EntityManager` to the thread for the whole request. The binding
itself keeps a connection checked out — §3.1 row two shows this with no lazy loading
anywhere in the request. Every millisecond the request spends afterwards on anything else
is a millisecond a connection is held and not used.

Here that is 150 ms of gateway call against ~38 ms of query, so roughly four fifths of the
hold is waste. §3.4 measures the consequence directly: two of ten connections busy.

## 5. 처방 / Remedy

| Option | p99 | Connections to the database | Chosen |
| --- | --- | --- | --- |
| leave the defaults | 9064.1 | 10 | |
| **projection + `open-in-view: false`** | **5919.4** | **10** | **✔** |
| enlarge the pool to 50 | 6766.0 | **50** | |
| move the slow call out of the request | 미측정 | — | |

**D.** It has the best p99 and p95, the tightest spread of the three, and it achieves that
with **a fifth of the database connections** arm C needs.

**Arm C deserves its own sentence, because it is the answer everyone reaches for and it is
not absurd.** It beats the default on every percentile and beats D on the median. It loses
where `measurement-discipline.md` says the decision is made — p99, 6766 against 5919 — and
it buys its median by holding fifty connections to keep about thirty-seven busy. That is
five times the database-side footprint for a worse tail. On a database shared with anything
else, it is worse still.

**What would make C correct:** a workload whose per-request database time genuinely
dominates, where the extra connections are all doing work rather than waiting. C is a
capacity decision; D is a correctness one, and they are not alternatives — a system that
needs more capacity should still not hold connections it is not using.

## 6. 재계측 / Re-measurement

| | before (`cceec6a`, defaults) | after (this commit) |
| --- | --- | --- |
| p50 @ 200 VU | 4138.9 ms | **2986.3 ms** |
| p95 @ 200 VU | 7985.7 ms | **3857.4 ms** |
| p99 @ 200 VU | 9064.1 ms | **5919.4 ms** |
| error rate | 0.00 % | 0.00 % |
| pool timeouts | 0 | 0 |
| connections busy, of 10 held | **2** | **10** |

Same session, arms interleaved, same schema, same dataset.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/recommendation/ConnectionHoldingGateTest.kt`, run by
`.github/workflows/build.yml`. Both halves, asserted as **effects rather than as settings**:

| Assertion | What it catches |
| --- | --- |
| no `OpenEntityManagerInViewInterceptor` bean is registered | `open-in-view` back on. Boot registers that interceptor only when the property resolves true, so the bean's absence is the behaviour, not a re-read of the file just written |
| a real HTTP request returns 200 with the concept name populated | the two halves separated. If `open-in-view` goes off without the projection, this is a 500 with `LazyInitializationException` |

The second test inserts the minimum data that makes the recommendation return a row, and
asserts on the content — **an empty response would assert nothing**, which is how a gate
quietly stops guarding.

> **And the load runs above had no such guarantee. Measured 2026-08-14, `R16` §3.4: on this
> seed the rule returns items for 210 learners in 1,000, so roughly four requests in five in
> every run on this page answered `200` with an empty list.** The harness had a
> `body is not empty` check the whole time and its threshold was `rate>=0.0`, so it passed
> whatever it saw — the fifth instrument in this repository found reporting into nothing.
>
> That does not invalidate the arms: all three ran the same traffic mix and the comparison
> between them is like for like. It does mean **p50 above is the empty path and p95/p99 are
> the working one** — 21 % > 5 % > 1 % — so the two halves of this table describe different
> code. The rule that *p99 decides* chose correctly here without knowing why.
>
> `R16` also measures what `V3`'s unique constraint — added for `T6`, two days after this —
> is worth to this endpoint: **15× on p99.** These numbers are **not** comparable with
> `R16`'s, and `R16` §7 sets out why rather than printing the ratio.

### What writing this gate found

**It failed on its first run, and it was right to.** `spring.jpa.open-in-view: false` is in
`src/main/resources/application.yml`, and the interceptor was registered anyway.

`src/test/resources/application.yml` **shadows** the main file rather than adding to it:
both resolve to `classpath:/application.yml`, Spring loads the first match, and test
resources come first. The test file mentioned nothing but the datasource, so every other
setting in the shipped configuration — `open-in-view`, `ddl-auto: validate`, actuator
exposure — fell back to a framework default **in every test this repository has ever run.**

Fixed by making the test file a profile (`application-test.yml`, activated from
`api/build.gradle.kts`), which is additive. One consequence worth stating: `ddl-auto:
validate` is now actually applied under test, and the entity mappings pass it — a claim
`EntityMappingTest` had been making in its own documentation since `0a05991` **without it
being true**.

## 8. 남는 위험 / Remaining risk

- **Every measurement in this repository before 2026-08-12 ran against a test context that
  had never loaded the shipped configuration.** §7. It does not invalidate the load numbers
  here — those come from `bootRun`, which reads the real file — but it does mean the test
  suite was weaker than its own documentation claimed, for six days, and nothing noticed
  until a gate was written that asserted an effect instead of a value.
- **The steady-state check is new and has not yet refused a run.** `load/recommendations.js`
  now splits its measurement window in half and flags a run whose first half is more than
  1.3× slower than its second. That threshold is **chosen, not derived** — no distribution
  of half-ratios across good runs was collected. It would have caught the discarded `A-r1`
  by a wide margin, and where it sits relative to a merely noisy run is 미측정.
- **One concurrency level, again.** 200 VU only, and p50 of 3–4 s means every arm is far past
  its knee. Where the knee is, and whether D still wins below it, is **미측정**. `R2` raised
  this and it is still not answered.
- **Arm A's third run is not where the others are.** `A-r1` was discarded — WSL had
  restarted, the page cache was empty, and that run took **91 minutes instead of 3.5**, with
  percentiles in hours. Its replacement ran at the end of the session rather than
  interleaved, so any drift across the session lands on arm A specifically.
- **The 30-second warm-up is sized for a JVM, not for a database.** That is a defect in this
  repository's own load script: it warms code paths and a connection pool, and it does not
  warm three million rows from a cold page cache. The tell is in §3.4's format — a cold
  cache shows `client backend:active=8..10`, a warm one `active=2`. **Nothing in
  `load/recommendations.js` checks this**, and the check is now known.
- **`Thread.sleep` stands in for the slow call.** It parks the thread exactly as a blocking
  network call does, and consumes no sockets, no TLS, no second process. A real dependency
  with its own pool could change the ranking.
- **The 150 ms delay is chosen, not measured.** No real dependency was timed. The ratio of
  gateway time to query time is what makes this defect large, and that ratio is an
  assumption.
- **`mastery` is still sequentially scanned.** The query is ~38 ms, of which the two
  `Parallel Seq Scan`s on `mastery` are most. `T6` adds `unique (learner_id, concept_id)`,
  whose index would serve them — so no index was added here to be superseded in two reports'
  time. Until then, every number above sits on a query four times slower than it needs to be.
- **Option D of §5 was never measured.** Moving the slow call out of the request entirely —
  the answer that removes the problem rather than shortening it — is 미측정.
- **What would break the conclusion:** a smaller gateway delay, or a much slower query. Both
  shift the fraction of the hold that is waste, and at some ratio C's extra capacity beats
  D's efficiency.

## 9. 배운 것 / What I learned

이 리포트는 **틀린 실험을 정확하게 측정한 40분** 위에 서 있다.

`entities`와 `projection`을 비교하면서, projection이 커넥션을 일찍 반납할 거라고 **코드를 읽고**
믿었다. 두 팔은 구별되지 않았고, 나는 편차 탓이라고 생각하며 라운드를 더 돌렸다. 요청 하나를
쏴보고 나서야 알았다 — **둘 다 붙들고 있었다.** `open-in-view`가 켜져 있으면 지연 로딩을 하든 말든
커넥션은 요청 끝까지 잡혀 있다.

내가 R2에 직접 이렇게 썼다: *"부하는 이미 아는 것을 크게 만들 뿐, 기전을 알려주지 않는다. 그래서
요청 하나가 먼저다."* 그 규칙을 **결함에는 적용하고 처방에는 적용하지 않았다.** red는 요청 1개로
확인하고 들어갔는데, green 후보는 그럴듯해서 그냥 부하에 태웠다. **처방도 가설이다.**

그리고 교과서 조언에 대해. *"트랜잭션 안에서 필요한 걸 다 가져와라"*는 널리 옳고, **단독으로는
측정 가능한 효과가 0이다.** 그 조언의 진짜 역할은 커넥션을 아끼는 게 아니라 **설정을 끌 수 있게
만드는 것**이다. 아무도 그 순서를 말해주지 않는다 — 둘 다 해야 하고, 하나만 하면 각각 다른 방식으로
아무 일도 안 일어나거나 깨진다.

마지막으로 로드맵 문장. T1은 *"데이터베이스는 한가한데 애플리케이션이 타임아웃한다"*고 썼고,
R2에서 나는 **틀렸다고 반박했다.** 오늘 인덱스가 붙은 뒤에 재보니 맞다 — 10개 중 8개가 논다. 둘 다
각자 잰 것에 대해서는 옳았다. **같은 문장이 스키마 하나 차이로 참이 되고 거짓이 된다**는 게, 조건을
안 적은 성능 조언이 왜 위험한지에 대한 오늘의 답이다.
