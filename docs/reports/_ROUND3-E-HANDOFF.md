# Round 3, slice E — handoff

> Transient integration note. It deliberately carries no *last-updated* date line: it goes
> stale the moment the integrator edits around it, and a date on it would be a claim nobody
> maintains.

Branch `round3/layers`, worktree `../proxima-e`, base **`77022a5`**.

> ⚠ **THIS FILE IS IN PROGRESS.** Slices D and G hold the machine; the measurement lock is
> D's. **All five traps have now run at least once.** The one thing still outstanding is E1's
> cost comparison, which is a duration sweep and needs an exclusive machine. Written as I go,
> per §5.

---

## 1. WHAT I OPENED

| Trap | Verdict |
| --- | --- |
| **E1** — three remedies at three layers, one instance then two | **`REPRODUCED`** — correctness measured; ⛔ **the cost comparison is `미측정` and needs the exclusive lock** |
| **E2** — a singleton bean holding mutable state | **`REPRODUCED`** — two of its three sub-arms; the third is `NOT-REPRODUCED` and §4 names it |
| **E3** — memory visibility, a flag one thread may never see | **`REPRODUCED`** — 0 of 6 trials observed the write, across two invocations |
| **E4** — deadlock, two rows locked in opposite order | **`REPRODUCED`** — red and green both taken |
| **E5** — nested transactions, `REQUIRES_NEW` against `NESTED` | **`BLOCKED-BY-FRAMEWORK`** as shipped — `NESTED` is refused outright, and measured anyway in a second context that enables it |

**On E5's verdict.** `BLOCKED-BY-FRAMEWORK` is chosen over `NOT-REPRODUCED` because the trap
did not fail to appear — **it could not be attempted.** `JpaTransactionManager` refuses
`PROPAGATION_NESTED` before any transaction exists:

```
org.springframework.transaction.NestedTransactionNotSupportedException:
Transaction manager does not allow nested transactions by default -
specify 'nestedTransactionAllowed' property with value 'true'
```

⭐ **And that refusal produced a test that passed while measuring nothing** — see §3. The trap
is then measured properly in a second application context that sets the switch the exception
message itself names, so `R38` carries both *what this application does* and *what the
alternative would have done*.

**An earlier revision of this section used a `PENDING` marker** for traps that had not run,
which is not one of the four verdicts §5 permits. That was deliberate at the time — writing
`REPRODUCED` against a test that has never executed is the failure §5 exists to prevent — and
it is recorded here rather than erased, because the reason it was needed is worth more than the
tidiness of never having needed it.

---

## 2. COMMITS

### Report and ADR numbers I actually took, and why

**`R34`–`R38` and `ADR-019`, unshifted — my assigned range.** Derived at `77022a5` before the
first commit, per §0, rather than trusted:

```
ls docs/reports/R*.md          | sed 's|.*/R||;s|-.*||' | sort -n | tail -1   ->  28
ls docs/decisions/adr/ADR-*.md | sed 's|.*ADR-||;s|-.*||' | sort -n | tail -1  ->  017
ls api/src/main/resources/db/migration/V*.sql | sed 's|.*/V||;s|__.*||' | sort -n | tail -1  ->  5
```

The assigned range sits clear of the highest number in the tree, so **no shift was needed and
none was made**. No migration is taken — this slice adds none, per the brief.

**One thing the integrator must know about an earlier edition of the pack.** A previous draft
assigned slice E `R33`–`R37` / `ADR-016`. Those are consumed on `main` (`ADR-016` is *the
loader does not vacuum*). **No brief I was given showed them**, and nothing in this branch
uses them.

### Assignment of numbers to traps

| Trap | Report | State |
| --- | --- | --- |
| E1 | **`R34`** | **written and green.** Correctness only — ⛔ the cost half is `미측정` |
| E2 | **`R35`** | **written and green.** *A cache in a bean, and the repair that fixes half of it* |
| E3 | **`R36`** | **written and green.** *A flag one thread wrote and another never saw* |
| E4 | **`R37`** | **written and green.** §6 carries all three arms |
| E5 | `R38` | **pending the nested-enabled arm's re-run** |
| — | **`ADR-019`** | **written, `Proposed` not `Accepted`** — *a lock order is a convention, nothing can be made to keep it, and no guard is written for a caller that does not exist*. `395ea38` |

### The commits

