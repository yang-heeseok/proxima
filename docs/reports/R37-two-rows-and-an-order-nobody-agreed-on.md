# R37. Two rows, and an order nobody agreed on

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `a108715` — two transactions take the same two rows in opposite order, and
> the test asserts what both authors would have believed
> **Green commit**: **this one** — the ordered arm and the retry arm, both measured. §6
> carries them.
> **Answers**: `R6` §8's last-but-one bullet — *"one row, one column, one increment.
> Multi-row transactions introduce lock ordering and deadlocks, **which this measured nothing
> about**"* — and `ADR-014` ledger entry **`6.6`**, class **a**, the price it put on that
> sentence.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0)
                   15.2.0, 64-bit — read with `select version()` IN THIS RUN, not inherited.
                   Pinned by digest, read out of TestcontainersConfiguration.POSTGRES_IMAGE:
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
                   measurement-discipline.md's block says "server 16.14" against a DIFFERENT
                   digest (57c72fd2…) and was NOT copied — rule 9. This run confirms 16.15,
                   which is what the pinned digest has always been.
                   Every setting below is read from pg_settings on the running server.
  Isolation      : READ COMMITTED — the default, unchanged. See §8.
  Contention     : 2 transactions, 2 rows, opposed order, CyclicBarrier BETWEEN the two locks
  Repetitions    : 10 opposed pairs per arm, 3 arms, one invocation
  WHAT ELSE WAS RUNNING ON THIS MACHINE: slice D's full test run was active, with its own
                   Testcontainers up, plus slice G. Three Gradle daemons.
                   Stated because every published number here must state it. EVERY FIGURE IN
                   THIS REPORT IS A COUNT, A SQLSTATE, AN EXCEPTION TYPE OR A pg_settings ROW
                   VALUE. None is a duration, so none of them contends with D's or G's load.
                   THIS REPORT PUBLISHES NO TIMING AT ALL, deliberately — see §8.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Two transactions each lock two rows. Ten times out of ten, one of them is killed by the
server.

```
org.springframework.dao.PessimisticLockingFailureException: PreparedStatementCallback;
SQL [select attempts_count from mastery where id = ? for update]; ERROR: deadlock detected
```

**Neither transaction did anything wrong.** Each locked two rows it was entitled to lock, with
two ordinary statements, violating no constraint and exceeding no limit. Read either side on
its own and there is no defect in it, because there is none in it.

The defect is the **pair**, and no reader of either file can see the pair.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.DeadlockTest --rerun-tasks
```

At `a108715`. One learner, two `mastery` rows, `rowA` and `rowB`. Two transactions, each
`REQUIRES_NEW`, each taking `select … for update` on one row and then the other:

```
  transaction 1 :  lock A  ──▶  |barrier|  ──▶  lock B
  transaction 2 :  lock B  ──▶  |barrier|  ──▶  lock A
```

**The barrier sits between the two locks and that is the whole design.** Without it this pair
is a race that usually does not happen: whichever transaction reaches its second lock first
simply takes it and commits, and the test passes while exercising nothing. With it, each side
is holding its first row before either asks for its second, so **the cycle exists by
construction**, and a run that produced no deadlock would be a fact about the server rather
than about scheduling.

That is `ADR-015`'s requirement — an arm proves its own precondition — applied to a second
test. `UniquenessRaceTest` needed `RaceOverlap.peak` because a barrier at the *start* of a call
proves nothing about its critical section. Here the barrier is *inside* the critical section,
so overlap is not measured, it is enforced.

## 3. 계측 / Measurement

Verbatim, from the run at `a108715`:

```
E4 >>> opposite order   pairs=10 casualties=10 bothDied=0
E4 >>>   sqlstates={40P01=10}
E4 >>>   exceptions={org.springframework.dao.PessimisticLockingFailureException=10}
E4 >>>   verbatim: org.springframework.dao.PessimisticLockingFailureException:
         PreparedStatementCallback; SQL [select attempts_count from mastery where id = ? for
         update]; ERROR: deadlock detected

