# R0. The scorecard — what the AI draft did with each trap it was writing about

> **Created**: 2026-08-13
> **Updated**: 2026-08-23
> **Position**: last, and numbered first. It could not be written until `T1`–`T9` were done,
> and it is the first thing worth reading afterwards.
> **Red commit / Green commit**: none. This report measures the other eleven; it changes no
> code and gates nothing.

```
근거 / Evidence base
  Commits      : 42, from `c039f00` to this one -- `git log --oneline`
  Reports      : R1..R11, and every §9 in them
  Tests        : 70 across :api and :seed
  CI runs      : github.com/yang-heeseok/proxima/actions -- build, secret scan, no learner data
  Period       : 2026-08-10 to 2026-08-13
  Author       : every commit in this repository was drafted by an AI and reviewed by the PO.
                 So "the AI draft" below is not a separate artefact -- it is the first version
                 of each commit, and this report is a self-assessment. §2 says what that makes
                 it worth and what it does not.
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 질문 / The question

Every trap in `docs/roadmap.md` is a defect chosen because it **passes review and passes the
tests**. Writing about such a defect is not the same as being immune to it. So, for each one:

1. **Did the draft step on the trap it was documenting?**
2. **Did the AI notice by itself?**
3. **What actually caught it?**

Question 3 is the one worth the report. A repository whose thesis is *features can be
invented, failures cannot* has to say which of its own controls ever refused anything, and
which were only ever arguments.

## 2. 방법과 그 한계 / Method, and what it cannot know

The evidence is the commit log, the reports' own §9 sections, and CI conclusions. All three
were written by the same author being scored, which bounds what this can claim:

- **A mistake nobody noticed is not in here.** This scorecard can only count failures that
  something caught. Its denominator is unknown, and that is not a modesty formula — it is the
  central limitation and §8 does not soften it.
- **Where the record does not say what caught a mistake, this report says so** rather than
  reconstructing a plausible account. §7 lists those.
- **The PO's reviews are not counted.** Several corrections came from the PO refusing an
  explanation or a claim. Those are real and they are not in the table, because the table is
  about what the *repository's own machinery* caught.

## 3. 채점표 / The scorecard

| # | trap | did the draft step on it | what caught it | self-caught |
| --- | --- | --- | --- | --- |
| **T3** | a transaction annotation that does nothing | **yes, twice** | a discrimination experiment the AI built; then bytecode | ✔ both |
| **T1** | a connection pool exhausted by a default | **yes, three times** | re-measuring warm; measuring instead of computing; **one request** | ✔ all three |
| **T2** | a page paginated in memory | trap absent — but **two instruments failed** | a planted control event; the first is **unrecorded** (§7) | partly |
| **T4** | an index that exists and is not used | prediction failed, not the trap | the plan not changing, then investigation | ✔ |
| **T5** | updates lost under concurrency | **no** — `T3` had already taught it | — | — |
| **T6** | a uniqueness check two requests both pass | **yes** | **the `T3` ArchUnit gate**, written three reports earlier | ✘ **a gate caught it** |
| **T7** | a test that counts queries | **yes** | the expected number not matching | ✔ |
| **T8** | what an in-memory database does not tell you | **yes** | a control the AI added *after* the prediction failed | ✔ |
| **T9** | authorisation, exposure, tokens | **yes** | noticing an implausible result — **not** the control that was supposed to | ✔, awkwardly |

Detail for each row, with what makes it more than a tick.

### T3 — and the mistake that set the pattern

Two, and the second is the one this repository is about.

The first: a test was written that looked like it verified atomicity, it was green, and it
proved nothing. It was caught by an experiment the AI designed on purpose — **delete the
annotation, run again, see whether the answer changes.** Four cells, and the red commit's row
reads `RED/RED`. That discrimination table is `R1`'s most reused idea.

The second: the build file was read, and the conclusion drawn that entities were `final` and
lazy proxies impossible. **Measuring the bytecode before fixing anything showed
`kotlin("plugin.jpa")` had already opened them.** A plausible reading of configuration was
mistaken for an observation of behaviour — which is a compressed version of the entire
roadmap.

### T1 — three wrong things, three measurements

- **577 ms reported as fact.** Cold buffers. Warm it is 139.6 ms, and a proposal to change
  `T1`'s design had already been sent on the wrong number.
- **Arithmetic presented as measurement.** *"Arm B does twice the database work"* was clean,
  consistent, and false: 149 ms against 140 ms. `R2` §9 — *"그럴듯한 산수는 관측이 아니다."*
- **The remedy assumed rather than tested.** `R4` §9: the rule *one request before load* was
  applied to the defect and not to the fix. One request showed both arms held the connection.

### T2 — the trap was gone, and the instruments were the finding

Hibernate 7.4.1 pushes the page into a derived table; there is no warning because there is
nothing to warn about. **No red commit and no green commit**, recorded as such.

What went wrong was measurement. A `pg_stat_user_tables` delta **passed while asserting the
opposite of the truth** — cumulative counters, reporting delay. Then a log appender that had
captured **zero events** nearly proved an absence. The second was caught by planting
`PROXIMA-APPENDER-CONTROL-EVENT` and requiring it back. That control is the ancestor of every
control in `R8`, `R9`, `R10` and `R11`.

### T5 — the only trap the draft did not step on, and why

`R6` §9 states it plainly: had the retry been called on `this` rather than through a separate
bean, the arm would have been **broken in exactly the way it was meant to fix, while looking
fixed**. It was not, because `T3` had already been measured. **The absence in this row is
evidence for `T3` being load-bearing, not evidence of care.**

### T6 — the entry this whole report exists for

`findOrCreateIsolatingTheInsert` called `insertInNewTransaction` on `this`. Seven failures out
of eight, identical to the strategy it was supposed to improve — **self-invocation, inside the
remedy for a different trap, three reports after self-invocation was measured and gated.**

The AI did not catch it. **The ArchUnit rule written at the end of `T3` did**, by name, and
the commit says so in its subject line:

```
f3c03f6  feat(db): V3 makes mastery unique — and a gate from three reports ago caught today's defect
```

`R7` §9 records that when those five rules were written there was no confidence they would
ever pay. This is the payment. **It is the only entry in this table where a regression gate,
rather than a measurement or a human, refused the author's work.**

### T7 — the scope of a test, caught by a number

The first version of the statement counter wrapped `nextItems`, expected 7, and got 2. Had the
expectation been "corrected" to 2, the repository would have shipped **a green gate sitting on
top of an N+1**. The service does not contain the N+1; it hands one out.

### T8 — a prediction that failed, and a control invented to explain it

Mixed-case ordering was expected to diverge between H2 and PostgreSQL. It did not. Stopping
there would have produced *"the two databases order text identically"* — every word true, the
meaning false. Naming a collation explicitly split it: PostgreSQL orders `apple,Apple,...`
when asked and byte-wise when not, because `postgres:16-alpine` is musl-built.

**And the finding was not about H2.** It was about this repository's own measurement
environment, and it put a risk against every ordering-dependent number in every report here.

### T9 — a control that passed while the conclusion was wrong

The heap dump measurement planted a canary, found it, and concluded the instrument was
trustworthy. It then searched for the datasource password and found it — because
`PostgreSQLContainer` defaults to password `test`, the active profile is `test`, and the
database is called `test`.

**The canary proved the dump was real and the search worked. It could not prove the needle was
specific**, which is a different property and the broken one. Caught by the result being
implausible — `/actuator/env` was reported as leaking, and it does not — not by any control in
place. `DistinctiveCredentialPostgres` and a `check` on the credential exist because of it.

Three more in the same strand, none about security, all the same shape: an `@Entity` placed
inside the scan root (**34 of 52 tests**, caught by CI), a KDoc quoting a url pattern whose
slash-star opened a nested comment (caught by the compiler), and a `Clock` bean duplicating one
that already existed with the same justification (**37 of 56 tests**, caught by the full suite).

### Round two — eight reports, three sessions, and a scorer who was not the author

**The method changed here, and it changed in the direction §2 said it could not.** §8's first
bullet says this report is a self-assessment and cannot be otherwise: the author, the evidence
and the scoring were one party. Round two ran as three parallel sessions against a frozen
contract, and a fourth session merged and scored them. **The scoring party is no longer the
authoring party**, which is what `AGENTS.md` §Claiming completion asks for and what this report
had never been able to satisfy.

**It is not independence, and calling it that would be the flattering reading.** Each slice
still reported its own stumbles; a trap none of them recorded is invisible to me exactly as it
was before. What moved is narrower and still worth having: **the aggregation, the counts, and
the verdicts were produced by someone who did not take the measurements**, and every claim
below was checked against `git diff` and the JUnit XML rather than against prose.

| slice | reports | self-inflicted traps recorded | caught by |
| --- | --- | --- | --- |
| **A** — the prerequisite graph | `R20` `R21` `R22` | **6** | reading a printed plan beside a printed summary; an assertion refusing its own threshold; three predicted numbers refused by their own assertions; the database refusing a malformed statement loudly; an executor dying with no test named |
| **B** — the deployment boundary | `R23` `R24` | **5** | **a drift control**, which found the harness leaking instances between arms; a probe that discarded the response body; a relaxed-binding environment variable that opened a connection and sealed the pool; four arms agreeing because the signal arrived after the work finished |
| **C** — the measurement gaps | `R25` `R26` `R27` | **4** | **mechanical re-derivation, all four** — none by review, none by a gate |
| **integration** — this session | — | **2** | a gradle exit code masked by a pipe to `tail`, found by reading the log rather than the status; an `awk` edit that dropped one line and duplicated another, found by re-reading the block |

**Seventeen, and the shape of what caught them is the same shape §4 already found.** A
measurement or an assertion did most of it. **No regression gate caught a defect in round two** —
the count in §4 stays at one, while the number of gates went up again.

**Three entries are worth more than their tick.**

**`A`'s buffer summariser is `R5`'s mistake, made by an author who had read `R5`.** It summed
nested cumulative counters and reported 2,392 buffers for a plan whose root node says 405 — a
5.9× over-count, and the same class of error as reading `pg_stat_user_tables` as an increment.
`R5` is in this repository, it is about exactly that, and it did not prevent the repeat. **A
written record of a mistake is not immunity from it**, which is `.study` chapter 9's title and
now has a second instance.

**`C`'s worst one survived because two errors cancelled.** A table in `R25` §3.6 was
transcribed from a `grep` listing rather than derived: two rows were merged, a prose row was
counted as a clause, and **the total came out right**. It was found by re-deriving the count
mechanically, and `ADR-014` now generates every count in itself from an embedded script with
its expected output beside it. A number that agrees with a wrong method is the hardest kind to
catch, and nothing here would have caught it if the arithmetic had not been redone.

**`B`'s drift control caught the instrument rather than the drift.** It was planted to satisfy
`R18`'s rule about calling a difference an effect. What it actually found was that the harness
tore down the instances it was about to start and not the ones already running, so a previous
arm's instances survived into the control. **The control was paid for a reason other than the
one it was bought for** — which is the second time in this repository a planted control has
earned its keep sideways (`R18` §3.3 was the first).

**And one gate fired without catching a defect.** `BaselineMigrationTest` went red on `V4` and
again on `V5`, because it asserts an exact table set and an exact migration list. That is the
gate working as designed — it demands a report for a change to the migration sequence, which
`ADR-002` says is the argument this repository makes. **It is not a defect catch and it is not
counted as one in §4**, because a tripwire that fires on an intended change is doing a
different job from the `T3` rules that refused `T6`'s remedy. Recording the distinction matters
more than the tick: this report's headline complaint is that gates here are promises, and a
gate that only ever fires on deliberate edits stays a promise about defects.

**And then, after the push, an instrument turned out never to have refused anything.** The
last commit of round one added `study-consistency.yml`, whose `S3` check re-runs
`docs-consistency.yml`'s artefact rule over `.study/리뷰 읽기/` so that a foreign repository's
filename cited in backticks is caught before the shared gate reddens on it. Run at
integration, it printed `read: Illegal option -d` and then `OK`. `read -d ''` is a bash
extension; the script declared `#!/bin/sh` and the workflow invoked it with `sh`, which on
`ubuntu-latest` is dash — so the loop body never ran, nothing was ever found, and the check
reported clean. Measured rather than argued: with a foreign artefact planted in the same tree,
**dash prints `OK` and bash prints `FAIL`**.

