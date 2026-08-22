# Round 3 — orchestration report

> **Written**: 2026-08-22 · **Status**: DRAFT — D, E and G are still running. Their sections are
> marked `PENDING ACCEPTANCE` and carry only what I have verified so far.
> **Orchestrator**: the session that executed slice H and then dispatched G, D and E.

---

## 0. What this document is, and what it is not

The brief says: *"Do not summarise a worker's report by trusting it."* So nothing below is a
relay. Every figure attributed to a slice was re-derived by me against the tree, and where my
re-derivation **disagreed** with the worker's, both numbers are printed with the method that
produced each.

**It is not an audit of the work.** It is an audit of the *claims*, plus a record of what the
orchestration itself did — including four errors of my own, in §6.

---

## 1. Assignment, and whether it was honoured

| Slice | Branch | Base | Assigned | Taken | Match |
| --- | --- | --- | --- | --- | --- |
| H | `round3/recency` | `77022a5` | `R43`–`R46`, `ADR-021` | `R43`–`R45`, `ADR-021` | ✅ `R46` unused |
| G | `round3/basics` | `99d558b` | `R39`–`R42`, `ADR-020` | `R39`–`R42`, `ADR-020` | ✅ |
| D | `round3/pools` | `77022a5` | `R29`–`R33`, `ADR-018` | `PENDING ACCEPTANCE` | — |
| E | `round3/layers` | `77022a5` | `R34`–`R38`, `ADR-019` | `R37`, `ADR-019` so far | `PENDING` |

**Ceilings were derived once, by me, before dispatch** and each worker was told its range rather
than deriving one while two others were committing. Verified free at each base at dispatch time:
`R29`–`R42` and `ADR-018`–`ADR-020` on both `main` and `round3/recency`.

**G is numbered below the base it descends from** — `R39`–`R42` sitting under H's `R43`–`R45`.
That is legal, collides with nothing, and was not tidied upward.

**No migration was added by any slice.** The ceiling is still `V5`; `V6` remains free.

---

## 2. Merge rehearsals

Run twice on throwaway branches in the order `H → G → D → E`, conflicts recorded, branch deleted,
**nothing resolved there.**

| | when | result |
| --- | --- | --- |
| Rehearsal 1 | before G and D had committed | CLEAN — and **nearly meaningless**, said so at the time |
| Rehearsal 2 | G 13 commits, E 11, D 1 | **CLEAN, all four** |
| Rehearsal 3 | pre-final; E still committing | **CLEAN, all four** — 74 commits, `CHECK 5` passed on the merged tree |
| Rehearsal 4 | 2026-08-22 21:0x, after `main` moved | ⛔ **ONE CONFLICT** — `docs/decisions/open.md`, from `round3/basics` only |
| **Rehearsal 5** | **2026-08-22 23:07, FULL STATE** — every branch at its final head, `main` at `e02b69b` | ⛔ **the same one conflict, and nothing else** |

✅ **Rehearsal 5 is the one that counts**, and it is the first taken on the tree F actually merges:

```
main e02b69b · recency 99d558b · basics 857e5e1 · pools 2b6633b · layers 434b6e4

main + round3/recency   CLEAN
main + round3/basics    CONFLICT -- docs/decisions/open.md ONLY
main + round3/pools     CLEAN
main + round3/layers    CLEAN
sequential H -> D -> E  CLEAN
46 distinct reports, R0..R45, no gaps
```

**One conflicting file across 85 commits and four branches**, and it is the `OPEN-13` identifier
collision of §6.3 — **against `main`, not against any worker.**

#### The four-item quiet-machine check, on its first real use

Run at 23:05 and it **failed**, which is the point of having written it:

| | found |
| --- | --- |
| worktrees with a process | 1 ✅ |
| Gradle daemons | 1 ⚠️ |
| **Kotlin compile daemons** | **2** ⛔ — the exact class that wedged attempt 3 |
| containers | **orphan `postgres` on the pinned digest, up 16 min** ⛔ — it survived `BUILD SUCCESSFUL` by three minutes, with Ryuk already gone |

⚠️ **And clearing it reproduced §7.3's finding immediately.** The first `./gradlew --stop` **failed**
— no `JAVA_HOME` in that shell — and the script carried on as though it had worked. **The AFTER
check caught it**, because the script reads the process table rather than believing the command's
exit. `--stop` is a verdict; `0 java processes` is a measurement.

**Floor at 23:10, verified rather than asserted: 0 Gradle daemons, 0 Kotlin daemons, 0 java
processes, no test container, load 0.42, VM up 3h22m unbroken.**


⭐ **Rehearsal 3 was clean and it was a rehearsal of a tree that no longer exists.** `main` gained
two commits at **20:21** and **20:35** that opened an `OPEN-13` of its own, and one at **21:00**
that is mine. Whether rehearsal 3 ran before or after 20:21 **is not recorded**, so its clean
result cannot be claimed to cover the tree F merges either way. **The PO's rule — *rehearsal 3 at
full state, or it does not count* — is the thing that caught this**, and it caught a real conflict
rather than a formality.

**Rehearsal 4, run with `merge-tree`/`commit-tree` so no ref is created and no branch is touched:**

| merge | result |
| --- | --- |
| `main` + `round3/recency` (H) | CLEAN |
| `main` + `round3/basics` (G) | **CONFLICT — `docs/decisions/open.md`** |
| `main` + `round3/pools` (D) | CLEAN |
| `main` + `round3/layers` (E) | CLEAN |

**Recorded, not resolved.** The resolution is §6.3 and it happens in F.

⚠️ **Rehearsal 1's "clean" was worth nothing and rehearsal 2's needed a correction to read.** My
first conflict-surface table counted files touched by more than one branch and reported thirteen —
but **G descends from H**, so every file H touched was being double-counted as if two slices had
touched it. Recomputed against each branch's *own* changes:

> **True overlap: zero. No file is changed by two independent slices.**

**The PO's flagged candidate did not materialise.** `TransactionBoundaryRules.kt` is touched only
by H. E designed around it deliberately — `NestedCounter`'s inner half is a separate bean so
`TRANSACTIONAL_METHODS_ARE_NOT_SELF_INVOKED` stays green — and G placed its `@Entity data class`
fixtures at `net.gseek.fixtures.basics`, outside the package the rule scans.

**One asymmetry F must handle.** Only H edited `ADR-014` directly. G, D and E all put their ledger
rows in handoff §7 in the ledger's own format, which is what §5 actually asks for. **F must
consolidate three slices' §7 rows into the ledger in one pass.**

---

## 3. Cross-cutting findings

These are not any one slice's. They were found by three sessions independently and they are the
round's real result.

### 3.1 ⭐ `measurement-discipline.md` is wrong in three ways, in the passage that corrects someone else's count

The document that governs every number in this repository. All three verified by me.

| | claim | actual |
| --- | --- | --- |
| **a** | *"Pinned by digest since `8dec7e6`"* then names `sha256:57c72fd2…` | `git show 8dec7e6` pins **`cf78e766…`**. The document names a digest that commit did not pin |
| **b** | *"Every environment block that names 16.14 has the digest on the next line, so none of them was ever wrong about what it ran on"* | **18 blocks have no digest at all** |
| **c** | *"Environment blocks carrying the identifier line: eight"* | **23–26 depending on the unit** |

**The mechanism is the finding, not the arithmetic.** "Eight" is 6 corrected + 2 already saying
16.15 — precisely the set that *carries a digest*. The author counted the disambiguable blocks,
called that the population, and declared the sweep complete. **The blocks with no digest were
invisible to the count built to find them** — and they are the ones actually at risk, because they
name `16.14` with nothing to disambiguate it, on a tag that now resolves to 16.15.

So claim **b** is not merely false; it is **inverted**, and it is the load-bearing justification
for *"none of them was ever wrong about what it ran on."*

The pinned image, asked directly: **`PostgreSQL 16.15 on x86_64-pc-linux-musl`**. Still musl, so
`R25` and `R26` stand.

**Every worker was ordered to write environment blocks from the container and from
`TestcontainersConfiguration.kt:72`, never from this document.** None of them touched it. It is F's.

### 3.2 ⭐ And then three careful sessions counted it three different ways

| scope / unit | total | with digest | without |
| --- | ---: | ---: | ---: |
| `docs/` + `README.md`, **first** `PostgreSQL:` line, 3-line window *(orchestrator)* | 23 | 6 | **17** |
| `git ls-files` docs globs, **whole entry** *(slice E)* | 26 | 8 | **18** |
| `git ls-files '*.md'` repo-wide, **whole fenced block** | 25 | 7 | **18** |

**The total is not a fact about the tree. It moves with the unit.** Stable across methods:
**18 blocks with no digest**, and the document's "eight" landing on the with-digest subset every
time.

> **The durable finding is not 18. It is that `measurement-discipline.md` requires an environment
> block for every number and does not require a count to publish the unit it counted — and its own
> count was wrong for precisely that reason.**

Reached independently by slices E and G, and it is better than what I was carrying. G's addition:
**the corrected number goes stale the same way unless the unit ships with it**, which is `R19`.
So what F owes that file is the **unit**, not only the digest.

`R9` is in the eighteen and is **the least at risk of them** — its block carries the verbatim
`select version()` output, which is a *measured* identifier. Sweeping it like `R6` would destroy
the one block that did the right thing a different way. `R37` and slice E's handoff appear in any
naive `16.14` grep and must **not** be swept: they name the string only inside a sentence saying it
must not be copied.

### 3.3 ⭐ A passing check reports a conjunction, and only half of it is usually wanted