two transactions taking the same two rows in opposite order both complete() FAILED
    org.opentest4j.AssertionFailedError: each side locks two rows it is entitled to lock;
    nothing here violates anything ==> expected: <0> but was: <10>
```

And the two remedies, in the same invocation, against the same two rows:

```
E4 >>> ascending id order         pairs=10 casualties=0 bothDied=0 bothBetweenLocks=0
E4 >>>   sqlstates={}
E4 >>>   exceptions={}
E4 >>> retry OUTSIDE, 3 attempts  pairs=10 casualties=0 bothDied=0 bothBetweenLocks=10
E4 >>>   sqlstates={}
E4 >>>   exceptions={}
E4 >>>   retries=10 over 10 pairs
```

| arm | pairs | casualties | both died | **both between locks** | retries |
| --- | --- | --- | --- | --- | --- |
| **opposite order** | 10 | **10** — one per pair | **0** | **10** | — |
| **ascending id order** | 10 | **0** | 0 | **0** | — |
| **retry outside, 3 attempts** | 10 | **0** | 0 | **10** | **10** |

`4 tests, 0 failures, 0 errors, 0 skipped` —
`api/build/test-results/test/TEST-net.gseek.proxima.mastery.DeadlockTest.xml`.

### 3.1 The database detected every one, and killed exactly one side

`bothDied=0` is the answer to the question the brief asks — *does the database detect it, or do
both hang to timeout?* **On this server, ten out of ten, it detects.** One side is aborted, its
locks are released, and the other side then acquires what it was waiting for and commits.
Nothing hung, and nothing waited on a timeout.

`casualties=10` over `pairs=10` with `bothDied=0` is therefore not a rate to be rounded. It is
the statement **one victim per cycle**, observed ten times with no counter-example.

### 3.2 What determines the detection, read off the server rather than recalled

```
E4 >>> pg_settings
E4 >>>   deadlock_timeout           1000ms (source=default, boot=1000ms)
E4 >>>   lock_timeout               0ms (source=default, boot=0ms)
E4 >>>   statement_timeout          0ms (source=default, boot=0ms)
E4 >>>   log_lock_waits             off (source=default, boot=off)
E4 >>>   max_locks_per_transaction  64 (source=default, boot=64)
```

`source=default` on every row: nothing in this repository, in `application.yml`, or in
Testcontainers has set any of them.

**`deadlock_timeout` is not a timeout that kills anything**, and the name invites exactly that
reading. It is how long a backend waits on a lock before it stops waiting and **runs the cycle
check** — the cost of *looking*, set high enough that ordinary lock waits never pay it. So what
happened here is **detection**, not expiry, and the distinction decides §5: a detected cycle
produces a definite, immediate, attributable failure; an expiry produces a slow one.

`lock_timeout` and `statement_timeout` are both `0` — **disabled**. So nothing else in this
configuration would have ended the wait. Had the detector not run, the two transactions would
have waited on each other with no bound at all. `log_lock_waits` being `off` is the third
finding in that block and the operational one: **the server logs nothing about the waiting**
that precedes a deadlock, so the only trace anything leaves is the single exception the losing
client received.

### 3.3 What the losing client actually receives

`org.springframework.dao.PessimisticLockingFailureException`, ten times out of ten.

Two things about that class matter and neither is guessable from the SQLSTATE:

- It is **not** `DeadlockLoserDataAccessException`, which is the class most readers would name.
  Spring has one, and this path does not produce it.
- It extends `ConcurrencyFailureException`, which extends **`TransientDataAccessException`**.
  So the framework has already classified this failure as *retryable*, in its type, before any
  application code decides anything.

That classification is defensible here and §5 says why — but it is a claim about **this
failure**, not about the transaction that suffered it.

### 3.4 The remedy did not survive the race. It removed it — and that is a count.

⭐ **`bothBetweenLocks` goes 10 → 0 the moment an order is imposed, and it is the most
informative number in this report.**

The rendezvous sits between the two locks and exists to build the cycle by construction. Under
the **ordered** arm it can never be satisfied, and the reason is the remedy itself: both sides
now want `rowA` first, so the second caller is queued on `rowA` while the first is still
holding it. **They cannot both be sitting between their locks.** The barrier times out, and
that timeout is the observation.

This is why the arm reports `bothBetweenLocks=0` rather than simply `casualties=0`. Those are
two different claims and only one of them is worth anything:

| what a green ordered arm could mean | distinguished by |
| --- | --- |
| the order prevented the interleaving | `bothBetweenLocks=0` **and** `casualties=0` |
| this run happened not to race at all | `bothBetweenLocks=0` and `casualties=0` — *identical* |

…which is exactly the confusion `ADR-015` was written about, and the table above shows the
pair of counts **cannot** separate them on its own. What separates them is that the **retry**
arm ran in the same invocation against the same rows and reported `bothBetweenLocks=10`. The
harness demonstrably still builds the cycle; the ordered arm is the only one where it cannot.

**A single arm could not have established this.** `ADR-015` fixed `UniquenessRaceTest` by
adding an instrument that proves the precondition; here the precondition is *unprovable by
construction in the very arm that needs it*, and the control has to come from a sibling arm in
the same run. That is a shape this repository has not had before.

### 3.5 The retry recovered every casualty, on the second attempt

`retries=10 over 10 pairs` — **exactly one retry per pair**, matching the unsorted arm's
`casualties=10` one for one. Every pair lost one side, and every losing side succeeded when it
was tried again.

That is the arithmetic §5 predicted from Spring's type hierarchy and did not measure: by the
time the loser retries, the survivor has committed and released both locks, so the second
attempt finds no contention. `maxAttempts=3` was never exhausted — no pair needed a third.

**The arm asserts `retries > 0` as its own precondition**, so a run in which the first attempts
did not deadlock cannot pass it by doing nothing.

## 4. 원인 / Mechanism

A row lock taken by `select … for update` is held **until the transaction ends**. It is not
released when the next statement runs.

So after the barrier: transaction 1 holds A and waits for B; transaction 2 holds B and waits
for A. Each is waiting for a lock the other will not release until it finishes, and neither can
finish. That is a cycle in the wait-for graph, and it is not a bug in either transaction — it
is a property of the **set** of transactions, which no single one of them can observe.

PostgreSQL does not prevent the cycle. It waits `deadlock_timeout`, walks the wait-for graph,
finds the cycle, and terminates one participant to break it. The survivor proceeds. That is why
`bothDied=0`: the detector's job is to break the cycle with the **minimum** number of
casualties, and one is the minimum.

**The asymmetry with `V3` is the finding.** `V3`'s own comment says of the uniqueness race:
*"there is no version of 'look, then leap' that closes the gap, because the gap is between two
statements and only the database can be inside it."* That was true, and the remedy was to move
the rule **into** the database as a unique constraint. **Lock order cannot be moved there.**
There is no constraint, no `GRANT`, no setting and no schema object that makes the sorted call
correct and the unsorted one an error — they are the same two statements, and PostgreSQL has no
notion that `id` orders anything. `R7`'s defect could be handed to the database. This one
cannot.

## 5. 처방 / Remedy

| Option | Prevents the cycle | Cost | Chosen |
| --- | --- | --- | --- |
| do nothing; rely on the detector | **no** — it breaks cycles, it does not prevent them | one aborted transaction per cycle, and `log_lock_waits=off` means no trace | |
| `lock_timeout` | no | converts a detected failure into a slower, less specific one; `statement_timeout` would hit innocent statements too | |
| `for update nowait` / `skip locked` | no | changes what the *first* lock does; a different feature answering a different question | |
| retry the loser | no — a repair, not a prevention | **measured: recovers 10 of 10, one retry each**; a second round trip per casualty | | |
| **take the lower id first, always** | **yes — measured, `bothBetweenLocks` 10 → 0** | **nothing at run time. It costs a convention.** | **✔** |
| one statement instead of two locks | yes, where the work fits in one | unavailable whenever two rows must be held together | |

**The remedy is `RowLocker.lockInAscendingIdOrder`, and the remedy is not the method.**

Two callers that both sort cannot deadlock on these two rows, because a cycle needs one holder
waiting on a lower id while another waits on a higher one, and neither ever asks in that
direction. The method is three lines and it is correct.

⭐ **What it is not is enforceable.** This is an **application convention and the database does
not enforce it.** Nothing in the schema distinguishes the sorted call from the unsorted one;
they issue the same two statements against the same two rows. The only thing standing between
this repository and `40P01` is that every future caller remembers to call the sorted method
instead of the one beside it — and a convention nobody can be **compelled** to keep is not a
remedy, it is a hope with a docstring.

⭐ **And the detector is not a substitute for the convention.** That is the half this
measurement adds rather than assumes. PostgreSQL detected and killed one side **ten times out
of ten without anyone having ordered anything**, so it is tempting to read `bothDied=0` as
*"the database handles it"*. It does not handle it. It **converts a hang into a failure**. The
cycle still formed, ten times; work was still discarded, ten times; a client still received an
exception, ten times. What the detector bought is that the failure is bounded, attributable and
`Transient` rather than unbounded — which is worth a great deal, and is not the same as
prevention.

The two halves have to be said together, because each alone is misleading:

- **Prevention is a convention** the database cannot enforce.
- **Detection is a mechanism** the database does enforce, and it prevents nothing.

**On the retry.** §3.3 establishes that Spring types this as `TransientDataAccessException`, so
a generic retry layer would retry it, and it would very likely succeed — the survivor has
committed and released its locks by then, so the cycle is gone. But `R6` §3.3 is the standing
warning against reading that as *"a retry fixes it"*: a retry **inside** the transaction that
failed recovered nothing and cost time, 180 → 135 increments with time going 3273 → 3425 ms. A
deadlock aborts the whole transaction, so the retry must be **outside** it, and every unit of
work between the first statement and the abort is discarded and must be re-done. **It does succeed here: `retries=10 over 10 pairs`, every loser recovered on its second
attempt, no pair needing a third.** That makes the retry a genuine repair rather than a
slower failure — and it still is not prevention, because the cycle formed all ten times and
the work between the first statement and the abort was discarded all ten times.

## 6. 재계측 / Re-measurement

Same invocation, same two rows, same ten pairs per arm.

| Metric | Opposite order | **Ascending id order** | **Retry outside** |
| --- | --- | --- | --- |
| pairs deadlocking, of 10 | **10** | **0** | 0 *(after retry)* |
| casualties | **10** | **0** | **0** |
| SQLSTATE `40P01` count | **10** | **0** | 0 *(final)* |
| both sides between their locks | **10** | **0** | **10** |
| retries needed | — | — | **10**, one per pair |

**Two remedies, both correct, and they are not interchangeable.** The ordered arm prevents the
cycle; the retry arm lets it form ten times and recovers from all ten. §5's table is what
chooses between them, and neither result changes that choice — it is now made against numbers
instead of against a type hierarchy.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/DeadlockTest.kt`, run by
`.github/workflows/build.yml`.