**This is the sixth instrument in this repository to report into nothing**, and the header of
that very workflow was already keeping the count at five.

**But the ending belongs to the repository.** That workflow carries a self-test job which
plants a violation and requires each check to fire, `S3` included — and it invokes the same
script the same way, so **the first CI run of this workflow would have failed on exactly
this.** The workflow reached `origin` for the first time in round two's push, so `S3` had
never been true on CI and the control had never had the chance to say so.

**What found it first was reading the whole output instead of the verdict line.** Had the `OK`
been taken at face value, CI would have said it minutes later. Both worked, and that is the
entry: **a planted control and the habit of reading past the verdict are backups for each
other, and this is the first time in this repository that the two were measured against the
same defect.** The interpreter is now `bash`, and both halves were re-checked — the clean tree
passes, the planted violation is refused.

### Round three — seventeen reports, and five defects in the machinery that produced them

⛔ **This round has two products and a scorecard that reports one of them is the same defect as a
check that is right about its literal predicate.** The first is seventeen reports. The second is
**five contract-level defects in this repository's own apparatus**, and none of them was found by
a sweep.

| | |
| --- | --- |
| reports | **17** — `R29`–`R45`, four parallel slices |
| decisions | `ADR-018`–`ADR-021` |
| ledger rows added | **67** report rows + **11** new `D.n` rows, to **249** classified |
| merge conflicts across 85 commits and four branches | **1**, and it was on the trunk rather than between the branches |
| **defects found in the machinery itself** | **5** |

