# R6. Updates lost under concurrency — and a retry that makes it worse

> **Created**: 2026-08-12
> **Updated**: 2026-08-12
> **Red commit**: `8d177b5` — the state before `MasteryCounter` existed
> **Green commit**: this one — the comparison, and the strategy the application should use
> **Status**: `T5` measured. **The application has not been changed to use the winner**; §8
> says so and says what that costs.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14, READ COMMITTED
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Contention     : 10 threads x 100 increments = 1,000, ALL ON ONE ROW
  Repetitions    : 3 runs, median reported, spread stated
  Raw output     : load/out/t5/
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A counter is incremented one thousand times. It reads 136.

Nothing failed. No exception was thrown, no constraint was violated, no line appeared in any
log. **864 increments are simply not there**, and the only way to know is to have counted
what should have been.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.LostUpdateTest
```

Ten threads, one hundred increments each, all against a single `mastery` row, against a real
PostgreSQL at its default isolation. A lost update is a race between transactions; it cannot
be produced single-threaded however carefully the statements are ordered.

## 3. 계측 / Measurement

Medians of three runs. `final` is what the counter says out of 1,000; `rejected` are
increments that raised; `lost` are increments that did neither.

| strategy | final | rejected | **lost silently** | ms |
| --- | --- | --- | --- | --- |
| read-modify-write | 136 | 0 | **864** | 896 |
| entity + `@Version` | 180 | 820 | 0 | 3273 |
| **retry INSIDE the transaction** | **135** | 865 | 0 | **3425** |
| retry OUTSIDE the transaction | 623 | 377 | 0 | 3068 |
| pessimistic lock (`for update`) | **1000** | 0 | 0 | 5018 |
| **single atomic statement** | **1000** | 0 | 0 | **977** |

Spread across runs: `final` 14–28 % depending on strategy, `ms` up to 76 % on the fastest
arm. **Every ranking above held in all three runs**; the magnitudes move, the order does
not.

### 3.1 The defect

`read-modify-write` is the only row with a number in the *lost* column, and the only row
with **zero rejections**. That combination is the finding: it is the strategy that never
tells anyone anything.

### 3.2 What `@Version` actually does

It does not save the increments. It converts **864 silent losses into 820 loud
rejections** — the counter is still wrong (180 of 1,000) but every missing increment threw.

That is a strictly better failure and a strictly worse feature. Optimistic locking is
unusable here without a retry, which is what makes §3.3 the interesting part.

### 3.3 The retry in the wrong place is worse than no retry

| | final | ms |
| --- | --- | --- |
| no retry at all | 180 | 3273 |
| **retry inside the transaction** | **135** | **3425** |
| retry outside the transaction | 623 | 3068 |

**Retrying inside the transaction recovered nothing and cost time.** It is slower than not
retrying and ends with fewer increments.

Every attempt after the first runs in the transaction that already failed, holding a
persistence context that already contains the stale entity. The one thing that must change
between attempts — the transaction — is the one thing the loop does not change. It spins,
fails identically each time, and the caller pays for all five attempts.

Moving the loop one level out recovers **4.6×** as many increments. That one level is a
proxy boundary, which is why `RetryingMasteryCounter` is a separate bean: a retry that
called `this.increment(...)` would never cross it, every attempt would run in the caller's
transaction, and it would become the broken version **while looking like the fixed one**.
That is `T3`, arriving as a consequence rather than as a subject.

### 3.4 The two that are correct

Both keep all 1,000, in every run. They differ by **5.1×** in time.

`for update` holds a lock from the read until the transaction commits, so every other
transaction waits for the whole round trip. The atomic statement holds the row only for the
duration of one `UPDATE`, and the read and the write cannot be separated because there is
nothing between them.

## 4. 원인 / Mechanism

At `READ COMMITTED`, a plain `SELECT` takes no lock. Two transactions read 5, both compute
6, both write 6. The second write is not a conflict — it is a perfectly ordinary update of a
row the writer had every right to update. **The database is behaving correctly and the
application has lost an increment**, which is why nothing reports it.

`update … set attempts_count = attempts_count + 1` never separates the read from the write:
PostgreSQL takes a row lock for the statement, re-reads the current value under it, and
adds. Concurrent statements serialise on the row for microseconds instead of a round trip.

## 5. 처방 / Remedy

| Option | Correct | Cost | Chosen |
| --- | --- | --- | --- |
| read-modify-write | **no** — 864 lost | fastest, and wrong | |
| `@Version` alone | no — 820 rejected | needs a retry to be usable | |
| `@Version` + retry inside the transaction | no — 865 rejected | **slower than no retry** | |
| `@Version` + retry outside | no — 377 rejected at 5 attempts | more attempts, more contention | |
| pessimistic `for update` | **yes** | 5018 ms | |
| **single atomic statement** | **yes** | **977 ms** | **✔** |

**The atomic statement**, for a counter. It is correct, it is 5.1× faster than the lock, and
it has no retry to place wrongly.

**What would make a different option correct.** The atomic statement works because the new
value is a pure function of the old one. The moment a write depends on something the
database cannot compute — a decision made in application code, a value from another service
— it stops being available, and the choice becomes optimistic-with-retry-outside against
pessimistic. Optimistic wins when contention is low and the retry is cheap; pessimistic wins
when contention is high, because at 10-way contention on one row the optimistic arm rejected
38 % of writes even with retries.

## 6. 재계측 / Re-measurement

| | before | after |
| --- | --- | --- |
| increments surviving, of 1,000 | **136** | **1000** |
| increments lost with no report | **864** | **0** |
| time | 896 ms | 977 ms |

The correct strategy costs 9 % more time than the broken one.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/LostUpdateTest.kt`, run by
`.github/workflows/build.yml`. It pins all six strategies, so it fails if:

