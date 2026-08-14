# R15. A migration that passes every test and cannot run

> **Created**: 2026-08-14
> **Updated**: 2026-08-14
> **Red commit**: `f3c03f6` — `V3` as it shipped, with the correlated subquery. It has been in
> the tree since `T6` and green in every CI run since.
> **Green commit**: this one — one aggregate pass, and a gate that runs the shipped statement
> against actual duplicates
> **Found by**: trying to measure something else. §1

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  PostgreSQL : proxima-db — postgres:16-alpine, server 16.14, default settings
  Data       : the seeded database. mastery = 600,000 rows, 600,000 distinct
               (learner_id, concept_id) pairs — ZERO duplicates
  Indexes    : mastery_pkey only. The unique index is what V3 exists to add
  Plans      : EXPLAIN quoted verbatim; arm B additionally EXPLAIN (ANALYZE, BUFFERS)
               inside a transaction that was rolled back
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

This report was not planned. The task was to re-measure `R4`'s p99 on the current tree,
because `T9` put a token filter in front of `/api/v1` after `R4`'s numbers were taken and the
filter's cost was **미측정**.

The application was pointed at the seeded database. It connected, Flyway validated three
migrations, and then:

```
Current version of schema "public": 2
Migrating schema "public" to version "3 - mastery unique learner concept"
```

and stopped there. The harness waited 120 seconds and gave up.

**`V3` has been in this repository since `T6` and has passed every CI run since.** It had
never been applied to a database with data in it.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.db.MigrationDeduplicationTest"
```

The plan comparison in §3 is reproduced by pointing `psql` at a `mastery` table of 600,000
rows with no index on `(learner_id, concept_id)` and asking for `EXPLAIN` on both statements.

## 3. 계측 / Measurement

`V3` deletes duplicate `mastery` rows and then adds the unique constraint. The delete, as
shipped:

```sql
delete from mastery m
 where m.id > (select min(m2.id) from mastery m2
                where m2.learner_id = m.learner_id
                  and m2.concept_id = m.concept_id);
```

```
Delete on mastery m  (cost=0.00..9139221232.00 rows=0 width=0)
  ->  Seq Scan on mastery m  (cost=0.00..9139221232.00 rows=200000 width=6)
        Filter: (id > (SubPlan 1))
        SubPlan 1
          ->  Aggregate  (cost=15232.00..15232.01 rows=1 width=8)
                ->  Seq Scan on mastery m2  (cost=0.00..15232.00 rows=1 width=8)
```

**A sequential scan of `mastery`, and for every row of it, another sequential scan of
`mastery`.** 600,000 × 15,232 ≈ 9.14 billion.

The rewrite — one aggregate pass, joined back:

```sql
delete from mastery m
 using (select learner_id, concept_id, min(id) as keep
          from mastery group by learner_id, concept_id) k
 where m.learner_id = k.learner_id and m.concept_id = k.concept_id and m.id > k.keep;
```

```
Delete on mastery m  (cost=18832.00..34214.00 rows=0 width=0)
  ->  Hash Join  (cost=18832.00..34214.00 rows=4000 width=54)
        Hash Cond: ((m.learner_id = k.learner_id) AND (m.concept_id = k.concept_id))
        Join Filter: (m.id > k.keep)
        ->  Seq Scan on mastery m  (cost=0.00..12232.00 rows=600000 width=30)
        ->  Hash  (cost=17932.00..17932.00 rows=60000 width=72)
              ->  HashAggregate  (cost=16732.00..17332.00 rows=60000 width=24)
                    Group Key: mastery.learner_id, mastery.concept_id
                    ->  Seq Scan on mastery  (cost=0.00..12232.00 rows=600000 width=24)
```

| | planner cost | measured |
| --- | --- | --- |
| correlated subquery, as shipped | **9,139,221,232** | **not run** — §8 |
| aggregate pass and join | **34,214** | **768.1 ms**, 0 rows removed |

**About 267,000× on cost.** The rewrite was executed inside a transaction and rolled back:
768.1 ms total, of which the hash build over 600,000 rows is 503.6 ms.

### 3.1 It costs nine billion to discover there is nothing to do

```
rows=600000
distinct (learner_id, concept_id) pairs=600000
```

**There are no duplicates.** The seed generates one `mastery` row per `(learner, concept)`, so
this delete removes nothing on this database. The whole cost is spent establishing that.

That is not an argument for removing the statement. `V3`'s own comment says why it is there:
*any database this migration meets in the wild may not be clean, and `CREATE UNIQUE INDEX`
would fail on it.* The defect is the **shape** of the check, not the existence of one.

### 3.2 Why no test caught it

Every test in this repository applies `V3` **to an empty schema.** Flyway runs at container
start, before any fixture inserts anything, so the delete has always executed against zero
rows in microseconds.

**The deduplication had never once done its job.** It was green everywhere and had never been
observed to work — which is the same relationship `R1` §9 describes for a test that passes
whether or not the thing it tests exists.

## 4. 원인 / Mechanism

A correlated subquery is evaluated once per candidate row. To evaluate
`min(m2.id) where m2.learner_id = m.learner_id and m2.concept_id = m.concept_id`, the planner
needs to find rows by `(learner_id, concept_id)` — **and there is no index on those columns**,
because building one is what this migration does *after* the delete.

So the statement's cost depends on the index it is a prerequisite for. On an empty table that
is free. On 600,000 rows it is quadratic.

The rewrite computes every group's minimum in **one** pass, then joins. Two sequential scans
and a hash, instead of 600,001.

## 5. 처방 / Remedy

| Option | Why not |
| --- | --- |
| build a temporary index on `(learner_id, concept_id)` first | it makes the subquery cheap and adds an index build over 600,000 rows to do it. The aggregate pass needs no index at all |
| skip the delete and let `CREATE UNIQUE INDEX` fail on duplicates | `V3`'s comment already rejects this: the migration would fail on exactly the databases it exists to repair |
| `delete … where id not in (select min(id) … group by …)` | equivalent, and `NOT IN` over a subquery with NULLable columns is a footgun this schema does not need to teach |
| **one aggregate pass, joined back** | **✔** |

Semantics are unchanged: the lowest `id` of each pair survives.

## 6. 재계측 / Re-measurement

§3. Cost **9,139,221,232 → 34,214**. The rewritten statement completes in **768 ms** on the
seeded database; the original did not complete in the 120 seconds the harness allowed, and
§8 records why it was not run to completion.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/db/MigrationDeduplicationTest.kt`, run by
`.github/workflows/build.yml`.