#### Per slice — and two cells this report cannot fill

Round two's table has the columns `slice | reports | self-inflicted traps recorded | caught by`.
Round three can fill **two of four rows**, and why is itself one of the five defects above.

| slice | reports | self-inflicted traps recorded | caught by |
| --- | --- | --- | --- |
| **D** — the five pools | `R29` `R30` `R31` `R32` `R33` | **6** | a quiet-guard reporting a false positive on **every call** before any run counted; the same guard never checking the system under test was still there; ⭐ **a vacuous measurement reported as a zero** — 6,000 requests that were all `{"error":"missing-token"}` because `xargs` stripped the quotes, so the application answered 401 without touching JDBC and *"0 pinned events"* measured nothing; two arms agreeing because both shared one monitor; a number true once and carried forward; ⭐ **a parser that could not match a leading minus and so failed in the direction that confirmed its author's hypothesis** |
| **H** — the recency window | `R43` `R44` `R45` | **4** | a check that reproduced `R17` §5's discarded prose check at 1 site in 3; a guard that fired on a true sentence one backtick from where it was silent; a service that reintroduced `R1`'s self-invocation on the first attempt — **caught by an existing gate**; and a green that was vacuous because `git ls-files` returned nothing, **caught by suspicion rather than by any instrument** |
| **E** — the layers | `R34`–`R38` | ⚠️ **not supplied** | E published **four** instrument bugs of its own and **five** stale claims found by re-reading its own handoff, but filed no count under this heading |
| **G** — the basics | `R39`–`R42` | ⚠️ **not supplied** | — |
| **F** — this pass | — | **7** | §6 of `_ROUND3-ORCHESTRATION.md` lists them; three are counts that were never derived, two are instruments matching their own text |

