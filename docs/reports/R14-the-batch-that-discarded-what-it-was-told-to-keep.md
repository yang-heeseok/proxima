# R14. The batch that discarded what it was told to keep

> **Created**: 2026-08-14
> **Updated**: 2026-08-14
> **Red commit**: this one, behind `proxima.recording.batch=stop-at-first-failure` — the loop
> that shipped from `21e7162` until now. A property rather than a separate commit, for the
> reason `R12`'s header gives: **the red state is already the whole history of this
> repository.**
> **Green commit**: this one — every recording attempted, every outcome returned
> **Discharges**: `AttemptRecorder`'s *"What this still does not solve"*, open since `T3`

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL : Testcontainers postgres:16-alpine -- server 16.14
  Framework  : Spring Boot 4.1.0, Hibernate 7.4.1.Final
  Batch      : 5 recordings, deltas 0.100 / 0.100 / 1.500 / 0.100 / 0.100
               The third leaves the 0..1 band and is rejected by R12's guard --
               a DOMAIN rejection, which is the case a batch API has to be right about
  Load       : none. These are row counts
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`AttemptRecorder`'s KDoc has said since `T3` why the unit of work is one recording and not the
batch:

> attempts are independent events, and **one learner's invalid submission is not a reason to
> discard the valid ones recorded beside it.**

And then, in the same file, under *What this still does not solve*:

> `AttemptRecordingService.recordAll` stops at the first failure, so a batch may be partially
> recorded and the caller is not told which recordings landed.

**Those two paragraphs contradict each other and sat three inches apart for four days.** The
loop discards the valid recordings after the invalid one — it never attempts them — which is
exactly what the domain decision above says must not happen.

The second paragraph also called it *"a known gap rather than an oversight"*. It was never
measured. `R12` is why that distinction now gets acted on: `R6` §8's three true premises
supported a false conclusion and stood for three days because nobody tested them.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.recording.PartialBatch*"
```

Five recordings, the third invalid. Both arms in one binary — `R4` §2:

| `proxima.recording.batch` | behaviour |
| --- | --- |
| `stop-at-first-failure` | the rejection propagates out of `recordAll`. **`red`** |
| `per-item-outcomes` | every recording is attempted and its result returned. **ships** |

## 3. 계측 / Measurement

| | what the caller received | `attempt` rows | `attempts_count` | `score` |
| --- | --- | --- | --- | --- |
| **red** | `threw IllegalArgumentException`, and nothing about which landed | **2** | 2 | 0.200 |
| **green** | `ok, ok, rejected, ok, ok` | **4** | 4 | 0.400 |

**Four of the five recordings are valid. The red arm records two of them.**

Recordings four and five are not rejected — they are **never attempted**. Nothing anywhere
says so: the caller holds one `IllegalArgumentException` about recording three and has no way
to distinguish *"four and five were rejected"* from *"four and five were never tried"* from
*"four and five landed"*.

### 3.1 The rejection carries its reason, and that is `R12`'s doing

```
index 2: IllegalArgumentException: mastery score would reach 1.700, which is outside the 0..1 band
```

`1.700` is `0.200 + 1.500` — the two recordings already applied, plus the bad delta. The
message can name that number **because the transaction was still healthy when it was
composed**: `R12` moved the band from `ck_mastery_score` into a `WHERE` predicate, so a
refusal is zero rows rather than an aborted transaction, and the row can still be read.

Behind the constraint, that read would itself have failed. **A decision made for one reason in
`R12` is what makes this report's error messages useful**, which was not why it was made.

### 3.2 There is no `NotAttempted`, and that is the finding stated as a type

`RecordingOutcome` was drafted with three cases: `Recorded`, `Rejected`, and `NotAttempted`.
The third was removed because **nothing can produce it.** Under the red arm no list is
returned at all — the caller gets an exception and nothing else.

The recordings that were never attempted have no representation because **there was nowhere to
represent them.** That is the defect, and it is visible in the type before it is visible in a
number.

## 4. 원인 / Mechanism

`recordings.forEach { recorder.record(learnerId, it) }` propagates the first exception out of
the loop. Each recording is its own transaction — `T3`'s fix, and it is correct — so the ones
already committed stay committed. The ones after the failure are never reached.

**Per-recording atomicity and stop-at-first-failure are individually reasonable and jointly
produce a partially applied batch nobody can reconcile.** Neither half is the defect. The
combination is, and it is invisible in either file on its own.

## 5. 처방 / Remedy

| Option | Why not |
| --- | --- |
| make the batch one transaction | reverses `T3`'s recorded domain decision — one bad submission would discard every valid recording beside it. Reversing a decision that was made with a reason needs a reason of equal weight, and there is none |
| idempotency keys, so the caller can retry safely | needs a schema change and a key generated by somebody. **There is no client** — `recordAll` has no caller outside tests — so this would be a contract with an absent party |
| **attempt everything, return per-item outcomes** | **✔** additive: it changes what is *reported*, not what is *stored*, and leaves both options above implementable later without rework |

**Continuing past a rejection is not a softening of the rule. It is the rule** — the one
`AttemptRecorder`'s KDoc already stated and the loop did not implement.

## 6. 재계측 / Re-measurement

§3. Valid recordings applied: **2 of 4 → 4 of 4.** Caller's information: an exception naming
one failure → five outcomes naming all five.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/recording/PartialBatchTest.kt`, class
`PartialBatchGateTest`, run by `.github/workflows/build.yml`. Five exact assertions:

- the caller is told about **every** recording it submitted — five outcomes for five inputs
- **four** are `Recorded`
- the rejected one is at **index 2** — so a pass cannot come from rejecting the wrong recording
- `attempt` rows are **exactly four**, so the outcomes and the database cannot disagree
- `score` is **exactly `0.400`**, four applications of `0.100`

No property override, so it shares `AttemptRecordingAtomicityTest`'s cached context and
asserts what production is configured to do.

`PartialBatchTest` is the red arm's own test. It asserts only that fewer than four valid
recordings land — the number is a consequence of where the invalid one sits, and pinning it
would be pinning the fixture rather than the behaviour.

## 8. 남는 위험 / Remaining risk

- **The catch is deliberately broad, and that is a trade rather than an oversight.** A lock
  timeout, a lost connection and an invalid score all become `Rejected`. For a domain
  rejection that is right. For a database that has gone away it means the batch makes **five
  failed round trips instead of one**, and reports five rejections that are really one
  outage. Narrowing it to `IllegalArgumentException` would fix that and would reinstate the
  red arm's defect for infrastructure failures — the caller would learn nothing about the
  rest. **Both directions are wrong in some case and this one is wrong in the rarer one.**
  Unmeasured either way.
- **There is still no endpoint.** `recordAll` has no caller outside tests, so this decides the
  shape of an API with no consumer. It was cheap for exactly that reason, and it is not
  validated by anybody's requirements.
- **The outcomes are returned, not acted on.** Nothing retries a rejection, nothing reports it
  to a learner, and no HTTP status has been chosen for *"four of five landed"*. That is the
  next decision and it needs the endpoint that does not exist.
- **Order is not part of the contract.** Recordings are applied in list order today because
  `mapIndexed` is sequential. Nothing says so and nothing tests it, and a future concurrent
  implementation would change the `score` arithmetic in §3 without failing the gate.
- **One shape of batch was measured.** Five recordings, one invalid, in the middle. An invalid
  *first* recording, several invalid ones, or a batch that is entirely invalid are 미측정.
- **What would break the conclusion**: a requirement that a batch is atomic — a marking run,
  say, that must not be half-applied. §5 rejects that on the grounds that no such requirement
  exists. **If one arrives, it does not modify this decision; it replaces it.**

## 9. 배운 것 / What I learned

**같은 파일 안에서 두 문단이 서로를 부정하고 있었다.**

`AttemptRecorder`의 KDoc은 작업 단위가 한 건인 이유를 *"한 학습자의 잘못된 제출이 옆에 기록된 유효한
것들을 버릴 이유는 아니다"* 라고 적어놨다. 그리고 같은 파일 아래에 *"recordAll은 첫 실패에서 멈춘다"*
고 적어놨다. **루프는 정확히 앞 문단이 금지한 일을 하고 있었고, 그 두 문단은 세 인치 떨어져 나흘을
같이 있었다.**

문서가 서로 모순될 때 눈에 안 띄는 이유를 알겠다. 각 문단이 **자기 자리에서는 옳기 때문**이다. 첫째는
왜 트랜잭션이 거기 있는지 설명하고, 둘째는 알려진 한계를 정직하게 적는다. **둘을 같이 읽어야만
모순이고, 같이 읽을 이유가 없었다.**

**그리고 "요구사항이 필요하다"는 말이 무엇을 가리고 있었는지.**

나는 이 항목을 *"리팩터링이 아니라 요구사항이 필요하다"* 고 기록해뒀다. 맞는 말이었고, **그래서
아무것도 재지 않았다.** 요구사항이 필요한 것은 *어떤 형태로 고칠지*였고, **지금 무슨 일이 일어나는지는
요구사항 없이도 잴 수 있었다.** 재보니 유효한 4건 중 2건이 사라지고 있었다. 그 숫자를 알고 나면 세
선택지 중 두 개는 저절로 떨어져 나간다.

R6 §8에서 배운 것과 같은 모양이다 — **막고 있는 것이 사실인지 논증인지 구분해야 한다.** 그때는 참인
전제 세 개가 틀린 결론을 받치고 있었고, 이번에는 참인 문장 하나가 **재보지 않을 이유**로 쓰이고 있었다.
