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

**`3b90db2`, `5d1554d`, `ba52381` and `98cbe2e` were committed before being run**, and each
commit message says so in its own body. **All four have since been run**, and every one of them
produced the red state its trap needed — the green SHAs are in the table above. They were
committed unrun because a red commit is a *state*, and holding them uncommitted while the
machine was busy would have destroyed the red/green structure the round is scored on.

⛔ **An earlier revision of this section said they "have not been run" and left it standing after
they had.** Corrected here rather than edited away: a handoff written as-you-go goes stale in
exactly this way, and it is the third such staleness found in this file by re-reading it.

**Branch size, counted rather than carried:**

```
git rev-list --count 77022a5..round3/layers   = 34
git rev-list --count main..round3/layers      = 34
git merge-base main round3/layers             = 77022a5   (this handoff's stated base)
git rev-list --count --merges 77022a5..HEAD   = 0
git reflog show round3/layers                 = 35 entries: 1 branch creation + 34 commits,
                                                 no reset, no amend, no rebase
```

⛔ **I published `35`, `36` and `37` for those three states and the tree says `32`, `33`, `34`.**
The offset is **exactly +3 at every point**, so the increments were right and **one initial
reading was wrong and then carried**. Nothing was made and discarded — the reflog rules that out.
The lesson is not the number: **a count I did not re-derive stayed wrong through eight
republications**, which is the same class as the `16.14` sweep in §6, and the correct habit is
the one command above rather than an increment on a remembered figure.

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

### ⭐ THE FULL SUITE — both modules, separately

```
./gradlew :api:test :seed:test --rerun-tasks        BUILD SUCCESSFUL in 24m 50s, exit 0
started 22:37:03, finished 23:01:53

  :api:test     56 classes   146 tests   0 skipped   0 failures   0 errors
  :seed:test     4 classes    14 tests   0 skipped   0 failures   0 errors
```

**Read from this run's own XML, not from the console summary**: all 56 api files written
23:01:44–23:01:45, all 4 seed files 22:40:41, and `grep '<failure'` / `grep '<error'` across all
60 files matches **zero**. `:seed:test` runs early in the build, which is why its files are
twenty minutes older than the api ones and still belong to this invocation.

**WHAT ELSE WAS RUNNING: nothing.** Only this worktree had a process; no other worktree, no VM
restart (uptime unbroken across the run), and the only containers were the buildkit builder plus
this run's own Testcontainers postgres and ryuk. **This is the first full suite of the day that
genuinely had the machine to itself.**

**146 was expected to stay 146.** An earlier full suite reported `146 tests, 1 failed` — the
failure being this slice's own `SingletonStateTest` precondition. The only change since was the
300 ms loader inside an existing test method; **no test was added, removed or renamed**, and the
class count is 56 in both runs. So the denominator holding while the failure disappears is the
expected result of that fix and not a coincidence to be waved at.

### ⚠ What else was on this machine, per run — and it was not always what I said

Every count above was taken while other slices worked, which is stated in each report's block
and does not contend. **But two of my own runs had writers on the machine that nobody had
counted**, and that is worth recording precisely because this round required every worker to
declare it:

| run | what else was running |
| --- | --- |
| E2/E3/E4/E5/E1 counts | slices D and G — declared in every block |
| `R34` §3.4 **cost sweep** | **nothing** — floor verified by me immediately beforehand |
| full suite, attempt 2 (`20:07`) | ⚠ **another worktree's Gradle daemon built twice inside my run**, at 20:13 and 20:32. Neither I nor the orchestration session knew at the time |
| full suite, attempt 4 (`21:35`) | alive and writing when cut — `binary` results written **14 s before** the cancel |
| **full suite, attempt 5 — the reported one** | **nothing.** Only `proxima-e`, uptime unbroken, no other worktree |

**There are nine worktrees on this machine**, four of them from a separate line of work that is
not part of this round. They were never counted as writers by anyone.

