# R40. The annotation is applied, the proxy is crossed, and the row is still there

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Red commit**: `94fe9ee` — the shipped batch path, reached from a caller that has a transaction
> **Green commit**: PENDING
> **Sibling**: `R1` — there the annotation was **never applied**. Here it is applied and still does not roll back.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200, WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres@sha256:cf78e766…0685 — server 16.15,
                   x86_64-pc-linux-musl. NOT the digest measurement-discipline.md names;
                   see the handoff §3 for why that document is wrong.
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counters       : committed row counts, read OUTSIDE the writing transaction;
                   java.lang.reflect Method.getExceptionTypes for the signature comparison
  Dataset        : one row per arm. This defect appears at concurrency 1 and needs no scale
  Load           : none. Every number here is a row count
  Concurrently   : slices D and E were active on this machine. These are row counts —
                   logical facts about the transaction rule — and they do not contend
  Repetitions    : counts, not timings
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

A unit of work raised, and a row it had written was still committed.

That is `R1`'s opening sentence almost word for word, and the point of this report is that
**everything `R1` fixed is still fixed here.** The boundary is on its own bean. The call crosses
the proxy. The interceptor runs. A transaction really is open. And the row is still there.

The second symptom is quieter and has no exception at the place that caused it. A method
catches a failure, logs it, and returns a value. The **caller** then receives an exception,
raised at commit, naming a transaction it never marked — and every write the method made,
including the ones that succeeded, is gone.

`docs/roadmap.md:99` has carried that second case since `T3` in the tree's own words:
*"the swallowed exception / rollback-only case is **not done at all**."*

## 2. 재현 / Reproduction

```bash
export JAVA_HOME=/home/airto/.jdks/jdk-21.0.12+8
./gradlew :api:test --tests 'net.gseek.proxima.basics.RollbackRuleTest' \
                    --tests 'net.gseek.proxima.basics.BatchInsideATransactionTest' --rerun-tasks
```

Requires a reachable Docker daemon. On this machine Docker runs **natively inside WSL2**, so
the command must be run there — Windows cannot reach the daemon at all.

Concurrency is 1. The dataset is one row per arm. **Neither load nor scale is involved.**

⛔ **The test is deliberately not `@Transactional`.** `R1` §4 measured why: a test annotated
`@Transactional` shares one transaction with the code under test, so the code's writes join the
test's and are rolled back by the harness whether or not the code had a boundary of its own. A
test that shares a transaction with the code it is testing **cannot observe that code's
transaction boundaries.** These tests run outside a transaction and count committed rows with a
fresh statement.

### 2.1 The Java arm is the first Kotlin-to-Java type reference in this build's test sources

Worth recording because it was an assumption before it was a fact.

`R1` already put planted violations in Java — `proxima.planted.PlantedFinalEntity` and two others
— because the Kotlin compiler plugins would have fixed a Kotlin plant before the rule could see
it. **But nothing reaches those classes by type.** `TransactionBoundaryRulesSelfTest` finds them
through ArchUnit's `importPackages("proxima.planted")`, which is a string. Verified by searching:
no Kotlin file in `api/src/test/kotlin` names any of the three.

`RollbackRuleTest` imports `net.gseek.fixtures.basics.JavaRollbackProbe` **as a type**, which
makes it the first place in this repository where Kotlin test code depends on Java test code
through the compiler rather than through a string. That is ordinary Gradle joint compilation and
it is expected to work; it had simply never been exercised here, and a report that leaned on it
without saying so would be resting on an untested property of the build.

⛔ It has to be a real Java class rather than a reflective lookup, because **the compiler is half
the finding.** §3.2 reads the `throws` clause back out of the class file, and there is no class
file without a Java compilation.

## 3. 계측 / Measurement

### 3.1 Which exception kinds roll back

PENDING — the measurement window is held for slice D.

⛔ **The rule is not stated here from documentation or from memory** — rule 9. Each arm executes
against a real PostgreSQL through a real proxy and the committed row is counted afterwards.

