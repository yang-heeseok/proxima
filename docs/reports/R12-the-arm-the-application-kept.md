# R12. The arm the application kept

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
> **Red commit**: this one, behind `proxima.recording.mastery-update=read-modify-write` —
> the arm the application actually shipped from `cceec6a` until now. It is a property rather
> than a separate commit because **the red state is already in this repository's history**:
> every commit from `R6` onwards ran it, and `R6` §8 said so at the time.
> **Green commit**: this one — `atomic-guarded`, and a gate that counts to a thousand
> **Discharges**: `R6` §8's first bullet and `R7` §8's deferral

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL : Testcontainers postgres:16-alpine -- server 16.14, READ COMMITTED
  Framework  : Spring Boot 4.1.0, Hibernate 7.4.1.Final
  Contention : 10 threads released from a CyclicBarrier, 100 recordings each,
               all on ONE (learner, concept). Score delta 0.001, so 1,000 applied
               recordings land the score exactly on 1.000 -- the edge of the band
  Runs       : 2 full executions, both quoted. The red arm is not deterministic
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R6` compared five ways to increment a counter under contention and named a winner. Then its
§8 said this:

> **The application still uses the second-worst option.** `AttemptRecorder.record` reads a
> `Mastery`, mutates `attemptsCount` and `score`, and saves — the `entity + @Version` arm,
> which rejected 82 % of writes here. **It has not been changed**, because the fix is not a
> substitution: `score` is computed with a business rule (`require(updated <= 1)`) that an
> atomic statement cannot express […] **That is a design decision with a measured cost and it
> is deferred rather than guessed at.**

`R7` §8 deferred in the same direction for the same class. So for the length of this
repository, **the one place its own measurements pointed at was the one place nothing
changed.** This report is that bullet being discharged, and the first thing it needed was to
find out whether the reason was true.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.recording.RecordingContention*"
```

Both arms in one binary — `R4` §2 — selected by `proxima.recording.mastery-update`:

| value | what it does |
| --- | --- |
| `read-modify-write` | `findByLearnerIdAndConceptId(…) ?: Mastery(…)`, mutate, `save`. **`red`** — `R7`'s check-then-insert sitting on top of `R6`'s `entity + @Version` |
| `atomic-guarded` | `R7`'s `on conflict do nothing`, then one statement carrying the rule as a predicate. **ships** |

## 3. 계측 / Measurement

### 3.1 The two arms

```
R12-RED    applied  196  rejected  804  attempts_count  196  score 0.196  attempt rows  196   611 ms
           {ObjectOptimisticLockingFailureException=801, DataIntegrityViolationException=3}
R12-RED    applied  195  rejected  805  attempts_count  195  score 0.195  attempt rows  195   742 ms
           {ObjectOptimisticLockingFailureException=801, DataIntegrityViolationException=4}

R12-GREEN  applied 1000  rejected    0  attempts_count 1000  score 1.000  attempt rows 1000   911 ms
R12-GREEN  applied 1000  rejected    0  attempts_count 1000  score 1.000  attempt rows 1000   833 ms
```

| | red, two runs | green, two runs |
| --- | --- | --- |
| recordings applied of 1,000 | **196 / 195** | **1000 / 1000** |
| rejected | 804 / 805 | **0 / 0** |
| final `score` | 0.196 / 0.195 | **1.000** exactly, both |
| wall clock | 611 / 742 ms | 911 / 833 ms |
| **per applied recording** | **3.1 / 3.8 ms** | **0.91 / 0.83 ms** |

Per unit of work actually done, **3.4× and 4.6×** across the two runs. The green arm is slower
on the wall clock and does five times the work while being slower; quoting only the wall clock
would be quoting the red arm's failure as its speed.

### 3.2 What the red arm is, and is not

**It is not data corruption.** `attempt rows` matches `attempts_count` in every red run — 196
and 196, 195 and 195. The two halves of a recording never came apart, because each rejection
rolled back both. `R1`'s atomicity property held throughout.

**It is an availability defect.** Four recordings out of five were **refused**. For a learner
answering a question, that is the submit button failing eighty percent of the time.

That distinction is `@Version` doing exactly what `R6` §9 measured it doing: *converting 864
silent losses into 820 loud rejections*. Without it this arm would have lost increments and
reported nothing. With it, nothing is lost and almost nothing gets through. **Optimistic
locking did not make this code correct; it made its incorrectness loud** — and then the code
was left in production for three days on the strength of it being loud.

### 3.3 Two defects, one code path

The rejections split:

| exception | count | which report |
| --- | --- | --- |
| `ObjectOptimisticLockingFailureException` | 801, both runs | `R6` — read, mutate, save |
| `DataIntegrityViolationException` | 3, then 4 | `R7` — `findBy… ?: Mastery(…)`, two requests both find nothing |

The second is small because it can only happen in the instant before the row exists. It is
also the one that would have been **eight silent duplicates** before `V3`. Both defects lived
in five lines that read like the obvious way to write this.

