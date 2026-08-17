# R7. A uniqueness check two requests both pass

> **Created**: 2026-08-12
> **Updated**: 2026-08-13
> **Red commit**: `ad474d8` — `V1`, no unique constraint. Eight requests, eight rows
> **Green commit**: this one — `V3__mastery_unique_learner_concept.sql`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14, READ COMMITTED
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Contention     : 8 threads released from a CyclicBarrier onto ONE (learner, concept)
  Repetitions    : the row counts are categorical and identical across runs
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A learner has one mastery of one concept. The application checks whether it exists before
creating it. Eight simultaneous requests produced **eight rows**, and **not one of them
failed**.

Every later read picks one of the eight, arbitrarily. Increments land on different
duplicates. Nothing in the system reports that the others exist.

## 2. 재현 / Reproduction

```bash
git checkout ad474d8
./gradlew :api:test --tests net.gseek.proxima.mastery.UniquenessRaceTest
```

Eight threads wait on a barrier and are released together, so the existence checks happen as
simultaneously as this machine allows. A race that is merely *likely* would make the test
flaky in the direction of passing, which is the worst direction for a test about a defect.

## 3. 계측 / Measurement

### 3.1 Before and after

| strategy | `V1` rows / failures | `V3` rows / failures |
| --- | --- | --- |
| naive check-then-insert | **8 / 0** | 1 / 7 |
| catch the violation, re-read (same transaction) | — | 1 / **7** |
| catch, re-read, **insert isolated in its own transaction** | — | 1 / **0** |
| `insert … on conflict do nothing` | **0 / 8** | 1 / **0** |

### 3.2 The constraint does not fix the code

It converts **seven silent duplicates into seven exceptions**. The data is now correct and
the application is exactly as wrong as it was — it still races, it just loses loudly.

That is worth stating because "add a unique constraint" is usually offered as the fix. It is
the fix for the *data*. The code still needs to decide what to do when it loses.

### 3.3 What losing does on PostgreSQL

The natural repair is *"someone beat me to it — read theirs."*

```kotlin
try { save(mastery) }
catch (e: RuntimeException) { return findByLearnerIdAndConceptId(...)!!.id }
```

**It does not work: 7 failures out of 8.** On PostgreSQL a constraint violation aborts the
entire transaction, so the recovery read runs inside a transaction that can no longer
execute anything. The catch block is reached and is powerless.

`R1` §9 met this by accident while measuring `T3` — a `CHECK` violation produced
`org.hibernate.AssertionFailure: Entry for instance of 'Mastery' has a null identifier` on
the *next* statement, which says nothing about constraints. It was routed here rather than
measured there, and this is it.

Putting the insert in its own transaction confines the abort: **0 failures out of 8.**

### 3.4 The isolated insert did not isolate anything, and the `T3` gate caught it

The first version of that strategy called the inner method on `this`. It never crossed the
proxy, `REQUIRES_NEW` never started a transaction, and the "isolated" version produced
**7 failures — identical to the version it was supposed to improve on.** The numbers were
the tell, and the cause came from the gate written for `T3`:

```
Architecture Violation - Rule 'methods that are annotated with @Transactional should not be
called from within their own class' was violated:
  MasteryProvisioner.insertInNewTransaction(long, long) is called from
  MasteryProvisioner.findOrCreateIsolatingTheInsert(long, long), inside its own class,
  so the call does not reach the proxy and @Transactional has no effect
```

**A gate written three reports ago caught a defect introduced today, in work that had
nothing to do with it.** That is the first time a gate in this repository has paid for
itself, and it is recorded because gates are usually justified by argument.

**Self-invocation has now appeared inside the remedy of three separate traps** — `T3` where
it was the subject, `T5` where a retry had to move to another bean, and here. These defects
are not independent, and `T3` is the one the others rest on.

### 3.5 The same run found the gate's own false positive

```
MasteryCounter.incrementWithRetryInside(long, int) is called from
MasteryCounter.incrementWithRetryInside$default(...)
```

Kotlin compiles a function with default arguments into the function plus a synthetic static
bridge `foo$default`, and the bridge calls the real method. ArchUnit sees a self-invocation.
**It is not one**: the bridge receives the proxy as its receiver argument and dispatches
through it.

Established by measurement, not by reading — `R6` §3.3 drove exactly that method under
concurrency and it behaved transactionally throughout. The rule now excludes `$default`
bridges, because a rule that is routinely wrong is a rule nobody reads.

### 3.6 The upsert is not an alternative to the constraint

Against `V1` every `on conflict do nothing` **failed** — all eight. `on conflict (learner_id,
concept_id)` requires a unique index on exactly those columns to conflict with.

It does not degrade to an ordinary insert and it does not quietly do the wrong thing. It
refuses. **"Use an upsert instead of adding a constraint" is not an available option**, and
it was written here as one before being measured.

## 4. 원인 / Mechanism

`SELECT` then `INSERT` are two statements with a gap between them, and at `READ COMMITTED`
nothing holds the gap closed. Both transactions see no row, both insert, and both are
correct about what they saw. **Only the database can be inside that gap**, which is why no
arrangement of application code closes it.

PostgreSQL marks a transaction as failed on any error and refuses every subsequent statement
until rollback. That is stricter than MySQL or Oracle, where a failed statement leaves the
transaction usable — so the `try/catch` repair above is portable-looking code whose
correctness depends entirely on which database is underneath.

## 5. 처방 / Remedy

| Option | Rows | Failures | Chosen |
| --- | --- | --- | --- |
| application check alone | 8 | 0 | |
| constraint + naive insert | 1 | 7 | |
| constraint + catch in the same transaction | 1 | 7 | |
| constraint + insert isolated in its own transaction | 1 | 0 | |
| **constraint + `on conflict do nothing`** | **1** | **0** | **✔** |

