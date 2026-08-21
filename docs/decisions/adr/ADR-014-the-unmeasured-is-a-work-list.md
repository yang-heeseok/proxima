# ADR-014 — The unmeasured is a work list, and it is 168 entries long

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Status**: Accepted
> **Opens**: nothing. It closes nothing either. **It counts.**

## Context

This repository writes `미측정` when it has not measured something, and it requires every
report to carry a *남는 위험 / Remaining risk* section that says what went unmeasured
(`PUB-4`, `measurement-discipline.md` rule 8). Both rules have been kept for eleven days.

The consequence has never been stated: **the gaps are therefore scattered across the tree in
a countable form.** Nobody had counted them.

`R19` came closest. It swept all 145 §8 bullets at `b1c1b95` against one question — *does
discharging this need a judgement, or only work?* — and found three decisions filed as risks.
It asked nothing about whether the work was **possible here**, and it did not read `미측정`
outside a §8 section at all. So after `R19` the repository knew which of its risks were
misfiled and still did not know how many of them were measurements somebody could simply take.

`R0` §8 says the thing this ADR is aimed at:

> **The denominator is unknown.** Six of nine is six of nine *that something caught*. A
> seventh that nothing caught would not appear.

That sentence is about mistakes. **This ledger is about gaps**, which is a different
denominator and a knowable one — a gap is knowable precisely because this repository is
required to write it down. §*What this does not do* keeps the two apart, because conflating
them would let a counted list stand in for an uncounted one.

## Decision

**One ledger, over every `미측정` and every *남는 위험* bullet in `docs/` and `README.md`,
classified by whether the measurement is available on this machine.** It is committed before
any measurement is taken from it, so that the priority order cannot be written after the
convenient result.

Every entry is exactly one of:

| Class | Meaning |
| --- | --- |
| **(a)** | **Measurable here, just not done.** A container, a JVM test, `k6`, or a sweep of the tree could produce the number today, with no new feature, no schema change, and no second party |
| **(b)** | **Not measurable here.** It needs something absent: production traffic, a real deployment, another party, an authenticated CI artefact, a fleet's clocks, or a surface an ADR has decided will not exist. The entry records **what would be needed** |
| **(c)** | **The question does not hold.** The entry names no unmeasured quantity — it is a scope statement, a trade somebody chose, a condition on a future world, or it has already been discharged. **The entry is closed** |

### The two columns that are not measurements, and the rule that keeps them out of reports

`Cost` (minutes) and `Flip` (H/M/L — the chance that taking the measurement overturns a
conclusion already published here) are **triage estimates**. They carry no environment block
and they were not measured.

**Rule: no figure in the `Cost` or `Flip` column may be quoted in a report, a commit message,
or `README.md`.** They exist to order this list and for nothing else.

This is stated because the temptation is real and this repository has already lost to it once
in the other direction: `R17` §8 contained the sentence *"a 30-commit repository"* inside a
bullet whose whole subject is that estimates are not numbers. The repository held 58 commits
at the time. An estimate written where a measurement belongs survives by looking like one, so
the columns are named, fenced, and confined to this file.

## The corpus, and how to re-derive every count below

```bash
cd /c/project/airtown/proxima-c

# 37 files: every tracked .md under docs/, plus README.md. .study/ is out of scope --
# those notes are learning, not evidence, and they carry no 미측정 discipline.
git ls-files 'docs/*.md' 'docs/**/*.md' README.md | wc -l

# 100 occurrences of 미측정 in that corpus
git ls-files 'docs/*.md' 'docs/**/*.md' README.md | xargs grep -c 미측정 | \
  awk -F: '{s+=$2} END {print s}'

# 21 of those are the reader's-note line, which defines the term rather than naming a gap
git ls-files 'docs/*.md' 'docs/**/*.md' README.md | \
  xargs grep -c '미측정 means not measured' | awk -F: '{s+=$2} END {print s}'

# 154 top-level §8 bullets across 20 reports
for f in $(ls docs/reports/R*.md | sort -V); do
  awk '/^## *8\./{p=1;next} /^## *9\./{p=0} p' "$f" | grep -cE '^[-*] '
done | awk '{s+=$1} END {print s}'
```

| | count | |
| --- | --- | --- |
| files in the corpus | **37** | `docs/**/*.md` + `README.md` |
| `미측정` occurrences | **100** | |
| — the reader's-note line | 21 | 19 reports + `_TEMPLATE.md` + `README.md`. **`R5` is the only report without one** |
| — governing text that defines or cites the term | 5 | `measurement-discipline.md` ×2, `_TEMPLATE.md` ×2, `publication-readiness.md` ×1 |
| — quotations of a gap that lives somewhere else | 9 | `roadmap.md` ×3, `open.md` ×1, `R18` ×2, `R19` ×1, `R16` §9 ×2 |
| — **naming a gap** | **65** | of which **46** are inside a §8 bullet and **19** are not |
| §8 bullets across `R0`–`R19` | **154** | `R19` counted 145 at `b1c1b95` and `R19` itself adds 9 |