It plants six rows — `(1,1)` three times, `(1,2)` twice, `(2,1)` once, **with different
scores** — runs the delete, and asserts that **three rows are removed and the survivors are
the lowest id of each pair**. Distinct scores are what make the assertion about *which* row
survived rather than *how many*: `V3`'s comment says it keeps the earliest row and
deliberately does not merge, and that claim now has a test.

**The statement is read out of `V3` on the classpath, not copied.** A copy drifts, and a
drifted copy passes while the migration is wrong.

Comment lines are stripped before the statement is located, and that is not a detail: `V3`'s
commentary now contains the **old** statement as an illustration of what not to do. A naive
search for `delete from mastery` finds that one first — and the gate would then be asserting
the correctness of the very statement it was written to replace.

## 8. 남는 위험 / Remaining risk

- **The original statement was never run to completion, so there is no wall-clock number for
  it.** Only a planner cost and a 120-second timeout. Letting it finish would take an
  unknown number of hours on a machine that is also the measurement environment. **미측정,
  deliberately**, and the cost ratio is a planner estimate rather than two stopwatches.
- **The rewrite was measured on a table with no duplicates.** 768 ms is the cost of finding
  nothing. A database with real duplicates does the same scans plus the deletes, and that is
  **미측정** — the gate proves correctness on six rows, not cost on six hundred thousand.
- **`V3` was modified after being committed.** Flyway validates checksums, so any database
  that had already applied the old `V3` will now refuse to start. **None exists** — it never
  completed anywhere, and CI builds a fresh container every run. That is why this is a fix to
  `V3` rather than a `V4`, and it would have been the wrong call the moment one real database
  had it applied.
- **Only `mastery` was checked.** `V1` creates seven tables and no other migration deduplicates
  anything today, but nothing structural prevents the next one from using the same shape.
  **No rule looks for correlated subqueries in migrations**, and one could.
- **The seeded database is the only large one that exists.** Everything here is measured
  against 600,000 `mastery` rows, and the shape of the defect — quadratic — means the numbers
  do not scale linearly to a different size. A table ten times larger is a hundred times worse.
- **What would break the conclusion**: an index on `(learner_id, concept_id)` existing before
  `V3` runs. Then the correlated subquery is an index lookup per row and perfectly reasonable.
  The defect is not the statement; it is the statement **on this table at this point in the
  migration sequence.**

## 9. 배운 것 / What I learned

**이 저장소가 수집하는 결함을, 이 저장소 자신의 마이그레이션이 갖고 있었다.**

로드맵의 선정 기준은 *"모든 단위 테스트를 통과하고, 부하·동시성·규모에서만 나타나는 것"* 이다. `V3`가
정확히 그것이었다. 나흘 동안 CI에서 초록이었고, 열네 개 리포트를 쓰는 동안 트리에 있었고, **데이터가
있는 데이터베이스에 한 번도 적용된 적이 없었다.**

이유는 단순하고 그래서 무섭다. **모든 테스트가 빈 스키마에 V3를 적용한다.** Flyway는 컨테이너 기동 시
돌고, 픽스처는 그 뒤에 삽입한다. 중복 제거 구문은 언제나 0행을 상대했다. **초록이었던 게 아니라, 일을
한 적이 없었다.**

**그리고 이 결함의 모양이 특이하다 — 자기가 만들 인덱스를 필요로 한다.**

상관 서브쿼리는 `(learner_id, concept_id)`로 행을 찾아야 하는데, 그 인덱스를 만드는 것이 바로 이
마이그레이션이다. 삭제가 끝나야 인덱스를 만들 수 있고, 인덱스가 있어야 삭제가 싸다. **빈 테이블에서는
이 순환이 무료라서 보이지 않는다.**

**마지막으로, 다른 것을 재려다 찾았다.**

R4의 p99를 다시 재려고 앱을 시드 데이터베이스에 붙였을 뿐이다. 만약 부하 하네스가 인증 때문에 401을
내고 있다는 걸 먼저 알아채지 못했다면, 앱을 띄울 이유도 없었고 이 결함은 계속 트리에 있었을 것이다.
**측정하려는 시도 자체가 측정 도구였다** — 그리고 아직 원래 재려던 것은 재지 못했다.