| Trap | Red SHA | Green SHA | What flipped |
| --- | --- | --- | --- |
| **E4** | **`a108715`** | **`5501f32`** | Red: the opposed pair, asserted to complete, does not — `casualties=10 bothDied=0 bothBetweenLocks=10`, `40P01` ten times. Green: ascending order gives `casualties=0` **and `bothBetweenLocks=0`** — the interleaving is removed, not survived; retry-outside gives `casualties=0` with `retries=10 over 10 pairs`. `4 tests, 0 failures` |
| **E1** | **`3b90db2`** | **`35aadbb`** | Red: at 2 instances ① gives **565** and ② gives **500**, both with `failures=0`. Green: ③ pinned at **1,000** at both instance counts, ① and ② pinned strictly below, and the per-instance totals asserted to sum to 1,000 while the row holds half. `2 tests, 0 failures` |
| **E2** | **`5d1554d`** | **`8e526ec`** | Red: plain `HashMap` keeps **1,939 of 2,000** with `raised=0`, and `get`-then-`put` on a `ConcurrentHashMap` loads **8** times for one key. Green: `computeIfAbsent` loads **1**, and the defect is pinned as characterisation. `5 tests, 0 failures` |
| **E3** | **`ba52381`** | **`8e526ec`** | Red: the plain flag's write is **never** observed — `0/3`, all three trials running the full 2×10⁹. Green: `@Volatile` observed `3/3`, defect characterised loosely so CI cannot flake it. `1 test, 0 failures` |
| **E5** | **`98cbe2e`** | **`35aadbb`** | Red: `NestedTransactionNotSupportedException`, **and an arm that passed while measuring nothing**. Green: the assertion moved off the row and onto what the inner call threw; `duringNESTED=-1` records the refusal as refusal. `3 tests, 0 failures` |

Full branch, oldest first:

```
a108715  red(mastery): two transactions, two rows, opposite order -- 10 pairs, 10 casualties, SQLSTATE 40P01
853cc3d  feat(mastery): one defect, three layers -- the instrument, before any number is taken
b6f4097  feat(mastery): a cache in a bean is process-wide state, and both repairs are here to be priced
fe846bd  feat(mastery): a flag one thread writes and another may never read -- built as a PAIR, and bounded
87df20e  feat(mastery): REQUIRES_NEW and NESTED in the same place, so the difference is a diff
0bf608a  docs(docs): R37 takes ADR-014's entry 6.6 -- 10 pairs, 10 deadlocks, one casualty each
3b90db2  test(mastery): E1 -- the three remedies at one instance and at two, asserted as believed
5d1554d  test(mastery): E2 -- and the first test in the file is the one that passes
ba52381  test(mastery): E3 -- the visibility trap, built as a pair so a null result can be trusted
98cbe2e  test(mastery): E5 -- REQUIRES_NEW against NESTED, and this is the small one
```

⚠ **`3b90db2`, `5d1554d`, `ba52381` and `98cbe2e` have not been run.** Each commit message
says so in its own body. They are the states the runs will be taken at.

**They now compile.** `:api:compileTestKotlin` — `BUILD SUCCESSFUL`, exit 0, with
`kaptGenerateStubsTestKotlin`, `kaptTestKotlin` and `compileTestKotlin` all *executed* rather
than `UP-TO-DATE`, so the four new classes really went through the compiler. **A clean compile
is not a run and no verdict follows from it.**

---

## 3. NUMBERS

**One trap is fully measured: E4, red and green. The other four have not run.**

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers, pinned BY DIGEST, read out of
                   TestcontainersConfiguration.POSTGRES_IMAGE at this commit —
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
                   Server: PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc
                   (Alpine 15.2.0) 15.2.0, 64-bit — `select version()` IN THIS RUN.
                   measurement-discipline.md's block says "server 16.14" against a DIFFERENT
                   digest (57c72fd2…) and was not copied — rule 9. This run confirms 16.15.
  Isolation      : READ COMMITTED, default, unchanged
  Contention     : 2 transactions, 2 rows, opposed order, barrier BETWEEN the two locks
  Repetitions    : 10 opposed pairs per arm, 3 arms; red at a108715, green at 5501f32
  WHAT ELSE WAS RUNNING: slice D's full test run, with its own Testcontainers up, plus
                   slice G. Three Gradle daemons. Nothing flaked; no arm was re-run.