**The 145 was re-derived rather than carried forward, and it holds.** Counting §8 bullets at
`b1c1b95` — the tree `R19` scored — gives 145 exactly, so no other report has gained or lost a
bullet since. That is the one cross-check available for this method, and it is reported because
`publication-readiness.md`'s workflow count was wrong when written and repaired itself
unnoticed; a count carried forward is a claim nobody re-establishes.

## The split

**168 classified entries: 154 §8 bullets, plus 14 gap-naming `미측정` entries outside any §8
section.** The tables below hold **171 rows**, because three of them are cross-references to an
entry classified elsewhere and are listed so the enumeration can be checked rather than trusted.

**Do not count this by reading it.** Every figure in this section comes out of the tables
themselves:

```bash
python - <<'EOF'
import io, re
from collections import Counter
rows, c, xref = 0, Counter(), 0
for l in io.open('docs/decisions/adr/ADR-014-the-unmeasured-is-a-work-list.md', encoding='utf-8'):
    if not re.match(r'^\| (\d+\.\d+|D\.\d+) \|', l):
        continue
    rows += 1
    if 'duplicate of' in l.lower() or 'counted once, there' in l:
        xref += 1
        continue
    for cell in l.split('|'):
        t = cell.replace('*', '').strip()
        if t in ('a', 'b', 'c'):
            c[t] += 1
print(rows, xref, dict(c), sum(c.values()))
EOF
# 171 3 {'a': 68, 'b': 27, 'c': 73} 168
```

| | count | share |
| --- | --- | --- |
| **(a) measurable here, just not done** | **68** | 40 % |
| **(b) not measurable here** | **27** | 16 % |
| **(c) the question does not hold** | **73** | 43 % |
| **classified entries** | **168** | |
| cross-references listed but not re-classified | 3 | `D.12` → `16.8`, `D.13` → `4.9`, `D.14` → `3.1` |
| rows in the tables | **171** | |

The 19 gap-naming occurrences outside a §8 section collapse to 16 lettered entries — three
occurrences of *"the token filter's cost"* (`R15` §1, `R16` §1, `R16` §5) are one question, and
`R2` §5 and `R4` §5 carry one question between them. Of those 16, `D.12` is tabled inside `R16`'s
section as `16.8` because that is where a reader looks for it, and `D.13` and `D.14` duplicate
§8 bullets. **14 are new.** 154 + 14 = 168.

> **This section was hand-counted twice and wrong twice, and the derivation above is the
> correction.** `3afe305` said *169 rows, 64 gap-naming occurrences, 6 governing, 18 outside a
> §8, 73 for (c)*; the first repair said *170 rows, 168 classified, 28 (b), 72 (c)*. **Both were
> produced by reading a listing and adding up.** The script above was written third, and it
> disagreed with both.
>
> Two distinct errors, and they are different in kind:
>
> - **A row the prose promised and the table never got** — the token filter's cost, now `D.16`.
>   The paragraph said *"three occurrences … are one question"* and then no row was written.
> - **A row counted twice** — `16.8` is `D.12`, tabled in `R16`'s section and cross-referenced
>   from the Outside table, and both hand tallies picked up one or the other inconsistently.
>
> **This is the same failure as `R25` §3.6's, in the file that states the rule against it, on
> the same afternoon.** §*The corpus* re-derives `R19`'s 145 rather than carrying it forward and
> explains why — *a count carried forward is a claim nobody re-establishes* — and the numbers
> directly beneath that paragraph were then produced from a reading. **The habit was applied to
> somebody else's count and not to my own, twice, before it was applied to a script.**
>
> `R0` §4 counts what actually catches things in this repository and puts *a deliberate
> measurement* at the top with 7. This is three more for that column and none for any other:
> nothing reviewed these numbers, no gate could see them, and each correction came from
> re-deriving rather than re-reading.

**(c) at 43 % is the number that changes how the corpus reads.** Nearly half of what looks
like a backlog is not one — it is the *what would break the conclusion* line every report
carries by template, a trade somebody argued and chose, or a bullet already discharged in
place. `R19` counted 12 falsified bullets and it was asking a different question; counting
*closed* rather than *falsified* gives more.

**68 is the work list.** It is the first time this repository has had one.

Of the 68, this round closes **2** — `R9` §3.3 and `R9` §8's first bullet, both collation —
and `R25` explains why those two were taken first and not the cheapest ones. **66 remain**, and
they are below with their cost and their flip risk so the next session does not have to
re-derive the order.

### Where the (a) items are

Derived by the same script, keyed on the section heading each row sits under:

| | (a) entries | |
| --- | --- | --- |
| `R18` | 6 | the drift band, the knee, `max_connections` headroom |
| the Outside table | 6 | four ADRs, `measurement-discipline.md`, `R9` ×3 — `D.12` is counted at `16.8` |
| `R10` | 5 | management surface strands measured once each |
| `R1` `R3` `R5` `R9` `R13` `R17` | 4 each | |
| `R2` `R4` `R6` `R8` `R12` `R14` `R16` | 3 each | `R16`'s third is `16.8`, which is a §5 `미측정` rather than a §8 bullet |
| `R7` `R15` | 2 each | |
| `R0` `R19` | 1 each | |
| `R11` | **0** | every gap it names needs a deployment |
| **total** | **68** | |

### The three (a) items with the widest attachment

Ordered by *what collapses if this is wrong*, not by cost. **None of the top three is cheap,
and two of them are named by three separate reports each** — a gap repeated across reports is
one nobody could get to, not one nobody noticed.

| rank | entry | named by | why it is first |
| --- | --- | --- | --- |
| 1 | **Only `READ COMMITTED` was ever measured** | `R6` §8, `R7` §8, `R12` §8 | `R6` says `REPEATABLE READ` *"would change every row of the table — including making read-modify-write safe but failing"*. If so, the ranking that chose the shipped `atomic-guarded` default holds at one isolation level and is unestablished at any other. Three reports and the application's default rest on it |
| 2 | **The knee is unmeasured; every load number is one point** | `R2` §8, `R4` §8, `R18` §8, `R16` §8 | `measurement-discipline.md` §*The knee* says a report measuring one concurrency level has found a point, not a curve. Every latency number here is at 200 VU. **`R18`'s drift control complicates it rather than only costing time**: 1.27× over seventy minutes is wider than some of the effects a sweep would try to resolve, so the honest form may be that this machine cannot answer it |
| 3 | **The `T3` rule's blind spots** | `R1` §8 | The `T3` ArchUnit rules are the **only** regression gate in this repository that has ever refused the author's work (`R0` §4). `R1` §8 records that a self-invocation through a lambda field, reflection, or a Kotlin `by` delegate is `미측정` and says *"treat the rule as catching the common shape, not as a proof."* Nobody has planted one. If the rule misses them, the single paid gate is narrower than the sentence everyone cites |

**None of the three is in this slice's owned paths**, and each is a measurement rather than a
feature, so each is schedulable as written. They are recorded here rather than started.

---

## The ledger

Bullets are abbreviated to their subject. `Cost` is minutes and `Flip` is H/M/L; both are
triage estimates under the rule above. `—` means the class makes the column meaningless.

### `R0` — the scorecard

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 0.1 | self-assessment: author, evidence and scoring are one party | b | — | — | needs a second party |
| 0.2 | the denominator of uncaught mistakes | b | — | — | unknowable from inside; §*What this does not do* |
| 0.3 | one gate has ever fired; eight are promises | **a** | 120 | M | re-score the gate ledger on today's tree. `README.md` already asserts three later firings with no report behind them |
| 0.4 | the traps were chosen by the author who failed them | c | — | — | not a quantity |
| 0.5 | `T2` and `T4` are unscoreable and were scored | c | — | — | stated and reasoned |
| 0.6 | nothing here measures the quality of the fixes | b | — | — | needs a judgement |
| 0.7 | what would break it: an outside review | b | — | — | needs a second party |

### `R1` — a transaction annotation that does nothing

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 1.1 | a partially recorded batch | c | — | — | discharged in place by `R14` |
| 1.2 | the rule sees only statically resolvable calls — lambda, reflection, `by` delegate | **a** | 90 | **H** | rank 3 above. Plant three violations against `TransactionBoundaryRules` |
| 1.3 | `REQUIRES_NEW` in the fixture takes a second connection; cost unmeasured | **a** | 60 | L | `Cm = 2` in the pool formula |
| 1.4 | a constraint violation poisons the persistence context | **a** | 45 | L | `R7` measured the database half; the Hibernate half is untouched |
| 1.5 | per-recording against per-batch transaction boundary throughput | **a** | 90 | M | §5 chose the boundary without timing it |
| 1.6 | the rules are structural: *can this work*, not *does this work* | c | — | — | a property of ArchUnit, argued |
| 1.7 | the green claim rested on a local run; CI was red | c | — | — | closed at `56f304c` |
| 1.8 | the gate has run on two machines; other JDKs and runtimes | b | — | — | needs other machines |
| 1.9 | what would break it: Spring stops proxying by subclassing | c | — | — | a future world |