⛔ **The two blank cells are not an oversight by E or G. They are `D.25`.** §8 of the slice
contract asks for *the exact sentences for the integrator* and **nothing reads §8** — no gate, no
template check, no field a worker leaves visibly empty. D supplied this row because D chose to;
H's is here because H was this session's own slice. **A contract clause nothing reads is not a
contract clause**, and the evidence is that the same clause under-delivered in two slices, two
different ways, and neither was caught until this table was being filled.

⚠️ **So round two's seventeen and round three's thirteen-plus-two-unknown are not comparable**,
and this report will not put them side by side.

#### The five, and where each was standing when it broke

| | the requirement | satisfiable without being met, by | found |
| --- | --- | --- | --- |
| `D.24` | `CHECK 3` — *every report has a roadmap row* | a substring anywhere in the file | writing the roadmap |
| `D.25` | §8 — *the exact sentences for the integrator* | four report titles | trying to use §8's output |
| — | a report's *Green commit* field | ⛔ **nothing.** The SHA does not exist when the sentence naming it is written | filling it |
| `D.21` | `./gradlew --stop` — *stop the daemons* | it returns success for Kotlin daemons it does not stop | clearing a floor between runs |
| `D.26` | a failing script's exit code | the next command in the chain discards it | a commit message that described a tree that did not exist |