⛔ **This is a condition, not a cause.** Attempt 2 hung and **nothing establishes that the
overlap caused it**; the verdict on that hang stays *unexplained*, and the temptation to adopt a
newly-discovered neighbour as the explanation is the same error as attributing a `SIGTERM` to a
VM reboot that happened four minutes later (§6). It is recorded because a reader comparing my
runs is entitled to know which of them had the machine to itself.

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
| **`R35`** | A cache in a bean, and the repair that fixes half of it | A `HashMap` on a bean loses entries every run with **nothing raised**; `ConcurrentHashMap` fixes that and **not** the compound operation — `get`-then-`put` loads **more than once** for a single key where `computeIfAbsent` loads **exactly 1**. ⭐ Four runs, and the claim is a sentence rather than a number: **the direction reproduces and the magnitude does not** — losses of 61 / 32 / 64 / 183 and loads of 8 / 2 / *refused* / 8, with **no mean published for either** | **yes** — 12 bullets, including the retraction of its own drafted headline and its own gate's flakiness |
| **`R36`** | A flag one thread wrote and another never saw | The brief warned this might not reproduce; **it reproduced 6 of 6**, the plain arm running the full 2×10⁹ every time while the `@Volatile` control terminated every time. The mechanism is **not** the memory system — it is C2 hoisting the read, which makes it deterministic rather than rare | **yes** — 8 bullets |
| **`R38`** | The propagation this repository has never used, and the test that passed without it | `NESTED` is refused outright — `NestedTransactionNotSupportedException` — so E5 is `BLOCKED-BY-FRAMEWORK` as shipped, and **following the exception's own instruction did not lift it in two attempts**. ⭐ The finding is the arm that **passed while measuring nothing**: `row=100` reads identically whether a savepoint rolled back or nothing ran. `REQUIRES_NEW` holds **2** pool slots, a number `R2` and `R24` never varied | **yes** — 9 bullets, including the decision to stop |

**`ADR-019`** (`395ea38`, accepted at `c6cd169`) — *a lock order is a convention, nothing can be
made to keep it, and no guard is written for a caller that does not exist*. Decision: **record
the gap, write no structural rule**, on `ADR-007`'s own **unbanked** ground — nothing in this
application takes two row locks, so a rule written now would guard one shape and that shape is
the test's own. **It was filed `Proposed` and only moved to `Accepted` once `R37` went green**,
because deciding a convention is the remedy before the remedy has been observed to work is the
same error `6.6` was closed out of. The ADR records that the measurement **did not change the
decision**, which is worth stating: a decision that survives its own evidence looks identical to
one never tested against any.

**`R37` is green** — red `a108715`, green `5501f32`, all three arms in one invocation.

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
| `build.yml` | **not run on this branch, and the local equivalent is green** | It remains my completion signal per the round's policy, and CI has not executed on this branch. What I can report is the same command run locally: `:api:test :seed:test --rerun-tasks` — **146/0 and 14/0, `BUILD SUCCESSFUL`**, §3. ⛔ **An earlier note here described a tree that no longer exists** — *"a deliberately failing test plus four uncompiled test classes"*, true mid-slice and false since `67240c3` |
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
- ⚠ **Five of my full-suite attempts, and the four that failed were killed by the launcher, not
  by the build.** Attempt 1 completed in 21m01s. Attempts 2 and 4 were cut at **exactly 60
  minutes** by a Windows-side background-task limit — attempt 4 was **alive and writing results
  14 seconds before it was cancelled**. Attempt 3 was a genuine wedge and the only one with a
  cause I established directly: a thread dump showed **two Kotlin compile daemons alive and no
  test worker ever forked**, because `./gradlew --stop` does **not** kill Kotlin compile daemons
  (`--daemon-autoshutdownIdleSeconds=7200`) and my "clean floor" between runs left one behind.
  Attempt 5 ran `setsid nohup` **detached from its launcher** and finished in 24m50s with zero
  disconnections. ⛔ **Attempt 2's cause remains unestablished** — the 60-minute correlation fits
  it, but I have direct evidence only for 3 and 4, and adopting a neighbouring explanation is the
  error this handoff already records twice.