### `R2` — a connection pool exhausted by a default

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 2.1 | arm C ran after a 93-minute gap and is not comparable | c | — | — | the report withdraws its own arm; `R18` re-ran the variable |
| 2.2 | the raw artefacts were lost to a WSL restart | c | — | — | historical |
| 2.3 | a pooled connection is not one backend; `max_connections` can be wrong by 3× | **a** | 120 | M | sample `pg_stat_activity` outside the window — `R16` §8 on why not inside |
| 2.4 | arm B changes two things at once | c | — | — | experimental design, stated |
| 2.5 | one concurrency level; the knee | **a** | 240 | M | rank 2 above |
| 2.6 | the 150 ms gateway delay is chosen, not measured | b | — | — | no real dependency exists to time |
| 2.7 | `Thread.sleep` stands in for a network call | c | — | — | a stated model |
| 2.8 | CPU saturation is inferred; host CPU never sampled | **a** | 60 | L | must be sampled outside the measurement window |
| 2.9 | what would break it: an index making the query negligible | c | — | — | discharged by `R3` and `R16` |

### `R3` — an index that exists and is not used

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 3.1 | nothing asserts the plan; a reversed column order is a 660× regression | **a** | 90 | L | **work, not a measurement** — writing a gate. `R19` §3.3 classified it the same way |
| 3.2 | every number is single-connection; the covering index under contention | **a** | 180 | M | the `INCLUDE` rejection is the decision most exposed |
| 3.3 | the `INCLUDE` decision rests on 0.07 ms, inside run-to-run spread | **a** | 45 | M | a larger sample could change the sign |
| 3.4 | `ANALYZE` never mattered; the generator's uniformity may hide it | c | — | — | confirmed in place by `R13` |
| 3.5 | bloat, `REINDEX` cost, write amplification on an append-only table | **a** | 90 | L | `pgstattuple` and a synthetic update load |
| 3.6 | keyset paging was rejected for this domain's endpoints | c | — | — | a scoped decision |
| 3.7 | what would break it: a change to how `attempt` is read | c | — | — | a future world |

### `R4` — the fix that is two halves

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 4.1 | every measurement before 2026-08-12 ran on a context that never loaded the shipped config | c | — | — | historical, closed by the profile split |
| 4.2 | the steady-state threshold 1.3× is chosen, not derived | **a** | 120 | M | needs a distribution of half-ratios across good runs; `R18` produced fifteen runs |
| 4.3 | one concurrency level; the knee | **a** | 240 | M | same entry as 2.5 |
| 4.4 | arm A's third run sits outside the session | c | — | — | historical, disclosed |
| 4.5 | the 30 s warm-up is sized for a JVM and does not warm the page cache | **a** | 60 | M | **the check would live in `recommendations.js`** — not this slice's file |
| 4.6 | `Thread.sleep` stands in for the slow call | c | — | — | a stated model |
| 4.7 | the 150 ms delay is chosen, not measured | b | — | — | same as 2.6 |
| 4.8 | `mastery` is still sequentially scanned | c | — | — | closed by `V3__mastery_unique_learner_concept.sql` and `R16` |
| 4.9 | option D — move the slow call out of the request — never measured | b | — | — | needs an asynchronous path that does not exist. Also the `미측정` cell in `R2` §5 |
| 4.10 | what would break it: a smaller delay or a slower query | c | — | — | a future world |

### `R5` — the defect the framework already fixed

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 5.1 | this is a statement about Hibernate 7.4.1.Final and nothing else | c | — | — | a version scope, correctly stated |
| 5.2 | a test passed while asserting the opposite of the truth | c | — | — | historical, and the origin of every control here |
| 5.3 | the dataset is 20 × 50; the derived-table rewrite at three million rows | **a** | 60 | L | run the same page against the seeded database and read the SQL |
| 5.4 | only `left join fetch` with `Pageable`; `@EntityGraph` and derived queries | **a** | 90 | M | other paths may not be rewritten |
| 5.5 | `Set` instead of `List` was not measured at all | **a** | 45 | L | named without evidence, the report says so |
| 5.6 | no load applied; the join's cost at scale | **a** | 150 | M | 15,000 rows for one page on the real dataset |

### `R6` — updates lost under concurrency

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 6.1 | the application still uses the second-worst option | c | — | — | discharged in place by `R12` |
| 6.2 | whether an 82 % rejection rate matters in production | b | — | — | needs production traffic |
| 6.3 | five retry attempts is a chosen number; no sweep | **a** | 60 | L | |
| 6.4 | timing spread reaches 76 % on the fastest arm | c | — | — | disclosed, and the ratio survives it |
| 6.5 | only `READ COMMITTED`; `REPEATABLE READ` is *"the single biggest lever not pulled"* | **a** | 120 | **H** | **rank 1 above** |
| 6.6 | one row, one column, one increment; no lock ordering, no deadlocks | **a** | 120 | M | |
| 6.7 | what would break it: a write not derivable from the old value | c | — | — | §5 states the condition |