**Four of the five surfaced during slice `F`, the integration pass**; the fifth surfaced in slice
`E` while clearing a machine between attempts. ⭐⭐ **Not one was found by looking for it.** Every
instrument this round built on purpose is a sweep — nine traps, four slices, five gates, one
ledger audit — and `F` was designed to merge, not to detect. `ADR-014` `D.27` is the row, filed as
a judgement so nobody turns it into an errand.

⭐ **All five are `R17` §5, and none of them is prose.** `R17` §5 filed the property as a
limitation of **keyword checks over documents**. A CI check, a slice contract, a form field, a CLI
flag and a shell chain say it is a property of **specifications**, and prose was where it happened
to be noticed first. `R17` keeps the finding.

#### Two vacuous zeros, and neither was caught by the number looking wrong

| | what it reported | what it had measured |
| --- | --- | --- |
| slice D, `R33`'s first pinning run | **0 pinned events** | **6,000 requests, every one `{"error":"missing-token"}`** — `xargs` stripped the quotes from `-H Authorization:`, so the application answered 401 without touching JDBC |
| slice E, `SingletonStateTest` inside the full suite | `loads=1` against `loads=1` | **nothing** — no thread overlapped the window, so both arms exercised the same empty case |

⛔ **A zero reads like a clean result and so does a tie.** Six thousand measurements of nothing
came back as a number a reader would have quoted.

⭐⭐ **Neither was caught by the figure looking wrong. Both were caught by a precondition that
refused the run** — D by reading the response bodies instead of trusting the zero, E by an
assertion that says a race test must prove a race happened. **That is the argument for
preconditions stated as a measurement rather than as advice**, and it is the same argument as
`dea05a5` re-reading the file from disk before `git` was allowed to run.

#### What repaired, and why the growing ledger is not the failure

**The ledger grew all day. The debt did not.** Four repairs landed inside the pass that found the
thing they repair:

- slice `E`'s 300 ms loader, which made an overlap hold **by construction** after a precondition
  assertion refused a run that had exercised nothing;
- the four-item floor check, whose **AFTER** read caught its own `./gradlew --stop` failing;
- the freshness boundary moved from a process start time to a **file mtime**, after `btime` was
  measured stepping `+35 s` on this host;
- `dea05a5`, the first commit here to **re-read the file from disk and assert before `git` was
  allowed to run** — the direct remedy for `D.26`, applied in the commit that filed it.

⭐ **A growing ledger is not a failure. A growing ledger with a flat repair rate would have been.**

#### The one rule that caught something it was not written for

`measurement-discipline.md`'s canonical environment block printed **the image digest its own pin
had replaced**, under the words *"Pinned by digest since `8dec7e6`"*. It was wrong for as long as
the pin existed.

**No report inherited it.** Of the round's seventeen, **eleven name a digest and every one names
`cf78e766`; six name none and all six argue the absence; zero carry the superseded value.** Four
workers, independently, read the version off the running container instead of copying the block.

> **Absolute rule 9** — *never write a version from memory; check the current release and write
> what you checked.*

⭐⭐ **It was written for the case where a document cannot be trusted. That case arrived without
anyone noticing, and the redundancy was the control.** First time in this repository a rule has
caught something it was not written for.

⛔ **And the same day says why that does not generalise.** Within one hour, the orchestrator
recorded a worker's non-independent cross-check as an error and then **made the identical error**,
comparing two readings that shared `btime`. Knowing the failure did not prevent it.

| | what the rule asks of a reader | held? |
| --- | --- | --- |
| **rule 9** — *check the version, write what you checked* | **do a thing**, every time, the same way | ✅ four for four, with no gate |
| *a cross-check must not share a derivation with what it checks* | **notice you are in a situation** | ⛔ failed on its own author inside an hour |