The gate that matters is the **characterisation** one, and it is worth saying why it is shaped
oddly. The test pins that the unsorted pair **does** deadlock, exactly as `LostUpdateTest`'s
first arm pins that read-modify-write **does** lose updates. If PostgreSQL ever stopped
producing `40P01` here — a changed default, a changed detector, a different server — this
repository would want to find out, because §5's entire argument rests on detection being what
happens.

**The ordered arm's own precondition is the part worth keeping.** It asserts
`bothBetweenLocks == 0`, which is not a restatement of `casualties == 0` — it is what stops the
arm passing because the harness stopped racing. §3.4 says why that check cannot be
self-contained and has to lean on the retry arm running beside it.

**What no gate here can do is enforce the convention.** A test can prove the sorted method does
not deadlock. Nothing can prove that the next caller used it. That gap is not an oversight in
the gate; it is §4's asymmetry showing up in the tooling, and `ADR-019` is where the decision
about it belongs rather than here.

## 8. 남는 위험 / Remaining risk

- **Ten pairs is ten pairs.** `casualties=10, bothDied=0` has no counter-example in this sample,
  and that is not the same as a guarantee. Nothing here establishes that `bothDied` is always 0
  — only that it was 0 ten times. The same applies to every arm: **each remedy is 10 pairs, in
  one invocation, on one machine.** No arm was repeated across invocations, so run-to-run
  stability of these counts is `미측정`.