### `R7` — a uniqueness check two requests both pass

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 7.1 | `V3` deletes duplicates and does not merge; nothing warns the operator | c | — | — | a domain decision the migration declined to make |
| 7.2 | the race was measured on one pair; the production rate | b | — | — | same as 6.2 |
| 7.3 | only `READ COMMITTED` | **a** | — | **H** | same entry as 6.5 |
| 7.4 | whether the isolated insert is necessary on MySQL | **a** | 90 | L | measurable — a container exists for it — and nothing here ports |
| 7.5 | the upsert cannot say who won | c | — | — | a property of `do nothing`, stated |
| 7.6 | `AttemptRecorder` still uses the naive pattern | c | — | — | false since 2026-08-13, annotated in place by `R12` |
| 7.7 | what would break it: a second unique rule on the same table | c | — | — | a future world |

### `R8` — a test that counts queries

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 8.1 | a statement count is not a duration | c | — | — | a property of the gate, stated |
| 8.2 | only the recommendation read is gated; one path out of everything | **a** | 90 | M | counting the other paths is a measurement; gating them is work |
| 8.3 | statistics are enabled only under the test profile | **a** | 45 | L | the difference from the shipped JVM is unmeasured |
| 8.4 | five rows; nothing shows `2 + n` stays linear at 500 | **a** | 30 | L | cheapest entry in the ledger |
| 8.5 | batch inserts are not counted at all | c | — | — | answered by `ADR-003` |
| 8.6 | what would break it: a second-level cache | c | — | — | `ADR-005` decided there is none |

### `R9` — what an in-memory database does not tell you

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 9.1 | **every ordering number here was taken on a byte-ordering database** | **a** | 180 | **H** | **closed this round — `R25`, `R26`** |
| 9.2 | the H2 dependency is itself the hazard; a third configuration nobody thought of | b | — | — | an unknown-unknown by construction |
| 9.3 | `replace = NON_TEST` is a version fact, not a guarantee | c | — | — | measured out of the bytecode and gated |
| 9.4 | `COPY … FROM STDIN` was not probed | **a** | 60 | L | a driver API, so it needs a probe of a different shape |
| 9.5 | identifier generation and batching | c | — | — | struck through; closed by `ADR-003` |
| 9.6 | 23 statements is not the application; Hibernate's own SQL is unprobed | **a** | 120 | M | every derived query and paging rewrite |
| 9.7 | container reuse is measured locally and unmeasured in CI | b | — | — | §3.6 argues reuse cannot apply on a fresh runner |
| 9.8 | `EmbeddedSubstitutionControlTest`'s share of CI's 65 s | b | — | — | needs a test-results artefact a green run does not upload |
| 9.9 | the cost argument spans 0.4–2 % across two machines | c | — | — | annotated by `ADR-004` as the rule-3 breach it is |
| 9.10 | what would break it: an embedded database that implements `on conflict` | **a** | 120 | L | the title is broader than the evidence |

### `R10` — authorisation, exposure, tokens

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 10.1 | two of three strands are not done | c | — | — | closed by `R11` the same day |
| 10.2 | the gate covers the file, not the running system | b | — | — | needs a deployment |
| 10.3 | whether a separate management port is the better control | **a** | 60 | L | *"a real option that was not compared"* |
| 10.4 | the heap dump was searched for three strings; 156 MB was not enumerated | **a** | 90 | M | *"contains the password" is a floor, not a description* |
| 10.5 | `env` masking was measured for one value | **a** | 45 | L | |
| 10.6 | `loggers` was measured writable for one logger name | **a** | 45 | M | §3.4's sentence about reading bound parameters is mechanism, not measurement |
| 10.7 | two extra Spring contexts paid on every build | **a** | 30 | L | |
| 10.8 | what would break it: a new endpoint with an open default | c | — | — | an endpoint that does not exist cannot be measured |

### `R11` — authenticated and not authorised

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 11.1 | there is no login and no way to obtain a token | c | — | — | a scope statement |
| 11.2 | one endpoint is authorised; a structural rule was not written | c | — | — | written 2026-08-14 as `AuthorisationRules`, annotated in place |
| 11.3 | the token has no revocation | c | — | — | *"a property of the fixture, not a finding"* |
| 11.4 | a single static key with no rotation | c | — | — | a design refusal, not a quantity |
| 11.5 | the right skew tolerance depends on how a fleet's clocks are synchronised | b | — | — | the report calls it *"unmeasurable from here"* |
| 11.6 | the clock scenarios are simulated; real NTP is not modelled | b | — | — | needs a fleet |
| 11.7 | no deployment was measured for drift | b | — | — | needs a deployment |
| 11.8 | what would break it: a resource whose owner is not the learner in the path | c | — | — | a future world |

**`R11` is the only report in the corpus with no (a) entry.** Every gap it names is outside
this machine, and that is a property of its subject rather than of its diligence.

