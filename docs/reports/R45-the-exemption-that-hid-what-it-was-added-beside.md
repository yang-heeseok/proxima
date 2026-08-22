# R45. The exemption that hid the defect it was added beside

> **Created**: 2026-08-22
> **Updated**: 2026-08-23
> **Red commit**: `77022a5` — the rule as shipped since `R7`. It passes on a self-invocation it
> is written to refuse, and the three arms in §3 are how that was established
> **Green commit**: `4726416` — *"this one"* when written; the rule looks through the
> bridge instead of exempting it. Resolved to a SHA by slice `F`, same reason as `R44`'s
> **Found by**: `R44` §3.5. The rule caught step 4's self-invocation **once** when the same tree
> contained **two**, and the missing one is this report

```
증거 / What the evidence here is
  Not durations. This is a gate measured against itself, so the template's hardware block
  would be hardware none of these numbers came from -- R0, R17 and R43 do the same.

  Instrument   : ./gradlew :api:test --tests '*TransactionBoundaryRules*' --rerun-tasks
                 Both the rule and its self-test, so the negative half is never skipped
  Subject      : TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED, in
                 api/src/test/kotlin/net/gseek/proxima/arch/TransactionBoundaryRules.kt
  Arms         : ONE defect, THREE spellings of the same call. Nothing else varies --
                 same two methods, same class, same annotations, one line changed
  Repetitions  : deterministic over a fixed tree. One run per arm per rule state
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

While implementing step 4 (`R44`), `TransactionBoundaryRulesTest` refused the service and named
one violation:

```
RecommendationService.evidenceFor(...) is called from RecommendationService.difficultyBandFor(...),
inside its own class, so the call does not reach the proxy and @Transactional has no effect
```

**There were two.** `nextRows` called `difficultyBandFor` — also `@Transactional`, also inside
its own class, also through `this`. The rule said nothing about it.

The only difference between the violation it caught and the one it missed is **whether the caller
supplied a default argument.**

**And the finding is not that the rule was wrong.** The rule is careful, it is self-tested, and
the line that blinded it was added deliberately, for a correct reason, with that reason written
out beside it: `R7` §3.5 measured the rule flagging every `@Transactional` method that has a
default argument and excluded the bridges, because *"a rule that is routinely wrong is a rule
nobody reads"*. **That sentence was true when it was written and is true now.** Nothing about
`R7`'s judgement is being reversed here.

What this report is about is what the exception cost, which nobody measured:

> **A justified exception is a place defects hide.** The exclusion was correct about the case it
> was written for and silently took a second case with it, and the two cases are indistinguishable
> from inside the rule — both arrive as *an access whose origin is a bridge*. The exception did not
> need to be wrong to become a blind spot. It only needed to be **wider than the reason for it**,
> and nothing counted the difference.

That is the class, and it is reusable. `R43` §3.3 narrowed a check to KDoc for an equally good
reason and **counted what fell outside: 172 of 552 comment blocks.** `R7` did not count, and this
is what was in the uncounted part. The difference between the two is not the quality of the
judgement — it is whether a number was attached to it.

## 2. 재현 / Reproduction

```bash
git checkout 77022a5
# in RecommendationService, have nextRows call a @Transactional method of its own class
# ARM B: difficultyBandFor(learnerId)                  -- default omitted
# ARM C: difficultyBandFor(learnerId, RECENCY_BASIS)   -- default supplied
./gradlew :api:test --tests '*TransactionBoundaryRules*' --rerun-tasks
```

## 3. 계측 / Measurement

One defect. One line changed between arms. `A` is the shipped remedy — a private, unannotated
method — and is the control that must stay green in both rule states.

| arm | the call in `nextRows` | rule at `77022a5` | rule after this commit |
| --- | --- | :---: | :---: |
| **A** | `evidence(learnerId, RECENCY_BASIS)` — private, unannotated | SUCCESSFUL | SUCCESSFUL |
| **B** | `difficultyBandFor(learnerId)` — **default omitted** | **SUCCESSFUL** ← blind | **FAILED** |
| **C** | `difficultyBandFor(learnerId, RECENCY_BASIS)` — default supplied | FAILED | FAILED |

Arm A green in both columns is the half that matters: the fix did not buy its new finding by
becoming indiscriminate, and the self-test's planted violation is still refused in the same run.

## 4. 원인 / Mechanism

`R7` §3.5 found the rule flagging Kotlin's synthetic `$default` bridges and taught it to skip
them. The KDoc on that exclusion is exact about why:

> *"Without this exclusion the rule reports every `@Transactional` method that has a default
> argument, and **a rule that is routinely wrong is a rule nobody reads**."*

The exclusion is one line:

```kotlin
.filterNot { (it.origin as? JavaMethod)?.isKotlinDefaultArgumentBridge() == true }
```

**It drops accesses whose *origin* is a bridge — and when the default is omitted, the bridge is
the only origin there is.** Kotlin compiles `nextRows` → `difficultyBandFor(learnerId)` as

```
nextRows  ->  difficultyBandFor$default(this, learnerId, 2, null)  ->  difficultyBandFor(...)
```

so the only access to the annotated method comes *from the bridge*, and is filtered out. The
access from `nextRows` lands on `difficultyBandFor$default`, which carries no `@Transactional`
and is therefore never a subject of the rule. **The defect falls between the two.**

**Why looking through the bridge is sound rather than a widening.** `$default` is a static method
taking the receiver as its first argument. Called from another class, the receiver is the injected
**proxy**, the forwarded call is advised, and there is no defect. Called from inside the owning
class, the receiver is `this`, the forwarded call is not advised, and the annotation does nothing.
So a bridge access is a violation exactly when **the bridge itself is called from within the
owning class** — which is decidable from the same bytecode the rule already reads.

`R7`'s reason is untouched: the bridge's own call to its target is compiler plumbing and is still
never reported. What changed is that the bridge is no longer a wall.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave it | The rule keeps catching the spelling with an explicit argument and missing the spelling without one | zero | no |
| Remove `R7`'s exclusion | Catches everything | Restores the false positive `R7` measured and rejected — *"a rule that is routinely wrong is a rule nobody reads"* | no |
| Ban default arguments on `@Transactional` methods | Decidable, and would have caught this | Refuses correct code for the convenience of a rule. `ADR-007` reason 3 | no |
| **Follow the bridge to its callers** | Catches both spellings, keeps `R7`'s exclusion for the plumbing it was written for | ~10 lines, and one more graph hop per bridge access | **yes** |

**What would have made a different option correct.** If `$default` bridges could be reached from
outside the owning class in a way that also bypassed the proxy, the receiver argument would not
be a sound discriminator and only the blunt options would remain. It cannot: the bridge takes the
receiver it is given.

## 6. 재계측 / Re-measurement

Identical conditions to §3 — same arms, same command, same tree apart from the rule.

| | `77022a5` | this commit |
| --- | ---: | ---: |
| arm A — the shipped remedy | green | green |
| arm B — default omitted | **green (wrong)** | **red** |
| arm C — default supplied | red | red |
| self-test: planted violation still refused | yes | **yes** |
| spellings of one defect the rule catches | **1 of 2** | **2 of 2** |

## 7. 회귀 게이트 / Regression gate

The rule **is** the gate, so the gate on the gate is
`api/src/test/kotlin/net/gseek/proxima/arch/TransactionBoundaryRulesSelfTest.kt`, which plants a
violation and requires the rule to refuse it. It passed before this change and passes after, which
is what establishes that the fix did not achieve its new finding by becoming indiscriminate.

**That self-test does not contain arm B.** It plants a self-invocation with an explicit argument,
which is arm C — the spelling that was already caught. **So nothing in the tree would catch this
regressing**, and adding a default-argument fixture to the self-test is §8's first bullet rather
than something this commit did.

## 8. 남는 위험 / Remaining risk

- **The self-test has no default-argument arm.** The exact defect this report is about could be
  reintroduced by reverting ten lines and every test here would stay green. The fixture is
  obvious and is **not written**: it is `43.x`-shaped work, and it is named rather than done.
- **Eight other rules live in this file and none was examined for the same shape.** The pattern —
  *an exclusion added to silence a false positive, which also silences a true one* — is not
  specific to this rule. `45.1` in `ADR-014`'s ledger, class **a**, 60 minutes. **미측정.**
- **The bridge hop is one level deep.** A bridge called from another bridge, or a call reaching a
  `@Transactional` method through some other synthetic — an inline-class mangled name, a
  `suspend` state machine, a lambda's `invokeSuspend` — is not followed. Nothing in this
  repository is `suspend` today, so this is unexercised rather than known-good. **미측정.**
- **This rule reads bytecode and answers *can this work?*, not *does it?*** It still cannot see a
  self-invocation performed reflectively or through an injected self-reference, which is the
  standard workaround and is not banned anywhere here.
- **What would break this conclusion.** A Kotlin version that changes the `$default` naming
  convention or the receiver-passing convention. `isKotlinDefaultArgumentBridge` matches on the
  literal suffix `$default`; a change there makes the rule silently stop following bridges and
  return to the `77022a5` behaviour, **green and blind**, with nothing failing.
- **Which earlier §8 bullet this report falsifies.** None directly. `R7` §3.5 recorded the
  exclusion as a fix and did not claim it was complete, so this does not contradict it — it
  finishes it. What it does contradict is the implicit reading, standing since `R7`, that
  `TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED` catches self-invocation. It caught half of it.
- **Does anything here need a judgement rather than work?** No. Every bullet above is an errand
  with a cost. They are in `ADR-014`'s ledger as such.

## 9. 배운 것 / What I learned

이 결함은 제가 찾은 게 아닙니다. **게이트가 저를 잡았고, 저는 그 게이트가 한 번만 울린 게
이상하다고 생각했을 뿐입니다.** step 4를 제일 자연스러운 모양으로 짰더니 그게 `R1`이었고 —
`@Transactional` 메서드끼리 `this`로 부르는 것 — `TransactionBoundaryRulesTest`가 첫 실행에서
거절했습니다. 리포트를 쓴다고 결함에 면역이 생기지 않는다는 `R9`의 주제를 제가 한 라운드에
두 번 실증했습니다.

그런데 위반이 **1건**이라고 나왔습니다. 저는 같은 클래스에서 `@Transactional` 메서드를 두 군데
호출하고 있었는데요. 여기서 "아 하나는 규칙이 알아서 걸러줬나 보다" 하고 넘어갈 수도 있었고,
솔직히 넘어갈 뻔했습니다. 세 팔로 나눠서 재보니 차이는 **기본 인자를 넘겼는지 하나뿐**이었고,
안 넘긴 쪽이 통과했습니다.

제일 배운 건 이겁니다. `R7` §3.5의 제외는 **틀린 조치가 아니었습니다.** 오탐을 없앤 정당한
수정이었고, KDoc에 이유까지 정확히 적혀 있었습니다 — *"routinely wrong인 규칙은 아무도 읽지
않는다"*. 그 문장은 지금도 맞습니다. 그런데 **오탐을 없애는 그 한 줄이 정탐도 같이 없앴고,
아무도 그걸 재보지 않았습니다.** 이 저장소가 계속 마주치는 모양입니다 — `R17` §5의 산문 검사,
`ADR-007`의 세 번째 이유, 그리고 오늘 `R43`에서 제가 KDoc으로 범위를 좁힌 것도 같은 종류의
거래입니다. **좁히는 건 공짜가 아니고, 무엇이 같이 빠졌는지는 세어봐야만 압니다.** `R43`에서는
172개라고 세었는데, `R7`은 세지 않았습니다. 저도 `R43`에서 세라고 시켜서 센 거지 스스로 센 게
아닙니다.
