# R8. A test that counts queries

> **Created**: 2026-08-12
> **Updated**: 2026-08-12
> **Red commit**: `cceec6a` — the entity-returning read `R4` rejected, with nothing stopping
> its return
> **Green commit**: this one — the count asserted as a number

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counter        : Hibernate `Statistics.prepareStatementCount`
  Dataset        : 1 learner, 5 concepts, 5 items — inserted by the test
  Load           : none. This is a count, not a duration
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R4` chose a projection over returning entities and measured what it was worth: p99 9064 ms
against 5919 ms at 200 VU. Nothing then prevented the change being reverted.

Both versions return **identical JSON**. Every functional test passes either way. The
difference is not visible in a code review of the diff that reintroduces it, because that
diff usually looks like adding a field to a response.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.perf.QueryCountTest
```

## 3. 계측 / Measurement

| path | rows | statements |
| --- | --- | --- |
| `nextRows` — the shipped projection | 1 | **1** |
| `nextRows` | 5 | **1** |
| `nextItems` — entities, service call alone | 5 | **2** |
| `nextItems` + reading `conceptPrimary.name` | 5 | **7** |

### 3.1 The service does not contain the N+1. It hands one out.

`nextItems` costs **two statements whatever the row count** — the id query and the entity
load. Measured alone it looks efficient, and it is.

The cost appears when the caller touches the graph. In `R4` that caller was the controller,
reading one field for the response.

**A statement-count test scoped to the service would certify it as clean.** The first
version of this test was scoped exactly that way, expected 7, and got 2. The scope was the
defect in the test, and the same mistake in a real gate would have produced a passing check
over an N+1 in production.

### 3.2 Exact counts, not upper bounds

The assertions are `assertEquals`, not `<=`.

An upper bound drifts: whoever adds a statement raises the bound by one, honestly, and the
test keeps passing forever while the number climbs. An exact count makes the person who
changes it **say that they changed it**.

### 3.3 The counter has a control

`StatementCounter` fails if Hibernate statistics are disabled rather than reporting zero.

A counter that silently returns 0 turns every assertion built on it into a test that passes
because it measured nothing. **This repository has already met that failure twice** — `R5`'s
log appender captured no events and nearly proved an absence, and its
`pg_stat_user_tables` delta passed while measuring the wrong thing.

## 4. 원인 / Mechanism

A lazy association is a promise to fetch later. Each `conceptPrimary` access that has not
been loaded is one `SELECT`, so the statement count is `2 + n` where `n` is the number of
rows whose associations are touched. The projection reads the same columns in the join it
already performs, so the count is `1` regardless.

## 5. 처방 / Remedy

Not a remedy — a gate. `R4` chose the projection; this makes the choice enforceable.

| Option | Why not |
| --- | --- |
| review | invisible in the diff that breaks it |
| a latency assertion | slow, flaky, and passes on five test rows |
| **an exact statement count** | **✔** |

## 6. 재계측 / Re-measurement

Not applicable — nothing changed in the application. The number that changed is what CI
knows.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/perf/QueryCountTest.kt`, run by
`.github/workflows/build.yml`:

- the shipped read is exactly **1** statement
- its count **does not grow** with the number of rows — an N+1 fails this even if someone
  updates the constant above
- the entity path's `2 + n` is pinned too, so a change that makes it cheaper is noticed as
  well as one that makes it worse

## 8. 남는 위험 / Remaining risk

- **A statement count is not a duration.** One statement can be slower than ten; `R3` has a
  36.6 ms single statement in it. This gate cannot see a query that got worse without
  getting more numerous.
- **Only the recommendation read is gated.** `AttemptRecorder`, the mastery paths from `R6`
  and `R7`, and every future endpoint have no count asserted. **The gate covers one path out
  of everything this application does.**
- **Statistics are enabled only under the test profile.** The SQL is the same in production,
  but the counts here are taken from a JVM configured differently from the shipped one, and
  the difference is unmeasured.
- **Five rows.** The N+1 is `2 + n` and was confirmed at `n = 5`. Nothing here shows it stays
  linear at 500, though there is no mechanism by which it would not.
- **Batch inserts are not counted at all.** `OPEN-3` — whether `IDENTITY` prevents batching —
  is precisely a statement-count question, and this counter could answer it. It has not been
  pointed at it.
- **What would break the conclusion:** a second-level cache, which would make the count
  depend on what previous tests had loaded and turn these assertions flaky. There is none
  today, and adding one means revisiting every number here.

## 9. 배운 것 / What I learned

**테스트의 측정 범위가 틀리면, 통과하면서 안심시킨다.**

`nextItems`만 감싸고 7개를 기대했는데 2개가 나왔다. 서비스는 N+1을 담고 있지 않다 — 만들어서
넘겨줄 뿐이다. 컨트롤러가 `conceptPrimary.name`을 읽는 순간 비용이 생긴다. 만약 내가 기대값을
2로 고치고 "서비스는 깨끗하다"고 게이트를 걸었으면, **N+1 위에서 초록불이 켜진 검사**를 만들었을
것이다. 숫자가 안 맞아서 살았다.

그리고 상한 대신 정확한 값을 쓰는 이유. `<=`는 매번 정직하게 하나씩 올라가고, 아무도 잘못한 사람이
없는 채로 숫자가 계속 큰다. **정확한 개수는 바꾸는 사람에게 "내가 바꿨다"고 말하게 만든다.** 이건
성능이 아니라 책임의 문제다.

마지막으로 계측기에 대조군을 넣은 것. 통계가 꺼져 있으면 0을 세는 게 아니라 실패하게 했다. R5에서
로그 appender가 아무것도 못 잡은 채로 "경고가 없다"고 결론 낼 뻔했고, 같은 리포트에서
`pg_stat_user_tables` 델타가 틀린 걸 재면서 통과했다. **두 번 당하고 나니 계측기를 만들 때 제일
먼저 쓰는 코드가 "이 계측기가 죽었는지 확인하는 코드"가 됐다.**
