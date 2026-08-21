# Round 2, slice C — handoff

> Transient integration note. **It deliberately carries no `Updated` line**: a date on it goes
> stale the moment the integrator edits around it, and `docs-consistency.yml` check 2 skips a
> document that has none. Delete it once the rows in §4 are pasted.

## 1. What this slice opened

This slice started from the observation that `미측정` is a discipline this repository has kept
for eleven days, which means its measurement gaps are **scattered across the tree in a
countable form** — and nobody had ever counted them. `ADR-014` is that count: every `미측정`
and every *남는 위험* bullet in `docs/` and `README.md`, **169 entries**, each classified by
whether the measurement is available on this machine. It was committed before a single
measurement was taken, so the priority order could not be written to fit the result. Against
that list the slice then took the mandated first target — `R9` §8's collation risk, the one
attached to *"every ordering number in this repository"* — and measured it against a glibc
PostgreSQL, which is what `R9` could not do because WSL had no network that day. The mechanism
turned out to be **larger** than `R9` claimed and its **scope empty**; pricing the alternative
deployment produced a second report; and pinning the two images by digest instead of by tag
turned up a third finding that **was in no ledger entry at all** — the tag has moved, and the
repository has been running two different database servers without noticing.

## 2. Reports and ADRs

| # | Title | The finding, in one line |
| --- | --- | --- |
| `ADR-014` | The unmeasured is a work list, and it is 169 entries long | **169 recorded gaps: 68 measurable here, 28 not, 73 where the question does not hold.** 43 % of what looks like a backlog is a template line, a stated trade, or a bullet already discharged — and the work list is 68 items with an order attached |
| `R25` | A risk written against every ordering number, and there are none | **The mechanism is confirmed and larger; the quantifier is withdrawn.** glibc orders `R9`'s four strings `apple,Apple,Banana,cherry`, and **4,461 of 4,465 two-character ASCII pairs re-order** — but the only `varchar` ordering in the tree is `R9`'s own probe, and `BaselineMigrationTest`'s two catalog orderings are on the `name` type, whose collation PostgreSQL fixes at `C` |
| `R26` | What a locale-aware collation costs, and the index it silently takes away | **Sort 2.66× (`C` 36.8 ms → `en_US.utf8` 98.1 ms; ICU 55.8 ms, so ICU is 1.76× faster than glibc for the same ordering).** The larger finding is not a duration: `like 'prefix%'` gets an Index Only Scan on a `C` column and a Seq Scan on a locale-collated one — **280× on time, 294× on buffers, same SQL, same index.** Uniqueness is collation-independent |
| `R27` | The digest nothing pulls, and the tag that moved eight days ago | **`postgres:16-alpine` moved on 2026-08-13.** Twenty documents say `16.14`; `build.yml` has no image cache, so every runner since has pulled `16.15`, while this machine's Docker cache still holds July's image. The digest `measurement-discipline.md` calls *"what makes the row citable"* reaches no artefact. **No file changed and every `Updated` date is right**, so no check here can see it |
| `OPEN-10` | Does the image get pinned by digest — and then correct or re-baseline? | Opened from `R27` §8's first bullet, which nobody can act on without a judgement. `R27` §5 recommends *pin and correct*; the second half is a trade nobody has made. Deadline: **now** |

## 3. New `미측정` items, and the ledger's headline counts

### The ledger's counts — what the integrator needs to re-score `R0`'s denominator

| | |
| --- | --- |
| entries total | **169** — 154 §8 bullets + 15 gap-naming `미측정` outside any §8 |
| **(a) measurable here, just not done** | **68** |
| **(b) not measurable here** | **28** |
| **(c) the question does not hold** | **73** |
| **(a) closed this round** | **2** — `9.1` and `D.8`, both by `R25` |
| **(a) partly closed** | **1** — `D.1`, the heap flag, for the test lane only |
| **(a) remaining** | **66** |
| findings that were in **no** entry | **1** — became `R27` and `OPEN-10` |

**Read the closure rate carefully before quoting it.** 2 of 68 is small on purpose: the two
closed are the two with the widest attachment, and a session that closed eight cheap entries
instead would have a better-looking table and a worse repository. `ADR-014` §*What this does
not do* says so in the file.

**And this does not reduce `R0` §8's denominator.** That bullet counts mistakes nothing caught;
this counts gaps somebody wrote down. `R27` is the evidence that the two are different: it was
found by the sweep and was in no entry of it. **A gap nobody recorded is invisible to this
ledger in exactly the way an uncaught mistake is invisible to `R0`.**

### What this slice could not measure, and what each would need

