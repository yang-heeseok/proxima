# R26. What a locale-aware collation costs, and the index it silently takes away

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: none, and it is not an omission. **Nothing here is a defect in this
> application.** `R25` §3.6 establishes that no query in it orders, ranges over, or
> pattern-matches a `varchar` column, so there is no state of this repository in which the
> effect below is observable through its own code. §2 says what that makes this report.
> **Green commit**: this one — the measurement and its control
> **Found by**: `R25` §5, which had to choose whether to pin `lc_collate = C` and could not,
> because the price of the alternative was 미측정

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3 (API 1.54), NATIVE INSIDE WSL2 -- not Docker Desktop
  JVM            : Temurin 21.0.12+8. Gradle test worker at -Xmx512m (R25 section 3.7)
  PostgreSQL     : ONE. postgres@sha256:e17e86066e5ef83e0952a9347f5c792b7ece00972e2aa787a6986f471b3dd3d5
                   PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) on x86_64-pc-linux-gnu
                   datcollate = en_US.utf8   datlocprovider = c
                   shared_buffers 128MB, work_mem 4MB -- the image's defaults, unvaried
                   ONE BINARY IS THE WHOLE DESIGN. See section 2
  Connection pool: none. Raw JDBC, one connection, no Spring context
  Dataset        : 200,000 rows of `learner-%06d`, built server-side by generate_series.
                   NOT the seed. The identifier format is Generator.kt:309's
  Load           : none. Single connection, no concurrency
  Repetitions    : 3 runs per arm, median reported, one discarded warm-up per arm.
                   Spread stated per arm in section 3.2 -- one of the three exceeds 10%
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`R25` established that `postgres:16-alpine` sorts byte-wise and a glibc PostgreSQL declaring
the same locale does not. Its §5 then had to choose what to do about it, and one of the four
options was to pin `lc_collate = C` in the container so the current behaviour is deliberate
rather than accidental.

That option was rejected, and the reason it was rejected is that **its cost was unknown in
both directions.** A byte-ordering database is faster; how much faster was `미측정`. And a
locale-aware one changes what an index can serve; whether it does here was `미측정` too.

`R25` §5 could reason about the trade and could not price it. This prices it.

## 2. 재현 / Reproduction

```bash
wsl -e bash -lc 'cd /mnt/c/project/airtown/proxima-c \
  && export JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8 \
  && ./gradlew :api:test --tests "net.gseek.proxima.collation.CollationCostTest" \
       --no-daemon --console=plain -i'
```

**One image, one session, one connection, and the only thing that varies is the collation
named in the statement.**

That is the whole design and it is a correction to how `R25` had to work. `R25` compares two
images because its question is *does the tag decide the ordering*, and its two servers are
16.14 and 16.15 — a confound it removes with a within-image control. A **duration** cannot be
rescued that way. Measuring 16.14-on-musl against 16.15-on-glibc and subtracting would be
`measurement-discipline.md` rule 3 exactly: *before and after come from the same run
conditions*. `R9` §3.6 broke that rule and `ADR-004` exists because of it, so this report does
not go near it.

**There is no red commit and this report says so in its header rather than inventing one.**
The classic form — a query that got slower — has no state here in which it is observable,
because `R25` §3.6 counted the ordered `varchar` reads in this application and there are
none. What this measures is a **property of the platform under a configuration this
repository does not currently deploy**, which is the same shape as `R9`'s H2 comparison: the
finding is what it would cost, not what it costs.

## 3. 계측 / Measurement

### 3.1 The probe

```sql
create table r26_probe as
  select 'learner-' || lpad(g::text, 6, '0') as v
    from generate_series(1, 200000) g;
analyze r26_probe;
```

Server-side, so nothing crosses the wire and the row count is not bounded by this machine's
JDBC batch speed. The identifier format is `Generator.kt:309`'s — `ref(kind, n)` — because a
sort cost depends on the length and the shared prefix of what is being sorted, and inventing
strings would have measured something this repository does not store.

The timed statement wraps the sort in `count(*)` so it runs to completion without returning
rows:

```sql
select count(*) from (select v from r26_probe order by v collate "…") s;
```

Read from `explain (analyze, timing off)`'s `Execution Time` line, verbatim.

### 3.2 What the sort costs

200,000 rows, one binary, one connection. One warm-up per arm, **discarded**.

| collation | runs (ms) | median | against `C` | spread |
| --- | --- | --- | --- | --- |
| `C` — byte order, **what `postgres:16-alpine` gives** | 36.8, 37.0, 36.1 | **36.8** | ×1.00 | 2.5 % |
| `en_US.utf8` — glibc, locale-aware | 98.1, 94.2, 100.0 | **98.1** | **×2.66** | 5.9 % |
| `en-US-x-icu` — ICU | 62.6, 55.8, 52.5 | **55.8** | **×1.51** | **19.2 %** |