### 3.4 The reasoning that had been blocking it

`R6` §8's argument was: the business rule `require(updated <= 1)` cannot be expressed as an
atomic statement, and moving it to `V1`'s `ck_mastery_score` would turn a violation into a
constraint error — which `R1` §9 measured aborting the whole transaction on PostgreSQL.

Both halves of that are true. **The conclusion does not follow, because a rule does not have
to be a constraint.** It can be a predicate:

```sql
update mastery
   set attempts_count = attempts_count + 1,
       score          = score + :delta,
       version        = version + 1,
       updated_at     = :at
 where learner_id = :learnerId
   and concept_id = :conceptId
   and score + :delta between 0 and 1.000
```

A recording that would leave the band **matches no row**. Nothing is raised, nothing is
aborted, `0` comes back, and the caller decides what it means.

And the transaction being intact is not a technicality — it is immediately useful. The failure
path reads the row to say what the score *would* have become, which is the error message the
old `require` produced. **Behind `ck_mastery_score` that read would itself have failed**, for a
reason unrelated to the cause, which is the whole of `R1` §9.

The guard is also **stricter** than the code it replaces. `require(updated <= 1)` checked one
end of the band; `between 0 and 1.000` checks both, so a negative delta that would have hit
`ck_mastery_score >= 0` and poisoned the transaction now returns `0` like any other refusal.
That was not the goal and it was not measured before this sentence was written — the
statement's shape simply covers it.

### 3.5 A green test that went red on a change that broke nothing

`AttemptRecordingServiceTest` asserted `mastery == null` after a failed recording. It went red.

**Nothing about the property changed.** After a failed recording, no mastery row exists — a
second request sees none, and `AttemptRecordingAtomicityTest` now asserts exactly that. What
changed is where it is observable. The old arm never wrote a row on the failure path, so
`null` was true *inside* the caller's transaction too. The new arm writes the row and rolls it
back, so inside that transaction the row is there.

That class's KDoc opens with **"This is the test that proves nothing, and it is green."** It
exists because `R1` needed a demonstration that a test sharing a transaction with the code
under test cannot observe that code's boundaries. This is the same flaw producing a **false
negative** rather than a false positive:

> A test that shares a transaction with the code under test does not merely fail to observe
> that code's boundaries. It can report their consequences backwards.

The assertion moved to the non-transactional test, where it is a claim. The old one was
replaced with what it can actually see, and why that is not the property.

## 4. 원인 / Mechanism

Read-modify-write across a transaction boundary has no connection between the read and the
write. `@Version` supplies one, by refusing any write whose row moved since the read — so
under contention on a single row, the great majority of writers lose the race and are told so.
Ten threads on one row is close to the worst case that exists.

The atomic statement has no window to lose: the database reads and writes the row with nothing
in between, and the business rule rides along in the `WHERE` clause instead of in the
application's head.

## 5. 처방 / Remedy

| Option | Why not |
| --- | --- |
| keep `@Version` and retry outside the transaction | `R6` measured it: 623 of 1,000, better than nothing and still a third lost. And it makes every recording potentially several transactions |
| pessimistic lock | `R6`: correct, and **5.1× slower** than the atomic statement |
| move the rule into `ck_mastery_score` | `R1` §9 — the constraint error aborts the transaction, and the error message this code produces needs a read afterwards |
| **`on conflict do nothing`, then one guarded statement** | **✔** |

## 6. 재계측 / Re-measurement