⭐ **A written rule holds as a habit when it names an action, and fails as a control when it names
a category the reader has to notice they are in.** That is what this repository's gates are
evidence *about* — not that people are careless, but **which rules can be left to people** — and
the two examples sit one day apart in the same round.

#### Slice E's five, in its own words

**Transcribed from `_ROUND3-E-HANDOFF.md` §8, not summarised.** E is the only slice that supplied scoring text for its own reports.

`R37` closes `ADR-014` ledger entry `6.6` — *"no lock ordering, no deadlocks"* — **by measuring
both halves, in one invocation.** Deadlocks: 10 pairs, 10 detections, `40P01`, one casualty
each, `bothDied=0`. Lock ordering: `casualties=0` under ascending order, and `bothBetweenLocks`
**10 → 0**, which is the stronger claim — the ordered pair cannot interleave at all, so the
remedy removes the race instead of surviving it. Seven new entries replace it, which is what a
closure that was really paid for looks like.

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

#### The template is a suspect and is not convicted

Round three's ledger rows are **88 % class (a)** against 40 % for rounds one and two. Four workers
writing §8 against a template that demands a cost in minutes produce **errands, not judgements**.
⛔ **Whether that is the ledger improving or the slices declining harder questions is `미측정`, and
this report does not resolve it.** An unresolved question about the round's own instrument is a
better thing to publish than a flattering reading of it. ⚠️ The 88 % also stands on **one count**:
a second party attempted an independent sub-count, its instrument returned zero rows, and no
number was offered rather than a shaky one.

---

## 4. 무엇이 잡았는가 / What actually caught things

Counting every recorded failure in this repository, not only the nine rows above:

| what caught it | count | which |
| --- | --- | --- |
| **a measurement the AI ran on purpose** | 7 | cold/warm; latency arithmetic; bytecode vs build file; one request; statement count; ICU collation; plan unchanged |
| **CI** | 3 | `gradlew` non-executable (`56f304c`); the entity scan (`8e5843a`→`bc26328`); the split-step lane naming the layer |
| **a control planted in an instrument** | 2 | the appender's control event; the statement counter refusing to report 0 |
| **a regression gate** | **1** | `T3`'s ArchUnit rules, on `T6`'s remedy |
| **the compiler** | 1 | the nested KDoc comment |
| **reading a file before acting** | 2 | `.wslconfig`'s recorded decision; `ProximaConfiguration`'s existing `Clock` — the second **after** the failure, not before |
| **an implausible result** | 1 | the heap dump needle |

Two things stand out.

**Measurement caught most of it, and measurement is the cheapest thing here.** Not the
architecture, not the review, not the types.

> **Round three moves this to two, and only by one.** `BatchInsideATransactionTest` is a new gate
> that **has been watched refusing** — it failed at `94fe9ee` and passes at `022675b`. Slice G
> supplied that correction itself, and supplied the limit with it: **`R39`'s and `R41`'s tests are
> gates that have never refused anything and must not be counted as paid.**
>
> ⛔ **Slice D asked for the same restraint in the opposite direction**, unprompted: *"none of my
> five new test classes has ever refused an edit. They assert the present state so that a future
> one trips them; that is a promise, not a payment."* ⭐ **Two slices, independently, argued
> against counting their own work as evidence.** The number is **2**, and the count of gates went
> up by far more than that again.
>
> ⭐⭐ **A scorecard is worth something only when the scored argue their score down.** This
> repository's signature line has always been *"eight of nine are still a promise."* Round three
> **added heavily to the promise column and moved the paid column by exactly one** — and the two
> parties who would have benefited from the other reading are the two who refused it, separately,
> without being asked. **That is the only evidence a scorecard can offer that it is not
> marketing.**

**Exactly one regression gate ever refused this author's work.** Nine test classes in this
repository exist to refuse a future edit rather than to measure anything —
`TransactionBoundaryRulesTest`, `ConnectionHoldingGateTest`, `QueryCountTest`,
`EmbeddedSubstitutionGateTest`, `ManagementSurfaceGateTest`, `AuthorisationGateTest`,
`TokenExpiryGateTest`, `PersistenceUnitGateTest`, and `BaselineMigrationTest`'s assertions
about what `V1` deliberately omits. Three more are controls for those, and two are trip-wires
on framework defaults.