- ⚠ **A negative claim about containers was nearly published and would have been false.** The
  orchestration session was about to report that no postgres container appeared during attempts
  3 and 4, from five `docker ps -a` samples. **`docker ps -a` cannot see Testcontainers
  containers** — they are removed on stop — and every sample fell before the point in the build
  where container startup happens. Caught before sending. It is recorded here because it is the
  same class as `R35` §3.3's silent `ConcurrentModificationException` arm: **an instrument that
  cannot record the event, reporting its absence.**
- ⛔ **I did NOT fix the two inherited `CHECK 5` findings, deliberately.**
  `RecommendationQueries.kt:9` and `RecommendationService.kt:69` report on this branch because
  `round3/layers` is based on `main`, which predates slice H's correction. **H has already fixed
  them.** A second fix here would be a merge conflict at F and an edit nobody reviewed, so both
  files are untouched by this slice — `git status` over `recommendation/` is empty.

  ⭐ **And the loop closes on itself, which is worth the integrator noticing**: the deferred
  merge is what created these inherited findings, and `CHECK 5`'s first run against the real
  merged tree — the very thing the deferred merge delayed — is what will close them.
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

### `docs/roadmap.md` — R34, R35, R36, R38

```markdown
| **R34** | **Two remedies that are correct only while there is one instance.** `R6` compared six ways to add one to a counter and every one of them lived inside the database's understanding of the work. It never asked the question one layer up, the one an application asks first: does the exclusion have to be in the database at all? | **done** — `R34`, red `3b90db2` / green `35aadbb`. On **one** instance `synchronized`, CAS and the database all keep every increment of 1,000 — **the premise was measured rather than assumed**, with the unguarded control losing 874 in the same run to prove the harness was racing. On **two**, `synchronized` keeps 565 / 557 / 618 and CAS keeps **500 / 500 / 500**, both with `failures=0`, no exception, no rejection, no constraint and no log line; only the database remedy still holds 1,000. ⭐ **The finding is not the loss, it is that the two failures have different shapes.** `synchronized` fails like a race and moves every run. **CAS fails like a partition — exactly 500, three times, `inMemory=[500, 500]`** — no increment was lost by either process, every one was counted somewhere the other cannot see. Ask instance A: 500. Ask B: 500. Ask the database: 500. **Three self-consistent answers and no disagreement anywhere to investigate**, which is the state that survives a debugging session. The third run was taken inside the full 146-test suite, and **a busier machine moved the race arm and left the partition arm untouched** — the strongest available evidence they are not one failure wearing different numbers. **And the trap's own premise turned out false**: `synchronized` is the *most* expensive arm measured, not a cheap one, because a monitor does not remove a round trip, it serialises around two. The only genuinely cheap arm is the one that never writes, which is the same arm that fails at two instances: **the saving and the defect are one property — the work never left the process.** ⛔ The contention at which CAS and locking invert was swept from 1 to 32 threads on 8 cores and **no inversion occurred**; the headline is the absence, and extending the sweep until a crossing appeared was refused |
| **R35** | **A cache in a bean, and the repair that fixes half of it.** Spring beans are singletons, so a field on one is process-wide state reached by every request thread at once — and the declaration is identical to a field on an object only one thread touches. There is no annotation to forget and no configuration to get wrong | **done** — `R35`, red `5d1554d` / green `8e526ec`. ⭐ **The first test in the file passes, and it is the most important one**: single-threaded, the plain `HashMap` cache is perfect at 2,000 of 2,000. That is the test anybody would actually write, and **there is no unit test that catches this single-threaded, because single-threaded there is nothing wrong.** At 8 threads with one writer per key the plain map loses entries in **every** run and **raises nothing**. `ConcurrentHashMap` keeps all 2,000 — **and that is the whole of what it fixes.** `get`-then-`put` on that same concurrent map loads once per *arriving thread* rather than once per key, where `computeIfAbsent` loads exactly **1**: both calls are individually atomic, the **pair** is not, and `Collections.synchronizedMap` is fully synchronised and still loads eight times, because the gap is between the caller's two calls and not inside either. **Kotlin's `getOrPut` is the eight, and it reads as a single call.** ⭐ Across five runs **the direction reproduces and the magnitude does not** — losses of 61 / 32 / 64 / 183 / 579, a factor of 18 — so **no mean, rate or percentage is published**. The arm that could have given the failure a *name* never fired: no `ConcurrentModificationException` in any run, so **both failure modes are silent**, and the absent exception is the worst news in the report rather than a relief. A reflection sweep of the running `ApplicationContext` — through `AopProxyUtils.ultimateTargetClass`, flagging non-`final` fields **and** `final` references to mutable types — found **0 findings across 19 beans and 39 fields**: the class of defect is reachable in this stack, **no shipped bean here is in it, and the reason is not that anyone guarded against it** |
| **R36** | **A flag one thread wrote and another never saw.** The slice brief warned this might not reproduce, because on x86 a visibility defect that depended on the memory system would be rare and brief | **done** — `R36`, red `ba52381` / green `8e526ec`. **It reproduced 6 of 6 trials across two invocations** — the plain field's write was never observed, the loop running its full two-billion-iteration bound every single time, while the `@Volatile` control terminated every single time. ⭐ **The warning was correct about the wrong layer.** This is not a memory-system effect: it is **C2 hoisting the read out of the loop**, turning `while (running)` into `while (true)`, which is a *correct* compilation of a program that established no happens-before edge. That is a compiler decision, so once warm-up guarantees the loop has been compiled it is not a race at all — it is deterministic. **And the practical consequence inverts the usual advice about this bug class: it does not get rarer the longer a process runs, it gets more certain**, because the longer the loop runs the more surely it has been compiled. The background thread that has been up for a week is the one that cannot hear you. The instrument is built as a **pair** — the same loop over a plain field and a `@Volatile` one, same run, same warm-up — because without the control *the loop did not exit* and *the writer never ran* are the same output, which is `ADR-015`'s finding in a package with no database in it. The spin is **bounded**, which is what made the verdict a boolean and let the report be written without publishing a single duration. The gate asserts the control **exactly** and the defect **loosely**, because an exact assertion on a JIT decision becomes a flaky gate on hardware this repository does not own |
| **R38** | **The propagation this repository has never used, and the test that passed without it.** `REQUIRES_NEW` sits on every transaction boundary this application owns and `NESTED` on none, and nothing had ever compared them in the same place | **done** — `R38`, red `98cbe2e` / green `35aadbb`. ⛔ **This is the smallest of the slice's five traps and is reported as small.** As shipped, `NESTED` does not run at all: `NestedTransactionNotSupportedException`, raised before a transaction, a connection or a savepoint exists, because `AbstractPlatformTransactionManager.nestedTransactionAllowed` defaults to `false` and Boot does not set it. ⭐ **The finding is the arm that passed while measuring nothing.** With the inner failure caught, the row reads **`100` whether a savepoint rolled back cleanly or the propagation was refused and nothing ran** — and the row was the entire assertion, so a test named for savepoint semantics was green on a stack with no savepoints enabled. **No assertion on the row can fix that**; it had to move onto what the inner call *threw*. This is `ADR-015`'s vacuous pass **in a test with not one thread in it**, which generalises the rule past races: *a vacuous pass is a property of any test whose observable is reachable by two routes when the test is named for only one of them.* Measured alongside: `REQUIRES_NEW` holds **2** pool slots for the duration of the inner call — the ×2 that `R2` sized a pool without, and that `R24` put three instances against `max_connections` without. Following the exception's own instruction did not lift the refusal in **two** attempts, and **why is `미측정`**: stopping was a decision rather than an omission |
```