- the atomic path is "simplified" into a read-modify-write — that arm asserts 1,000 exactly
- the pessimistic path loses its `for update`
- moving a retry inside a transaction stops being worse than moving it outside

**The first test also asserts that read-modify-write DOES lose updates.** A characterisation
test on a defect, which reads oddly until the alternative is considered: if PostgreSQL or
Hibernate ever changed such that it stopped losing them, this repository would want to find
out, because `R6` would then be describing a world that no longer exists — the position `R5`
found itself in.

## 8. 남는 위험 / Remaining risk

> **Discharged 2026-08-13 by `R12`, and the reason recorded below turned out not to hold.**
> All three of its premises are true and the conclusion is not: a business rule does not have
> to be a constraint, it can be a `WHERE` predicate — so a recording that would leave the band
> matches no row instead of raising, and the transaction is never poisoned. Measured on the
> application's own path, 10 threads × 100 recordings on one row: **196 of 1,000 applied
> before, 1,000 of 1,000 after.** The bullet is left standing because *the argument that kept
> a measured defect in place for three days* is worth more than a tidy edit.

- **The application still uses the second-worst option.** `AttemptRecorder.record` reads a
  `Mastery`, mutates `attemptsCount` and `score`, and saves — the `entity + @Version` arm,
  which rejected 82 % of writes here. **It has not been changed**, because the fix is not a
  substitution: `score` is computed with a business rule (`require(updated <= 1)`) that an
  atomic statement cannot express, and `V1`'s `ck_mastery_score` would turn a violation into
  a constraint error, which `R1` §9 showed poisons the transaction. **That is a design
  decision with a measured cost and it is deferred rather than guessed at.**
- **Whether that matters in production is 미측정.** This test puts ten threads on **one
  row**. Real traffic spreads across a thousand learners, so the contention per row is far
  lower and the rejection rate would be too — by how much is unmeasured, and the difference
  between "82 %" and "negligible" is the entire question.
- **Five retry attempts is a chosen number.** The outside-retry arm still rejected 377 of
  1,000; with more attempts it would reject fewer and take longer. No sweep was run.
- **Timing spread reaches 76 %** on the fastest arm (687–1209 ms). The 5.1× ratio between
  atomic and pessimistic survives the worst case in both directions (1209 against 4215,
  still 3.5×), but the millisecond figures should not be quoted more precisely than that.
- **Only `READ COMMITTED` was measured.** `REPEATABLE READ` would turn the lost update into
  a serialisation failure and change every row of the table — including making
  read-modify-write *safe but failing*. 미측정, and it is the single biggest lever not
  pulled here.
- **One row, one column, one increment.** Multi-row transactions introduce lock ordering and
  deadlocks, which this measured nothing about.
- **What would break the conclusion:** a write whose new value is not derivable from the old
  one. §5 states the condition; the atomic statement's advantage disappears entirely there.

## 9. 배운 것 / What I learned

**재시도를 잘못 둔 것이 재시도를 안 한 것보다 나빴다.** 이게 오늘 제일 놀란 숫자다. 180 → 135로
줄고 시간은 3273 → 3425로 늘었다. 재시도는 보통 "도움이 안 될 수는 있어도 손해는 아니다"라고
생각하는데, 같은 트랜잭션 안에서 도는 재시도는 **실패를 다섯 번 반복하는 값을 치르고 아무것도
회복하지 못한다.** 그리고 코드만 보면 고친 것처럼 생겼다.

한 칸 밖으로 옮기면 4.6배 회복하는데, 그 한 칸이 프록시 경계다. 별도 빈으로 만들지 않고
`this.increment(...)`로 불렀으면 **정확히 망가진 버전이 되면서 고친 것처럼 보였을 것**이다. T3에서
잰 것이 T5의 처방 안에서 다시 나왔다. 함정들이 독립적이지 않다는 걸 처음으로 몸으로 봤다.

두 번째. `@Version`을 켜면 "안전해진다"고 막연히 알고 있었는데, 실제로 한 일은 **864건의 조용한
유실을 820건의 시끄러운 거부로 바꾼 것**이다. 카운터는 여전히 틀렸다(1000 중 180). 낙관적 잠금은
그 자체로 해결책이 아니라 **재시도를 붙일 수 있게 만드는 장치**다 — R4에서 배운 것과 같은 모양이다.
거기서도 "트랜잭션 안에서 다 가져오기"는 그 자체로 아무것도 안 했고, 설정을 끌 수 있게 만드는
전제였다. **널리 퍼진 조언들은 절반인 경우가 많고, 나머지 절반은 아무도 말해주지 않는다.**

마지막으로 §8에 적은 것. 우리 애플리케이션 코드가 이 표에서 밑에서 두 번째다. 그걸 이번에 안 고쳤다 —
`score`에 붙은 업무 규칙을 원자적 문장으로는 표현할 수 없고, DB 제약으로 넘기면 R1에서 본
트랜잭션 오염이 나온다. **고칠 수 있는데 안 고친 게 아니라, 무엇으로 고칠지가 아직 결정이 아니다.**