**A locale-aware sort of this repository's own identifier format costs 2.66× byte order.**

**ICU costs 1.51×, which makes it 1.76× faster than glibc's `en_US.utf8`** for the same
linguistic ordering. That is the number a deployment decision would turn on and it is not the
one anybody would guess: the choice is usually framed as *"correct ordering or fast
ordering"*, and there are two correct orderings a factor of 1.76 apart.

**The ICU arm's spread is 19.2 % and it is quoted rather than smoothed.**
`measurement-discipline.md` rule 5 asks for the spread when it is wide. Three runs cannot
separate 55.8 from 62.6, so the honest form of the ICU row is *"about one and a half times"*
and not `1.51`. The `C` and `en_US.utf8` rows are 2.5 % and 5.9 % apart and are far enough
outside each other that the 2.66× survives the worst pairing (94.2 against 37.0 is still
2.55×).

**The control.** The three arms must not agree, or the `collate` clause is being ignored and
nothing above is a measurement. They do not: `CollationCostTest` asserts three distinct
medians and would turn red if a future PostgreSQL folded them together. That is the same
shape as `R5`'s log appender, which captured zero events and nearly proved an absence.

### 3.3 The index a locale-aware collation takes away

The effect that is not a duration, and the sharper of the two.

One table, two columns holding identical values, one declared `collate "en_US.utf8"` and one
`collate "C"`, a plain B-tree index on each, `analyze` run. Then a prefix predicate.

**`v_default`, the `en_US.utf8` column:**

```
Aggregate  (cost=3971.05..3971.06 rows=1 width=8) (actual time=12.020..12.022 rows=1 loops=1)
  Buffers: shared hit=1471
  ->  Seq Scan on r26_prefix  (cost=0.00..3971.00 rows=20 width=0) (actual time=0.012..12.012 rows=100 loops=1)
        Filter: (v_default ~~ 'learner-0001%'::text)
        Rows Removed by Filter: 199900
        Buffers: shared hit=1471
Planning Time: 0.129 ms
Execution Time: 12.041 ms
```

**`v_c`, the `C` column, same rows, same index shape:**

```
Aggregate  (cost=8.76..8.77 rows=1 width=8) (actual time=0.029..0.029 rows=1 loops=1)
  Buffers: shared hit=5
  ->  Index Only Scan using ix_r26_c on r26_prefix  (cost=0.42..8.71 rows=20 width=0) (actual time=0.009..0.022 rows=100 loops=1)
        Index Cond: ((v_c >= 'learner-0001'::text) AND (v_c < 'learner-0002'::text))
        Filter: (v_c ~~ 'learner-0001%'::text)
        Heap Fetches: 100
        Buffers: shared hit=5
Planning Time: 0.190 ms
Execution Time: 0.043 ms
```

| | `en_US.utf8` | `C` | ratio |
| --- | --- | --- | --- |
| plan | **Seq Scan** | **Index Only Scan** | — |
| rows removed by filter | 199,900 | 0 | — |
| shared buffers hit | 1,471 | **5** | **294×** |
| execution time | 12.041 ms | **0.043 ms** | **280×** |
| planner cost | 3,971.05 | **8.76** | 453× |

**The index exists in both arms and only one of them can use it.** PostgreSQL rewrites
`like 'learner-0001%'` into the range `>= 'learner-0001' and < 'learner-0002'` only when the
column's collation makes that range the contiguous span the predicate names — which is true of
`C` and false of every locale-aware collation, because the locale's ordering interleaves other
values into that span.

**Equality is unaffected.** Both columns answer `where v = 'learner-000042'` from their index.

**This is a defect that arrives with no diff.** A prefix search written and measured on this
repository's own test image would show an Index Only Scan and 0.043 ms, and the identical
code on a glibc deployment would show a sequential scan and 12 ms — no error, no warning,
nothing in the source to review. The remedy is a second index with `text_pattern_ops`, which
nobody adds to a query they have watched use an index.

**This is `R3` in a different register**, and worth naming as such: `R3` is a report about an
index that exists and is not used because of column order. Here the index exists and is not
used because of the *deployment's locale*, which is not in the schema, not in the query, and
not in the repository at all.

### 3.4 Whether the collation changes what is unique

`V1__baseline.sql` puts three unique constraints on text — `uk_learner_external_ref`,
`uk_concept_code`, `uk_item_code` — and `R7` is an entire report about what a unique
constraint on this schema does under concurrency. If a collation changed which values
collide, every one of `R7`'s numbers would be conditional on the image.

Two values that order differently under the two collations, inserted into a unique index
declared under each:

| table | outcomes | rows |
| --- | --- | --- |
| `r26_unique_default` — `collate "en_US.utf8"` | `Item-000001 INSERTED`, `item-000001 INSERTED` | **2** |
| `r26_unique_c` — `collate "C"` | `Item-000001 INSERTED`, `item-000001 INSERTED` | **2** |

**It does not.** Both collations are *deterministic*: they may rank two values differently and
they never call them equal unless the bytes are equal. So `R7`'s measurements, `V3`'s
deduplication, and every unique constraint in this schema are collation-independent.

That is a null result and it is the most reassuring number in this report, because it bounds
the blast radius: **a collation change moves what comes first. It never moves what exists.**

## 4. 원인 / Mechanism

**The sort cost.** Byte order is `memcmp`, which compares aligned words and stops at the first
difference — and PostgreSQL's abbreviated-key optimisation packs the leading bytes of a `C`
sort key into the tuple so most comparisons never touch the string at all. A locale-aware
comparison must build a multi-level sort key: primary weights (letters, ignoring case and
punctuation), then secondary, then tertiary for case. glibc's `strcoll` walks the ISO 14651
table per character. ICU builds the same thing through a table designed for it, which is
where the 1.76× between the two locale-aware arms comes from.

**The lost index.** A B-tree stores keys in the column's collation order. `like 'p%'` can only
become an index range scan if every value beginning with `p` is contiguous in that order.
Under `C` it is, because the ordering is the byte ordering and a shared prefix implies
adjacency. Under a locale-aware collation it is not: `learner-0001x` and `learner-0001` may be
separated by values sharing no prefix with either, because punctuation carries no primary
weight and the ordering is not lexicographic over bytes. PostgreSQL therefore refuses the
rewrite rather than returning wrong rows, and the alternative it offers is
`varchar_pattern_ops` / `text_pattern_ops`, an operator class that indexes by byte order
inside a database that sorts by locale.

## 5. 처방 / Remedy

**Nothing in this application changes.** The remedy question this report answers is `R25`
§5's: *should the container pin `C` explicitly?*

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Pin `lc_collate = C` in the test container | makes today's behaviour deliberate and reproducible | **it also hides the divergence from any future measurement that wants it**, and it is a performance choice made in a hygiene file | |
| Move the test image to glibc so tests match a likely deployment | tests would then meet the collation a production PostgreSQL usually has | **every ordering-dependent number would have to be re-baselined**, and §3.2 says the sort is 2.66× — the load numbers are not ordering-dependent, so the cost is all disruption and no information | |
| Add `text_pattern_ops` indexes now, in case | — | `AGENTS.md` §Scope: a guard that protects nothing yet is unbanked. **There is no prefix predicate in this application** (`R25` §3.6) | |
| **Change nothing, and write the price down** | the choice stays where it is and stops being uninformed | this report | **✔** |

**Why the fourth.** `R25` §5 rejected pinning `C` because it could not price it; that reason is
now gone and the decision does not move, which is the useful outcome — the option was rejected
for the right reason after being rejected for a provisional one. The number that would change
it is in §3.3, not §3.2: **a 2.66× sort nobody performs costs nothing, and a 280× prefix
predicate would arrive the day somebody writes one.**

**What would make a different option correct.** A search endpoint. The moment this application
gets `where code like ?` — which is the obvious next thing an item catalogue grows — the third
row stops being unbanked and the first row stops being hygiene.

## 6. 재계측 / Re-measurement

Not applicable. Nothing changed, so there is no after.

The one comparison worth stating is against `R25` §3.2, taken on the same two images an hour
earlier: the glibc arm's ordering behaviour is unchanged, so the two reports' measurement
conditions agree on the property they share.

## 7. 회귀 게이트 / Regression gate

- `api/src/test/kotlin/net/gseek/proxima/collation/CollationCostTest.kt` — the control, and it
  is the only assertion in the class. **The three collations must produce three distinct
  execution times.** If they ever agree, the `collate` clause is being ignored and every ratio
  in §3.2 is a measurement of nothing. It asserts a difference and never a duration, which is
  what `ADR-004` requires of anything that runs in CI.

**No gate asserts the prefix-predicate finding, and that is deliberate rather than missing.**
The thing worth gating is *"no query in this application uses a prefix predicate on a text
column"*, which is `R25` §7's structural rule and belongs there rather than duplicated here.
Asserting the plan itself — that `like` on a `C` column uses an index — would gate PostgreSQL's
behaviour rather than this repository's, and `R3` §8 already names *"nothing asserts the plan"*
as a gap about a plan that ships. This one does not ship.

## 8. 남는 위험 / Remaining risk