### `README.md` — R34, R35, R36, R38

```markdown
| A counter incremented 1,000 times, at one instance and then two — *2026-08-22* | **`synchronized` and CAS both keep every increment** — and on a second instance keep **565** and **500**, with `failures=0` and nothing logged anywhere | **only the database remedy holds 1,000** — and CAS is wrong *identically*, `inMemory=[500, 500]`, every instance internally consistent and confidently wrong | [`R34`](docs/reports/R34-two-remedies-that-are-correct-only-while-there-is-one-instance.md) |
| A cache field on a Spring singleton, 8 threads, 2,000 keys — *2026-08-22* | **entries lost in every run with nothing raised**, and `get`-then-`put` on a `ConcurrentHashMap` loading once per arriving thread | **2,000 of 2,000 kept and the loader run exactly once**, with `computeIfAbsent` — the map was never the axis; the compound operation was | [`R35`](docs/reports/R35-a-cache-in-a-bean-and-the-repair-that-fixes-half-of-it.md) |
| A shutdown flag written by one thread, read by another — *2026-08-22* | **0 of 6 trials ever observed the write**; the loop ran its full bound every time | **6 of 6 with one keyword** — and the cause is the JIT hoisting the read, not the memory system, so it is **deterministic rather than rare** | [`R36`](docs/reports/R36-a-flag-one-thread-wrote-and-another-never-saw.md) |
| `@Transactional(NESTED)` beside `REQUIRES_NEW` — *2026-08-22* | **refused outright**, and a test asserting savepoint behaviour **passed anyway**, because the row reads the same whether a savepoint rolled back or nothing ran | **the assertion moved off the row and onto what the inner call threw** — and `REQUIRES_NEW` is measured holding **2** pool slots, a multiplier two earlier pool reports never varied | [`R38`](docs/reports/R38-the-propagation-this-repository-has-never-used.md) |
```

