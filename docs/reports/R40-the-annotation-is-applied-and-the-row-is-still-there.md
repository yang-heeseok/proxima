# R40. The annotation is applied, the proxy is crossed, and the row is still there

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `94fe9ee` — the shipped batch path, reached from a caller that has a transaction
> **Green commit**: `022675b` — `AttemptRecorder.record` isolates itself, so the unit of work is
> one recording regardless of the caller
> **Decision**: `ADR-020`
> **Sibling**: `R1` — there the annotation was **never applied**. Here it is applied and still
> does not roll back.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767
                   934dd0a95e671f9a0fc20685 — server 16.15 on x86_64-pc-linux-musl.
                   Read from TestcontainersConfiguration.kt:72 and the container's own
                   Flyway line, NOT from measurement-discipline.md, which is wrong
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counters       : committed row counts, read OUTSIDE the writing transaction;
                   java.lang.reflect Method.getExceptionTypes and javap -p for §3.2
  Dataset        : one row per arm; five recordings for §3.4. This defect appears at
                   concurrency 1 and needs no scale
  Load           : none. Every number here is a row count or a class-file fact
  Concurrently   : slices D and E were active on this machine. These are row counts —
                   logical facts about the transaction rule — and they do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A unit of work raised, and a row it had written was still committed.

That is `R1`'s opening sentence almost word for word, and **the point of this report is that
everything `R1` fixed is still fixed here.** The boundary is on its own bean. The call crosses
the proxy. The interceptor runs. A transaction really is open. And the row is still there.

The second symptom is quieter and has no exception where it is caused. A method catches a
failure, logs it, and returns a value. **The caller** then receives an exception raised at
commit, naming a transaction it never marked — and every write the method made, including the
ones that succeeded, is gone.

`docs/roadmap.md:99` has carried that second case since `T3` in the tree's own words: *"the
swallowed exception / rollback-only case is **not done at all**."*

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
git checkout 94fe9ee
./gradlew :api:test --tests 'net.gseek.proxima.basics.RollbackRuleTest' \
                    --tests 'net.gseek.proxima.basics.BatchInsideATransactionTest' --rerun-tasks
```

Requires a reachable Docker daemon. On this machine Docker runs **natively inside WSL2**, so the
command must be run there. Concurrency is 1; the dataset is one row per arm. **Neither load nor
scale is involved.**

⛔ **The tests are deliberately not `@Transactional`.** `R1` §4 measured why: a test annotated
`@Transactional` shares one transaction with the code under test, so the code's writes join the
test's and are rolled back by the harness whether or not the code had a boundary of its own.
**A test that shares a transaction with the code it is testing cannot observe that code's
transaction boundaries.** These run outside a transaction and count committed rows with a fresh
statement.

### 2.1 The Java arm is the first Kotlin-to-Java type reference in this build's test sources

`R1` already put planted violations in Java, because the Kotlin compiler plugins would have fixed
a Kotlin plant before the rule could see it. **But nothing reaches those classes by type** —
`TransactionBoundaryRulesSelfTest` finds them through `importPackages("proxima.planted")`, a
string. Verified by searching: no Kotlin file in `api/src/test/kotlin` names any of the three.

`RollbackRuleTest` imports `net.gseek.fixtures.basics.JavaRollbackProbe` **as a type**, making it
the first place here where Kotlin test code depends on Java test code through the compiler. That
is ordinary Gradle joint compilation and it works; it had simply never been exercised, and a
report leaning on it silently would rest on an untested property of the build.

⛔ It has to be a real Java class rather than a reflective lookup, because **the compiler is half
the finding**: §3.2 reads the `throws` clause out of the class file, and there is no class file
without a Java compilation.

## 3. 계측 / Measurement

### 3.1 Which exception kinds roll back, and which commit

⛔ **Not stated here from documentation or from memory** — rule 9. Each arm executes against a
real PostgreSQL through a real proxy, and the committed row is counted afterwards by a statement
issued outside the transaction that wrote it.

```
R40-ROLLBACK >>> one write, then one failure, inside a real transaction
  arm                                                  raised                       committed rows
  Kotlin, default rule, no failure                     —                            1
  Kotlin, default rule, RuntimeException               IllegalStateException        0
  Kotlin, default rule, checked IOException            IOException                  1
  Kotlin, default rule, Error                          AssertionError               0
  Java,   default rule, checked IOException            IOException                  1
  Java,   default rule, RuntimeException               IllegalStateException        0
  Kotlin, rollbackFor = Exception, checked             IOException                  0
  Java,   rollbackFor = Exception, checked             IOException                  0