§3.1. 196 → **1000** of 1,000 recordings applied; 804 → **0** rejected; score 0.196 → **1.000**.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/recording/RecordingContentionGateTest.kt`, run by
`.github/workflows/build.yml`. Four exact assertions, no tolerances:

- **no** rejections
- `attempts_count` is **exactly 1000**
- `score` is **exactly `1.000`** — which also checks the band's edge, since a
  thousand-and-first recording would match nothing
- `attempt` rows are **exactly 1000**, so the two halves of a recording cannot come apart
  without the gate noticing

It carries no property override, so it shares `AttemptRecordingAtomicityTest`'s cached context
and asserts **what production is configured to do**.

`RecordingContentionTest` is the red arm's own test and asserts only that it rejects or loses
*something* — the number is not deterministic, and a gate that pinned it would be flaky by
construction. Its job is to keep the comparison honest: if the red arm ever stopped failing,
the green arm would be being compared against nothing.

## 8. 남는 위험 / Remaining risk

- **Ten threads on one row is not production.** `R6` §8 said this and it is still true: real
  traffic spreads across many learners, so the red arm's rejection rate in production is
  **미측정** and would be far lower. The difference between "80 % refused" and "occasionally
  refused" is unmeasured, and it is the difference between an outage and a nuisance.
- **The guard duplicates `ck_mastery_score`.** The band now lives in two places — a `CHECK`
  in `V1` and a predicate in `RecordingQueries`. They agree today. Nothing enforces that they
  keep agreeing, and the constraint is the one that is authoritative.
  > **Moved to `OPEN-6` on 2026-08-13, the same day, and closed by `ADR-006` the next
  > morning.** This was on the wrong shelf. A bullet here is something someone chose to live
  > with; that was something someone still had to decide, and leaving it in a *남는 위험*
  > section would have meant nobody ever had to.
  >
  > The decision keeps both definitions and gates the **ordering** between them rather than
  > their text: the predicate may be stricter than `ck_mastery_score` and may never be laxer,
  > because only the lax direction puts `R1` §9's aborted transaction back. `ScoreBandGateTest`
  > drives seven boundary deltas — including two negative ones, an end `require(updated <= 1)`
  > never checked at all — and requires every refusal to come from the guard.
- **`@Version` is now maintained by hand on this path.** The native statement does
  `version = version + 1`. Any future statement that forgets to leaves the optimistic-locking
  column claiming the row never moved. A structural rule could catch it; none is written.
- **The atomic statement only works because the new value is derivable from the old one.**
  `R6` §5 states the condition and this inherits it. A scoring rule that depended on anything
  outside the row — the learner's recent accuracy, say, which the recommendation rule already
  wants — would put this straight back to a read-modify-write, and the remedy would have to
  be different.
- **Only `READ COMMITTED` was measured**, as in `R6`. `REPEATABLE READ` changes every number
  in the red column and is still the biggest lever not pulled in this repository.
- **The wall-clock comparison is between arms doing different amounts of work.** §3.1 quotes
  per-applied-recording figures for that reason, but 195 samples against 1,000 is not a like
  comparison and no attempt was made to make it one.
- **Batch partiality is untouched.** `AttemptRecordingService.recordAll` still stops at the
  first failure and does not tell the caller which recordings landed. `AttemptRecorder`'s KDoc
  has said so since `T3`; it is still true and still needs a requirement rather than a
  refactor.
- **What would break the conclusion**: contention spread across rows rather than concentrated
  on one. The atomic statement's advantage narrows as contention falls, and at zero contention
  the two arms should be indistinguishable. **That was not measured**, and it is the
  measurement that would tell a real deployment whether any of this mattered.

## 9. 배운 것 / What I learned

**막고 있던 것은 사실이 아니라 결론이었다.**

R6 §8은 세 가지를 적어놨다. `score`에는 업무 규칙이 있다 — 참이다. 원자적 문장은 규칙을 표현할 수
없다 — 참이다. DB 제약으로 옮기면 트랜잭션이 오염된다 — 참이고, R1이 측정까지 했다. **세 문장이 다
맞는데 결론이 틀렸다.** 규칙이 제약일 필요가 없기 때문이다. `WHERE`에 넣으면 밴드를 벗어나는 기록은
예외가 아니라 **0행**이 되고, 트랜잭션은 멀쩡하다.

그리고 트랜잭션이 멀쩡하다는 게 곧바로 값을 했다 — 실패 경로에서 행을 다시 읽어 "점수가 얼마가 될
뻔했는지" 를 말해줄 수 있다. 제약으로 밀어넣었다면 그 읽기가 먼저 실패했을 것이다. **피하려던 문제가
해결책의 품질까지 결정하고 있었다.**

내가 이 항목을 사흘 동안 안 건드린 이유는 §8에 적힌 논증이 설득력 있었기 때문이다. 내가 쓴 논증이다.
**참인 문장 세 개로 만든 결론을 다시 안 열어본 것**이 오늘의 실수고, R0에 적은 "읽으면 알 수 있는 것을
추론으로 대체" 와 같은 계열이다 — 이번엔 남의 코드가 아니라 내 논증이었다.

**두 번째. 초록인 테스트가 아니라, 빨간불이 된 테스트가 거짓말을 했다.**

`mastery == null`이 깨졌을 때 처음 든 생각은 "내가 뭔가 깼구나" 였다. 아니었다. 도메인 속성은 그대로고,
그걸 볼 수 없는 자리에서 단언하고 있던 테스트가 깨진 것이다. 그 클래스 KDoc 첫 줄은 **"This is the test
that proves nothing"** 이다. R1이 그렇게 써놨는데도 나는 그 단언을 근거로 삼을 뻔했다. **트랜잭션을
공유하는 테스트는 경계를 못 보는 데서 그치지 않는다 — 경계의 결과를 거꾸로 보고한다.**

**마지막으로 `@Version`에 대해.** 이 arm은 데이터를 잃지 않았다. 5건 중 4건을 **거절**했다. 낙관적
잠금이 조용한 유실을 시끄러운 거부로 바꿔놨고 — R6 §9가 이미 잰 것이다 — 나는 그 시끄러움을 안전으로
읽고 사흘을 뒀다. **시끄러운 결함은 조용한 결함보다 낫지만, 고쳐진 결함은 아니다.**