```

| Figure | Value | Contends with D/G? |
| --- | --- | --- |
| opposed pairs run | **10** | no — a count |
| pairs that deadlocked | **10 of 10** | no — a count |
| casualties | **10**, exactly one per pair | no — a count |
| pairs where both died | **0** | no — a count |
| SQLSTATE | **`40P01`**, ten times, one distinct value | no — a SQLSTATE |
| exception class | `org.springframework.dao.PessimisticLockingFailureException`, ten times | no — a type |
| `deadlock_timeout` | **`1000ms`**, `source=default`, `boot_val=1000ms` | no — a `pg_settings` row |
| `lock_timeout` | **`0ms`**, `source=default` | no — a `pg_settings` row |
| `statement_timeout` | **`0ms`**, `source=default` | no — a `pg_settings` row |
| `log_lock_waits` | **`off`**, `source=default` | no — a `pg_settings` row |
| `max_locks_per_transaction` | **`64`**, `source=default` | no — a `pg_settings` row |
| server version | `PostgreSQL 16.15 on x86_64-pc-linux-musl…` | no — a row value |

**E1 (`R34`), two runs — the right-hand column is the point:**

| remedy | 1 instance | 2 instances | raised | moved between runs? |
| --- | --- | --- | --- | --- |
| control — read-modify-write | 126 / 124 | — | 0 | **yes** |
| ① `synchronized` | 1,000 / 1,000 | **565 / 557** | 0 | **yes** |
| ② CAS (both forms) | 1,000 / 1,000 | **500 / 500** | 0 | **no** |
| ③ database | 1,000 / 1,000 | **1,000 / 1,000** | 0 | no |

**E2's shipped-bean sweep (`R35` §3.4):** `beans=19 fields=39 findings=0`. By reflection over
the running context, not by grep.

**E5 as shipped (`R38`):** `duringREQUIRES_NEW=2`, `duringNESTED=-1` — the `-1` is a refusal
recorded rather than a connection count, because `NESTED` is refused before a connection is
taken.

**Green arm, same invocation, same two rows (`5501f32`):**

| arm | pairs | casualties | both died | **both between locks** | retries |
| --- | --- | --- | --- | --- | --- |
| opposite order | 10 | **10** | 0 | **10** | — |
| **ascending id order** | 10 | **0** | 0 | **0** | — |
| **retry outside, 3 attempts** | 10 | **0** | 0 | **10** | **10** |

`4 tests, 0 failures, 0 errors, 0 skipped`, from
`api/build/test-results/test/TEST-net.gseek.proxima.mastery.DeadlockTest.xml`.

⭐ **`bothBetweenLocks` 10 → 0 is the headline and `casualties=0` is not.** The two sides
cannot both sit between their locks once they queue on the same first row, so an imposed order
**removes the interleaving** rather than surviving it. `casualties=0` on its own cannot
distinguish that from *this run did not race* — and `R37` §3.4 records that the ordered arm
**cannot prove its own precondition** and depends on the retry arm reporting `10` in the same
invocation. That is a control living in a sibling arm, which is a shape this repository has not
had before, and running the two arms separately would silently make the green result vacuous.

⭐ **Every figure above was taken while slices D and G were running, and the environment block
says so.** Each is a count, a SQLSTATE, an exception type or a `pg_settings` row value —
**none is a duration**, so none of them contends. A deadlock either formed or it did not, and
the server either detected it or it did not; another slice's load cannot move those.

### Numbers I refused to take

- **Time from cycle formation to the loser learning about it.** This is the figure an operator
  actually needs and `deadlock_timeout=1000ms` is a floor on it rather than a value for it. It
  is a duration, D holds the lock, and the machine is loaded. **`미측정`.**
- **The E1 cost comparison, and the contention level at which CAS and locking invert.** The
  brief forbids concluding *"CAS is faster than locking"* and requires finding where it
  inverts. That is a duration sweep. **`미측정`, and it is the only part of this slice that
  genuinely needs the lock.**
- **Any suite-wide test count.** I have run targeted `--tests` filters only. **I have no
  `:api:test` + `:seed:test` figure and will not quote one until I have run both modules
  myself.** For the record the two `DeadlockTest` invocations reported `2 tests completed, 1
  failed` (red) and `4 tests, 0 failures` (green) for that one class; neither is a suite count
  and neither is offered as one. **I am also not citing slice G's baseline** — G is on
  `99d558b`, a different tree with H's step-4 change in it, so its query count is not mine.

### Comparisons I am not making

No number here is placed beside any number from `R6`, `R18`, `R24` or any other report.
`R6` §3's millisecond table and this slice's counts describe different runs on different days;
rule 3 forbids the arithmetic and there is no ratio worth the breach.

---

## 4. REPORTS WRITTEN

| Report | Title | One line | §8 non-empty? |
| --- | --- | --- | --- |
| **`R37`** | Two rows, and an order nobody agreed on | Opposed lock order deadlocks 10 pairs of 10 at `40P01` with **one casualty each and `bothDied=0`**; `deadlock_timeout` is when the server *looks*, not when it kills, and `lock_timeout`/`statement_timeout` are both `0` so nothing else would have ended the wait — and the remedy, a lock order, is **a convention the database cannot enforce**, while the detector that saved every pair **prevents nothing** | **yes** — 11 bullets, including a judgement routed to `ADR-019` and the falsification of `R6` §8 |
| **`R34`** | Two remedies that are correct only while there is one instance | On one instance ①, ② and ③ all keep every increment. On two, ① gives **565/557** and ② gives **500/500** with `failures=0` and nothing logged, while ③ holds **1,000**. ⭐ The finding is the *shape*: ① fails like a race and moves between runs; **② fails like a partition — exactly 500, `inMemory=[500,500]`, each instance internally perfect and confidently wrong** | **yes** — 8 bullets, led by the missing cost comparison |
| **`R35`** | A cache in a bean, and the repair that fixes half of it | A `HashMap` on a bean loses **61 then 32** of 2,000 entries with **nothing raised**; `ConcurrentHashMap` fixes that and **not** the compound operation — `get`-then-`put` loads **8 then 2** times for one key where `computeIfAbsent` loads **1** both times. ⭐ The `after` column is stable across runs and the `before` column is not | **yes** — 9 bullets, including the retraction of its own drafted headline |
| **`R36`** | A flag one thread wrote and another never saw | The brief warned this might not reproduce; **it reproduced 6 of 6**, the plain arm running the full 2×10⁹ every time while the `@Volatile` control terminated every time. The mechanism is **not** the memory system — it is C2 hoisting the read, which makes it deterministic rather than rare | **yes** — 8 bullets |
| `R38` | *(E5)* | not written | — |

**`ADR-019`** (`395ea38`) is written and is deliberately **`Proposed`, not `Accepted`**. Its
decision is *record the gap, write no structural rule*, on `ADR-007`'s own **unbanked**
ground — nothing in this application takes two row locks, so a rule written now would guard
one shape and that shape is the test's own. It cannot be Accepted until `R37` has a green
commit, because it has been measured that the **unsorted** pair deadlocks and **not** that the
sorted one does not, and deciding a convention is the remedy before the remedy has been
observed to work is the same error `6.6` is being closed out of.

⚠ **`R37`'s header does not say *green* and its §6 is `미측정`.** It is the red half plus the
mechanism and the remedy argument. It is not a finished report and says so in its own first
lines.

**One edit outside this slice's source contract, made deliberately.** `R6` §8's bullet *"one
row, one column, one increment… which this measured nothing about"* is annotated **beside the
sentence**, per `_TEMPLATE.md` §8, and `R6`'s `Updated` date moves with it.
`_ROUND2-B-HANDOFF.md` §3 records an identical debt as *"knowingly the fourth"* of its kind
and `R19` §3.4 measured what it costs when a falsification lives only in the new report; this
is not being made a fifth. **`R6` is not on this slice's forbidden list** — that list is
`README.md`, `docs/roadmap.md` and `R0`, and none of them has been touched.

---

## 5. GATES AND CI

**I have changed no workflow file.** `.github/workflows/` is byte-identical to `77022a5`.

| Workflow | State | Note |
| --- | --- | --- |
| `build.yml` | **not yet run on this branch** | This is my completion signal, per the round's policy. The tree currently contains a deliberately failing test (`DeadlockTest`, the red commit) plus four uncompiled test classes, so **it would be red now and that is the intended state mid-slice**, not a result |
| `docs-consistency.yml` | expected red, ignored | It wants a `docs/roadmap.md` row per report and I am forbidden to touch that file. Per the round's policy this is the normal state and is **not** read as a signal about this work |
| `image-pin.yml`, `load-harness.yml`, `no-learner-data.yml`, `secret-scan.yml`, `study-consistency.yml` | untouched | no file in their scope was edited |

**New gate added by this slice:** `DeadlockTest` (`a108715`), a characterisation gate in the
shape `LostUpdateTest`'s first arm already uses — it pins that the unsorted pair **does**
deadlock, so a server that stopped producing `40P01` would be noticed rather than silently
invalidating `R37` §5.

⚠ **`api/src/test/kotlin/net/gseek/proxima/arch/TransactionBoundaryRules.kt` — NOT TOUCHED.**
It was named as a known merge-conflict candidate with slice H. I have not edited it and have
designed **around** it: `NestedCounter`'s inner half is a separate bean (`InnerIncrementer`)
precisely so `TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED` stays green, and `LayeredCounter`
and `RowLocker` use `TransactionTemplate` rather than `@Transactional` so they add no
annotated methods for those rules to inspect at all. **If this slice ever needs a new
structural rule it will go in a new file.** The integrator should expect no conflict here from
E.

---

## 6. WHAT I DID NOT DO

- **Four of five traps are unmeasured.** E1, E2, E3 and E5 have instruments and tests
  committed and **no runs**. This is not scope I dropped; it is scope not yet executed,
  blocked on the machine.
- **No timing anywhere in this slice.** Deliberate, and it costs `R37` a real number — see §3.
- **E1's cost comparison and the CAS/locking inversion point are not done.** They are the
  headline the brief asks for (*"vary contention, find where it inverts, and make that the
  headline"*) and they need the lock.
- **A real second JVM was not used for E1.** "Two instances" is modelled as **two objects in
  one heap**, each with its own monitor and its own `AtomicInteger`, against one database. The
  direction of that approximation is stated in `LayeredCounter`'s own class doc and will be
  stated in `R34`: two objects in one heap share a JIT, a collector and a cache-coherent view
  of memory, so the construction is **strictly more favourable to ① and ② than a real fleet
  is**. Anything it shows them losing, they lose worse in production — but it cannot support
  the opposite claim, and **no cost figure taken this way describes a real second instance.**
  Round 2 slice B's container harness (`load/ops/harness.sh`) is the tool that would close
  this, and using it is a container-and-timing exercise this slice has not been able to reach.
- **`REPEATABLE READ` is untouched.** `R6` §8 calls it *"the single biggest lever not pulled"*
  and `ADR-014` `6.5` prices it separately at importance **H**. It is not in this brief and
  `R37` does not close it.
- **`R6`'s environment block still reads `postgres:16-alpine — server 16.14` with no digest
  line, and it is one of eighteen.** I noticed it while annotating §8, **did not change it**,
  and then counted the population myself rather than accepting a relayed number. ⛔ **This is
  `F`'s to fix, not mine** — correcting identifier lines is `ADR-017`'s work and widening my
  own diff into eighteen files would be exactly the silent scope creep §6 exists to catch.

  Counted over `git ls-files 'docs/*.md' 'docs/**/*.md' README.md`, taking a *`PostgreSQL`
  identifier line inside a `측정 환경` block* as the unit and looking for `sha256:` anywhere in
  that entry:

  | | |
  | --- | --- |
  | `PostgreSQL` identifier lines in environment blocks | **32** |
  | of those naming `16.14` **with** a digest in the entry | **8** |
  | of those naming `16.14` **with no digest at all** | **18** |

  ⛔ **The middle two rows above are unit-dependent and the table as first written implied they
  were not. Corrected here rather than left standing.** Three careful counts — mine, the
  orchestration session's, and a third — produced **three different totals**, because each of us
  chose a different unit and a different scope and none of us published one:

  | scope / unit | total | with digest | without |
  | --- | --- | --- | --- |
  | `docs/` + `README.md`, first `PostgreSQL:` line only, 3-line window | 23 | 6 | **17** |
  | `git ls-files` docs globs, whole entry *(mine)* | 26 | 8 | **18** |
  | `git ls-files '*.md'` repo-wide, whole fenced block | 25 | 7 | **18** |

  ⭐ **The total is not a fact about the tree. It moves with the unit.** What is stable is the
  half that matters: **18 blocks name `16.14` with no digest at all**, in two of three methods,
  and `measurement-discipline.md`'s *"eight"* lands on the **with-digest** subset under every
  one of them (6, 7, 8). So the mechanism is confirmed independently of whose count is used.

  **The finding to carry forward is therefore a number plus its unit, and never the number
  alone.** Any report quoting a total must state the scope and the unit beside it, or it is not
  reproducible — and the reason all three of us diverged is that `measurement-discipline.md`
  imposes no such requirement, **which is also why its own count was wrong.** That gap is F's,
  and it is a change to a file I am forbidden to touch.

  The first count's blind spot is worth recording because it is the same shape as the defect it
  was reporting: it matched only the **first** `PostgreSQL:` line, and `R9` carries `16.14` on
  the **second** line of its entry — so `R9` was invisible to the instrument built to find
  exactly `R9`.

  The eighteen: `ADR-003`, `R5`, **`R6`**, `R7`, `R8`, **`R9`**, `R10`, `R11`, `R12`, `R13`,
  `R14`, `R15`, `R16`, `R18`, `R20`, `R21`, `R22`, `_ROUND2-A-HANDOFF`.

  **What this contradicts.** `measurement-discipline.md:92-103` says *"every environment block
  that names 16.14 has the digest on the next line, so none of them was ever wrong about what
  it ran on"*, and puts the population at **eight**. The population is **26** blocks naming
  `16.14`, of which **8** carry a digest. The mechanism is worth more than the count: *eight*
  is exactly the set of blocks that **carry a digest** — the author counted the ones that were
  disambiguable, called that the population, and concluded the sweep was complete. **The
  blocks with no digest were invisible to the count meant to find them**, and they are the
  ones actually at risk, because they name `16.14` against a tag that now resolves to 16.15.

  ⭐ **Two refinements the raw count hides, and F should have both.**

  1. **`R9` is in the eighteen and is the least at risk of them.** Its entry carries the
     verbatim `select version()` string — `PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled
     by gcc (Alpine 15.2.0)` — which is a *measured* identifier. It cannot be pinned to an
     image, but it was never vague about what it ran on. Sweeping it as though it were `R6` would
     lose that.
  2. **`R37` and this handoff appear in any naive `16.14` grep and must not be swept.** Both
     name the string only inside a sentence saying `measurement-discipline.md`'s value **must
     not be copied**. Their own pins are `cf78e766…`.

  **F must re-derive rather than inherit any of the three, and must publish the unit beside
  whatever number it arrives at.**
- **One run was killed mid-flight and produced no evidence; it is discarded, not reported.** The
  `NestedEnabledPropagationTest` invocation `bi3d1goja` returned `GRADLE_EXIT=143` having reached
  only `kaptGenerateStubsTestKotlin`. **It wrote no test XML at all**, and the stale XML sitting
  in `api/build/test-results/` was from the previous run 53 minutes earlier — I checked the file
  timestamps rather than the contents, which is the only reason it was not read as a result.
  ⛔ **I attributed that kill to the host VM reboot and that attribution is wrong.** The run's log
  was last written at **18:34:26.659** (`stat`, KST), and the VM rebooted at approximately
  **18:38** — roughly four minutes *later*, so the reboot cannot have caused it. An orchestrator
  kill of a Gradle pid is reported at **18:34:41**, 15 s after my log stopped. **The two are
  close and do not match, and I am not resolving a 15-second gap by choosing which clock to
  trust.** What is established is only what it was *not*. Recorded because inventing a tidier
  cause after the fact is the same error as leaving one unexamined.
- **I have not merged, rebased or pushed.** The branch sits where it was created.

---

## 7. NEW UNMEASURED

### Ledger entries this work **closed**, by id

| Id | Claim | Status |
| --- | --- | --- |
| **`6.6`** | *"one row, one column, one increment; no lock ordering, no deadlocks"* — class **a**, 120 min, importance **M** | ⭐ **CLOSED.** |

⛔ **Closed by measurement, not by argument — and I called it wrong once on the way.** An
earlier revision of this handoff marked `6.6` *partly closed* because the remedy had not run.
That was right when written and is no longer. The entry names two things `R6` measured nothing
about, and both are now measured in one invocation:

| the entry's two halves | evidence |
| --- | --- |
| **deadlocks** | `R37` §3 — 10 opposed pairs, 10 deadlocks, `40P01`, one casualty each, `bothDied=0`, with `deadlock_timeout`, `lock_timeout`, `statement_timeout`, `log_lock_waits` and `max_locks_per_transaction` all read off the running server |
| **lock ordering** | `R37` §3.4 — ascending order gives `casualties=0` **and `bothBetweenLocks=0`**: the ordered pair cannot interleave at all. Plus the retry fallback at `retries=10 over 10 pairs`, one per pair |

Red `a108715`, green `5501f32`, `4 tests, 0 failures, 0 errors, 0 skipped`.

**What closing it does not mean.** It does not mean multi-row locking is understood here. It
means the specific gap `R6` §8 named is measured. Seven **new** entries replace it below, which
is the ledger working rather than a hedge — a closed entry that spawns successors has been paid
for, and one that spawns none usually was not really closed.

### Entries to **add** to `ADR-014`'s ledger

In that ledger's own format. `Cost` and `Flip` are triage estimates carrying no environment
block, per that ADR's own fence, and **none of them appears in any report, commit message or
`README.md`.**

Under a new `### R37 — two rows, and an order nobody agreed on` heading:

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 37.1 | how long the losing client waits before it learns; `deadlock_timeout` is a floor, not a value | **a** | 45 | M | needs the measurement lock and a quiet machine |
| 37.2 | `deadlock_timeout` was read, never varied | **a** | 60 | L | including whether a large value lets something else expire first |
| 37.3 | `lock_timeout` and `statement_timeout` are both `0`; every §5 conclusion assumes nothing else ends the wait | **a** | 60 | **H** | a production server with either set takes a different path entirely |
| 37.4 | `log_lock_waits=off`, so what the server would say in the log is unknown | **a** | 30 | L | the answer at this setting is *nothing* |
| 37.5 | ten pairs; `bothDied=0` has no counter-example and that is not a guarantee | **a** | 30 | L | |
| 37.6 | the retry is argued from Spring's type hierarchy, not run | **a** | 45 | M | `TransientDataAccessException` is a reading of a class |
| 37.7 | only two rows, only `for update`, only `READ COMMITTED` | **a** | 180 | M | three-way cycles, FK locks, `update`-induced cycles all untouched |
| 37.8 | nothing can enforce the lock-order convention | **c** | — | — | not a measurement; routed to `ADR-019` |