```

| Arm | Raised | Committed rows |
| --- | --- | ---: |
| Kotlin, default rule, **no failure** *(control)* | — | 1 |
| Kotlin, default rule, `RuntimeException` | `IllegalStateException` | **0** |
| Kotlin, default rule, **checked `IOException`** | `IOException` | **1** ⟵ **the defect** |
| Kotlin, default rule, `Error` | `AssertionError` | **0** |
| Java, default rule, checked `IOException` | `IOException` | **1** |
| Java, default rule, `RuntimeException` | `IllegalStateException` | **0** |
| Kotlin, `rollbackFor = Exception`, checked | `IOException` | **0** |
| Java, `rollbackFor = Exception`, checked | `IOException` | **0** |

**One row, written by a unit of work that raised, still committed.** The control row commits, so
the fixture is capable of committing; the `RuntimeException` and `Error` rows roll back, so the
boundary is real. **The rule discriminates on exception kind and nothing else.**

### 3.2 Where Kotlin and Java diverge — and it is not the behaviour

Read from the compiled class files with `javap -p`, **and independently** at run time through
`Method.getExceptionTypes()`:

```
R40-SIGNATURE >>> declared checked exceptions, read from the class files
  JavaRollbackProbe.writeThenThrowChecked                  [IOException]
  JavaRollbackProbe.writeThenThrowRuntime                  []
  KotlinRollbackProbe.writeThenThrow                       []
```

| Method | Declared checked exceptions |
| --- | --- |
| `JavaRollbackProbe.writeThenThrowChecked` | **`IOException`** |
| `JavaRollbackProbe.writeThenThrowRuntime` *(control)* | none |
| `KotlinRollbackProbe.writeThenThrow` | **none** |

⭐ **Two independent instruments over one artefact, agreeing.** `javap` reads the class file;
reflection reads the loaded class. A disagreement would mean an instrument was wrong rather than
the finding. `R8` §3.3 is why this repository does not rest a claim on a single counter.

**The control row is doing real work.** `writeThenThrowRuntime` is Java, annotated identically,
and declares nothing — because there is nothing for the Java type system to have recorded. That
is what shows the empty Kotlin row is a **language difference** and not an artefact of how the
attribute was read.

### 3.3 The swallowed exception

```
R40-SWALLOW >>> outer writes, inner fails, outer catches and carries on
  what the outer method computed for itself : never returned
  what the caller actually received         : UnexpectedRollbackException
  committed rows, outer's own write         : 0
  committed rows, inner's write             : 0
```

| | Value |
| --- | --- |
| What the outer method computed for itself | *never returned* |
| What the caller actually received | **`UnexpectedRollbackException`** |
| Committed rows, the outer's **own** write | **0** |
| Committed rows, the inner's write | 0 |

⭐ **The outer's own write is gone too, and that is what makes this expensive.** The exception it
caught was about the inner. It took the outer with it.

And the remedy arm, identical code against an inner that owns its transaction:

```
R40-ISOLATED >>> the same code, against an inner that owns its transaction
  what the outer method computed for itself : swallowed IllegalStateException
  what the caller actually received         : —
  committed rows, outer's own write         : 1
  committed rows, inner's write             : 0
```

**The call site is character-for-character the same.** One propagation setting on a different
class changes the outcome from *everything lost, caller cannot explain why* to *the failed work
is gone and the rest committed*.

### 3.4 The same defect in shipped code

Five recordings, deltas `0.100, 0.100, 1.500, 0.100, 0.100`, the third invalid — the batch `R14`
measured, **re-run on this base rather than quoted across branches.**

```
R40-CONTROL >>> the shipped call path, no outer transaction
  outcomes    : ok, ok, rejected, ok, ok
  attempt rows: 4

