# Round 3, slice E — handoff

> Transient integration note. It deliberately carries no *last-updated* date line: it goes
> stale the moment the integrator edits around it, and a date on it would be a claim nobody
> maintains.

Branch `round3/layers`, worktree `../proxima-e`, base **`77022a5`**.

> ⚠ **THIS FILE IS IN PROGRESS.** Slices D and G hold the machine; the measurement lock is
> D's. Everything below that is marked `PENDING` has had **no run**, and no verdict is
> claimed for it. Written as I go, per §5.

---

## 1. WHAT I OPENED

| Trap | Verdict |
| --- | --- |
| **E1** — three remedies at three layers, one instance then two | `PENDING` — instrument and tests committed, **not yet run** |
| **E2** — a singleton bean holding mutable state | `PENDING` — instrument and tests committed, **not yet run** |
| **E3** — memory visibility, a flag one thread may never see | `PENDING` — instrument and tests committed, **not yet run** |
| **E4** — deadlock, two rows locked in opposite order | **`REPRODUCED`** |
| **E5** — nested transactions, `REQUIRES_NEW` against `NESTED` | `PENDING` — instrument and tests committed, **not yet run** |

⛔ `PENDING` is not one of the four verdicts §5 permits, and it is used deliberately rather
than picking one of them early. **Four of these five traps have no run behind them**, and
writing `REPRODUCED` or `NOT-REPRODUCED` against a test that has never executed is exactly the
failure this repository's §5 exists to prevent. They become real verdicts or they do not.

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
| E1 | `R34` | not written yet |
| E2 | `R35` | not written yet |
| E3 | `R36` | not written yet |
| E4 | **`R37`** | **written**, red half complete, §6 `미측정` |
| E5 | `R38` | not written yet |
| — | **`ADR-019`** | **written, `Proposed` not `Accepted`** — *a lock order is a convention, nothing can be made to keep it, and no guard is written for a caller that does not exist*. `395ea38` |

### The commits

| Trap | Red SHA | Green SHA | What flipped |
| --- | --- | --- | --- |
| **E4** | **`a108715`** | *(none yet)* | The opposed pair, asserted to complete, does not: `pairs=10 casualties=10 bothDied=0`, `40P01` ten times. The green arm — `lockInAscendingIdOrder` plus a retry-outside arm — has **not run** |
| **E1** | **`3b90db2`** | *(none yet)* | Instrument at `853cc3d`; test asserts the naive belief at one and at two instances. **Not yet run**, so `3b90db2` is a *state* and not yet an observed red |
| **E2** | **`5d1554d`** | *(none yet)* | Instrument at `b6f4097`. **Not yet run** |
| **E3** | **`ba52381`** | *(none yet)* | Instrument at `fe846bd`. **Not yet run** |
| **E5** | **`98cbe2e`** | *(none yet)* | Instrument at `87df20e`. **Not yet run** |

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

⚠ **`3b90db2`, `5d1554d`, `ba52381` and `98cbe2e` have never been compiled or run.** Each
commit message says so in its own body. They are the states the runs will be taken at.

---

## 3. NUMBERS

**One measurement exists so far. It is E4's red half.**

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers, pinned BY DIGEST, read out of
                   TestcontainersConfiguration.POSTGRES_IMAGE at this commit —
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
                   Server version string: 미측정. `select version()` has NOT been run in this
                   session. measurement-discipline.md's block says "server 16.14" against a
                   DIFFERENT digest (57c72fd2…) and was not copied — rule 9.
  Isolation      : READ COMMITTED, default, unchanged
  Contention     : 2 transactions, 2 rows, opposed order, barrier BETWEEN the two locks
  Repetitions    : 10 opposed pairs, one invocation, at a108715
  WHAT ELSE WAS RUNNING: slices D and G, concurrently, on other worktrees.
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
- **Any test count.** I have run `:api:test --tests DeadlockTest` only. **I have no
  `:api:test` + `:seed:test` figure and will not quote one until I have run both modules
  myself.** Note for the record: that run reported `2 tests completed, 1 failed` for the one
  class named, which is not a suite count and is not offered as one.

### Comparisons I am not making