Under a new `### R35 — a cache in a bean, and the repair that fixes half of it` heading:

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 35.1 | entry loss is 1,939 then 1,968; no distribution characterised | **a** | 60 | L | direction stable, magnitude not |
| 35.2 | `loads` moved **8 → 2** between two consecutive runs; no distribution | **a** | 60 | M | two samples prove instability and characterise nothing |
| 35.3 | no `ConcurrentModificationException` in either run — a negative result on one JVM | **a** | 45 | L | `modCount` is best-effort; a different interleaving would throw |
| 35.4 | thread count and key count never swept | **a** | 90 | L | 8 and 2,000 are chosen numbers |
| 35.5 | **the application's own beans were never swept for mutable fields** | **a** | 90 | **H** | this is what turns `R35` from a demonstration into a finding about `proxima`; cheapest valuable thing left in E2 |
| 35.6 | nothing inspected the table for structural corruption short of entry loss | **a** | 60 | L | `size()` correctness not separately checked |

Under a new `### R36 — a flag one thread wrote and another never saw` heading:

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 36.1 | the spin bound was never varied | **a** | 45 | L | whether a smaller bound flips the verdict |
| 36.2 | the warm-up was never varied | **a** | 60 | M | at what warm-up the hoist first appears — i.e. how fast a real background thread goes deaf |
| 36.3 | no `-Xint` / `-XX:TieredStopAtLevel=1` control | **a** | 45 | M | §4's mechanism is a strong inference, not a measurement |
| 36.4 | one machine, one JVM, one JIT | **b** | — | — | needs hardware this repository does not own; rule 3 forbids combining with a CI run |
| 36.5 | no production caller spins on a plain flag | **c** | — | — | proves the class is reachable, not that it is present |