R40-RED >>> the same shipped call, from a caller that has a transaction
  outcomes the caller received : none -- UnexpectedRollbackException
  raised to the caller         : UnexpectedRollbackException
  attempt rows committed       : 0  (of 4 valid recordings)
```

| Call path | Outcomes the caller received | Attempt rows committed |
| --- | --- | ---: |
| `service.recordAll(...)` — as shipped, no outer transaction | `ok, ok, rejected, ok, ok` | **4** |
| `caller.recordAllInsideMyTransaction(...)` — from a `@Transactional` caller | **none** | **0** |

⭐ **The caller is not told less than `R14` promised. It is told nothing.** The per-item outcome
list is computed exactly as designed and then discarded with the transaction, and what arrives is
an exception naming a transaction the caller never marked.

⚠️ **No production file was modified to obtain this.** The defect is reached through a *caller*,
because editing `recordAll` would manufacture the failure — and because a caller is what actually
happens: the next service that records attempts alongside its own writes will be `@Transactional`,
since almost every service is.

## 4. 원인 / Mechanism

`@Transactional` decides two separate things and people read it as deciding one. **Where** the
boundary is — `R1`'s subject, correct throughout this report. And **what counts as a failure**,
which is this report's subject and is a *policy with a default*.

That default is expressed as a Java language distinction: unchecked means *the caller could not
have anticipated this*, checked means *the caller was told and chose to proceed*. Rolling back on
the first and not the second is a faithful reading of that. **Kotlin does not have the
distinction** — no checked exceptions, no `throws` clause, no diagnostic when one crosses a
method boundary. The runtime type still exists, so the rule still fires; the signal that used to
accompany it does not.

⭐ **That is the answer to "was this a Spring problem or a Java problem".** Neither, exactly:
Spring's default is a faithful reading of Java, and Kotlin removed the half of Java the reading
depended on. Both are individually defensible and jointly produce a committed row.

The swallowed-exception case is a different mechanism with the same root — a policy applied by an
interceptor that does not own the transaction it is applying it to:

1. the outer opens a transaction and writes;
2. the inner is `REQUIRED`, so it **joins** rather than getting its own;
3. the inner raises. Its interceptor cannot roll back a transaction it does not own, so it does
   the only safe thing available and **marks the shared transaction rollback-only**;
4. the outer catches, logs, and returns normally;
5. the outer's interceptor tries to commit a marked transaction, and that is where it surfaces.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — `rollbackFor = Exception` on every boundary | closes the checked-exception half — **measured, §3.1, both languages to 0** | must be remembered on every new `@Transactional`; nothing enforces it; does **nothing** for the swallowed-exception half | |
| B — never throw checked exceptions from a boundary | closes the same half | **unenforceable in Kotlin** — §3.2 shows the information is not in the class file, so no static rule can read it | |
| C — **`REQUIRES_NEW` on the inner unit of work** | closes the swallowed-exception half; makes the declared unit of work true regardless of caller — **measured, §3.3: outer's write survives** | **a second connection while the first is held**, `Cm = 2`. See §8 — the price is measured and it is not small | **✔** |
| D — a gate refusing transactional callers | makes the property checkable | narrow, and it forbids something callers may legitimately want | |
| E — do nothing, document it | free | a correctness property that depends on everyone remembering is not a property — `R1` §5 already rejected this reasoning | |

**C, for the shipped defect. A remains available and is not applied anywhere, deliberately.**

### ⭐ Why C's price does not weigh against C's benefit — the two are never both live

C costs a second connection, and §8 shows that is not a small price on this machine. **It does
not weigh against the defect, and the reason is structural rather than a rationalisation.**

> With no outer transaction, `REQUIRED` and `REQUIRES_NEW` are **the same code path** — one
> connection, one commit. **The two prices are never both live.** The moment a transactional
> caller appears, `REQUIRED` begins losing data and `REQUIRES_NEW` begins costing a second
> connection. **Nothing that exists today pays either.**

So the choice is not *correctness versus connections*. It is **a doubled connection demand on a
path nothing currently uses, against silent total loss on that same path the first time somebody
writes an ordinary transactional service** — measured at **4 of 4 valid recordings, zero rows**.
That is the number that settles it.

*"A path nothing currently uses"* is checked, not assumed: `AttemptRecordingService` holds no
`@Transactional`, `RecordingController.record` carries none, and `ADR-009` records that
`recordAll` has no caller outside tests.

**Why C rather than D.** D keeps the defect and forbids the caller. A service that records
attempts alongside its own writes is an ordinary thing to want; what the repository has reason to
refuse is *silently changing the unit of work underneath it*. C answers the question the defect is
actually asking, which is the same one `R1` §5 answered: **what is the unit of work?** One
recording. `REQUIRES_NEW` is that sentence written so the framework enforces it.

**Why A is not also applied.** Nothing in `api/src/main` currently raises a checked exception from
a `@Transactional` method — verified by reading them. Applying `rollbackFor` everywhere would be
defending against a shape this codebase does not have, and it would have to be remembered forever
by every future author to keep working. **§7 says what could enforce it and why nothing does.**

**What would have made E correct:** a requirement that a batch is all-or-nothing with the
caller's work — a gradebook import where a partially applied file is worse than a rejected one.
`R1` §5 already named that as the thing that flips this choice. It is not this application's
requirement; `R14` chose per-item outcomes after measuring the alternative.

## 6. 재계측 / Re-measurement

Identical conditions to §3. **No test file changed between the red and green commits** for the
shipped-path arm — `BatchInsideATransactionTest`'s assertion is the same assertion.

| Metric | Red `94fe9ee` | Green `022675b` |
| --- | --- | --- |
| attempt rows committed, batch from a transactional caller | **0** of 4 valid | **4** of 4 valid |
| what the caller received | `UnexpectedRollbackException` | the per-item outcome list |
| attempt rows committed, batch with **no** outer transaction *(control)* | 4 | 4 |
| `BatchInsideATransactionTest` | **RED** | **GREEN** |

⭐ **The control row is what shows the fix did not change the shipped behaviour**, only the
behaviour under a caller that did not previously exist.

### 6.1 ⭐ The remedy broke a test that predicted it would, and the property it guards is intact

The full run on the green tree returned **one** failure across 139 `:api:test` cases:

```
AttemptRecordingServiceTest.what this test can see after a failed recording,
  and why that is not the property
    expected: <0.000> but was: <null>