**`V3` ships the constraint. The upsert is the recommended call pattern** — one statement,
no gap, no exception to catch, and no second transaction to remember to isolate.

The isolated-insert strategy is equally correct on these numbers and is kept because it is
the answer when the insert is not a single statement — several rows, or a value the database
cannot compute. **It costs a second connection while the first is held**, which is `R4`'s
`Cm = 2`, and that cost is real at pool size 10.

**Not changed:** `AttemptRecorder` still creates mastery rows with the naive pattern. It now
fails loudly instead of duplicating, which is an improvement, and moving it to the upsert is
a change to the recording path that belongs with the `R6` §8 decision about `score`.

> **Changed 2026-08-13 by `R12`**, together with that decision, which is where it belonged.
> The naive pattern's cost in the application's own path was measured before it was replaced:
> **3 and 4 `DataIntegrityViolationException`s** across two runs of 1,000 concurrent
> recordings on one `(learner, concept)` — small, because the window only exists in the
> instant before the row does, and **eight silent duplicates before `V3`**. The upsert arm
> raises none.

## 6. 재계측 / Re-measurement

| | before (`V1`) | after (`V3`) |
| --- | --- | --- |
| rows after 8 concurrent creates | **8** | **1** |
| requests that failed | 0 | 7 naive, **0 by upsert** |
| duplicates reported anywhere | none | n/a |

## 7. 회귀 게이트 / Regression gate

- `UniquenessRaceTest` — pins all four strategies, including that catching in the same
  transaction still fails. If PostgreSQL ever stops aborting the transaction, that test goes
  red and this report is out of date rather than silently wrong.
- `BaselineMigrationTest.the domain rule mastery has always claimed is now enforced` — the
  assertion that began as its own opposite (*"mastery must NOT yet have a unique
  constraint"*) and was inverted in this commit. The inversion is left visible.

## 8. 남는 위험 / Remaining risk

- **`V3` deletes duplicate rows and does not merge them.** It keeps the lowest id per group.
  On this repository's data that is a no-op — the generator emits one row per pair — but on
  any database that already has duplicates, **scores and attempt counts on the discarded
  rows are lost**. Merging them would be a domain decision this migration is not entitled to
  make, and making it silently would be worse. **Nothing warns the operator**: the delete is
  quiet, and a `raise notice` would have been better.
- **The race was only measured on one pair.** Eight threads on one `(learner, concept)` is
  maximal contention; real traffic spreads across a thousand learners and the failure rate
  would be far lower. **미측정.**
- **Only `READ COMMITTED`.** Under `REPEATABLE READ` the naive path would fail differently,
  and `R6` §8 already names this as the largest unpulled lever.
- **The transaction-abort behaviour is PostgreSQL's**, and the `try/catch` repair looks
  portable while depending on it. Whether the isolated-insert strategy is necessary on MySQL
  is **미측정** and would change the recommendation for anyone porting this.
- **The upsert returns no information about who won.** `do nothing` cannot distinguish
  "inserted" from "already there", so the caller re-reads. For a create-if-absent that is
  fine; for anything that must know, `returning` or `do update` changes the shape.
- **`AttemptRecorder` still uses the naive pattern**, now failing rather than duplicating.
  How often that fails in production is the same 미측정 as `R6` §8.
- **What would break the conclusion:** a second unique rule on the same table. `on conflict`
  names one constraint; with two, the statement must choose which it is prepared to lose to,
  and silently picks wrong if the author does not think about it.

## 9. 배운 것 / What I learned

**세 리포트 전에 만든 게이트가 오늘의 나를 잡았다.** T3 끝내고 ArchUnit 규칙을 다섯 개 썼을 때,
솔직히 이게 값을 할지 확신이 없었다. 오늘 `insertInNewTransaction`을 같은 클래스 안에서 부르고
"격리했다"고 생각했는데, 숫자가 개선 안 된 걸 보고 이상하다 싶던 차에 게이트가 정확한 문장으로
지목했다. **게이트는 논증으로 정당화되는 게 보통인데, 오늘 처음으로 스스로 값을 치렀다.**

그리고 자기호출이 **세 번째로** 다른 함정의 처방 안에서 나왔다. T5에서는 재시도를 옮기면서,
여기서는 insert를 격리하면서. 이 함정들은 목록이 아니라 **의존 그래프**고, T3가 밑에 깔려 있다.
로드맵은 T3를 "Tier 1의 한 항목"으로 적어놨는데, 실제로는 나머지의 전제였다.

두 번째. 유니크 제약을 "그 결함의 해결책"이라고 알고 있었는데, 실제로 한 일은 **조용한 중복 7개를
시끄러운 예외 7개로 바꾼 것**이다. 데이터는 고쳐지고 코드는 그대로 틀려 있다. R6에서 `@Version`이
한 일과 정확히 같은 모양이고, R4에서 프로젝션이 한 일과도 같다. **세 번 연속으로, 내가 "해결책"이라고
알던 것이 "다음 결정을 가능하게 만드는 장치"였다.** 이제 이건 패턴이라고 불러도 될 것 같다.

세 번째로 upsert. `on conflict do nothing`을 "스키마 안 건드리고 유니크를 얻는 방법"으로 쓰려고
했는데, 제약이 없으면 **실행 자체가 안 된다.** 제약의 대안이 아니라 제약의 소비자다. 다만 조용히
틀리지 않고 대놓고 거부한다는 게 다행이었다 — 그게 아니었으면 나는 V1 위에서 upsert를 쓰고 다
됐다고 생각했을 것이다.