**More entries will follow from `R34` and `R38` once those have run.** They are not listed here
because inventing ledger rows for measurements that have not happened is the failure mode this
ledger exists to count.

---

## 8. FOR THE INTEGRATOR

⛔ I have not edited `README.md`, `docs/roadmap.md` or `R0`, and these are the exact sentences
to place there. **Only `R37`'s rows are ready.** The rest follow when their reports do.

### `docs/roadmap.md` — *After the traps* table

```markdown
| **R37** | **Two rows, and an order nobody agreed on.** `R6` §8 closed with *"one row, one column, one increment. Multi-row transactions introduce lock ordering and deadlocks, which this measured nothing about"*, and `ADR-014` priced that sentence as ledger entry `6.6` | **done** — `R37`, red `a108715` / green `5501f32`. Two transactions taking the same two rows in opposite order deadlock **10 pairs out of 10**, `SQLSTATE 40P01`, with **exactly one casualty per pair and `bothDied=0`** — the server detects the cycle and kills the minimum rather than letting both hang. `deadlock_timeout` is **`1000ms` at `source=default` and is not a timeout that kills**: it is when a backend stops waiting and runs the cycle check. `lock_timeout` and `statement_timeout` are both `0`, so **had the detector not run, nothing on this server would ever have ended the wait**, and `log_lock_waits=off` means the server logs nothing about the waiting either. The loser receives `PessimisticLockingFailureException` — not `DeadlockLoserDataAccessException` — which extends `TransientDataAccessException`, so the framework types it retryable before any application code decides. **The remedy is an application convention the database cannot enforce**: the sorted call and the unsorted one are the same two statements against the same two rows, and unlike `V3`'s uniqueness rule there is nowhere to move it to. **And the detector is not a substitute** — it killed one side ten times without anyone ordering anything, which converts a hang into a failure and prevents no cycle. ⭐ **The green arm's headline is a count nobody would have thought to take**: under ascending order `bothBetweenLocks` goes **10 → 0**, because the two sides *cannot* both sit between their locks once they queue on the same first row — **the order removes the race rather than surviving it**, and `casualties=0` alone could not have told those apart. The retry fallback recovers **10 of 10, one retry each** |
```