- **The ordered arm's green result rests on a control in a sibling arm, not in itself.** §3.4
  is explicit that `bothBetweenLocks=0` cannot by itself separate *the order worked* from *the
  harness stopped racing*; what separates them is the retry arm reporting `10` in the same
  invocation. **If the two arms were ever run separately, that separation would be gone** and
  the ordered arm would be exactly the vacuous shape `ADR-015` was written against.
- **The retry arm was measured at `maxAttempts=3` and never needed more than 2.** No sweep was
  run, and `R6` §8 carries the identical unmeasured item about its own chosen attempt count.
  What a retry costs under contention higher than one opposed pair is `미측정`.
- **This report publishes no duration at all, on purpose, and that is a real gap and not only a
  discipline.** *How long the losing client waits before it learns* is the number an operator
  actually needs, `deadlock_timeout=1000ms` is a floor on it rather than a value for it, and
  **it is `미측정`** — slice D's own full test run was active throughout this invocation and a
  duration taken beside it would have been unusable. It needs the measurement lock and a quiet
  machine.
- **The detection interval was read, not exercised.** `deadlock_timeout` is `1000ms` at
  `source=default`. Nothing here varied it, so *what a different value does to the outcome* is
  `미측정` — including whether a value large enough would let something else time out first.
- **`lock_timeout` and `statement_timeout` are both `0`.** Every conclusion in §5 is drawn
  against a server where nothing else would ever end the wait. A production server with either
  set would produce a different exception on a different path, and this measured none of it.
