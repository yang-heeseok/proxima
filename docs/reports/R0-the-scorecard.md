# R0. The scorecard — what the AI draft did with each trap it was writing about

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
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
- **One gate has ever fired.** Eight more are argued for. If the argument for a gate is that
  it will catch a future edit, then eight of the nine are still promises, and this report is
  the one place that says so plainly.
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