No number here is placed beside any number from `R6`, `R18`, `R24` or any other report.
`R6` §3's millisecond table and this slice's counts describe different runs on different days;
rule 3 forbids the arithmetic and there is no ratio worth the breach.

---

## 4. REPORTS WRITTEN

| Report | Title | One line | §8 non-empty? |
| --- | --- | --- | --- |
| **`R37`** | Two rows, and an order nobody agreed on | Opposed lock order deadlocks 10 pairs of 10 at `40P01` with **one casualty each and `bothDied=0`**; `deadlock_timeout` is when the server *looks*, not when it kills, and `lock_timeout`/`statement_timeout` are both `0` so nothing else would have ended the wait — and the remedy, a lock order, is **a convention the database cannot enforce**, while the detector that saved every pair **prevents nothing** | **yes** — 11 bullets, including a judgement routed to `ADR-019` and the falsification of `R6` §8 |
| `R34` | *(E1)* | not written | — |
| `R35` | *(E2)* | not written | — |
| `R36` | *(E3)* | not written | — |
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
  line.** I noticed it while annotating §8 and **did not change it** — correcting identifier
  lines is `ADR-017`'s work and that ADR states it corrected six of eight deliberately. I am
  flagging it rather than silently widening my own diff. **The integrator may want to check
  whether `R6` was one of the eight.**
- **I have not merged, rebased or pushed.** The branch sits where it was created.

---

## 7. NEW UNMEASURED

### Ledger entries this work **closed**, by id

| Id | Claim | Status |
| --- | --- | --- |
| **`6.6`** | *"one row, one column, one increment; no lock ordering, no deadlocks"* — class **a**, 120 min, importance **M** | ⚠ **PARTLY CLOSED, not closed.** |

⛔ **Not closed by argument, and here is exactly what was and was not measured.** The entry has
two halves and I have measured one of them.

- **Deadlocks: measured.** `R37` §3 — 10 opposed pairs, 10 deadlocks, `40P01`, one casualty
  each, `bothDied=0`, with the four governing `pg_settings` read off the server. The red half
  is complete and committed at `a108715`.
- **Lock ordering: NOT yet measured.** The remedy `lockInAscendingIdOrder` exists in the tree
  and **has never been run.** `R37` §6 is `미측정` and the report has no green commit.

So `6.6` moves from *not done* to *half done*, and **the integrator must not tick it in
`ADR-014` yet.** When the ordered-lock arm and the retry-outside arm have run and `R37` goes
green, it closes — both arms are counts, so neither needs the timing lock, and this is
expected to complete inside this slice.

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

**More entries will follow from `R34`–`R36` and `R38` once those have run.** They are not
listed here because inventing ledger rows for measurements that have not happened is the
failure mode this ledger exists to count.

---

## 8. FOR THE INTEGRATOR

⛔ I have not edited `README.md`, `docs/roadmap.md` or `R0`, and these are the exact sentences
to place there. **Only `R37`'s rows are ready.** The rest follow when their reports do.

### `docs/roadmap.md` — *After the traps* table

```markdown
| **R37** | **Two rows, and an order nobody agreed on.** `R6` §8 closed with *"one row, one column, one increment. Multi-row transactions introduce lock ordering and deadlocks, which this measured nothing about"*, and `ADR-014` priced that sentence as ledger entry `6.6` | **red half done** — `R37`, red `a108715`, **no green commit yet**. Two transactions taking the same two rows in opposite order deadlock **10 pairs out of 10**, `SQLSTATE 40P01`, with **exactly one casualty per pair and `bothDied=0`** — the server detects the cycle and kills the minimum rather than letting both hang. `deadlock_timeout` is **`1000ms` at `source=default` and is not a timeout that kills**: it is when a backend stops waiting and runs the cycle check. `lock_timeout` and `statement_timeout` are both `0`, so **had the detector not run, nothing on this server would ever have ended the wait**, and `log_lock_waits=off` means the server logs nothing about the waiting either. The loser receives `PessimisticLockingFailureException` — not `DeadlockLoserDataAccessException` — which extends `TransientDataAccessException`, so the framework types it retryable before any application code decides. **The remedy is an application convention the database cannot enforce**: the sorted call and the unsorted one are the same two statements against the same two rows, and unlike `V3`'s uniqueness rule there is nowhere to move it to. **And the detector is not a substitute** — it killed one side ten times without anyone ordering anything, which converts a hang into a failure and prevents no cycle |
```