### `R12` — the arm the application kept

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 12.1 | ten threads on one row is not production | b | — | — | same as 6.2 |
| 12.2 | the guard duplicates `ck_mastery_score` | c | — | — | moved to `OPEN-6`, closed by `ADR-006` |
| 12.3 | `@Version` is maintained by hand; a structural rule could catch a forgetful statement | **a** | 90 | L | work, not a measurement |
| 12.4 | the atomic statement works only because the new value is derivable | c | — | — | inherited condition, stated |
| 12.5 | only `READ COMMITTED` | **a** | — | **H** | same entry as 6.5 |
| 12.6 | the wall-clock comparison is between arms doing different work | c | — | — | disclosed; per-applied figures given instead |
| 12.7 | batch partiality is untouched | c | — | — | discharged in place by `R14` |
| 12.8 | what would break it: contention spread across rows rather than one | **a** | 90 | M | *"the measurement that would tell a real deployment whether any of this mattered"* |

### `R13` — the dataset was hiding a strand

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 13.1 | one run per cell; no medians, no spread | **a** | 60 | L | 46.2 ms against 29.2 ms is one pair of samples |
| 13.2 | not the shipped schema and not the shipped data | **a** | 120 | M | four columns against `attempt`'s seven plus its constraints |
| 13.3 | one heavy value is not a heavy tail; real usage is Zipf-shaped | **a** | 120 | M | |
| 13.4 | nothing establishes that `T4`'s fifth strand is the only one the uniform generator hides | b | — | — | open-ended: needs a hypothesis per strand |
| 13.5 | `work_mem`, `random_page_cost`, parallelism are image defaults and were not varied | **a** | 120 | M | *"one point on a surface nobody mapped"* |
| 13.6 | what would break it: a query shape where the plan cannot change | c | — | — | §2 explains it |

### `R14` — the batch that discarded what it was told to keep

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 14.1 | the broad catch turns one outage into five rejections | **a** | 60 | L | *"unmeasured either way"* |
| 14.2 | there is still no endpoint | c | — | — | `ADR-009` decided there will not be |
| 14.3 | the outcomes are returned, not acted on | c | — | — | same |
| 14.4 | order is not part of the contract and nothing tests it | **a** | 45 | L | work, not a measurement |
| 14.5 | one shape of batch; invalid-first and all-invalid are unmeasured | **a** | 45 | L | |
| 14.6 | what would break it: a requirement that a batch is atomic | c | — | — | a future world; §5 says it replaces rather than modifies |

### `R15` — a migration that passes every test and cannot run

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 15.1 | how long the original statement would actually take | b | — | — | it did not finish in 32 minutes; an unbounded run is not a measurement anyone can schedule |
| 15.2 | thirty minutes of unbudgeted CPU beside other measurements | c | — | — | historical, and the affected run was restarted |
| 15.3 | the rewrite was measured on a table with no duplicates | **a** | 60 | M | 768 ms is the cost of finding nothing |
| 15.4 | `V3` was modified after being committed | c | — | — | reasoned; no database had applied it |
| 15.5 | only `mastery` was checked; no rule looks for correlated subqueries | c | — | — | `OPEN-7`, closed by `ADR-007` |
| 15.6 | the seeded database is the only large one that exists | **a** | 120 | L | the generator can produce another scale |
| 15.7 | what would break it: an index existing before `V3` runs | c | — | — | §7 states it |

### `R16` — the constraint that was also an index

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 16.1 | `README.md`'s table cites two runs two days apart | c | — | — | disclosed there and in §7 |
| 16.2 | one dataset, one pool, one concurrency — concurrency still unvaried | **a** | 240 | M | half discharged by `R18`; the knee half is 2.5 |
| 16.3 | arm B is not `V2` | c | — | — | a stated construction |
| 16.4 | the filter's 0.9 µs excludes everything around `verify` | **a** | 45 | L | header read, refusal allocation, servlet chain |
| 16.5 | 21 % is a property of this seed and this rule | c | — | — | `domain-model.md` places policy out of scope |
| 16.6 | the measurement contaminated itself three times | c | — | — | historical, all three named |
| 16.7 | what would break it: a pool large enough that scans stop queueing | c | — | — | struck through; measured by `R18` |
| 16.8 | *(outside §8)* the 2× between `R4` and this report's arm B — *"everything else is 미측정"* | **a** | 240 | M | the two runs are two days apart; rule 3 forbids the comparison, so closing it means re-running both |