### `R0` — the scorecard, R34, R35, R36, R38

```markdown
`R34` measured the premise it was handed instead of building on it, and the premise did not
survive. The brief said `synchronized` and CAS are *"far cheaper"* than a database statement on
one instance; **`synchronized` is the most expensive arm in the table**, because a monitor does
not remove a round trip, it serialises around two. The one arm that is dramatically cheaper is
cheaper **for exactly the reason that makes it wrong** — the work never left the process — so
the saving and the defect are a single property. The brief also asked where CAS and locking
invert and called that the headline; swept 1 to 32 threads on 8 cores, **they never inverted**,
and the absence is reported rather than a sweep extended until a crossing appeared.

`R35` retracted its own headline in its own body. It was drafted from one run claiming a
compound operation loads *"once per thread"*; the second run returned a different number and the
fifth widened the spread to a factor of 18. **What survives all five runs is a sentence and not
a figure — the direction reproduces and the magnitude does not** — so no mean is published. Its
gate then failed inside the full suite, correctly, because no thread overlapped and the arm had
measured nothing; the repair was to make the overlap hold **by construction** rather than to
loosen the assertion, and the report states what that narrowing costs as well as what it buys.

`R36` was expected not to reproduce and reproduced 6 of 6. The expectation was sound and aimed
one layer below the effect: the defect is **not** the memory system but the JIT hoisting a read,
which makes it a compiler decision rather than a race. **The consequence inverts the usual
advice — a visibility defect of this shape does not get rarer the longer a process runs, it gets
more certain.** The report publishes no duration at all; bounding the spin is what turned the
verdict into a boolean.

`R38` is this round's second independent instance of a test that **passes while measuring
nothing**, found in the same hour as slice G's and in a subsystem with nothing in common with
it. One such report is evidence about one test; two, by sessions with no contact, is evidence
about how often the shape occurs. The rule both arrived at — **assert on the route, not the
destination** — has now been reached from a race, a propagation attribute, an ArchUnit exclusion
and a sibling-arm control, which is why races are the instance and not the definition.
```

⚠ **None of the four `README.md` rows above quotes a duration**, for the same reason `R37`'s
does not: this slice's only timings live in `R34` §3.4, they carry their own environment block
and their own verified floor, and a headline figure lifted out of that block would arrive
without either.

⛔ **These four sets were promised in an earlier revision of this section and did not follow.**
It said *"only `R37`'s rows are ready; the rest follow when their reports do."* The reports were
finished within the hour and **the rows were never written, because nothing fired when the
condition came true.** `R37`'s rows exist only because they were drafted before that report was
finished. **That is a deferral whose trigger passed unnoticed — `ADR-014` D.3's exact shape** —
and it is recorded here rather than quietly repaired, because the integrator asked for the rows
and would otherwise have had to compose them, which the round forbids for good reason: a row
composed by the integrator reads as a worker's finding.

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
