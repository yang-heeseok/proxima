# R36. A flag one thread wrote and another never saw

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `ba52381` — the spin loop reads a plain field, and the test asserts the
> write is seen
> **Green commit**: **this one** — `@Volatile`, and the defect pinned as a characterisation
> **Answers**: nothing that was previously written down. This is a trap the slice brief warned
> **may not reproduce**, and the brief's instruction was that *not observed* would itself be
> the result. **It reproduced.**

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  JVM            : Temurin 21.0.12+8, -Xmx512m (the Gradle test worker's default here)
  PostgreSQL     : NOT USED. No container, no Spring context. The defect is in the heap.
  Spin bound     : 2,000,000,000 iterations — A CHOSEN PARAMETER, NOT A MEASUREMENT
  Trials         : 3 per arm per invocation, both arms in the same invocation, warmed
                   identically. TWO invocations — red (ba52381) and green. Both reported.
  WHAT ELSE WAS RUNNING ON THIS MACHINE: slice D's chained full test runs and slice G, on
                   other worktrees. Three Gradle daemons.
                   THE VERDICT HERE IS A BOOLEAN PER TRIAL — did the loop stop before the
                   bound — AND IT DOES NOT CONTEND. §2.1 says why load cannot manufacture
                   this result and can only ever hide it.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

One thread sets a `Boolean` field to `false`. Another thread, spinning on that field, **never
sees it** — not once in six trials across two invocations, and never within two billion reads.

There is no exception, no deadlock, no lock, and no contention. One writer, one reader, one
boolean. A torn `boolean` is not a thing, so there is nothing here that atomicity would fix.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests net.gseek.proxima.mastery.MemoryVisibilityTest --rerun-tasks
```

At `ba52381`. `VisibilityFlag` holds the same field twice — once plain, once `@Volatile` — and
the same loop reads each. The reader signals that it has entered the loop, the writer sets the
flag, and the reader is joined.

**The instrument is a pair and the pair is the whole design.** The `@Volatile` arm is a control
that runs in the same JVM, in the same test method, warmed the same way, in the same
invocation. Without it, *the loop did not exit* and *the writer never ran* are the same output
— which is `ADR-015`'s finding arriving in a package that has nothing to do with databases.

### 2.1 Why this verdict survives a loaded machine

Slices D and G were running. That would matter if the result were a duration; it does not
matter here, and the reason is worth stating because it is not obvious.

- **Load cannot manufacture this result.** More load means more context switches and more
  scheduling churn, which can only make a write *more* likely to become visible. A machine
  under load is a machine biased **against** observing this defect.
- **Load cannot fake the writer having run.** The control arm terminated in all six trials, in
  the same invocations, on the same loaded machine. So the writer thread was scheduled and did
  its work.

The direction is therefore safe: a loaded machine could have hidden this defect and could not
have invented it.

### 2.2 The bound, and why it is a parameter rather than a number

An unbounded spin on a hoisted read never returns. A test that left a thread burning a core for
the rest of the JVM's life would corrupt every measurement taken after it — **including
measurements belonging to another slice on this machine**, which is not a hypothetical this
week.

So the loop stops at `bound` and returns the iteration it stopped at. `bound` means the write
was never observed; anything less means it was. **The verdict is the boolean.**

The loop is also warmed — three passes at 50,000,000 before the trial — because without that
the reader spends the interesting window in the interpreter, which re-reads the field every
time. A *not observed* verdict from an un-warmed run would be a statement about the warm-up.

## 3. 계측 / Measurement

Run 1 — red, at `ba52381`:

```
E3 >>> bound=2000000000 trials=3
E3 >>> @Volatile control   observed=3/3  stoppedAt=[699146, 163442, 190892]
E3 >>> plain field         observed=0/3  stoppedAt=[2000000000, 2000000000, 2000000000]
```

Run 2 — green, this commit:

```
E3 >>> bound=2000000000 trials=3
E3 >>> @Volatile control   observed=3/3  stoppedAt=[165766, 7168, 168722]
E3 >>> plain field         observed=0/3  stoppedAt=[2000000000, 2000000000, 2000000000]
E3 >>> verdict: plain observed 0/3, control 3/3
```

| arm | trials | **write observed** | stopped at |
| --- | --- | --- | --- |
| `@Volatile` — the control | 3 + 3 | **6 of 6** | 699,146 / 163,442 / 190,892 — then 165,766 / 7,168 / 168,722 |
| plain field — the defect | 3 + 3 | **0 of 6** | 2,000,000,000 — *the bound, all six times* |

⭐ **Two invocations, six trials, and the verdict did not move once.** That matters more here
than it usually would: `R35`, written from the same pair of invocations, had its headline number
change by 4× between run 1 and run 2. **This one did not change at all** — which is what §4
predicts, because a hoisted read is a compiler decision rather than a race outcome.

**The plain arm did not merely stop late. It never stopped**, in any trial of either
invocation, and every figure is the bound exactly.

⛔ **The `stoppedAt` figures are not a rate and must not be read as one.** They are the
iteration the loop happened to be on. The control's six values span 7,168 to 699,146 — nearly
100× — which is scheduling noise on a loaded machine and is exactly why the *magnitude* carries
no information here. The only thing being claimed is `< bound` against `== bound`, and
on that the two arms do not overlap or come close to overlapping.

## 4. 원인 / Mechanism

There is no happens-before edge between the writing thread and the reading thread. The Java
memory model therefore does not require the reader to ever observe the write, and — this is the
part that turns a permission into an event — it lets the JIT **hoist the read out of the loop**.

`while (i < bound && plainRunning)` is compiled, once C2 has seen it enough times, into
something equivalent to `if (plainRunning) while (i < bound)`. The field is read once and the
loop never looks again. **That is a correct compilation of the program as written**, because
the program never asked for the field to be re-read.

`@Volatile` forbids exactly that. The write happens-before every subsequent read of the same
field, so the read cannot be hoisted, and the loop must terminate. The keyword does not make
the write *faster*; it makes it **required to be visible**.

**This is why the brief expected it might not reproduce, and why it did.** On x86 the hardware
is strongly ordered, so if the effect were a memory-system delay it would be rare and brief.
It is not a memory-system effect. It is a compiler decision, and once the warm-up guarantees
C2 has compiled the loop, it is not a race at all — it is deterministic on this JVM. Six of
six, at the bound exactly, across two invocations two minutes apart.

## 5. 처방 / Remedy

| Option | Correct | Cost | Chosen |
| --- | --- | --- | --- |
| plain field | **no** — 0 of 6 | none, and wrong | |
| **`@Volatile`** | **yes** — 6 of 6 | one keyword; a read that cannot be cached in a register | **✔** |
| `AtomicBoolean` | yes | an object, and an API where a field would do | |
| `synchronized` around both | yes | mutual exclusion for a problem that has no mutual-exclusion component | |
| an interrupt instead of a flag | yes | different shape; the JDK's own answer for cancellation | |

**`@Volatile`.** It is the smallest thing that establishes the edge the program was missing.

The other three are correct and each buys something this problem does not need — an allocation,
a lock, or a redesign. `AtomicBoolean` is the right answer the moment the flag needs
compare-and-set, which is `E1`'s subject and not this one.

⭐ **What makes this trap worth a report is not the remedy — everyone knows the remedy.** It is
that the defective version **passes every single-threaded test that can be written against it**,
and the failure mode is *silence*: no exception, no log line, and a background thread that
simply keeps running after it was told to stop. The `E2` report reaches the same conclusion by a
completely different route, and `R37` a third time. That is three of this slice's five traps
whose entire symptom is that nothing is reported.

## 6. 재계측 / Re-measurement

Same invocation, same warm-up, same bound.

| Metric | plain field | `@Volatile` |
| --- | --- | --- |
| trials observing the write | **0 of 6** | **6 of 6** |
| stopped at the bound | 6 of 6 | 0 of 6 |

Across both invocations.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/mastery/MemoryVisibilityTest.kt`, run by
`.github/workflows/build.yml`.

The gate has two assertions and they are deliberately different in strength:

- **The control is exact**: `3 of 3` must observe. Its failure message says that if *this* line
  is red, the harness is broken and the other assertion must not be read at all.
- **The defect is characterised loosely**: *at least one* trial must fail to observe. It was
  `0 of 3` in each of two invocations — but the effect is a JIT decision, and CI runs on hardware
  this repository does not own. **An exact assertion would convert a real finding into a flaky
  gate**, which is the failure that survives longest: `R16` had `rate >= 0.0` in three tests at
  once, and `ADR-015` is the round-two version of the same lesson.

## 8. 남는 위험 / Remaining risk

- **This is one machine, one JVM, one JIT.** `0 of 6` is a fact about Temurin 21.0.12+8 on this
  CPU with this bound and this warm-up. **It is not a claim about JVMs in general**, and the
  gate is written loosely precisely because the next JVM may differ.
- ⭐ **"Not observed" and "does not happen" are different, and here the *inverse* also needs
  saying.** This report observed the defect, which proves the JMM permits it and that this stack
  takes it. It does **not** prove that a plain flag always fails — a different bound, a colder
  loop, or an interpreter-only run would have shown the write arriving. **The defect is
  conditional and the conditions are in §2.2.** `R0` §8's sentence about its own denominator is
  the same shape: what an instrument did not see is not the same as what did not happen.
- **The bound was never varied.** `2,000,000,000` was chosen to be far beyond what the writer
  needed and finite enough to be neighbourly. **Whether a smaller bound flips the verdict is
  `미측정`**, and it is the cheapest remaining measurement here.
- **The warm-up was never varied.** Three passes at 50,000,000 is a chosen shape. At what
  warm-up the hoist first appears is `미측정`, and it is the number that would say how quickly a
  real background thread becomes deaf.
- **`stoppedAt` magnitudes are noise and are printed anyway.** They are in §3 so the raw output
  is verbatim, not because they measure anything. Anyone quoting them as a rate is quoting
  scheduling on a machine that was running two other slices.
- **No `-Xint`, `-XX:-TieredCompilation` or `-XX:TieredStopAtLevel=1` control was run.** Those
  would establish the mechanism *is* the JIT rather than inferring it from §4's reasoning. It is
  a strong inference — the control arm differs only by a keyword — but it is an inference.
  `미측정`.
- **Nothing in this application has a spin loop on a plain flag.** `VisibilityFlag` was built to
  measure the shape, and there is no production caller. So this report proves a class of defect
  is reachable here and **not** that it is present here — the same standing this repository gives
  `ADR-019`'s unbanked guard, and it should not be read as a fix to anything shipped.
- **What would break the conclusion:** a JVM that stops hoisting the read. §7's loose assertion
  is written to go red rather than silently pass if that day comes.

## 9. 배운 것 / What I learned

**재현 안 될 거라고 들었는데 6번 중 6번 다 재현됐다.** 그것도 아슬아슬하게가 아니라 2,000,000,000
번을 다 돌고 나서. 브리프는 "x86에서는 드물다"고 했고 그건 맞는 말인데, **틀린 건 내 머릿속의
원인 모델이었다.** 나는 이걸 하드웨어 캐시 문제로 생각하고 있었다. 그래서 x86이면 금방 보일
테니까 재현이 어렵겠다고. 실제로는 **JIT이 읽기를 루프 밖으로 끌어낸 것**이고, 그건 확률이 아니라
컴파일 결정이다. C2가 루프를 컴파일하고 나면 더 이상 경합이 아니라 결정론이다. 그래서 워밍업을
넣은 순간 "가끔 되는 실험"이 "매번 되는 실험"으로 바뀌었다.

두 번째로 배운 게 계측 쪽인데 더 오래 남을 것 같다. **경계값(bound)을 넣은 게 처음엔 타협이라고
생각했다.** 무한 스핀이 정직한 실험이고 나는 옆 슬라이스 때문에 어쩔 수 없이 잘라낸 거라고. 그런데
쓰다 보니 경계값이 있어야 **판정이 불리언이 된다.** 무한 스핀이면 "아직 안 끝났다"랑 "영원히 안
끝난다"를 구분할 방법이 없어서 결국 타임아웃을 봐야 하고, 그럼 그게 시간 측정이 된다. 잘라낸 덕에
이 보고서는 **지속시간을 하나도 안 쓰고** 결론을 냈다. 제약이 방법을 개선한 경우다.

세 번째. 대조군을 넣을 때는 "형식이니까" 하는 마음이 좀 있었는데, 이번엔 대조군이 **결과를 읽을
수 있게 만든 유일한 이유**였다. 기계에 다른 슬라이스 두 개가 돌고 있었으니까, 대조군 없이
"루프가 안 끝났다"만 있었으면 그게 JMM인지 스케줄링인지 나는 끝까지 몰랐을 것이다. 같은 실행에서
`@Volatile` 팔이 3/3으로 끝나줬기 때문에 "쓰는 스레드는 돌았다"가 증명됐다. ADR-015가 DB 테스트
얘기인 줄 알았는데, 데이터베이스가 하나도 안 들어가는 테스트에서 똑같은 게 필요했다.