### `README.md` — Results table

```markdown
| Two transactions, two rows, opposite lock order — *2026-08-22* | **10 of 10 pairs deadlock, `40P01`, one casualty each** — and `lock_timeout` and `statement_timeout` are both `0`, so nothing but the detector would have ended the wait | **0 of 10, with `bothBetweenLocks` 10 → 0** — an imposed lock order removes the interleaving rather than surviving it. The remedy is **a convention the database cannot enforce** | [`R37`](docs/reports/R37-two-rows-and-an-order-nobody-agreed-on.md) |
```

### `R0` — the scorecard

```markdown
`R37` closes `ADR-014` ledger entry `6.6` — *"no lock ordering, no deadlocks"* — **by measuring
both halves, in one invocation.** Deadlocks: 10 pairs, 10 detections, `40P01`, one casualty
each, `bothDied=0`. Lock ordering: `casualties=0` under ascending order, and `bothBetweenLocks`
**10 → 0**, which is the stronger claim — the ordered pair cannot interleave at all, so the
remedy removes the race instead of surviving it. Seven new entries replace it, which is what a
closure that was really paid for looks like.
```

⚠ **The `README.md` row above quotes no duration and that is deliberate**, not an omission to
be filled in later from a similar run.

⚠ **One change belongs in `docs/explanation/measurement-discipline.md` and it is not mine to
make.** Beyond the two false claims at lines 92-103 (§6), that file requires an environment
block for every number and **does not require a count to publish the unit it counted**. Three
sessions counting the same property of the same tree got three totals for exactly that reason.
This is the rule whose absence produced the error the file itself contains.