### `R17` — the guard that was a person

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 17.1 | a document edited on the same day it becomes false passes check 2 | **a** | 120 | M | the *incidence* is measurable — sweep history for same-day falsifications. `R12` §8 names one instance |
| 17.2 | no check reads a claim | c | — | — | the check that tried was built, measured, and discarded — §5 |
| 17.3 | check 1 has never fired on this repository | **a** | 30 | L | run it across history rather than at `HEAD` |
| 17.4 | `.study/` is excluded from check 2; 3,720 lines, untested by a planted violation | **a** | 45 | L | |
| 17.5 | check 4 sees a heading, not honesty | c | — | — | `publication-readiness.md` already said so |
| 17.6 | check 3 does not verify a roadmap row is correct | c | — | — | `2b3e1b1` is the worked example |
| 17.7 | CI cost | c | — | — | struck through; measured, with the residual handed to the lane |
| 17.8 | what would break it: a claim in a diagram or a code comment leaves the corpus | **a** | 120 | M | same sweep as 19.6 |

### `R18` — the pool was not the explanation

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 18.1 | `A / C = 1.33×` is inside the drift band and is not claimed | **a** | 240 | M | needs arms interleaved rather than run in blocks |
| 18.2 | where the drift came from | **a** | 120 | M | page cache, thermal state, background work — none sampled |
| 18.3 | two pool sizes, one concurrency; the knee | **a** | 240 | M | same entry as 2.5 |
| 18.4 | how close `max_connections=100` came | **a** | 90 | L | same instrument as 2.3 |
| 18.5 | arms B and D are `V1..V3` with one constraint dropped | c | — | — | a stated construction |
| 18.6 | 21.0 % of responses carried a recommendation | c | — | — | inherited from `R16` §3.4 |
| 18.7 | three refused runs remain in the medians | **a** | 240 | L | §3.6 shows the conclusion holds either way, and argues a re-run lands inside a wider drift band |
| 18.8 | the verdict file is enforcement by procedure | c | — | — | `OPEN-8`, closed by `ADR-008` |
| 18.9 | what would break it: `shared_buffers` holding `mastery`, or a pool small enough to queue | **a** | 180 | M | neither was varied |

### `R19` — decisions filed where nobody had to make them

| # | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- |
| 19.1 | one reader, one pass, 145 judgement calls | b | — | — | a second session is not a second party |
| 19.2 | the judgement/work line is one sentence of `AGENTS.md` | c | — | — | argued, and named as the load-bearing choice |
| 19.3 | `R0` §8's own example is stale and was left | c | — | — | a recorded judgement |
| 19.4 | `R17` §8's CI-cost bullet might be stale | c | — | — | struck through; checked the same day |
| 19.5 | §7's numbers are one tree and four findings | c | — | — | may not be re-scored, and says so |
| 19.6 | nothing read code comments; the same sweep over KDoc | **a** | 120 | M | *"`R14`'s existence is evidence it would find something"* |
| 19.7 | the remedy in §5 is procedure and is enforced by nothing | c | — | — | §7 is the argument |
| 19.8 | three rows opened and none decided | c | — | — | all three decided the next day |
| 19.9 | what would break it: a second reader re-drawing the line | b | — | — | needs a second party |

### Outside any §8 section

Fifteen entries. Two are duplicates already in the ledger and are listed for completeness with
their duplicate marked; they are not counted twice in the totals.

| # | Source | Subject | Class | Cost | Flip | Note |
| --- | --- | --- | --- | --- | --- | --- |
| D.1 | `measurement-discipline.md` §*The environment block* | the JVM heap flag is unmeasured as a property of these runs | **a** | 20 | L | *"a stated JVM flag that no run used is worse than no flag"* |
| D.2 | `publication-readiness.md` `PUB-7` row | seed digests on other architectures, vendors and locales | b | — | — | needs other machines |
| D.3 | `ADR-001` §Consequences | `kapt`'s build cost | **a** | 45 | L | **the ADR deferred it *"once the seven entities exist"* and they exist.** A deferral whose trigger has passed |
| D.4 | `ADR-004` §*What this audit did not check* | whether figures in one table came from different days | b | — | — | *"cannot be answered from the tree as it stands"* — report headers carry no per-figure timestamps |
| D.5 | `ADR-005` §*What was not measured* | what a cache would actually buy | b | — | — | needs a cache the same ADR forbids |
| D.6 | `ADR-006` §*What was not measured* | whether two recordings racing at the boundary can both pass the predicate | **a** | 60 | M | *"the mechanism says no, and the mechanism is not a measurement"* |
| D.7 | `ADR-009` | the write path under HTTP load | b | — | — | needs the endpoint `ADR-009` decided against |
| D.8 | `R9` §3.3 | whether a glibc PostgreSQL orders those four strings differently | **a** | 60 | **H** | **closed this round — `R25`.** `R9` names its blocker: *"the Debian-based image is not present locally and WSL had no network to pull it"* |
| D.9 | `R9` §3.6 | what drives `BaselineMigrationTest`'s 198 s against 422 s | **a** | 90 | L | both quoted rather than the convenient one |
| D.10 | `R9` §3.6 | why local runs are dominated by something CI is not — `drvfs` is a hypothesis | **a** | 90 | M | |
| D.11 | `R9` §3.6 | per-class timings in CI | b | — | — | a green run uploads no artefact; changing that is a workflow edit |
| D.12 | `R16` §5 | the 2× between `R4` and `R16` arm B | **a** | 240 | M | same as 16.8; counted once, there |
| D.13 | `R2` §5, `R4` §5 | option D as a `미측정` cell in both remedy tables | b | — | — | **duplicate of 4.9** |
| D.14 | `R3` §7 | nothing asserts on `EXPLAIN` output | **a** | 90 | L | **duplicate of 3.1** |
| D.15 | `R9` §3.6 | CI time before and after container reuse | c | — | — | the question does not apply — a fresh runner has nothing to reuse |
| D.16 | `R15` §1, `R16` §1, `R16` §5 | what the `T9` token filter costs the request path | c | — | — | **three occurrences, one question, closed by `R16`** at ≤ 0.9 µs/req — below that harness's floor. **This row was missing from the first commit of this file** and is what made the totals short; see the annotation in §The split |