| | What would be needed |
| --- | --- |
| **Whether any *published* number changes on 16.15** (`R27` §8) | Re-running load scenarios. `R18` measured a 1.27× drift band over seventy minutes on this machine, so most of a re-baseline would sit inside its own noise. This is the substance of `OPEN-10` |
| **When CI first ran on 16.15** (`R27` §8) | The Actions REST API. `gh` is not on this machine — `R19` §8 records the same blocker. The inference is very likely right and it is an inference |
| **Anything beyond printable ASCII** (`R25` §8) | Nothing but time; it was scoped out because `V1__baseline.sql`'s columns hold nothing else under this generator. **This domain's real data would be full of Hangul**, which is where glibc and ICU famously disagree |
| **The `bootRun` JVM's heap flag** (`R25` §3.7) | A `bootRun` invocation. The **test worker** is measured at `-Xmx512m`, Gradle's own default, read off the worker command line. The lane every load number came from is a different JVM |
| **Sort cost under concurrency, at other row counts, other `work_mem`, other locales** (`R26` §8) | Each is a straightforward run; none was in scope. `R26`'s numbers are single-connection at one row count with the image's default `work_mem` |
| **The selectivity at which the 280× collapses** (`R26` §8) | A sweep. `'learner-0001%'` matches 100 of 200,000 rows; at low selectivity the planner would choose the scan anyway |
| **This slice's share of a CI run** (`R25` §8) | A test-results artefact a green run does not upload — `R9` §8's blocker, unchanged. Local per-class wall times are in `R25` §8 |
| **Whether `docs-consistency.yml` check 1 fired on this branch** | It did not: check 1 reports 0 findings at `HEAD`, as it has at every commit measured. `R17` §8's third bullet stands |

### Escalations — three files this slice may not touch and one of them is a live false claim

1. **`api/src/test/kotlin/net/gseek/proxima/TestcontainersConfiguration.kt`** — its KDoc says
   *"Every ordering-dependent number in this repository was taken under the first behaviour."*
   `R25` narrows that. It is also where `R27`'s one-line fix belongs — pin
   `POSTGRES_IMAGE` by digest. **Slice B owns this file this round.**
2. **`docs/explanation/measurement-discipline.md`** — says the heap flag is `미측정` (now
   measured for the test lane, `R25` §3.7) and records `postgres:16-alpine — server 16.14`,
   which is what a runner has not run since 2026-08-13. **Slice B owns it.**
3. **`docs/roadmap.md` and `README.md`** — read-only here. §4 has the rows.

**`docs-consistency.yml` check 3 fails on this branch until §4's roadmap rows are pasted.**
That is by design, not an oversight: the check requires every report to have a roadmap row and
this slice may not write one. Checks 1, 2 and 4 were run over the whole tree here and pass.

## 4. Rows for the integrator to paste

### `docs/roadmap.md` — three rows for the *After the traps* table

```markdown
| **R27** | **The digest nothing pulls.** `measurement-discipline.md` records the image digest and says it is *"what makes the row citable"*; `TestcontainersConfiguration.kt` pins the tag | **done** — `R27`, no green commit and the header says why: the fix is one line in a file another slice owns. **`postgres:16-alpine` moved on 2026-08-13** — `sha256:57c72fd2` (16.14) to `sha256:075f7ba6` (16.15). Twelve facts compared across the two containers, three differ, all three the same fact; migrations apply and ordering holds on both. **The finding is the split**: `build.yml` has no image cache, so every runner since has pulled 16.15 while this machine's Docker cache holds July's image, and **twenty documents say 16.14**. `R17` §8's second bullet met by a harder case — the claim went false while every file was untouched and every date was right, so there is no diff for a date proxy to see. Second finding, from counting: the digest appears in `R1`–`R4` and in no report after 2026-08-12. → `OPEN-10` |
| **R26** | **What a locale-aware collation costs.** `R25` §5 had to choose whether to pin `lc_collate = C` and could not, because the price was 미측정 in both directions | **done** — `R26`, no red commit: `R25` §3.6 counted the ordered `varchar` reads in this application and there are none, so the effect has no observable state here. One image, one session, only the collation varies — a duration cannot be rescued by the within-image control `R25` used. Sort of 200,000 rows: **`C` 36.8 ms, `en_US.utf8` 98.1 ms (×2.66), ICU 55.8 ms (×1.51)** — so **ICU is 1.76× faster than glibc for the same linguistic ordering**, and *"correct or fast"* is one axis short. The larger finding is not a duration: `like 'prefix%'` is an **Index Only Scan on a `C` column and a Seq Scan on a locale-collated one — 280× on time, 294× on buffers**, same SQL, same index, arriving with no diff. Null result that bounds it: **uniqueness is collation-independent**, so `R7` and `V3` are unaffected |
| **R25** | **A risk written against every ordering number.** `R9` §8 put a risk on *"every `order by` on text in every report here"* and nobody had counted that set | **done** — `R25`, red `0819a47` / green the commit carrying `R25` — and the red is **this report's own AIMED control refusing its author**, not the defect. Two images pinned by digest: glibc orders `R9` §3.3's four strings **`apple,Apple,Banana,cherry`** and musl **`Apple,Banana,apple,cherry`**; told `collate "C"` the glibc server reproduces musl exactly, which is what attributes the difference to collation and not to 16.14 against 16.15. **The mechanism is larger than `R9` claimed — 4,461 of 4,465 two-character ASCII pairs re-order, 90 of 95 characters re-ranked. The quantifier is empty**: thirteen `order by` occurrences exist at `a417ce3`, nine are SQL clauses, four of those are on text — two on the `name` type whose collation PostgreSQL fixes at `C`, and two on `varchar`, both of which are `R9` §3.3's own probe. **One of five text columns diverges** — `concept.grade_band`, `G1-2` against `G10-12` — and nothing reads it in an order |
```