⭐ **`ADR-014`'s entry `6.6` is CLOSED and may be ticked.** §7 gives the evidence for each of
its two halves. Note that an earlier revision of this handoff said *half done*; that was true
until `5501f32` and is not now. **Add entries `37.1`–`37.8` in the same edit** — closing `6.6`
without them would understate what is still open.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution you performed?**
No. Every figure comes from a `--rerun-tasks` invocation I issued in this session, and every
number was read either from the run's console output or from that run's own XML under
`api/build/test-results/`. **One run is explicitly discarded rather than reported** — §6's
`bi3d1goja`, which wrote no XML at all; the stale XML beside it was 53 minutes old and was caught
by checking file timestamps rather than contents. ⚠ **One process re-established itself across a
host VM reboot without my issuing a second launch** (§6); I treat that run as trustworthy because
of what is observable about it — a fresh `--rerun-tasks` invocation with a live worker — and not
because I can account for how it started.

**b. Does any number here cross machines, sessions, or a long time gap?**
No. Every figure is from this machine, this session, today. Where a report shows two runs
(`R34`, `R35`, `R36`) both are from this session and are printed **side by side rather than
averaged**. **I did not cite slice G's baseline**, because G is on `99d558b` — a different tree
with H's step-4 change in it. `R37` quotes `R6` §3.3's `180 → 135` and `3273 → 3425 ms` **as
`R6`'s own result inside `R6`'s own conditions**, and combines it with nothing.