- **The ICU arm's spread is 19.2 % and its ratio should not be quoted to two decimals.**
  55.8 against 62.6 across three runs; `measurement-discipline.md` rule 5 says three runs that
  looked good are three runs that looked good. **The honest ICU figure is "about one and a
  half times".** Why the ICU arm is noisier than the other two is **미측정**.
- **One row count, one string shape, one connection.** 200,000 rows of a 14-character
  identifier with a 8-character shared prefix. Sort cost depends on all three, and a longer
  string, a shorter shared prefix, or a set that spills `work_mem` would move the ratios.
  `work_mem` is the image's 4 MB and was **not varied** — `R13` §8 records the same limitation
  about `work_mem` and it applies here unchanged.
- **No concurrency at all.** Every number is single-connection, which `R3` §8 names as its own
  largest caveat: under contention the ranking can move, and a 2.66× CPU-bound term behaves
  differently when eight of them are running. **미측정.**
- **The 280× in §3.3 is one selectivity.** `'learner-0001%'` matches 100 of 200,000 rows.
  At a selectivity where the planner would choose a sequential scan anyway the difference
  collapses to nothing, and where that boundary is is **미측정** — the same shape of gap `R13`
  §8 calls *"one point on a surface nobody mapped"*.
- **`en_US.utf8` is one locale.** Nothing here says what `C.UTF-8`, `en_GB.UTF-8`, or a Korean
  locale would cost, and this domain's real deployment would be the last of those. **미측정**,
  and `R25` §8 carries the same gap about ordering rather than cost.
- **This report measures a configuration this repository does not run.** Every number is about
  a deployment that does not exist, which makes it the weakest kind of finding this repository
  produces — `R9`'s H2 comparison has the same property and defends it the same way: the
  question is what a plausible alternative would cost, and the answer is worth having before
  somebody chooses it rather than after.
- **What would break this conclusion**: PostgreSQL gaining an abbreviated-key path for
  locale-aware collations, or a version that can plan a prefix predicate against a
  non-`C` B-tree. Both would collapse the two findings independently, and neither is measured
  here against any version but 16.15.
- **No bullet here needs a judgement rather than work.** The decision this report was written
  to inform — whether to pin `C` — is made in §5 with the number it was waiting for, so it is
  not a row in `docs/decisions/open.md`.

## 9. 배운 것 / What I learned

**"정확한 정렬 대 빠른 정렬"이라는 프레이밍이 틀렸다.**

콜레이션 비용을 재기 전에 나는 답을 알고 있다고 생각했다 — 로케일 정렬은 느리고, `C`는 빠르다.
숫자는 그렇게 나왔다: 2.66배. 그런데 같이 잰 ICU가 1.51배였다. **언어적으로 옳은 정렬이 두 개
있는데, 그 둘 사이가 1.76배 벌어져 있다.** 선택지는 "옳음 대 빠름"의 1차원이 아니라 최소 2차원이었고,
나는 한 축만 재고 끝낼 뻔했다. 실제로 첫 설계에는 ICU 팔이 없었다.

**그리고 값비싼 쪽은 정렬이 아니었다.**

2.66배는 이 저장소에서 아무도 하지 않는 작업의 비용이다 — R25 §3.6이 세어봤고, `varchar`를 정렬하는
쿼리는 없다. 그러니 정렬 비용은 **0의 2.66배**다. 진짜 발견은 §3.3에 있었다: `like 'prefix%'`가
`C` 컬럼에서는 Index Only Scan이고 로케일 컬럼에서는 Seq Scan이다. **280배, 그리고 코드 차이는
없다.** alpine 이미지 위에서 짜고 재면 인덱스를 탄다. glibc 위에 올리면 안 탄다. 같은 SQL, 같은
스키마, 같은 인덱스.

이게 R3와 정확히 같은 함정인데 한 층 아래에 있다. R3는 컬럼 순서 때문에 인덱스를 못 쓰는 이야기고,
이건 **배포 환경의 로케일 때문에** 못 쓰는 이야기다. 로케일은 스키마에도, 쿼리에도, 저장소 어디에도
안 적혀 있다. 리뷰에서 잡힐 수 있는 종류가 아니다.

**마지막으로, 유니크 제약이 콜레이션과 무관하다는 널 결과가 이 리포트에서 제일 마음이 놓이는
숫자였다.** deterministic collation이라는 단어는 알고 있었지만, 알고 있는 것과 `Item-000001`과
`item-000001`을 양쪽에 넣어보고 둘 다 2행이 남는 걸 보는 건 다르다. R7 전체가 유니크 제약 위에 서
있는데, 그게 이미지에 의존했다면 R7의 모든 숫자에 조건이 붙었을 것이다. **범위를 넓히는 발견보다
범위를 닫는 발견이 더 값쌀 때가 있다.**