Slice G's sentence, and the best thing the round produced:

> **A passing check reports the conjunction of "the property holds" and "the check could have seen
> it fail", and only one of those is usually what the reader wants.**

Four instances in slice G alone, with the unit stated before the count — *an occasion where a green
would have been read as establishing more than it does*:

1. CHECK 5 passing on eight sources that **make no index claim at all** — thin, not evidence
2. the arch rule passing with two `@Entity data class`es present — needs the self-test **and**
   `planted.size >= 5`, or the self-test itself passes on an empty import
3. `BUILD SUCCESSFUL` that **would not have caught a missing no-arg synthesis** — a compile-time
   plugin whose absence fails at runtime
4. `orElse` measured through `JdbcTemplate`, which would have reported it costs nothing

This repository's two earlier encounters are `R8` §3.3 and `ADR-017`'s *"a guard that stops finding
its input and reports OK"*, and neither said it that plainly. **Rows 1 and 2 differ in whether the
gap is mechanisable** — row 2 was fixed by adding a check, row 1 admits none — which is what ledger
`40.2`'s class **(c)** rests on.

⭐ **And slice E generalised it past races entirely, which is the version F should carry:**

> **A vacuous pass is a property of any test whose observable is reachable by two routes when the
> test is named for only one of them.**

Concurrency is *one* way to get a second route and not the only one. E found it in a test with **no
scheduling in it at all** — `NESTED` refused at transaction creation, `runCatching` swallowing the
refusal, and the row reading `100` whether a savepoint rolled back or the work never ran. `R9` §7's
`rate >= 0.0` and `R16`'s three tests are the third and fourth kinds. **Whether `ADR-015` now owes a
worked example with no scheduling in it is a judgement**, and `R38` §8 routes it rather than
deciding it.

⚠️ **One precision that the round's tally would otherwise flatter.** Of the three vacuous passes
slice E caught in its own work, **two were caught by measurement and only one by reading** —
`R35`'s `loads=8` fell to a second run, E5's `row=100` fell because E printed what the inner call
actually threw, and only the cost test's `assertEquals(0, memId)` was caught by inspection, ten
minutes after E wrote it. E's own summary is the one to keep: **the instrument found more of its
errors than it did.** That is an argument for instruments, not for care.


#### ⭐⭐ Where it has arrived since, and the unit before the count

**Unit: one occasion where an observable reachable by two routes was named for only one of them.**
That is E's generalised unit, not G's — G's four are counted against the narrower *a green read as
establishing more than it does*, and widening the unit is what lets the rows below be counted at
all. Arrivals **outside** G's original four:

| # | where | the two routes | named for | caught by |
| --- | --- | --- | --- | --- |
| 1 | `R38` (E) | the propagation is exercised / nothing uses it | the first | E, same afternoon as G, neither could see the other |
| 2 | `R45` (H) | the rule holds / the rule was loosened past the case | the first | H — **no threads and no transaction**, which is why `ADR-015` demotes races to instance |
| 3 | `autoMemoryReclaim` boot lines (me, §7.2) | the key enabled it / `hv_balloon`'s default did | the key | the PO's question, not my check |
| 4 | my suite watcher (me, §6.5) | still watching / died at launch, exit 127 | still watching | reading the process table directly, four minutes late |
| 5 | E's stall detector | the run is hung / `:seed:test` is running normally | hung | E, **before acting on the alarm** |
| 6 | E's stall detector, again | the condition is true / **the probe returned nothing** | the condition is true | E, from *"the empty status string in the alarm text was the tell"* |

⭐ **Row 5 is the sharpest of the five and E reported it against itself.** The first two detectors
required *no worker* **AND** *no new writes*. Adding a feature — name the last class written —
dropped the `workers == 0` conjunct, so the alarm became satisfiable by an entirely healthy run:
`LayeredCostTest` alone takes 11 minutes, and a five-minute gap between XML writes is ordinary.
**A conjunction with one conjunct silently removed is this property arriving as a regression inside
a fix**, which is where §3.4 says remedies put things.

⭐⭐ **Row 6 is not a sixth class. It is `CHECK 5`'s vacuity failure, in a different tool.** Slice H
added an assertion to `CHECK 5` for exactly this — the gate had printed `OK` over a tree carrying
two true findings, because it had been run where `git` could not resolve the worktree and its input
was empty. **Empty input, read as a clean result.** E's monitor did the same thing: the probe path
was MSYS-rewritten, `$st` came back empty, the counters defaulted, and the stall condition became
**trivially true**.