**c. Did you loosen a threshold, a sample size, or an assertion to make something pass?**
⭐ **Yes — three assertions are deliberately weaker than the observation that motivated them, and
that needs stating plainly rather than defending.**

| gate | observed | asserted | why |
| --- | --- | --- | --- |
| `MemoryVisibilityTest` | plain flag observed `0/3`, twice | `< trials` | the effect is a JIT decision; CI runs on hardware this repository does not own |
| `SingletonStateTest` | `loads=8`, then `loads=2` | `> 1` | the magnitude moved 4× between two consecutive runs |
| `LayeredRemedyTest` | ① `565`, then `557` | `< expected` | a race outcome; only the direction is stable |

**None was loosened to turn a red test green.** In every case the defect had already been
observed at full strength, and the weakening happened when converting that observation into a
*characterisation gate* — where an exact assertion on a race or a JIT decision becomes a flaky
gate, and `R16`'s `rate >= 0.0` in three tests at once is what that looks like after it rots.
**The exact values are all published in the reports**; only the gates are loose. The opposite
error is also present and deliberate: `computeIfAbsent` is asserted **exactly** at `1`, because
that one is a contract rather than an outcome.

**d. Is there any claim in a code comment that your work has made false?**
⭐ **Yes, two, and both are annotated rather than deleted.**

1. **`R6` §8** — *"Multi-row transactions introduce lock ordering and deadlocks, which this
   measured nothing about."* False as of `R37`. Annotated **beside the sentence**, per
   `_TEMPLATE.md` §8, with `R6`'s `Updated` date moved.
2. **`NestedCounter`'s own KDoc** — its table describes `NESTED` as *"a savepoint inside the
   first transaction. One connection."* That describes **Spring**, not this stack: measured,
   this application refuses `PROPAGATION_NESTED` outright. Annotated in place; the
   `REQUIRES_NEW` line beside it **is** measured at 2 slots. The same KDoc's *"a pool of `n`
   stalls at `n` concurrent callers"* is now marked **argued and not measured** — every arm
   behind that figure was single-threaded.

`TransactionBoundaryRules`' KDoc cites `R6` §3.3 and remains true; I did not edit that file.

**e. Did you write any version number, default value, or API behaviour from memory?**
No, and this was the sharpest edge in the slice. Every `pg_settings` value was **queried**, with
`source` and `boot_val` printed beside it, so `deadlock_timeout=1000ms` is not a remembered
default. The server string came from `select version()` **in the run that reports it**. The image
digest was read out of `TestcontainersConfiguration.POSTGRES_IMAGE` in this tree — **not** copied
from `measurement-discipline.md`, whose block names a different digest and server `16.14`.
`vmIdleTimeout=60000` and `autoMemoryReclaim=gradual` were read out of the Windows-side
`.wslconfig`. **Two things I expected and got wrong**: the deadlock loser's exception is
`PessimisticLockingFailureException`, not `DeadlockLoserDataAccessException`; and a
`@Bean`-declared `BeanPostProcessor` does not post-process a bean built before it. Both were
found by measurement failing, not by reading. **One thing I deliberately did not write**: why an
uncontended monitor costs 85 ms where a CAS costs 25 ms. There is an obvious candidate and it is
absent from `R34` because I did not measure it.

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**
No.