**One of the nine has ever been paid.** That is not an argument against the rest — `R8`'s
statement count and `R11`'s authorisation gate would each refuse a specific, plausible future
edit, and `PersistenceUnitGateTest` exists because the failure it prevents already happened
once. But a repository that counted gates as evidence would be overstating its case eight
times over, and this is the report that has to say so.

## 5. 패턴 / What repeated

**Self-invocation appeared inside the remedy of three separate traps** — `T3`, `T5`, `T6`. The
roadmap lists `T3` as one item in Tier 1. It is not a peer of the others; it is underneath
them, and `R7` §9 says so.

**Three times, the thing believed to be "the solution" turned out to be the enabler of the
next decision.** The projection (`R4`) did nothing until `open-in-view` went off. `@Version`
(`R6`) converted 864 silent losses into 820 loud rejections and fixed no counter. The unique
constraint (`R7`) converted 7 silent duplicates into 7 exceptions and left the code wrong.
`R7` §9 named it a pattern; nothing since has contradicted it.

**Four times, a defect "everyone knows about" was already closed by the framework** — `T2`'s
in-memory pagination, `T4`'s stale statistics, `T8`'s embedded-database substitution, `T9`'s
heap dump. In each case the useful move was not to delete the row but to **measure what is
holding it shut**, so that a changed default fails the build. `management.endpoint.heapdump.access`
defaulting to `none` was read out of a jar, not out of documentation.

**And the same failure mode recurred at three different layers on the last day**: something
readable was inferred instead of read. Scan root, comment nesting, existing bean. The third is
the sharpest — an argument was re-derived from scratch that already existed, nearly verbatim,
in a file three directories away. **Being obvious enough to reach twice is a signal that
somebody already reached it.**

## 6. 초안이 결함을 만든 비율 / How often the draft was the problem

Of nine traps, the draft stepped into the defect it was documenting in **six**: `T3`, `T1`,
`T6`, `T7`, `T8`, `T9`. `T5` did not, and the reason is that `T3` had been measured first.
`T2` and `T4` are not scoreable that way — in both the trap turned out to be absent, and what
failed was the instrument or the prediction.

**Six of nine is the honest headline of this repository.** The traps were selected precisely
because they survive review and green tests, and an author who had just finished writing about
one of them walked into the next one anyway.

## 7. 기록이 말하지 않는 것 / Where the record is silent

Listed rather than reconstructed, because a plausible account written after the fact is
exactly the failure mode `R2` §9 is about.

- **What caught the `pg_stat_user_tables` delta.** `R5` §9 records that it passed while
  measuring the wrong thing, and does not say what exposed it.
- **Whether the AI would have caught `T6`'s self-invocation without the gate.** `R7` §9 says
  the numbers "looked odd" before the gate named it. That is not evidence either way.
- **How many mistakes were never caught.** §2. Unknowable from inside.
- **Which corrections came from the PO.** Several did — an explanation refused as
  term-listing, a claim challenged as imprecise — and they are deliberately outside this
  table, which measures the repository's machinery rather than its review.

## 8. 남는 위험 / Remaining risk

- **This is a self-assessment and cannot be otherwise.** The author, the evidence, and the
  scoring are the same party. Every number here is a count of things that were *recorded*,
  and recording was also done by that party.
- **The denominator is unknown.** Six of nine is six of nine *that something caught*. A
  seventh that nothing caught would not appear, and would change the conclusion in the
  direction that matters.

  > **Round two, 2026-08-21: still unknown, and now surrounded by a different denominator that
  > is known.** `ADR-014` classified every `미측정` and every *남는 위험* bullet in the tree —
  > **168 entries: 68 measurable and not done, 27 not measurable here, 73 questions that do not
  > hold.** That is the first counted denominator this repository has had, and **it is not this
  > one.** `ADR-014` counts gaps the documents admit to; this bullet is about traps the drafts
  > stepped on that *nothing recorded at all*. `R25`–`R27` closed two of the 168 and the slice
  > that built the ledger says in three places that it does not reduce the number here — and it
  > proved its own point by finding `R27`, which the sweep produced and **no entry of the ledger
  > contained.** A count of what you know you do not know is worth having and is not the same
  > count.