### `README.md` — three rows for the Results table

```markdown
| `R9` §8's ordering risk, over every `order by` on text — *2026-08-21* | **a risk on "every ordering number"**, unverified for 8 days | **4,461 of 4,465 ASCII pairs re-order — and the set it applies to is 0** | [`R25`](docs/reports/R25-a-risk-written-against-every-ordering-number.md) |
| A prefix predicate on a locale-collated column — *2026-08-21* | **Seq Scan, 12.041 ms, 1,471 buffers** | **Index Only Scan, 0.043 ms, 5 buffers** — same SQL, same index, only the collation differs | [`R26`](docs/reports/R26-what-a-locale-aware-collation-costs.md) |
| The server every published number names — *2026-08-21* | **20 documents say `16.14`**; the tag moved 2026-08-13 | **CI has pulled `16.15` since**, and no check here can see it | [`R27`](docs/reports/R27-the-digest-nothing-pulls.md) |
```

**A note the integrator should read before pasting the README rows.** `README.md`'s existing
caveat — *"The two latency rows are dated and are not a progression"* — applies to `R26`'s row
too, for a stronger reason: **`R26`'s numbers are on `postgres:16`, a glibc image this
repository does not run.** The row says so in its own text; if it is trimmed, that clause is
the one that must survive.

## 5. Conditions my numbers were taken under

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2
  JVM            : Temurin 21.0.12+8; Gradle test worker at -Xmx512m (Gradle's default,
                   read off the worker command line -- R25 section 3.7)
  PostgreSQL     : THREE images, ALL PINNED BY DIGEST, none by tag
    postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
      16.14, musl -- the image measurement-discipline.md records
    postgres@sha256:e17e86066e5ef83e0952a9347f5c792b7ece00972e2aa787a6986f471b3dd3d5
      16.15 (Debian), glibc -- postgres:16
    postgres@sha256:075f7ba66bc9b3ce7d6b8b635208ff61cd7cf1a67d71ec530eec5d7ae0cbe571
      16.15, musl -- what postgres:16-alpine resolves to on 2026-08-21
  Dataset        : none is the seed. R25 transcribes value shapes from Generator.kt with
                   the line cited; R26 builds 200,000 rows with generate_series
  Load           : none. Single connection, no concurrency, no k6
  Repetitions    : R25 and R27 are deterministic comparisons, one run. R26 is 3 runs per
                   arm, median, one discarded warm-up per arm, spread stated per arm
```

### What makes another slice's numbers non-comparable with mine

- **No number in this slice is a latency at concurrency, and none may be put beside `R4`,
  `R16` or `R18`.** `R26`'s milliseconds are single-connection `EXPLAIN (ANALYZE)` execution
  times on a synthetic 200,000-row table, on a **glibc image this repository does not run**.
- **`R26` and `R25` deliberately do not share an image.** `R25` compares two images because its
  question is *does the tag decide the ordering*; `R26` refuses to, because a duration across
  two images is `measurement-discipline.md` rule 3 — the rule `ADR-004` was written after `R9`
  §3.6 broke. **Do not compute a ratio across the two reports.**
- **Every one of my containers is digest-pinned; every other test in the repository uses the
  tag.** Since 2026-08-13 those are different servers (`R27`). So a container-start or
  test-duration figure of mine is not comparable with `R9` §3.6's, and `R9` §3.6's figures were
  taken on an image a runner no longer pulls.
- **`ADR-014`'s `Cost` and `Flip` columns are triage estimates and carry no environment block.**
  The ADR states the rule: **they may never be quoted in a report, a commit message, or
  `README.md`.**

### Existing reports I added a forward link to

**One, and only one.** Check that nothing else edited it this round.

| File | What changed | Nothing else |
| --- | --- | --- |
| `docs/reports/R9-what-an-in-memory-database-does-not-tell-you.md` | one `>` annotation line under §8's **first** bullet, and `**Updated**` 2026-08-13 → 2026-08-21 | `git diff a417ce3 -- <that file>` is **3 insertions, 1 deletion**. The report's body is untouched |

Two other documents were edited and they are this slice's own files, not forward links:
`docs/decisions/open.md` (`OPEN-10`, plus one annotation on the now-stale *"every one of the
nine rows"* sentence) and `docs/decisions/adr/ADR-014-*.md` (its own post-measurement section).