- **`log_lock_waits=off` means this repository has never seen what the server would say.** The
  operational question — *what appears in the log when this happens in production* — is
  `미측정`, and the answer at this setting is *nothing*.
- **Only two rows, only `for update`, only `READ COMMITTED`.** A three-transaction cycle, a
  cycle through a foreign-key lock, a cycle produced by `update` rather than an explicit lock,
  and the whole of `REPEATABLE READ` are all untouched. `R6` §8 calls `REPEATABLE READ` *"the
  single biggest lever not pulled"* and it is still not pulled; `ADR-014` `6.5` prices it
  separately and this report does not close it.
- **The retry is measured, and what it is measured against is narrow.** §3.5 —
  `retries=10 over 10 pairs`, every loser recovered on its second attempt. But that is one
  opposed pair at a time. **A retry under real contention re-enters a queue that other writers
  are also re-entering**, and nothing here measures whether the recovery rate holds when more
  than two transactions are cycling. `R6` §5 makes the neighbouring point from the other side —
  *"pessimistic wins when contention is high"* — and this report does not test it.
- **What would break the conclusion:** a server that does not detect. Every sentence in §5 about
  the failure being bounded and attributable depends on `deadlock_timeout` being reachable and
  the detector running. Disable it, or push it past a `statement_timeout`, and *"one victim per
  cycle"* becomes *"two transactions waiting"* — which is the outcome the brief asked about and
  which **did not occur here**.
- **Whether any bullet here needs a judgement rather than only work.** One does. *Does this
  repository do anything about a convention it cannot enforce, or does it record the gap and
  stop?* That is a decision, not a measurement, so it is `ADR-019` and not a risk bullet.