- **One gate has ever fired.** Eight more are argued for. If the argument for a gate is that
  it will catch a future edit, then eight of the nine are still promises, and this report is
  the one place that says so plainly.

  > **Round two: still one, and the denominator of promises grew.** Eight reports added test
  > classes; **no regression gate caught a defect in any of them.** `BaselineMigrationTest`
  > fired twice, on `V4` and `V5` — as designed, demanding a report for a change to the
  > migration sequence — which is a tripwire on an intended edit rather than a catch, and is
  > not counted above. The one gate that has ever caught a defect is still `T3`'s ArchUnit
  > rules on `T6`'s remedy, three reports after they were written and twenty-one reports ago.
  > **A control came closest.** `study-consistency.yml`'s self-test was built to fail if `S3`
  > misses a planted violation, and `S3` was vacuous from the day it was written — so that job
  > would have been paid on its first CI run. It did not get to be the finder: reading the
  > check's own output past the `OK` line got there first. **The control was correct and was
  > not the one that caught it**, which is a better outcome than either alone and is still not
  > a gate catching a defect.
- **The traps were chosen by the same author who then failed them.** A trap chosen because it
  is interesting is not a random sample of the defects this system could have.
- **`T2` and `T4` are unscoreable in the table's own terms** and were given rows anyway, with
  the reason stated. A cleaner scorecard would have refused to score them.
- **Nothing here measures the quality of the fixes**, only whether the author fell into the
  defect. A repository can be excellent at catching itself and still ship the wrong remedy —
  `R6` §8 records that the application still uses the second-worst arm it measured.
- **What would break the conclusion**: a review by somebody else finding a defect in the
  measurements themselves. None has happened, and until one does, "six of nine" is a lower
  bound reported by the person being counted.

## 9. 배운 것 / What I learned

**아홉 개 중 여섯 개.**

이 저장소가 고른 함정은 전부 "리뷰를 통과하고 테스트도 초록인" 것들이다. 그 성질을 알고, 그것에 대해
글을 쓰면서, 나는 여섯 번 같은 함정 안으로 걸어 들어갔다. T3의 자기호출을 재고 게이트까지 만든 뒤에
T6의 처방 안에서 똑같은 자기호출을 했고, **내가 세 리포트 전에 만든 게이트가 나를 잡았다.** 그게 이
저장소 전체에서 회귀 게이트가 실제로 값을 치른 **유일한** 사례다.

그러니까 미래의 편집을 거부하려고 존재하는 테스트 클래스 아홉 개 중, 실제로 돈을 낸 건 하나다.
나머지 여덟 개는 여전히 약속이다. 그걸 리포트에 적는 게 맞다고 생각한다 — **게이트의 개수를 증거로
세는 순간, 이 저장소는 자기가 반대하는 종류의 주장을 하게 된다.**

그리고 이 문단을 쓰면서 한 번 걸렸다. 처음에는 "열한 개"라고 썼는데, 세어본 적이 없는 숫자였다.
**정직성에 관한 리포트에 근거 없는 숫자를 쓸 뻔했다.** 세어보니 아홉이었다.

**잡아낸 것의 대부분은 측정이었다.** 아키텍처도, 타입 시스템도, 리뷰도 아니었다. 콜드 버퍼를 웜으로
다시 잰 것, 산수 대신 실제로 재본 것, 빌드 파일 대신 바이트코드를 본 것, 부하 전에 요청 하나를 쏴본
것. 전부 값싸고 전부 지루하다. 그리고 그게 이 저장소가 파는 것의 전부다.

**마지막으로, 대조군에 대해 한 층 더 배웠다.** R5에서 "계측기가 죽었는지 확인하라"를 배웠고, R8·R9에서
그걸 습관으로 만들었고, R10에서 **대조군이 통과한 채로 결론이 틀리는 것**을 봤다. 카나리는 덤프가
진짜라고 말해줬지, 내 바늘이 특정하다고 말해준 적이 없다. 대조군은 계측기가 살아 있음을 증명할 뿐,
**내가 옳은 것을 겨누고 있음을 증명하지 않는다.** 다음 계측기에는 두 개가 필요할 것이다 — 살아 있는지
확인하는 것과, 겨누는 곳이 맞는지 확인하는 것.