⛔ **And the mechanism was written down in E's briefing before it wrote a line** — `wsl.exe -- bash
/mnt/c/…` invoked from Git Bash is rewritten to `C:/Program Files/Git/mnt/c/…`. **The orchestrator
hit the identical trap earlier in the same round.** A documented hazard, in the brief, hit by two
of the three people who had read it.

> **The repair generalises past both.** `CHECK 5` now fails if its corpus is empty; a monitor must
> fail if its probe produced no output. **Neither is a check about the subject — both are checks
> that the check ran.** That is `ADR-015`'s argument with no race anywhere in it, and it is a
> stronger fourth example than the one §7.1 currently routes.

⚠️ **Rows 4 and 5 landed within one hour of each other, in instruments built to watch this round's
own measurements** — while §3.3 was already written down. **The property survived being known**,
which is the part worth generalising rather than the count.

**Neither cost a number.** E's alarm was refused before it killed a healthy run; my watcher's
failure direction published nothing. ⚠️ **Both had a cheap opposite direction that would have**:
E's would have destroyed a good run and reported a stall that never happened; mine would have read
stale XMLs from a targeted run and reported `:api:test 1 test` as a completed suite.

### 3.4 ⭐ A remedy can make its own test's precondition unsatisfiable, and the harness then reports the fix as a harness error

Slice E, writing E4's green arm, hit a problem that is the same principle as §3.3 arriving from the
other direction — and the same principle `ADR-015` was written for.

The barrier that **guarantees** the deadlock cycle in the unsorted arm — both callers sitting
between their two locks at once — **cannot be satisfied once the lock order is imposed.** Both
sides now sort to `rowA`-then-`rowB`, so the second caller is queued on `rowA` while the first still
holds it. They can never both be between their locks. **A barrier that hard-failed would have
reported the remedy working as a broken harness.**

E's resolution turns the obstacle into the measurement: the rendezvous gives up quietly and
**counts itself** as `bothReachedBarrier`. The ordered arm then asserts `bothReachedBarrier == 0`
and the unsorted arm asserts `bothReachedBarrier == pairs` as its own precondition, which separates
the two things `ADR-015` exists to keep apart:

| | |
| --- | --- |
| *no deadlock because the order prevented the interleaving* | the claim |
| *no deadlock because this run happened not to race* | what an unraced run silently looks like |

`ADR-015` reached this through `RaceOverlap.peak` — *"an unraced run now says the harness failed
instead of the losers did not fail."* E reached it from the opposite side: a run that **cannot**
race because the fix works, which must not be reported as the same thing as a run that did not race
by luck. The retry arm carries the matching guard, asserting `retries > 0`, so a pair that never
contended cannot pass it.

**This is `ADR-015`'s principle generalising beyond the test it was written for**, and F should
consider whether it belongs in that ADR as a second worked example rather than only in `R37`.

### 3.5 ⭐⭐ An instrument whose bug failed in the direction that confirmed the hypothesis its author already held

**The round's sharpest methodological finding, and it is a different class from §3.6's stale
counts.** Found and withdrawn by slice D, after I had already published the wrong version to the PO.

D reported that `tomcat.threads.*` and every `executor.*` gauge "return 404" under virtual threads
and that a dashboard "goes blank". Re-measured by starting the same jar twice and reading **the HTTP
status as well as the value** — the reading that had not been taken:

| metric | platform threads | virtual threads |
| --- | --- | --- |
| `tomcat.threads.config.max` | `200 → 200.0` | **`200 → -1.0`** |
| `tomcat.threads.current` | `200 → 10.0` | **`200 → -1.0`** |
| `tomcat.threads.busy` | `200 → 1.0` | **`200 → -1.0`** |
| `executor.pool.core` | `200 → 8.0` | `404` |
| `hikaricp.connections.max` | `200 → 10.0` | `200 → 10.0` |

**The cause was one character class in the harness:**

```
sed -n 's/.*"value":\([0-9.E]*\).*/\1/p'      [0-9.E]* cannot match a leading minus
"value":-1.0  ->  capture is empty  ->  census printed  미측정(gauge-absent)
```

**The instrument printed this repository's phrase for *nobody measured this* over a number that had
been measured and was `-1`.** And it failed in **the direction that confirmed the hypothesis its
author was already carrying**: D expected the gauges gone; the bug said they were gone.

> **A bug that contradicts you gets investigated. A bug that agrees with you gets published.**
> That direction has no natural detector, and this one was caught by luck plus attention — a later
> run printed raw JSON and D noticed a gauge answering that it had just written could not answer.

**And the corrected defect is worse than the claimed one.** An absent metric is at least honest
about being absent. **`-1` is a number**: a dashboard plots it, an alert on
`tomcat.threads.busy > 180` never fires, and a capacity review reads a worker pool whose maximum is
minus one and has no reason to look twice.

D re-verified the `mbeanregistry` claim **because it came from the same parser**, and it survived
with better evidence than it had — 404 *and the metric name absent from `/actuator/metrics`
entirely* at the default, against 200 with three names listed when enabled.

### 3.6 ⚠️ A count carried forward past the moment it stopped being true — eight instances, three mine

The round's recurring failure, and it is not one slice's.

| | instance | caught by |
| --- | --- | --- |
| 1 | `77 tests` — one module, said so nowhere *(historical, `R17`)* | the PO |
| 2 | slice G's commit count: 12 published, 11 in the tree — `rev-list` run once, then **incremented in the author's head** | orchestrator |
| 3 | `measurement-discipline.md`'s "eight" — §3.1 | orchestrator |
| 4 | **orchestrator repeated slice D's "monotonic slide" to the PO as fact.** `hikariPending` is `115.4 → 52.5 → 90.2` — down, then back up | slice D |
| 5 | **orchestrator's own 16.14 count missed `R9`**, whose block carries `16.14` on the *second* line, because the instrument matched `grep -m1` on the first | slice E |
| 6 | **slice E's own handoff, four claims stale at once** — `R38` listed as unwritten, `ADR-019` as `Proposed`, `R37` warned as not-green, and `R35`'s one-liner still quoting `61 then 32` from the first two of four runs | **slice E, by re-reading it** — nobody asked |
| 7 | ⛔ **orchestrator's *"30 of 31 blocks are silent"* in §7.2 — never counted at all.** The PO repeated it back as fact. ⛔ **The correction was worse than the error and took three attempts** — see below | orchestrator, then the PO, then the tree |
| 8 | **slice E's commit count, `+3` at three separate readings** — `35` / `36` / `37` published at `67240c3` / `73d2eae` / `d312376`, which the tree numbers **`32` / `33` / `34`** | orchestrator |

⭐ **Instance 8 is a different mechanism from 2, and the difference is the useful part.** Slice G's
was a count run once and then **incremented in the author's head**, so the error grew. E's is
**constant at `+3` across three readings**, which means every increment after the first was
correct and **the first reading was already wrong.** Established rather than assumed:

```
rev-list --count 77022a5..round3/layers   34      (77022a5 = the base E's handoff states)
rev-list --count main..round3/layers      34
rev-list --count --merges 77022a5..HEAD    0
reflog show round3/layers                 35 entries
   = 1 "branch: Created from main" + 34 "commit:", NO reset, amend or rebase
```

**So E did not make and discard three.** ⛔ **Where the first reading came from is 미측정** — it was
reported to E for E to find, and the orchestrator does not edit a worker's deliverable.


Instances 4, 5 and 7 are mine. Instance 5 has the same shape as the defect it was reporting: **an
instrument blind to part of the population it existed to count.**

⛔ **Instance 7 is the worst of the three and it is worth being exact about why.** `30 of 31` is not
a vague number, it is a **specific false claim** — it asserts that one block does carry the
setting, and none does. It went into the orchestration report unmeasured, the PO read it, quoted it
back in a ruling, and **it would have shipped inside the very document arguing that only measured
things become numbers.** Rows 2 and 4 were counts run once and then carried; this one was never run.

⛔⛔ **And then I corrected it into a number with no unit, which is the trap slice G named in
advance.** G's §8: *"Do not fix this by writing a corrected number. A corrected number with an
unstated unit goes stale exactly as the first did and nothing notices."* **I did exactly that, and
the first table I wrote to fix it contradicted its own paragraph two lines above.**

**The count, restated with the unit and the tree state on every row.** Unit: **files under
`docs/reports/` matching `R<n>-*.md`** — a *report*, not a file containing a string.

| tree state | `측정 환경` | `증거` | neither | total reports |
| --- | ---: | ---: | ---: | ---: |
| `main` | 25 | 2 | **2** — `R0`, `R27` | 29 |
| `main` + H | 25 | **5** | 2 | 32 |
| all four merged | **39** | **5** | 2 | **46** |

**The `증거` form is 5, not 2** — `R17`, `R19`, `R43`, `R44`, `R45`. My first table said 2 while
the paragraph above it named `R43` and `R45` as using that form. **One message, two answers.**

#### ⛔⭐ And `31` was a third unit again — one that counts documents *quoting* the block

`31` was **files anywhere under `docs/` containing the heading string**, on `main`'s working tree.
That population is not reports:

| in the 31, but not a report's own environment block | |
| --- | --- |
| `explanation/measurement-discipline.md` | the canonical copy — **the standard counts itself** |
| `reports/_TEMPLATE.md` | the blank the standard is copied into |
| `_ROUND2-A/B/C-HANDOFF.md` | three handoffs |
| `decisions/adr/ADR-003-identifier-generation.md` | an ADR |

⭐⭐ **It now reads 32, and the thirty-second file is this report.** `_ROUND3-ORCHESTRATION.md`
line 355 contains the heading **for one reason only: it is the line that states the count.**

> **A count that changed by being published.** Nothing in the tree moved; a document said `31` and
> that made it `32`. The unit *"files containing the heading"* admits any document that mentions
> the subject, so **the act of reporting joined the population being reported on.**

**That is the strongest available argument for G's rule** — stronger than the stale-number version
G gave it — and it is why the table above counts reports rather than files.

> ⛔⭐ **And it closed the loop while being written.** Rewriting the paragraph above replaced the
> full heading with a shortened form, so this report **dropped out of the population** and the
> count returned to **31**. Re-measured just now: `31`.
>
> **`31 → 32 → 31`, and the tree never changed.** The number tracked how this sentence was
> phrased. **A unit that admits documents mentioning the subject makes the measurement a function
> of the prose**, which is the failure the whole section is about, arriving three times in one
> paragraph: unstated unit, self-inclusion, and a value that moved when the wording did.

⭐ **§3.2's missing cause, now established.** Three sessions counted environment blocks three ways
and the reason is not only that nobody published the unit — **there are two block forms and
`measurement-discipline.md` mentions the second zero times.** The `증거` form is not a deviation:
it is what a report with no hardware to declare correctly does, and the standard has never named
it. **Any count of "environment blocks" is ambiguous until that is written down**, and no corrected
number fixes it. F names the second form in the standard.

---

### 3.7 ⛔⭐⭐ The document that defines what makes a number citable names the wrong image, in the sentence that cites the commit which pinned the right one

Found by **slice D** and routed to the integrator rather than fixed — *"one document, four branches"* —
which is the correct call. Verified here against the tree rather than taken on report:

| | |
| --- | --- |
| `measurement-discipline.md` §*The environment block*, lines 47–50 | `server 16.14` … **"Pinned by digest since `8dec7e6`"** … `sha256:57c72fd2a128e416c7fcc4…` |
| what `8dec7e6` actually pinned, from `git show` | `sha256:cf78e76683b9ca8c5733cb…` |
| what `TestcontainersConfiguration.kt` pins today | `sha256:cf78e76683b9ca8c5733cb…` |
| what `R29`–`R33`'s blocks say, read from the running container | **16.15 / `cf78e766…`** |

**Two different digests, and the wrong one is the one the canonical block presents as the pin.**

⛔ **The defect is real. ✅ It did not spread, and this sentence is a correction of my own.**

I wrote that the block is *"copied verbatim into the header of every report"* and therefore *"every
report inherits a pin that is not the pin."* **The PO counted the tree and it is not what the tree
says.** Re-counted here, all 17 reports of the round:

| | |
| --- | ---: |
| name `cf78e766…` — **the correct pin** | **11** |
| name no digest at all | **6** — `R32` `R35` `R36` `R43` `R44` `R45` |
| name `57c72fd2…`, the value the governing document prints | **0** |

⭐⭐ **And why it did not spread is the largest finding of the day: absolute rule 9 beat the
governing document.**

> *"Never write a version from memory. Check the current release and write what you checked."*

The document said `57c72fd2`. **Four workers, independently, read it off the running container and
wrote `cf78e766`.** When I verified §9(e) as *"all four wrote 16.15 / `cf78e766` from the
container"* I recorded it as a habit being honoured. **It was not a habit being honoured. It was
the moment that habit stopped a defect**, and none of us knew — not the workers, not me.

**Rule 9 was written for the case where a document cannot be trusted. That case arrived, silently,
and the rule held.** It is the first time in this repository a rule has caught something it was not
written for, and ✅ **that belongs in `R0`** rather than only here.

⛔ **So the fix must not overstate what it repairs.** `R27` and `ADR-017` exist because a moving tag
made local and CI run different servers; this is the same failure one level up, in the artefact
written to prevent it — **and the corpus is clean.** F corrects the pairing and says what kept the
corpus clean. **A fix that overstates its own blast radius is the class of error being fixed.**

✅ **The two reports flagged for confirmation are clean, and finding that out cost the count its
unit.** `R43` and `R45` argue the absence exactly as `R32`, `R35`, `R36` and `R44` do — they simply
argue it in a **different block form**:

> `증거 / What the evidence here is` … *"the hardware block the template carries would be hardware
> none of these numbers came from — `R0` and `R17` do the same and for the same reason."*

**All six argue it. None is silent.** A grep for the standard block misses two, which is why they
looked different from the other four.

⭐ **And it is `R43`'s class in the governing document itself.** *"Pinned by digest since `8dec7e6`"*
is a true sentence. The digest under it is a true digest. **The pairing is false, and nothing reads
pairings.** `CHECK 5` sweeps KDoc for index claims; no check relates a document's quoted digest to
the digest in the code it names.

✅ **What D left open, G answered — and it makes the defect exact rather than vague.** `57c72fd2…`
is not a wrong digest. It is the **superseded** one, and it is still in the tree on purpose:

| | |
| --- | --- |
| `CollationDivergenceTest.kt:81` | `const val MUSL_DIGEST = "postgres@sha256:57c72fd2…"` — arm **A**, annotated *"the digest in `measurement-discipline.md`"* |
| `ImageTagDriftTest.kt:42` | arm **RECORDED**, `sha256:57c72fd2…` |
| the pin since `8dec7e6` | `cf78e766…` = **PostgreSQL 16.15 on x86_64-pc-linux-musl** (G, read from the running server) — **still musl, so `R25` and `R26` are unaffected** |

> ⛔ **So the document prints the digest that `8dec7e6` replaced, directly beneath the sentence
> *"Pinned by digest since `8dec7e6`"*.** Not a typo and not an unknown — **a superseded value
> presented as the current one**, in the clause naming the commit that superseded it.

⭐⭐ **And it has a second edge, inside the class written about this exact hazard.**
`ImageTagDriftTest`'s KDoc glosses arm RECORDED as *"the image every number in this repository was
taken on."* That was true when written on 2026-08-21. **It is expiring**, verified against the
tree today:

```
R29  16.15  sha256:cf78e766      R31  16.15  sha256:cf78e766
R30  16.15  sha256:cf78e766      R33  16.15  sha256:cf78e766
```

**Four of slice D's five reports record numbers taken on the other image.** `R43`'s class, in the
test class whose subject is a recorded identifier going stale. Its KDoc also quotes a revision of
`measurement-discipline.md`'s block that no longer exists in the file.

⚠️ **One correction to D, in D's favour.** Its §8.6 says *"Every environment block in `R29`–`R33`
says 16.15 / `cf78e766…`"* — it is **four of five**. `R32`'s block names **no PostgreSQL line at
all**, deliberately and correctly: `Load: NONE`, every figure a count of threads or tasks, and the
report argues the omission rather than leaving it. **The summary sentence overreaches; the thing it
describes is right.** Recorded because a claim of *every* is checkable and this one is not true.

⛔⛔ **My own part in this is the worst detail.** I edited that exact block today at `223c1fa`,
adding the `WSL VM config` line **three rows above the false pairing**, and did not read the next
line. The commit message argues that a true sentence nothing read is this repository's defect
class. It was two rows below the line I was writing.

---

### 3.8 ⭐⭐⭐ A derivable value that is written down instead of derived — the round's second property

**Named by the PO on 2026-08-22**, out of three of the day's findings turning out to be one defect.
The general form is a sentence of mine widened past the case it was written for:

> **A derived value must be derived from the thing it describes, never written down beside it.**

A written-down copy is a **second fact that has to be maintained in step with the first**, and
nothing maintains it. The original cannot go stale; only the copy can.

| # | the value | what it should have been derived from | what was there instead |
| --- | --- | --- | --- |
| 1 | the pinned image digest | `git show 8dec7e6` | `sha256:57c72fd2…` typed beneath the words *"Pinned by digest since `8dec7e6`"* |
| 2 | the freshness boundary | the live wrapper's own start time | `21:09:27`, correct for exactly one run |
| 3 | §6's error count | the list under it | *"Four errors"* over five |
| 4 | slice E's commit count | `git rev-list --count` | `35 / 36 / 37` over `32 / 33 / 34` — **survived eight republications**, E's own phrase |
| 5 | slice G's commit count | the same command | `12` over `11` — run once, then incremented from memory |
| 6 | `measurement-discipline.md`'s *"eight"* | counting the blocks | a figure that landed on the with-digest subset every time |
| 7 | ⛔ **this report's own block-form table** | counting reports at a named tree state | `증거 = 2`, written two lines under a paragraph naming **five** — *`R17` `R19` `R43` `R44` `R45`* |

**Seven, and only the copy ever went stale.** ⛔ **Row 7 arrived while the property was being
written down**, in the table built to demonstrate it, and was caught by the PO rather than by me.
**Documenting a failure does not confer immunity from it** — §3.9 is the sharper form of that. Note the asymmetry rows 4 and 5 make plain: `rev-list`
was *available* in every one of these cases and is not slow, is not flaky, and needs no judgement.
**The copy was not a substitute for a hard measurement. It was a substitute for a cheap one.**

⚠️ **One instance belongs to the PO and is recorded because the PO asked for it.** The ruling
*"`R43` and `R45` did not show that argument to my grep — confirm at F"* routed to the integrator a
question the tree had already answered, **because the grep looked for one block form and there are
two.** The PO's own note: *"that is the defect this round is about, committed by the person ruling
on it, for the second time today."* It is kept here rather than softened, and it cost F an errand
that was never owed.

⚠️ **One adjacent case that is *not* this property**, kept separate so the class stays sharp: my
*"30 of 31 blocks are silent"* was never derived at all, so there is no original it drifted from.
That is fabrication, not staleness, and §3.6 instance 7 is its place.

#### ⚠️ The property is necessary and it is not sufficient — measured, on this host, today

**Deriving is only as sound as what you derive from.** Having written *"derive the boundary from
the live wrapper's start time"* into this report, I did exactly that — and the boundary moved four
times for a process that started once:

```
21:35:31   21:36:22   21:36:29   21:36:57      (same PID, 22635)
```

**The cause is `btime`, and slice E published it this morning** — `R36`, *"btime does not drift, it
steps."* Reproduced here independently:

| read at | `/proc/stat` btime | implied boot |
| --- | ---: | ---: |
| ~21:48 | `1787395345` | 19:42:25 |
| ~21:55 | `1787395380` | **19:43:00** |

**A +35 s step.** Three reads *inside one process* returned an identical value, so it is a step
between epochs and not jitter. `ps -o lstart=` is `btime + starttime`, so **every process-derived
timestamp on this host inherits the step.**

⛔ **And I made slice E's other error while checking it.** I cross-checked `ps -o lstart=` against
`/proc/PID/stat` start-ticks plus `/proc/stat` btime, saw them agree, and called that two
derivations agreeing. **They share `btime`.** E was recorded in this very report for the same
mistake — a `/proc/uptime` cross-check that was not independent because it had the same base. I
read that, wrote it down, and then did it.

✅ **The repair that holds: compare a file mtime to a file mtime.** A marker file was written at
`21:50:21`; result files carry filesystem timestamps; **both come from one clock, so a `btime` step
cannot move the comparison.** It is also how the answer was settled — `:seed:test`'s four files are
stamped `21:49:15`–`21:49:16`, which is inside attempt 4, so that module genuinely re-ran rather
than the boundary having slid under it.

> **So property ② needs its second clause:** *derive the value from the thing it describes — and
> derive it through a clock the thing itself is measured on.* A derivation routed through a
> stepping clock is a transcription with extra steps.

⛔ **This is a ledger row, not an errand, and no check is built for it this round.** The PO ruled
so, and the reason is `R19`'s line: a check for *"is this number derived?"* would have to read
intent, and a check that reads intent is a check that produces judgement calls. **Writing the
property down is the deliverable.**

⭐ **This is the second property this round has earned.** The first is slice E's, and the two are
about opposite halves of the same failure:

> **① Assert on the route, not the destination** — *an instrument must not report a state that a
> different state also produces.*
>
> **② Derive, do not transcribe** — *a value must not be a copy that nothing keeps in step.*

**① is about reading. ② is about writing.** Every finding in §3.3 is an instance of ①, every
finding here is an instance of ②, and `R43` — a true sentence nothing read — is what happens when a
② failure survives long enough to be met by a ① failure.

---

### 3.9 ⛔⭐⭐⭐ A true sentence the author read, wrote down, and violated inside the hour

**Three of today's findings are *a true sentence nobody read*** — `R43`'s KDoc, `.wslconfig`'s
comment, and `223c1fa` writing four rows above a false pairing without reading down. **This one is
not in that class and it is worse.**

| | |
| --- | --- |
| **~19:00** | I record, in this report, that slice E's `/proc/uptime` cross-check **was not independent** — it shared a derivation with the reading it was checking |
| **21:48** | I cross-check `ps -o lstart=` against `/proc/PID/stat` start-ticks plus `/proc/stat` btime, see them agree, and call it **two derivations agreeing** |
| | **They share `btime`.** Same failure, same hour, by the person who had just written it down |

**Nothing was missing. The sentence was read, understood well enough to be written up as someone
else's error, and then not applied.** Knowing the failure did not prevent the failure.

⭐ **This is the argument this repository is built on, and it is the first time the argument has
evidence from the person who writes the gates.** `CHECK 1`–`CHECK 5`, the ArchUnit rules,
`ADR-015`'s precondition assertions, `CHECK 5`'s vacuity guard — every one of them exists because a
written instruction is not a control. **That premise has always been argued. It has not, until
today, been demonstrated on its own author.**

#### ⚠️ And it does not generalise to "warnings never work" — the same day disproves that

**Absolute rule 9 has no gate and it held.** §3.7: `measurement-discipline.md` printed the
superseded digest, four workers independently read the container instead, and **zero of 17 reports
carry the wrong value.** A written rule, no enforcement, and it beat the governing document.

**So the distinction is not gate-versus-warning. It is what the rule asks of the reader:**

| | rule | what it asks | held? |
| --- | --- | --- | --- |
| **procedure** | *never write a version from memory — check, and write what you checked* | **do a thing**, every time, the same way | ✅ four for four |
| **judgement** | *a cross-check must not share a derivation with what it checks* | **recognise a situation**, correctly, in the moment | ⛔ failed on its own author within the hour |

> ⭐ **A written rule can hold as a habit when it names an action. It fails as a control when it
> names a category the reader has to notice they are in.** Rule 9 fires on *every* version you
> write. The independence rule fires only when you happen to see that two readings share a base —
> and the whole failure is not seeing it.

**That is what F should take to `R0`**, and it is a better claim than either half alone: this
repository's gates are not evidence that people are careless. They are evidence about **which
rules can be left to people** — and the two examples sit one day apart in the same round.

---

## 4. Per-slice verdicts

### 4.1 Slice H — accepted *(executed by this session, audited by the PO)*

Verified: 8 commits, base `77022a5`, `R43`–`R45`, `ADR-021`, no migration, ledger `19.6` partly
closed and `43.3` corrected-not-closed. Test counts from a run I executed: `:api:test` **125 / 48
classes**, `:seed:test` **15 / 5**, 0 failures, `--rerun-tasks`, 16 of 16 executed.

CI read on the runner for `5ac5fd5`: `build`, `secret scan`, `no learner data` green;
`docs consistency` red at **CHECK 3 only**. **CHECK 5 against the real tree has still never
executed** — skipped behind CHECK 3. It first runs when F adds the roadmap rows, and **F must read
that run rather than assume it.**

### 4.2 Slice G — ✅ **ACCEPTED**

17 commits at `136142f` verified at time of writing; full run in flight. No migration; `db/migration`
untouched, ceiling `V5`. `README.md`, `docs/roadmap.md`, `R0`, `measurement-discipline.md`,
`arch/TransactionBoundaryRules.kt` all untouched — verified by `git diff`.

Verified independently by me: the `16.14`-in-2-of-48-result-files finding; CHECK 3 costing **seven**
rows on its branch and **already failing at its base**, so that branch's CI colour is not evidence
about its work; commit counts at two separate checks after its correction, both right.

Measured before the machine: no-arg synthesis present in bytecode, `@field:` annotations on the
fields, `javac` writing `throws IOException` where `kotlinc` writes nothing — `R40`'s centre, from
two instruments over one artefact.

### 4.3 Slice D — ✅ **ACCEPTED**

Timing session 15:16:17 → 16:35:37, one uninterrupted stretch, five arms, twenty runs, fifteen
publishable. Quiet-guard **0 aborts**; SUT watchdog had the database `running` at every 15-second
sample. One discard for cause: arm A run 2, refused by the steady-state gate — **the flattering
one**, fastest p50 and highest throughput.

Its own drift control came in at **1.08× / 1.09×** — tighter than `R18`'s 1.27× — and it **refused
D5's latency claim on that basis**, virtual threads having moved the numbers by less than the same
configuration moved against itself.

⚠️ **Its observability finding was published by me in a wrong form and then withdrawn by D.** See
§3.6. The corrected version: `executor.*` genuinely 404s, but `tomcat.threads.*` answers **HTTP 200
with `-1.0`**.

Two corrections it accepted from me and one it gave back: the Little's-law derivation was replaced
by the measured `hikaricp.connections.acquire` timer; the worker-held cross-check was rebuilt
against A′; and **it corrected my "monotonic" characterisation** (§3.4, instance 4).

### 4.4 Slice E — ✅ **ACCEPTED**

15 commits at `f157d81` verified, tree clean. `ADR-019` deliberately **`Proposed`, not Accepted** —
it has measured that the unsorted pair deadlocks and **not** that the sorted one does not, and
declines to conclude by argument where a measurement was available.

Ledger `6.6` **half-closed, not ticked**: deadlocks measured (10 pairs, 10 casualties, `bothDied=0`,
`SQLSTATE 40P01`, `deadlock_timeout=1000ms` at `source=default`), lock ordering **not** —
`lockInAscendingIdOrder` is committed and has never run.

Its annotation of `R6` §8 verified as procedure-correct: 16 lines added, 1 removed (the `Updated`
date, which CHECK 2 requires), original sentence left standing beside the annotation.

---

### 4.5 The close-out, re-verified against the tree on 2026-08-22

**Nothing below is taken from a worker's report.** Every cell was read out of the tree after the
worker said it was finished.

| | D `round3/pools` | E `round3/layers` | G `round3/basics` | H `round3/recency` |
| --- | --- | --- | --- | --- |
| head | `2b6633b` | `434b6e4` | `857e5e1` | `99d558b` |
| base | `77022a5` | `77022a5` | `77022a5` | `77022a5` |
| commits | 7 | **36** | 34 *(26 its own + H's 8)* | 8 |
| reflog: reset / amend / rebase | none | none | **none** — the two non-commit entries are `Created from round3/recency` and a fast-forward merge of it | none |
| reports | `R29`–`R33` | `R34`–`R38` | `R39`–`R42` | `R43`–`R45` |
| ADRs | `ADR-018` | `ADR-019` | `ADR-020` | `ADR-021` |
| `open.md` rows | — | — | `OPEN-13`, `OPEN-6` | — |
| guarded files touched | none | none | none | `ADR-014` only, by design |
| migrations | 0 | 0 | 0 | 0 |

**Numbers taken versus numbers assigned: identical, all four, zero shifts.** The only identifier
collision in the round is `OPEN-13`, and it is against `main` rather than against a worker — §6.3.

#### The §9 self-check, re-answered by me for slice E

| | answer, from the tree |
| --- | --- |
| **(a)** cache rather than execution | `--rerun-tasks`, `BUILD SUCCESSFUL in 24m 50s`, and **all 60 result files newer than a marker planted at 21:50:21 — zero stale.** Nothing was reused |
| **(c)** loosened threshold or assertion | **0 removed, 106 added, and not one pre-existing test file modified.** The strongest form this answer can take |
| **(d)** a code comment made false | none — E introduces no index claim, and `CHECK 5`'s axis finds nothing in its diff |
| **(e)** a version written from memory | `16.14` appears **14** times in E's added lines and **not once as a claim about its own runs** — every occurrence quotes another document or reports the sweep, including E's own warning that *"`R37` and this handoff appear in any naive `16.14` grep and must not be swept."* Its blocks say `16.15` / `cf78e766`, read from the container |
| **(f)** company / job / CV / interview / portfolio wording | **0.** ⚠️ A naive grep returns **1** — it matches **the self-check question's own text.** The instrument matched the question asking about it |

#### ⭐ Slice G's §9(c), verified rather than accepted

The PO ruled G's assertion change *a strengthening, not a loosening*. **Read out of the diff:**

- The two `assertEquals` on the row (`score == 0.000`, `attemptsCount == 0`) became a single
  **exact `assertNull`** — because `ADR-020` made `record` `REQUIRES_NEW`, so the row genuinely is
  no longer visible from the caller's transaction. **The observable changed because the code
  changed**, which is §3.4, handled by moving the observable rather than relaxing the assertion.
- A **new** assertion was added on `rejection.reason`, so the test can no longer pass on an
  accident rather than on the mechanism it names.
- The load-bearing assertion moved from the row to the `RecordingOutcome` — *assert on the route,
  not the destination* — and the KDoc now records that `null` has had **three different causes**
  and that the row cannot tell them apart.

**Three assertions before, three after, plus one. Strictly stronger.** ⚠️ My own first count said
*eight assertions removed*; four of those eight were **KDoc prose lines containing the word**. The
instrument matched the word, not the construct — §6.2's class, in the check written to close the
round.

---

## 5. Conditions every number was taken under

⚠️ **The four slices did not measure under the same conditions and F must not compare across them
without saying so.**

| | |
| --- | --- |
| **H** | Before any other slice existed. Machine otherwise quiet. Counts and CI-check results only; **no load number, nothing beside a `p99`** |
| **D** | Exclusive timing lock, 15:16:17 → 16:35:37. Guard sampled processes and containers at the start and end of every run. **This is the only slice whose numbers are timings** |
| **G** | Counts, plan shapes and booleans only. Baseline `125`/`15` taken while D and E were active; final baseline taken while D's full run was active, load 1.79 at start, 10 containers |
| **E** | Counts, row values, SQLSTATEs. Targeted runs released to overlap D's full run. **E1's cost comparison still owes an exclusive lock** |

**Server version differs from the historical corpus.** D, E and G measure on `cf78e766…` =
**16.15**; `R2`, `R18` and `R3` were taken on `57c72fd2…` = **16.14**. `R27` compared 16.14-musl
against 16.15-**glibc**, a different pair, so **nothing in this repository has calibrated the
16.14→16.15 musl step.** Slice D has already refused to place a D1 figure beside `R2`/`R18` on
that ground.

---

## 6. What the orchestration itself got wrong

**Seven errors, all mine.**

> ⚠️ **This line read *"Four errors"* over a list of five until 2026-08-22.** Corrected rather than
> quietly fixed, because it is §3.6's class occurring in the heading of the section that catalogues
> §3.6's class. The list has since grown to seven; the count is now derived from it.


1. **I gave G and D stale line ranges.** The canonical pack was updated from 866 to 885 lines and
   every section shifted by +19; both were already dispatched. Caught by me, corrected within
   minutes, and I attached a verification condition — *"line 412 must read `## §3`; if it does not,
   stop"* — rather than asking them to trust the second set either.
2. **I told G that counting work "does not contend."** Its *numbers* do not; its *execution* does.
   G was nine Testcontainers containers deep in a full suite on the cores D's latency arms needed.
   **Slice D caught this**, refused to measure against it, and asked me to arbitrate.
3. **I published D's "monotonic slide" to the PO as fact.** §3.6, instance 4.
4. **My `16.14` count missed `R9`.** §3.6, instance 5.
5. **I read the wrong `docker ps` column and wrote a characterisation over it.** I described a
   container as *"6 weeks old, pre-existing infrastructure"* from `--format {{.RunningFor}}`, which
   reports **time since created**. **`.Status` is uptime; `.RunningFor` is age.** Caught by slice E,
   which checked the floor itself instead of taking my listing — exactly what I had told it to do,
   and the one time it mattered.
6. **I published *"30 of 31 environment blocks are silent"* without ever counting it**, and the PO
   quoted it back in a ruling. The figure is **31 of 31**. §3.6, instance 7 — and the only one of
   my three that was never measured at all rather than measured once and carried.
7. **I edited the canonical environment block and read past a false claim two rows below it.**
   `223c1fa` added the `WSL VM config` line; `measurement-discipline.md` prints the *superseded*
   image digest under the words *"Pinned by digest since `8dec7e6`"* four rows further down, and I
   did not read it. §3.7. **The commit message argues that a true sentence nothing reads is this
   repository's defect class.**

### 6.1 ⭐⭐ Three VM restarts, two lost measurements — and the cause was my own hygiene

**I filed two environment incidents as *cause unestablished* and refused to exonerate my sweep.
The cause is now established and it was worse than un-exonerated: it was me, through a mechanism
none of us knew about.**

`C:\Users\airto\.wslconfig`:

```
[wsl2]
vmIdleTimeout=60000          # changed 2026-07-25 from -1
# "With a finite timeout the VM terminates once no distribution is running and
#  returns the whole footprint to Windows."
```

**Sixty seconds with nothing running and the WSL2 VM shuts down.** Against the three boots:

| boot | what preceded it | what it cost |
| --- | --- | --- |
| ~15:11 (inferred) | the machine going quiet around my first daemon sweep | `proxima-d-db` stopped — **slice D lost two measurement attempts** |
| **18:38:24** (measured) | **my full sweep at 18:34:41 left 0 java processes** | the container cycle that cost slice E a false *"cause unestablished"* note |
| **19:31:30** (measured) | slice G released and the machine sat idle | **both of slice E's count-only runs, killed mid-flight** |

> **The hygiene imposed to protect measurements is what destroyed them.** — slice E's phrasing,
> and it is this round's own subject in a different costume: **a remedy correct in one scope and
> silently wrong in another**, which is `E1`'s finding arriving at the orchestration layer.

**Remedy, disclosed rather than hidden:** from ~19:34 I hold a `sleep` inside the distribution so
`vmIdleTimeout` cannot fire. No CPU, no IO, no containers. ⭐ **It was introduced only after slice E
released the timing lock** — an hour earlier it would have been an extra process inside E's cost
sweep. Every environment block written after that time says so.

### 6.2 ⚠️ An instrument matching its own command line — five instances, three of them mine

| | who | what |
| --- | --- | --- |
| 1 | slice D | `ps -eo args` matched its own `grep`; **would have aborted all fifteen arms** and looked exactly like a contaminated machine |
| 2 | slice G | `pgrep` matched itself; caught before it became a fact in a message to me |
| 3 | **orchestrator** | `pgrep -af 'sleep 86400'` matched itself while verifying the keepalive — *while writing up the other two* |
| 4 | **orchestrator** | `grep -c "denies an index"` counted CHECK 5's own `FAIL:` summary line, reporting slice E at **3** findings when it has **2** |
| 5 | slice E | `pgrep -cf GradleWorkerMain` matched **its own `bash -c` wrapper**, so `workers=1` meant *zero* and `daemon=2` meant *one* |

**Instance 3 is the one worth keeping**: I introduced it in the same hour I recorded instances 1
and 2 as a class.

⭐⭐ **Instance 5 is worse than a self-match and E said so itself.** Because the count could never
reach zero, **the monitor's stall condition was unreachable** — it was not a detector that
mis-fired, it was a detector that *could not fire*, which is §3.3 exactly: a check reporting a
conjunction whose second half was never satisfiable. ⛔ **And E had the right reading beside the
wrong one the whole time** — a separate script used the bracket trick, `grep '[G]radleWorkerMain'`,
and was correct. **Two instruments over one machine disagreeing, and the wrong one trusted**, which
is `.study` 12장 §6.3's shape and §3.2's.

**The fix E adopted is the round's own rule turned on the harness**: `R38`'s *assert on the route,
not the destination* — report `workers`, `kotlinDaemons` and `gradleDaemons` **separately**, so
*compile-stuck* and *test-running* stop producing the same reading. **No monitor used anywhere
today could distinguish those two**, mine included.

**And one near-miss.** I was one message from accusing G of jumping its slot, having seen a
`GradleWorkerMain` in its worktree while D held the machine. Inspecting the command line first
showed `--tests net.gseek.proxima.arch.*` — a **targeted** run, explicitly permitted by the rule I
had written. Inferring from the process kind rather than reading the arguments would have produced a
false accusation from a true observation.

**One rule of mine I changed, and said so.** "One full test run at a time, machine-wide" was mine,
not the PO's, and it was about flake risk. I relaxed it for G on measured grounds — load average
**1.14 on 8 cores**, D's run IO-bound rather than CPU-bound — rather than let it look as though the
rule had always been soft.

**One environment incident, cause unestablished.** `proxima-d-db` stopped at `06:06:13Z`, thirty
seconds after my daemon sweep finished, `exit=0`, not OOM. It carries **no Testcontainers labels**
so standard Ryuk reaping does not apply, and I can show no mechanism by which my sweep stopped it —
but a thirty-second gap is not something I get to call coincidence. **Recorded as unestablished
rather than resolved in my favour.** It cost slice D two measurement attempts and exposed a real gap
in its guard: it watched for foreign containers *appearing* and not for the required one *leaving*.
D added the symmetric check.

---

### 6.3 ⛔⭐ I reserved report numbers and ADR numbers, and never reserved `open.md` row IDs

**The round's one merge conflict is a number I assigned twice.**

| | row | opened |
| --- | --- | ---: |
| `round3/basics` (G) | *must the connection pool be able to report its own exhaustion before the recording path acquires a transactional caller?* | **18:29** |
| `main` | *should the toolchain pin a vendor, so that the JVM every number here was taken on is requested rather than merely recorded?* | **20:21** |

Both are good rows. Both are `OPEN-13`.

**What my numbering scheme actually covered, checked rather than remembered.** Introduced since
the common base `77022a5`:

| writer | reports | ADRs | `open.md` rows |
| --- | --- | --- | --- |
| D `round3/pools` | `R29`–`R33` | `ADR-018` | — |
| E `round3/layers` | `R34`–`R38` | `ADR-019` | — |
| G `round3/basics` | `R39`–`R42` | `ADR-020` | **`OPEN-13`** |
| H `round3/recency` | `R43`–`R45` | `ADR-021` | — |
| **`main`** | **—** | **—** | **`OPEN-13`** |

**Reports and ADRs: zero collisions, exactly as claimed.** That part of the scheme worked and the
table is the evidence. **`open.md` row IDs were in no scheme at all**, for a reason that is worth
writing down rather than excusing: I assumed a slice would not open one, and then **I personally
ruled that G's cross-slice finding should become `OPEN-13`** — the ruling in §3.3 — without
entering it anywhere a second writer could see.

⭐ **And the second writer was `main`, which I had not counted as a writer.** Three worker branches
were being tracked for collisions; the trunk was being treated as a fixed base. It was not fixed —
it took six commits during the round, two of them into `open.md`.

**Resolution, decided here and executed in F:** ⛔ **`OPEN-13` stays with G's pool row**, which
opened 1h52m earlier and was the one actually assigned. **The toolchain row becomes `OPEN-14`.**
Citations to move, counted: `open.md` ×3, `measurement-discipline.md` ×2, `ADR-000` ×1 — **six**,
all on files already on `main`, so the renumber touches **no worker's signed deliverable**. G's six
citations (`open.md` ×3, `_ROUND3-G-HANDOFF.md` ×3) do not move.

**Both sides also annotated *the table is not empty any more* in different words.** Those two
annotations are about two different arrivals and **both are kept.** Merging them into one sentence
would delete the fact that the table filled twice in one day from two unrelated directions.

### 6.4 ⭐ A number refused in a place where no report was watching

While checking whether this build honours `autoMemoryReclaim` I read the host footprint:
**`vmmemWSL` working set 5,041 MB, commit 5,105 MB**, against the baseline `.wslconfig`'s own
comment records as measured before the `vmIdleTimeout` change — *"vmmemWSL WS 4,769 MB / commit
6,710 MB."*

Two numbers, the same counter, a difference in both directions. **It was not offered**, because
four hours of builds sit between them and neither figure was taken under a stated condition. It is
not a before/after; it is two readings of a machine that changed for reasons nobody controlled.

**The PO asked for this to be recorded rather than left in a chat log, and gave the reason.** The
refusal happened in a side conversation. Nothing was going into a report, nobody had asked for the
figure, and no gate would ever have seen it. ⭐ **The discipline that holds while a report is
watching and the discipline that holds when nothing is watching are different things**, and only
the second one is evidence about the discipline.

`R35` refused a magnitude four times; those refusals are in a report, under review, by an author
who knew they would be read. **This one is worth more precisely because it is smaller.**

### 6.5 ⛔ My own instrument failed at launch, and I read its silence as evidence three times

I put a background watcher on slice E's final suite: wait for the wrapper PID to exit, then read
both modules' XML counts. It **exited 127 within a second** — `END: command not found` — because
the `awk` program's quoting was mangled passing through PowerShell into `bash -lc`.

Its output file stayed empty. **I read that empty file three times and recorded *still running*
each time.**

⭐ **Empty meant dead. I read it as alive.** That is §3.3's property once more — one observable,
two causes, and only one of them named — and it is the **fifth** instance in this round, this time
in the orchestrator's own instrument while §3.3 was already written down two sections above.

**What it cost, stated rather than minimised.** Four minutes of not noticing that E's run had hung,
and nothing else — the failure direction was safe, because a broken watcher publishes no number.
⚠️ **The other direction was available and would not have been safe**: had the `awk` survived and
the PID check been the broken half, it would have read the stale XMLs left from a *targeted* run at
20:06 and reported `:api:test 1 test` as a completed full suite. **Same bug, one line over,
publishes a false count.**

**The repair is the one this round keeps arriving at**: the watcher had no precondition assertion.
`ADR-015` says a race test must prove a race happened; a watcher must prove it watched. One line —
*fail loudly if the PID was never observed alive* — and the silence becomes a failure instead of a
reading.

> ⭐⭐ **Confirmed the same hour, by the run itself.** The rebuilt watcher fired at **21:34:58** on
> slice E's third attempt and reported:
>
> ```
> --- :api:test --- xml files: 1
>     tests=5 skipped=0 failures=0 errors=0
>     STALE CHECK -- fresh=0  stale=1
> --- :seed:test --- xml files: 4
>     tests=14 skipped=0 failures=0 errors=0
>     STALE CHECK -- fresh=4  stale=0
> ```
>
> **`:api:test` `tests=5, failures=0` is the false count this section predicted**, read off a single
> XML left by a *targeted* run at 20:06. It is a clean-looking green from a suite that never
> started. **The line under it is the whole difference** — `fresh=0 stale=1` — and it is there only
> because writing §6.5 forced the question *what would a broken version of this publish?*
>
> **A precondition assertion earned its place inside one hour of being written**, which is the
> argument `ADR-015` makes and did not yet have an instance of outside a race test.

> ⛔⭐ **And then the assertion itself went stale, which is the strongest of the three.** The
> freshness boundary was a **hardcoded timestamp** — attempt 3's start, `21:09:27`. When slice E
> launched attempt 4 at `21:35:31`, the same script kept comparing against the old boundary and
> reported `:seed:test  fresh(this run)=4`. Those four files belong to **attempt 3**.
>
> Re-deriving the boundary from the live wrapper's own start time — `ps -o lstart=` — **changed the
> answer on the spot**:
>
> ```
> hardcoded boundary   :seed:test  xml=4  fresh(this run)=4  stale=0
> derived boundary     :seed:test  xml=4  fresh(THIS run)=0  stale=4
> ```
>
> ⭐ **A staleness check that goes stale.** It was correct for exactly one run and became wrong for
> the next **without changing its output format**, which is §3.6's class occurring inside the
> instrument built to catch §3.6's class. Nothing was published from the wrong reading — the earlier
> `fresh=4` I did report was taken while `21:09:27` was still the live run's start, and was true
> then — but **it was one launch away from not being.**
>
> **The general rule this yields, and it is the one to keep**: a freshness boundary must be
> *derived from the thing being measured*, never written down beside it. A written-down boundary is
> a second fact that has to be maintained in step with the first, and nothing maintains it.

### 6.6 ⛔⭐ I made every worker declare what else was running, and I did not know myself

`git worktree list`, read today rather than assumed: **nine worktrees.**

| | |
| --- | --- |
| round 3 | `proxima-d` `proxima-e` `proxima-g` `proxima-h` + `main` |
| **a separate line of work I never counted** | `proxima-counts` `proxima-pin` `proxima-premises` `proxima-why9` |

Those four produced the **six commits `main` took during the round**, including the `OPEN-13` of
§6.3.

**And one of them was building while slice E took the round's last measurement.** Attributed out of
the Gradle daemon logs rather than inferred — each daemon served exactly one worktree:

| daemon | worktree | builds |
| --- | --- | --- |
| `924` | `proxima-e` | E's suite started **20:07:13** |
| `10919` | **`proxima-pin`** | **20:13:17** and **20:32:55** — both inside E's run |
| `19686` | `proxima-e` | E's third attempt, **21:09:27** |

**Two Gradle daemons at `-Xmx2g` each, plus test workers and Testcontainers, on 8 cores.** E's first
full suite took **21 minutes**. The second ran **59 minutes, produced no result file at all**, and
was cancelled.

⛔ **This is a condition, not a cause.** Nothing here establishes that the overlap hung the build,
and E is right to call the hang unexplained rather than attribute it — **미측정**. What is
established is that the round's last measurement ran on a machine with another writer on it, and
**neither E nor I knew.**

⭐ **The hole is the same one as §6.3, from the same assumption.** I locked timings across the three
workers I dispatched and treated the trunk as a fixed base. It was not a base; it was a fifth
writer with four worktrees. **The rule *every published number states what else was running* was
enforced on everyone except the person enforcing it.**

**What this changes for F.** The census above is what a quiet machine has to be checked against —
not `docker ps`, which showed nothing relevant, and not load average, which was 1.10 while a
second daemon held 2 GiB. **`git worktree list` plus one build-command grep per daemon log** is the
check, and F's final run does not start until it comes back with one worktree on it.

## 6.7 The §9 self-checks, re-answered by me against the tree

The brief forbids delegating this. Each answer below is mine, from the tree, naming what I ran.

| | verdict | what I checked |
| --- | --- | --- |
| **(f)** company / job / CV / interview / portfolio wording | ✅ **CLEAN, all four** | `git grep` for eleven patterns across every branch. **Every hit is the §9 question text itself.** Zero real occurrences |
| **(e)** versions written from memory | ✅ **CLEAN, all four** | all four cite `cf78e766` / **16.15** read from the container. Every `16.14` in a round-3 document is the **superseded** value being explicitly refused — `R29` refuses the cross-version comparison, `R33` names it, `R37` flags the discipline doc |
| **(c)** loosened thresholds or assertions | ✅ **verified** | pre-existing test files modified: **G 1, D 0, E 0.** D and E touched no prior test at all, so their (c) answers can only concern gates they wrote. G's single change is the route-not-destination fix, ruled a **strengthening** — stricter, not looser |
| **(d)** false claims in code comments | ⚠️ **2 inherited each on D and E; none introduced** | CHECK 5 run on all four worktrees. H and G pass. D and E carry exactly `RecommendationQueries.kt:9` and `RecommendationService.kt:69` — **the two comments H fixed**, false on their branches only because both are based on `main`, which predates `2c7f16d`. Confirmed by diffing the file across `main` and `round3/recency` |
| **(a)** results from a Gradle cache | ✅ | every slice's reports name `--rerun-tasks` executions; counts re-read by me from each worktree's own XML rather than from any report |
| **(b)** numbers crossing machines or sessions | ✅ with a stated exception | one machine, one day. **The exception is the server version**: D, E and G measured on 16.15 while the historical corpus is 16.14, and all three refuse the comparison rather than making it |

⭐ **The inherited (d) findings are a measured cost of the deferred merge, and they close themselves.**
D and E must **not** fix them — H already did, and a second fix is a conflict at F plus an edit
nobody reviewed. **CHECK 5's first real-tree execution happens at F, which is the thing the
deferred merge delayed — so the deferred merge created those four findings and the delayed thing
is what closes them.** A clean loop, and true.

## 7. For slice F

- **CHECK 3 costs up to 17 roadmap rows in one pass**, not one per report per slice. `main` carries
  rows for 29 reports; `R39`–`R45` are unrowed now and D and E will add more. Counted from the
  branches, not inferred.
- **CHECK 5 against the real tree has never executed.** It runs for the first time when the roadmap
  rows land. ⛔ **Read that run.** If it fires, that is a finding, not a gate to be quieted.
- **Correct ledger `43.3` rather than closing it**, and say which half closed when.
- **Consolidate G, D and E's handoff §7 ledger rows into `ADR-014`** — only H edited it directly.
- **Fix `measurement-discipline.md`: the digest, the two false claims at lines 92-103, and the
  missing rule that a count must publish its unit.** ⛔ Do not sweep `R9`, `R37`, or slice E's
  handoff — §3.2 says why for each.
- **Count the ledger rows yourself.** Slice H added **14** and partly closed one; the pack says
  thirteen, and I counted 14 from the tree.

### 7.1 ⭐ `ADR-015` owes three worked examples, and the races get demoted

An ADR whose examples are all races teaches *watch out for races*. Slice E's generalisation is a
**strictly larger class**:

> **A vacuous pass is a property of any test whose observable is reachable by two routes when the
> test is named for only one of them.**

**State the general property first and let four shapes stand under it as instances** — the races
already there, E's sibling-arm control, E's vacuous propagation pass (`NESTED` refused, inner work
never ran, row read `100` either way), and **`R45`**, where an ArchUnit exclusion added for a false
positive also hid the true one. **None of the last three has any concurrency in it.**

⭐ **What makes this evidence rather than a stretch: two of the instances were found independently,
in different subsystems, by sessions with no contact, inside the same hour.** That is a claim about
how *often* the shape occurs, which neither slice could have made alone.

### 7.2 ✅ `autoMemoryReclaim` — the cheap half is done; the half that needs a quiet machine is not

**The PO re-priced this after opening `.wslconfig`, and the re-pricing was right.** The first
question is not what `gradual` does to `ADR-005`'s `576.8`/`140` pair. It is **whether this build
honours the key at all** — and if it does not, the whole concern evaporates.

#### What was checked on 2026-08-22, without starting or stopping a distribution

`wsl --version` reads the host binary and touches no VM, so slice E's running suite was safe.

| | |
| --- | --- |
| WSL | **2.6.3.0** — the exact build `.wslconfig`'s own comment names |
| kernel | **6.6.87.2-1** |
| guest boot, `dmesg` | `hv_vmbus: registering driver hv_balloon` · `Using Dynamic Memory protocol version 2.0` · **`Free page reporting enabled`** · **`Cold memory discard hint enabled with order 9`** · `Max. dynamic memory size: 16132 MB` |

✅ **Established: the reclaim machinery is present and enabled.** The key is not being silently
dropped by a build that has no idea what it means.

⛔ **Not established, and not claimed: that the key is what enabled it.** `Free page reporting` and
`Cold memory discard hint` are reachable by two routes — the key set them, or `hv_balloon`'s
default did — and **the boot lines are identical either way.** Nothing observed so far separates
them.

⭐⭐ **That is this round's own headline arriving a fourth time, and this time in the judge's
question rather than in a worker's test.** §3.3 is the property: *a passing observation reports a
conjunction, and only one branch of it was named.* G found it in `AttemptRecordingServiceTest`, E
found it independently the same afternoon in `R38`, `R45`'s ArchUnit exclusion is the third shape —
and the fourth was **applied by the worker to the person ruling on the worker**, about a config
key. **A property that keeps arriving from directions nobody aimed at is worth generalising**, and
that generalisation is the next round's proposition rather than F's errand.

#### The three steps

| | state |
| --- | --- |
| **1** — ledger row `ADR-014` `D.17` | ⏳ **deferred to F, and the reason is mechanical**: `ADR-014` is edited by `round3/recency` and `round3/basics`. Writing `D.17` on `main` now would manufacture a second merge conflict in a round that has one |
| **2** — environment-block standard | ✅ **done, `223c1fa`** |
| **3** — `ADR-005` conditional marking | ✅ **done, `223c1fa`** — on the pair, not on the decision |

⚠️ **The PO's figure was 30 of 31 blocks silent. Counted: 31 of 31.** No environment block in
`docs/` carries any key from `.wslconfig`, and none ever has.

⚠️⚠️ **A debt F must clear, and it is this repository's own defect class.** Both documents
committed at `223c1fa` cite **`ADR-014` `D.17`**, which does not exist yet. **If F does not write
that row, two documents on `main` carry a citation to nothing** — a true-looking sentence with no
referent, which is `R43` one more time and this time authored knowingly. `D.17` is the first item
on F's list, not the last.

#### The A/B, written as a procedure so the next person does not re-derive it

⛔ **Not now.** It needs one `wsl --shutdown`, and today's only quiet machine has the round's last
measurement on it. ⭐ **And it needs a quiet machine for its own sake** — run under load and it
reproduces the mistake it is investigating. **The cheapest slot is immediately after F's final full
run**, when the machine is already being held quiet for F's own numbers.

✅ **Nothing published depends on the answer. It changes a marking, not a number.** That is why it
defers rather than blocks.

1. Machine quiet — no builds, no containers, nothing else measuring.
2. `dmesg | grep -iE 'free page reporting|cold memory discard'` — record both lines **with** the key set.
3. Comment out `autoMemoryReclaim=gradual` in the host's `.wslconfig`.
4. `wsl --shutdown`, then start one distribution.
5. Repeat step 2. **If the two lines are gone, the key drives it. If they are unchanged, the default does** — and `.wslconfig`'s own comment says what follows: *"it can simply be removed - it is inert, not harmful."*
6. Restore the file either way, and write the result into `D.17` and into `ADR-005`'s marking.

**About fifteen minutes, one restart, and it is decisive** — which is why the PO's re-pricing beat
my original *"that is a round, not an errand."* **I had priced the wrong experiment**: I costed the
`576.8`/`140` re-measurement, when the question in front of me was a boot log.

⭐ **`R2` already records being burned once by a cold-buffer artefact**, in its own body: *"That
number was quoted in an earlier decision before being recognised as a cold-buffer artefact."* This
is the same class one layer down, and it went unrecorded for the whole life of the repository.

---

### 7.3 ⛔ `./gradlew --stop` does not stop the Kotlin compile daemons, and F's quiet-machine check must say so

**Found by slice E** on its third failed attempt, and it is the root cause of that attempt rather
than a theory about it:

```
19686  Gradle daemon                     8.5%
19827  Kotlin compile daemon   1309 s    3.7%
19947  Kotlin compile daemon   1273 s   15.6%     <- 21 minutes inside compilation
```

**No test worker existed at any point.** `:api:test` never started because `:api:compileKotlin` /
`kapt` never finished. That accounts for every reading that looked like a hang: a results directory
still holding a file from 20:06, zero XMLs, and a live child under the daemon's process reaper.

⭐ **The PO's framing, and it is better than "an omission": this is a tool reporting something it
did not do.** The flag is named `--stop`; the Kotlin daemons run with
`--daemon-autoshutdownIdleSeconds=7200` and **survive it**. Nothing lied — `--stop` stops the
*Gradle* daemon, which is what it documents — but **the operator's conclusion, "the floor is
clean", was an unverified claim the whole time**, and this repository's standing rule is that a
tool's verdict is not believed on its own word. **`--stop` is a verdict. `0 java processes` is a
measurement.**

⛔ **The mechanism is the part F needs.** Kotlin compile daemons run with
`--daemon-autoshutdownIdleSeconds=7200` and **survive `./gradlew --stop`**. So E's "clean floor"
between attempts 2 and 3 was not clean: it left a wedged Kotlin daemon standing, and attempt 3
inherited it. Attempt 4 was launched only after both were killed explicitly and `0 java processes`
was verified.

⭐ **E declined to extend this backwards, correctly.** Attempt 2's hang stays **unexplained** —
attempt 3 is the one with direct evidence, and adopting a newly-found mechanism as the explanation
for an earlier event is the error E already made once today with the `SIGTERM`.

✅ **What F must change.** The quiet-machine check in §6.6 was `git worktree list` plus one
build-command grep per daemon log. **That is not sufficient** — a wedged Kotlin daemon appears in
neither. F's check is:

```
git worktree list                                  # one worktree with a process
ps -eo pid,etime,args | grep '[G]radleDaemon'      # bracket trick, not pgrep -f
ps -eo pid,etime,args | grep '[k]otlin-build-tools'
docker ps
```

**All four, and the Kotlin line is the one nothing had ever looked at.** This belongs in a ledger
row, not only here.

---

### 7.4 ⛔⭐⭐ A sixty-minute cap on the launcher killed two full suites, and F's own final run must not be launched the same way

**The round's last measurement failed four times and only one failure was the build's.** Established
from the Gradle daemon logs and the results files, not from the reports:

| attempt | started | outcome | cause |
| --- | --- | --- | --- |
| 1 | 19:41 | **completed, `21m 1s`** | the suite *does* finish in ~21 minutes |
| 2 | 20:07:13 | last write **21:07:13** — `client disconnection detected, canceling the build` | **exactly 60 minutes** |
| 3 | 21:09:27 | E killed it at ~22 min | wedged Kotlin daemon, §7.3 — the only genuine build failure |
| 4 | 21:35:26 | **`BUILD FAILED in 1h`**, `Build cancelled while executing task ':api:test'` | **60 minutes again** |
| 5 | 22:37 | away, **detached** | — |

⭐ **The daemon log is explicit that attempt 4 was not stuck**: *"Build **cancelled while executing**
task `:api:test`"*, and `api/build/test-results/test/binary/output-events.bin` carries mtime
**22:35:12** — **fourteen seconds before the cancellation.** It was alive, executing, and writing.

⛔ **So the cap is a property of the launcher, not of this repository's tests**, and it terminates
by killing the client, which Gradle correctly reports as a disconnection. **A measurement longer
than an hour cannot be taken through a foreground-parented process on this machine.** E's remedy —
`setsid nohup`, so the build's lifetime is not the launcher's — is the right one and it reached it
independently.

✅ **This is an F precondition, not a note.** F's final full run is `--rerun-tasks` across both
modules and the only completed instance of it took 21 minutes — **but attempts 2 and 4 show that a
slower one is reachable, and the cap does not care why.** F launches detached and polls. **A run
cut at sixty minutes produces no XML at all**, so the failure mode is not a partial number, it is
an hour spent for nothing.

#### ⛔ And I was one sentence from a false claim about slice E's run

I had written, and was about to report, that **no PostgreSQL container ever appeared during
attempts 3 and 4** — from `docker ps` at 21:20, 21:48, 21:54, 21:56 and 22:06, and from
`docker ps -a` showing no container created since 21:00.

**Both instruments are blind to the event.** Testcontainers removes its containers on stop, so
`docker ps -a` cannot show them — the same query returns **no `ryuk` container in the entire
history**, and I watched one running earlier in the evening. And all five `docker ps` samples fall
**before 22:06**, while `:api:test` only reached container startup afterwards.

⚠️ **Absence of evidence from an instrument that cannot record the event**, presented as evidence of
absence — and it would have contradicted a worker's true report of its own run. It is the same
shape as the near-miss already in §6.2, where I was one message from accusing slice G of jumping
its slot. **Twice now the orchestrator's error would have landed on a worker rather than on a
number.**

---

## 8. The standard this round is being judged by

> **I will not call it finished while a measurement is still running.**

Applied throughout: the `146 / 1 failed` reading was refused because that run contained a gate
slice E had since fixed; `R35`'s run 3 is labelled **the run the instrument refused** rather than
counted as a data point; `44.3` is **half**-closed; `6.6` moved from *partly* to *closed* only when
the second arm ran; and F does not begin until rehearsal 3 exists **and** E's clean count exists.