```

⛔ **Not `ConnectionHoldingGateTest` and not `QueryCountTest`** — the two gates this change most
obviously threatened both **passed** (2/0 and 4/0).

**That test is `R12`'s demonstration piece and its KDoc names this outcome in advance:**

> *"A test that shares a transaction with the code under test does not merely fail to observe that
> code's boundaries — **it can report their consequences backwards**."*

The class is `@Transactional`, so it reads from inside the caller's transaction. Under `REQUIRED`
the recorder **joined** that transaction, so the `on conflict do nothing` row was visible to it.
Under `REQUIRES_NEW` the recorder owns a transaction that then **rolls back**, so the row never
becomes visible to that reader.

⛔ **The domain property is intact, and this was checked rather than assumed.**
`AttemptRecordingAtomicityTest` — which reads from **outside** the transaction and is the test that
actually holds the property — **passes**, as do `PartialBatchTest`, `PartialBatchGateTest`,
`RecordingContentionTest`, `ScoreBandGateTest` and `RecordingContentionGateTest`. **Nothing about
what is stored changed. What changed is what an in-transaction observer can see.**

⭐ **And that makes this `null` the third distinct cause of one value:**

| when | value | because |
| --- | --- | --- |
| pre-`R12` | `null` | the read-modify-write arm never wrote a row at all |
| `R12` → `022675b` | `0.000` | the row was written and visible inside the **shared** transaction |
| after `022675b` | `null` | the row was written in a transaction that then **rolled back** |

**Same literal, three mechanisms, and the test cannot distinguish them.** `R12` turned this
assertion from `null` to `0.000` and wrote that the old one *"was never evidence about
atomicity"*. The new `null` is not a return to the old state — it is a third state that happens
to print the same way, which is the strongest possible restatement of `R12`'s point.

⚠️ **This is a consequence of the remedy, not a test defect.** `ADR-020` gave up *"the caller can
abandon the batch by rolling back"*; this is that same trade seen from a test's vantage point,
and it is recorded here rather than resolved by quietly editing the assertion.

## 7. 회귀 게이트 / Regression gate

**`BatchInsideATransactionTest`**, run by `.github/workflows/build.yml`. Reverting the propagation
turns it red with the row count in the failure message. It asserts both arms — with and without an
outer transaction — so a change that fixes one by breaking the other is caught.

`RollbackRuleTest` holds §3.1's table and §3.2's signatures as exact assertions, so a Spring
release that changes the default rollback rule, or a Kotlin release that begins emitting an
`Exceptions` attribute, turns red rather than silently invalidating this report.

⚠️ **One gate that cannot be built, and the reason is a measurement rather than an opinion.**
An ArchUnit rule refusing `@Transactional` methods that declare checked exceptions would catch
the Java half of this codebase and is **structurally unable** to see the Kotlin half, because
§3.2 shows the `Exceptions` attribute is absent from the Kotlin class file. A gate that is green
on half a mixed codebase for reasons unrelated to correctness is the exact failure
`TransactionBoundaryRulesSelfTest` exists to prevent. **It is not shipped, and this paragraph is
why.**

## 8. 남는 위험 / Remaining risk

- ⭐ **`AttemptRecordingServiceTest` is RED on the final tree and it is left red deliberately.**
  §6.1 has the analysis. The assertion it fails is `expected: <0.000> but was: <null>`, and the
  right repair is **not** to flip the literal back: this `null` has a different cause from the
  pre-`R12` `null`, and a test that prints the same value for three different mechanisms is
  exactly what `R12` wrote that class to demonstrate. ⛔ **Editing the assertion without
  rewriting the KDoc that names the causes would destroy the point of the test.** The proposed
  repair — assert `null`, name all three causes, keep pointing at
  `AttemptRecordingAtomicityTest` as the test that holds the property — is written down here
  rather than applied, because three reports depend on that class and the change deserves its own
  verification rather than being folded into someone else's last commit.
- ⭐ **THE REMEDY DOUBLES CONNECTION DEMAND ON THE RECORDING PATH, AND THE PRICE IS MEASURED
  RATHER THAN SPECULATED — BY TWO OTHER SLICES, NEITHER OF WHICH COULD SEE THIS LINE.**
  Slice E measured `REQUIRES_NEW` holding **2 connections** for the duration of the inner call,
  against `NESTED` refused outright. Slice D measured this application's pool at **10**, with
  roughly **115 of 200 workers blocked waiting for one** — and **zero WARN, zero ERROR across 54
  log lines, at a `0.00 %` error rate.** So a batch of *n* recordings from a transactional caller
  now wants two connections at a time instead of one, against a pool D has just shown is the
  binding constraint. **D measured a cost, E measured a mechanism, and the two only meet here.**
  See `R29` (pool) and slice E's `R38` (connection count).
- ⚠️ **And the pool cannot report its own exhaustion.** `0.00 %` error rate with 115 workers
  queued means the queue is invisible: if this price is ever paid it will be paid **silently**.
  That is not an argument for `REQUIRED` — under `REQUIRED` the same caller loses the whole batch
  — but it is a precondition somebody has to decide about.

  ⛔ **This is a judgement, not an errand, so it is an `open.md` row rather than a ledger
  entry.** `ADR-014` is for things measurable-but-not-done, with a cost in minutes. There is no
  number of minutes that settles *whether observability should come first*; that is a trade
  someone chooses. `R19` §7 is why this repository draws that line, and `OPEN-6` is the
  precedent for what it costs when nobody asks. **Drafted for `docs/decisions/open.md`:**

  > **The recording path may not acquire a transactional caller until the pool queue is
  > observable.** `R29` measured the pool as the binding constraint and measured that its
  > exhaustion produces **no WARN, no ERROR and a `0.00 %` error rate**. `R40` doubles connection
  > demand on that path when — and only when — a transactional caller exists. The two are safe
  > today because neither condition holds. Deciding whether observability comes first is **not
  > work, it is a trade.**
  >
  > ⭐ **Slice D measured the cost, slice E measured the mechanism, slice G found where they
  > meet.** None of the three could see it alone.
- **The price is zero today and *inert* is not *risk-free*.** With no outer transaction,
  `REQUIRED` and `REQUIRES_NEW` are the same path, one connection, one commit. Nothing that
  exists pays anything. It becomes payable the moment a transactional caller appears — which is
  precisely the event the annotation is there for, so the cost and the benefit arrive together
  and neither is live before then.
- **The caller can no longer abandon the batch by rolling back.** What succeeded is committed.
  A real loss of capability, and the same trade `R14` made between recordings, extended to the
  boundary with the caller.
- **The checked-exception half is measured on probes and is not fixed.** No `@Transactional`
  method in `api/src/main` raises a checked exception today, so it is **latent** — a property of
  the framework and the language this application has not yet met. `rollbackFor` is available and
  applied nowhere. ⛔ A weaker claim than a live defect, and stated as one.
- **No static gate for the checked-exception half is possible in Kotlin.** §7. Ledger `40.3`.
- **No timing was taken anywhere in this report.** Whether the remedies cost throughput is 미측정
  in this slice; the connection *count* comes from E and the pool *behaviour* from D, both on this
  machine today, and neither is a throughput measurement of this path.
- **What would break the conclusion:** a Spring release changing the default rollback rule, or a
  Kotlin release emitting an `Exceptions` attribute for exceptions it can prove escape — the
  second would make §7's gate buildable and this report's §7 stale.
- **Which earlier §8 bullet this falsifies:** none directly. `R1` §8 says *"`REQUIRES_NEW` is used
  in `RecordingFixture` and its cost is unmeasured here"* — still true, and this report adds a
  second shipped use of it with the same cost still unmeasured *as a duration*, now with E's
  connection count attached.

## 9. 배운 것 / What I learned

`R1`을 다시 읽고 시작했는데, 그 리포트가 고친 건 **경계가 어디에 있는가** 였고 내가 만난 건
**무엇을 실패로 칠 것인가** 였다. 애노테이션 하나가 두 가지를 결정하는데 사람들은 하나만 읽는다.
나도 그랬다. 프록시를 넘어갔고 인터셉터가 돌았고 트랜잭션이 열려 있었으니 다 된 줄 알았다. 행은
남아 있었다.

제일 이상했던 건 **자바 쪽을 실제로 써 보기 전까지는 이게 언어 문제인지 프레임워크 문제인지 말할 수
없었다**는 것이다. 브리프가 "둘 다 써 보라"고 했을 때는 대조군 정도로 생각했는데, 클래스 파일을 열어
보니 `javac`는 `Exceptions` 속성을 쓰고 `kotlinc`는 안 쓴다. 같은 예외, 같은 애노테이션, 같은 결과,
**다른 흔적**. Spring의 기본값은 자바를 정확하게 읽은 것이고, Kotlin은 그 읽기가 의존하던 절반을
없앴다. 둘 다 각자는 옳다. 그래서 §7에서 게이트를 만들지 않기로 한 것이, 못 만든 게 아니라
**만들면 절반만 초록인 게이트가 된다는 걸 측정으로 알게 된 것**이라 마음이 편했다.

그리고 오늘 제일 크게 배운 건 내 슬라이스 밖에서 왔다. 나는 `REQUIRES_NEW`가 "커넥션 하나 더"라고
적어 두고 미측정으로 넘길 생각이었다. 오케스트레이터가 D와 E의 오늘 측정치를 붙여 주고 나서야 그게
**풀 10개짜리, 200 워커 중 115가 대기하는, 그리고 에러율 0.00%로 그 대기가 보이지 않는** 시스템에
대한 두 배 요구라는 걸 알았다. D는 비용을 쟀고 E는 메커니즘을 쟀는데, **둘이 만나는 지점이 내가 고친
한 줄**이었다. 세 세션 중 누구도 혼자서는 볼 수 없었다.

그래서 이 리포트의 §8은 내가 쓴 것 중 처음으로 **다른 사람이 잰 숫자 때문에 결론이 바뀌지는 않았지만
가격표가 붙은** 절이다. 고치는 게 맞다는 판단은 그대로다 — 대안은 싼 게 아니라 조용히 배치를 통째로
잃는 것이니까. 다만 "공짜"라고 쓸 뻔한 자리에 **잰 숫자와 그것을 잰 사람**이 들어갔고, 그 차이가
이 저장소가 하는 일의 전부인 것 같다.