### `README.md` — Results table

```markdown
| Two transactions, two rows, opposite lock order — *2026-08-22* | **10 of 10 pairs deadlock, `40P01`, one casualty each** — and `lock_timeout` and `statement_timeout` are both `0`, so nothing but the detector would have ended the wait | **미측정 — no green commit yet.** The remedy is a lock order, which is **a convention the database cannot enforce** | [`R37`](docs/reports/R37-two-rows-and-an-order-nobody-agreed-on.md) |
```

### `R0` — the scorecard

```markdown
`R37` took `ADR-014` ledger entry `6.6` — *"no lock ordering, no deadlocks"* — and **half of
it closes.** Deadlocks are measured: 10 pairs, 10 detections, one casualty each. Lock ordering
is not: the remedy is committed and has never been run, and `R37` §6 is `미측정`. **The entry
is marked half done rather than ticked**, which is the distinction this ledger exists to make.
```

⚠ **The `README.md` row above quotes no duration and that is deliberate**, not an omission to
be filled in later from a similar run.

⚠ **`ADR-014`'s entry `6.6` must be marked half done, not closed.** §7 says exactly what was
and was not measured.

---

## 9. SELF-CHECK

**a. Did any test result come from a Gradle cache rather than an execution you performed?**
No. The single result quoted here is from `:api:test --tests DeadlockTest --rerun-tasks`,
which I invoked, in this session, and which printed its own output above. **No suite-wide test
count appears anywhere in this handoff**, because I have not run `:api:test :seed:test` and
will not quote a number for both modules until I have.

**b. Does any number here cross machines, sessions, or a long time gap?**
No. Every figure comes from one invocation on this machine today. **No figure here is placed
beside a figure from `R6`, `R18`, `R24` or any other report**, and no ratio is computed across
runs. The one comparison `R37` makes across time — `R6` §3.3's `180 → 135` and `3273 → 3425
ms` — is **quoted as `R6`'s own result inside `R6`'s own conditions** and is not divided into
or combined with anything measured here.

**c. Did you loosen a threshold, a sample size, or an assertion to make something pass?**
No. Nothing here has been made to pass. E4's assertion is the naive belief and it is failing
on purpose; the four uncommitted-to-verdict traps assert the naive belief too and have not
run. **The one place a threshold was chosen is `MemoryVisibilityTest`'s spin bound**, and it
is a *parameter* chosen to keep a hoisted-read thread from burning a core into another slice's
window — it is documented as such in the class, it is not a measurement, and no rate derived
from it is published.

**d. Is there any claim in a code comment that your work has made false?**
Not yet, and I checked the two that were at risk. `MasteryCounter`'s KDoc says each
`increment*` is *"one unit of work — `REQUIRES_NEW`"*, which stays true; I added `NestedCounter`
beside it rather than changing it. `TransactionBoundaryRules`' KDoc cites *"`R6` §3.3 exercised
such a method under concurrency"* — untouched, and still true. **`R6` §8's bullet was made
false by this work and is annotated beside the sentence rather than left standing**, which is
the §8 procedure, and its `Updated` date moved with it.

**e. Did you write any version number, default value, or API behaviour from memory?**
No, and this was the sharpest edge in the slice. Every `pg_settings` value in `R37` was
**queried from the running server**, with `source` and `boot_val` printed beside it, precisely
so `deadlock_timeout=1000ms` is not a remembered default. The image digest was **read out of
`TestcontainersConfiguration.POSTGRES_IMAGE` in this tree**, not recalled and not copied from
`measurement-discipline.md`, whose block names a different digest (`57c72fd2…`) and server
`16.14`. **The server version string is written `미측정`** because I have not run `select
version()` here — I would rather carry the gap than inherit a number. The exception class
`PessimisticLockingFailureException` and its `Transient` supertype are likewise **observed
output**, not recalled: I expected `DeadlockLoserDataAccessException` and was wrong.

**f. Did any company name, job posting, CV, interview, or portfolio wording enter the tree?**
No.