## What the sweep found that no `미측정` marks

A sweep of recorded gaps can only find recorded gaps. Two things turned up in the corpus that
nothing had written down, and they are listed apart from the ledger because they were **not
inherited from it**.

| | |
| --- | --- |
| **`postgres:16-alpine` is pinned by tag, and the digest beside it is used by nothing.** `measurement-discipline.md` records the digest and says why — *"`16-alpine` is a moving tag… the digest is what makes the row citable"* — and `TestcontainersConfiguration.kt` pins the tag alone. So the document predicted the hazard in prose and no artefact acts on it | **`R27` measures whether the tag has moved** |
| **`R5` is the only report carrying neither the reader's-note line nor the *Rules for every number below* line** that the template puts above §1, and that the other nineteen carry | one line, and it belongs to whoever next edits `R5` — **not to this slice**, which may add a forward link and a date to a report and nothing else |

## What this does not do

- **It does not reduce `R0` §8's denominator.** That bullet counts mistakes nothing caught.
  This counts gaps somebody wrote down. **A gap nobody recorded is invisible to this sweep in
  exactly the way an uncaught mistake is invisible to `R0`** — `R0` §8's first bullet applies
  here unchanged, and the two entries above are the evidence that the invisible set is not
  empty.
- **It does not establish that a (c) is closed.** 73 entries were read once by one session and
  judged not to name a quantity. `R19` §8's first bullet is the same limitation and is the
  precedent for stating it: one reader, one pass.
- **It does not price anything.** `Cost` and `Flip` are triage estimates, fenced by the rule in
  §Decision, and a reader who quotes one has taken an estimate for a number.
- **It closes 2 of 68.** The ledger's value is the list and the order, not the closure rate,
  and a session that closed eight cheap entries instead of the two with the widest attachment
  would have a better-looking table and a worse repository.

## What the round actually did with it — annotated 2026-08-21, after the measurements

**Kept separate from everything above, because everything above was committed at `3afe305`
before a single measurement was taken.** The order was fixed first so that it could not be
written to fit the result, and this section is the only part of this file that was written
afterwards.

| | |
| --- | --- |
| **(a) entries closed** | **2 — `9.1` and `D.8`**, both by `R25`, exactly the two this file named |
| **(a) entries partly closed** | **1 — `D.1`.** `R25` §3.7 read `-Xmx512m` off the Gradle test worker's command line. That is the test lane and Gradle's own default; the `bootRun` lane every load number came from is a different JVM and is still `미측정` |
| **(a) entries remaining** | **66** |
| entries whose class this round would change | **none** |
| **findings that were in no ledger entry** | **1**, and it became `R27` and `OPEN-10` |

**The one that was not on the list is the one worth the paragraph.** §*What the sweep found
that no `미측정` marks* recorded that `postgres:16-alpine` is pinned by tag while the digest
beside it reaches no artefact. `R27` measured it: **the tag moved on 2026-08-13**, a GitHub
runner has pulled `16.15` ever since, twenty documents say `16.14`, and nothing in the tree
changed — so no date, no diff, and no `미측정` marked it.

That is this ledger's own §*What this does not do* arriving on the same day it was written:
**a sweep of recorded gaps finds recorded gaps.** The count is 168 and the denominator is
still unknown, and the first evidence for that was produced by the sweep itself.

## Consequences

- **`docs/roadmap.md` gains no row from this ADR.** It is not a measurement and it is not an
  item; the reports that come out of it are. Whoever integrates this slice adds rows for
  `R25`–`R27` only.
- **The next session that writes a report inherits an ordering.** The three entries in
  §*The three (a) items with the widest attachment* are ranked and none was started here.
- **A `미측정` written from now on is a row in a list somebody keeps**, which is a different
  thing from a word in a paragraph. The list is this file, it is dated, and it will go stale —
  `R17` is this repository's report on what that costs.