| Arm | Raised | Committed rows |
| --- | --- | ---: |
| Kotlin, default rule, no failure | | |
| Kotlin, default rule, `RuntimeException` | | |
| Kotlin, default rule, checked `IOException` | | |
| Kotlin, default rule, `Error` | | |
| Java, default rule, checked `IOException` | | |
| Java, default rule, `RuntimeException` | | |
| Kotlin, `rollbackFor = Exception`, checked | | |
| Java, `rollbackFor = Exception`, checked | | |

### 3.2 Where Kotlin and Java diverge — and it is not the behaviour

**MEASURED 2026-08-22**, and this one needed no database at all.

Read from the compiled class files with `javap -p`, at commit `4c25d2b`, after
`./gradlew :api:compileTestKotlin :api:compileTestJava` (BUILD SUCCESSFUL, 4 tasks executed):

```
KotlinRollbackProbe.writeThenThrow(String, Failure)
JavaRollbackProbe.writeThenThrowChecked(String) throws java.io.IOException
JavaRollbackProbe.writeThenThrowRuntime(String)
```

| Method | Declared checked exceptions, in the `Exceptions` attribute |
| --- | --- |
| `JavaRollbackProbe.writeThenThrowChecked` | **`java.io.IOException`** |
| `JavaRollbackProbe.writeThenThrowRuntime` *(control)* | **none** |
| `KotlinRollbackProbe.writeThenThrow` | **none** |

⭐ **Two independent methods, deliberately.** The figures above come from `javap` reading the
class file. `RollbackRuleTest` reads the same fact at run time through
`Method.getExceptionTypes()`. They are different instruments over the same artefact, and a
disagreement between them would mean one of them is wrong rather than the finding being wrong.
`R8` §3.3 is why this repository does not rest a claim on a single counter.

**The control row is doing work.** `writeThenThrowRuntime` is Java, is annotated identically, and
declares nothing — because there is nothing for the Java type system to have recorded. That is
what shows the empty Kotlin row is a **language difference** rather than an artefact of how the
attribute was read.

⛔ **This is the fact §7 rests on, and it is why no ArchUnit rule is shipped for this defect.**
The information a static rule would read exists in one half of this codebase and does not exist in
the other.

### 3.3 The swallowed exception

PENDING.

| | Value |
| --- | --- |
| What the outer method computed for itself | |
| What the caller actually received | |
| Committed rows, the outer's **own** write | |
| Committed rows, the inner's write | |

### 3.4 The same defect in shipped code

PENDING. Five recordings, deltas `0.100, 0.100, 1.500, 0.100, 0.100`, the third invalid — the
same batch `R14` measured, re-run on this base rather than quoted across branches.

| Call path | Outcomes the caller received | Attempt rows committed |
| --- | --- | ---: |
| `service.recordAll(...)` — as shipped, no outer transaction | | |
| `caller.recordAllInsideMyTransaction(...)` — from a `@Transactional` caller | | |

## 4. 원인 / Mechanism

PENDING in its numeric particulars; the structure is:

`@Transactional` decides two separate things and people read it as deciding one. **Where** the
boundary is, which is `R1`'s subject and is correct throughout this report. And **what counts as
a failure**, which is this report's subject and is a *policy* with a default.

That default is expressed as a Java language distinction. Kotlin does not have the distinction —
it has no checked exceptions, no `throws` clause, and no compiler diagnostic when one crosses a
method boundary. The runtime type still exists, so the rule still fires; the signal that used to
accompany it does not.

**That is the answer to "was this a Spring problem or a Java problem".** It is neither, exactly:
Spring's default is a faithful reading of Java, and Kotlin removed the half of Java the reading
depended on. The two are individually defensible and jointly produce a committed row.

The swallowed-exception case is a different mechanism with the same root — a policy applied by
an interceptor that does not own the transaction it is applying it to:

1. the outer opens a transaction and writes;
2. the inner is `REQUIRED`, so it **joins** rather than getting its own;
3. the inner raises. Its interceptor cannot roll back a transaction it does not own, so it marks
   the shared transaction **rollback-only** — the only safe action available to it;
4. the outer catches, logs, and returns normally;
5. the outer's interceptor tries to commit a marked transaction, and that is where it surfaces.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| A — `rollbackFor = Exception` on every boundary | closes the checked-exception half | must be remembered on every new `@Transactional`; nothing enforces it; does **nothing** for the swallowed-exception half | |
| B — never throw checked exceptions from a boundary | closes the same half | unenforceable in Kotlin — §3.2 shows the information is not in the class file, so no static rule can read it | |
| C — `REQUIRES_NEW` on the inner unit of work | closes the swallowed-exception half; makes the declared unit of work true regardless of caller | **a second connection while the first is held** — `Cm = 2` in `R2`'s pool formula. 미측정 here | |
| D — a gate that refuses the shape | makes the property explicit and checkable | narrow; catches the shape it names and not the class of defect | |
| E — do nothing, document it | free | a correctness property that depends on everyone remembering is not a property — `R1` §5 already rejected this reasoning | |

PENDING — the choice, and what would have made a different option correct.

⭐ **`R8` §5's precedent applies: "not a remedy — a gate" is a legitimate outcome.** The framework
behaviour is not this repository's to change, and pretending a report fixed Spring's default
would be worse than saying which shape is now refused.

## 6. 재계측 / Re-measurement

PENDING. Identical conditions to §3.

## 7. 회귀 게이트 / Regression gate

PENDING.

⚠️ **One gate that cannot be built, and the reason is a measurement rather than an opinion.**
An ArchUnit rule refusing `@Transactional` methods that declare checked exceptions would catch
the Java half of this codebase and is **structurally unable** to see the Kotlin half, because
§3.2 shows the `Exceptions` attribute is absent from the Kotlin class file. A gate that is green
on half a mixed codebase for reasons unrelated to correctness is the exact failure
`TransactionBoundaryRulesSelfTest` exists to prevent. It is not shipped, and this paragraph is
why.

## 8. 남는 위험 / Remaining risk

- **`REQUIRES_NEW`'s pool cost is 미측정.** It takes a second connection while the first is held.
  `R2` sized the pool and `R18` found the pool was not the explanation for something else; neither
  measured this. It needs load, therefore the timing lock, which this session does not hold.
  Ledger row `40.1`.
- **The checked-exception half is measured on probes, not on shipped code.** No `@Transactional`
  method in `api/src/main` currently raises a checked exception — verified by reading them. So
  this half is **latent**: it is a property of the framework and the language that this
  application has not yet met. That is a weaker claim than a live defect and is stated as one.
- **The swallowed-exception half in shipped code is reachable only from a caller that does not
  exist yet.** `RecordingController.record` carries no `@Transactional`, so nothing in the
  application currently calls `recordAll` from inside a transaction. §3.4 reaches it with a test
  caller. **This is a latent defect with a real blast radius, not a live one**, and the
  distinction is the difference between `R6` and `R26`.
- **The gate is structural where it exists at all.** It answers *can this shape occur* and not
  *does this code do the right thing*.
- **No timing was taken anywhere in this report.** Whether the remedies cost throughput is 미측정.
- **What would break the conclusion:** a Spring release changing the default rollback rule, or a
  Kotlin release that begins emitting an `Exceptions` attribute for exceptions it can prove
  escape. The second would make the gate in §7 buildable and this report's §7 paragraph stale.
- **Which earlier §8 bullet this falsifies:** PENDING — to be annotated in place, beside the
  sentence, not summarised here. `R19` §3.4 found three bullets that had gone false with nothing
  anywhere saying so.

## 9. 배운 것 / What I learned

PENDING — written the same day as the measurement, per the template's own instruction that a §9
written a week later becomes a summary of the conclusion with the useful part gone.