- **Which earlier §8 bullet this report falsifies.** `R6` §8's *"one row, one column, one
  increment. Multi-row transactions introduce lock ordering and deadlocks, which this measured
  nothing about."* It is no longer true that nothing has been measured about them. Per
  `_TEMPLATE.md` §8 that annotation belongs **beside the sentence in `R6`**, and `R6` is inside
  this slice's file contract, so it is made there rather than only summarised here — the debt
  `_ROUND2-B-HANDOFF.md` §3 records as knowingly the fourth of its kind is not being made a
  fifth.

## 9. 배운 것 / What I learned

**제일 놀란 건 `bothDied=0`이었다.** 교착이면 둘 다 매달려 있다가 타임아웃으로 죽는 그림을 막연히
그리고 있었는데, 10번 중 10번 정확히 한쪽만 죽었다. 그리고 `deadlock_timeout`이 죽이는 타임아웃이
아니라 **"이제 그만 기다리고 사이클을 검사할 시각"** 이라는 걸 `pg_settings`에서 읽고 나서야
이유가 붙었다. 이름이 완전히 반대로 읽히게 지어져 있다.

두 번째. `lock_timeout`도 `statement_timeout`도 `0`, 즉 꺼져 있다. 그러니까 **탐지기가 없었으면
두 트랜잭션을 멈춰 세울 것이 이 서버에는 아무것도 없었다.** 브리프가 물은 "둘 다 타임아웃까지
매달리나?"의 답은 이 설정에서는 "타임아웃이라는 게 애초에 없다"였다. 재현이 안 된 게 아니라
질문의 전제가 이 서버에서는 성립하지 않았다.

세 번째가 제일 오래 남을 것 같다. `V3` 주석은 "두 문장 사이의 틈에는 DB만 들어갈 수 있다"고 쓰고
유일성 규칙을 제약조건으로 **옮겼다.** 그런데 잠금 순서는 옮길 데가 없다. 정렬한 호출과 정렬 안
한 호출은 **문자 그대로 같은 두 문장**이고, `id`가 잠금을 정렬한다는 건 PostgreSQL이 모르는
얘기다. R7의 결함은 DB에 넘길 수 있었고 이건 못 넘긴다 — 그 비대칭이 이번에 배운 것이다.

네 번째는 초록 팔을 짜다가 걸린 건데 이게 제일 재밌었다. **사이클을 강제로 만들려고 넣은
랑데부가, 순서를 매기고 나니 절대 성립하지 않았다.** 둘 다 `rowA`를 먼저 잡으러 가니까 두 번째
호출자는 `rowA` 앞에 줄을 서 있고, 그래서 "둘 다 잠금 사이에 서 있는" 상태가 될 수가 없다.
처음엔 하네스 버그인 줄 알고 고치려다가, **그게 바로 처방이 동작한다는 증거**라는 걸 알아챘다.
그래서 타임아웃을 실패가 아니라 관측값으로 바꿔서 `bothBetweenLocks`로 셌다. 10 → 0.

그런데 거기서 한 번 더 걸렸다. `bothBetweenLocks=0, casualties=0`은 **"순서가 막았다"와 "이번엔
그냥 경합이 없었다"를 구분하지 못한다.** 두 경우의 숫자가 완전히 같다. ADR-015가 딱 이 문제로
쓰인 건데, 이번엔 그 팔 안에서는 원리적으로 자기 전제를 증명할 수가 없었다 — 옆 팔(재시도)이
같은 실행에서 `bothBetweenLocks=10`을 찍어줘야만 구분이 선다. **자기 전제를 스스로 증명 못 하는
팔이 있을 수 있다는 걸 처음 봤다.**

그리고 `bothDied=0`을 "DB가 알아서 해준다"로 읽고 싶은 유혹이 꽤 셌다. 안 해준다. **사이클은 10번
다 생겼고, 버려진 작업도 10번 다 있었다.** 탐지기가 산 건 예방이 아니라 *멈춤을 실패로 바꾼 것*
이다. 그게 싸구려란 뜻은 전혀 아닌데, 예방이랑 같은 물건은 아니다. 이 두 문장을 따로 쓰면 둘 다
거짓말이 된다.
