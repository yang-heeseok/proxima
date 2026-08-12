# R5. A page that is paginated in memory — except it is not, on this version

> **Created**: 2026-08-12
> **Updated**: 2026-08-12
> **Red commit**: none. **The first strand of `T2` does not reproduce on this stack.**
> **Green commit**: none, for the same reason.
> **Status**: `T2` is **half a defect**. One strand was fixed by the framework before this
> repository existed; the other reproduces and announces itself loudly.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2
  JVM            : Temurin 21.0.12+8
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14
  Framework      : Spring Boot 4.1.0, **Hibernate 7.4.1.Final**, Kotlin 2.3.21
  Dataset        : 20 learners x 50 attempts, inserted by the test. NOT the seed
  Load           : none. This is a question about generated SQL, not about throughput
  Repetitions    : n/a — the SQL is the same every time or it is a different finding
```

> **The framework version is the whole report.** Every claim here is about Hibernate
> 7.4.1.Final and would need re-measuring on any other.

---

## 1. 증상 / Symptom

None. That is the finding.

`T2` was written down as: *"A collection join with paging, where the framework fetches the
whole result and slices it in the heap. There is a warning in the log and no error
anywhere."* On this version there is **no warning, no in-memory slicing, and nothing to
fix.**

## 2. 재현 / Reproduction

```kotlin
@Query("select l from Learner l left join fetch l.attempts")
fun pageWithAttempts(pageable: Pageable): List<Learner>
```

Called with `PageRequest.of(0, 5)` against 20 learners of 50 attempts each. The log is
captured inside the test by a Logback appender, because the artefact that answers this is
the generated SQL and nothing else does.

## 3. 계측 / Measurement

### 3.1 What Hibernate actually emits

Verbatim, from `org.hibernate.SQL` at `DEBUG`:

```sql
select l1_0.id, a1_0.learner_id, a1_0.id, a1_0.attempted_at, a1_0.correct,
       a1_0.elapsed_ms, a1_0.hint_used, a1_0.item_id, l1_0.created_at, l1_0.external_ref
from (select l1_0.id, l1_0.created_at, l1_0.external_ref
        from learner l1_0
       offset ? rows fetch first ? rows only) l1_0(id, created_at, external_ref)
left join attempt a1_0 on l1_0.id = a1_0.learner_id
```

**The page is applied inside a derived table, by the database, to the roots alone**, and the
collection is joined to the result. That is the two-query approach expressed as one
statement. The database returns 5 × 50 rows, not 20 × 50.

### 3.2 What it says about it

Nothing. With `org.hibernate` turned down to `DEBUG` so that a notice at any level would be
visible, no event mentions `firstResult`, `maxResults`, `in memory`, `HHH000104`, or
`HHH90003004`.

**There is no warning because there is nothing to warn about.**

### 3.3 The control, and why it is here

The first version of §3.2 concluded "Hibernate says nothing" from an appender that had
captured **zero events of any kind** — which is equally consistent with the appender not
being attached. The assertion was rewritten to plant a known log event and require it back
before drawing any conclusion from an absence:

```
WARN proxima.control — PROXIMA-APPENDER-CONTROL-EVENT
DEBUG org.hibernate.orm.core — HHH006588: Opening session [tenant=null]
DEBUG org.hibernate.SQL — select l1_0.id,a1_0.learner_id,... (above)
DEBUG org.hibernate.session.metrics — HHH000401: Logging session metrics: ...
```

Four events, the planted one among them. **Only then does "no warning" mean anything.**

### 3.4 The strand that does reproduce

```
select l from Learner l left join fetch l.attempts left join fetch l.masteries
```

```
org.hibernate.loader.MultipleBagFetchException: cannot simultaneously fetch multiple bags:
  [net.gseek.proxima.domain.Learner.attempts, net.gseek.proxima.domain.Learner.masteries]
```

Raised when the query is built, before any SQL is sent. **This is a good failure**: it is
immediate, it names both collections, and no amount of load is required to find it.

## 4. 원인 / Mechanism

A `List` mapping is a *bag* — unordered and permitting duplicates. Fetching two bags in one
query produces a cartesian product between them, and Hibernate cannot tell which duplicates
are real, so it refuses rather than returning a plausible wrong answer.

For a single collection, the historical problem was that a `LIMIT` applied to a
root×collection join limits **rows**, not roots, so the framework could not push it down and
sliced in the heap instead. Hibernate now rewrites the query so the limit applies to a
derived table of roots, where it means what the caller meant.

## 5. 처방 / Remedy

**None is needed for the first strand, and that is the recommendation.**

| Option | Status |
| --- | --- |
| leave the single-collection query as written | **✔** — the framework already does the right thing |
| `distinct` | measured, no effect on where the page is applied |
| two queries by hand — page roots, then fetch by id | measured, works, and is now redundant |
| two bags in one query | **do not** — `MultipleBagFetchException` |

For two collections, the options are a second query, or `Set` instead of `List` — which
changes the semantics of the mapping and is a domain decision, not a workaround. **Neither
is chosen here, because nothing in this application fetches two collections.** Writing a fix
for a query that does not exist is how a repository accumulates code nobody asked for.

## 6. 재계측 / Re-measurement

Not applicable. There is no before and after.

## 7. 회귀 게이트 / Regression gate

`CollectionPagingWarningTest` asserts the derived-table rewrite **positively** — that the
generated SQL contains a subquery carrying the `offset`. If a future Hibernate stops doing
this, the test fails and `T2`'s original defect is back, with a report already written
explaining what it was.

That is the useful shape for a defect the framework fixed: **the gate guards the fix
someone else made.**

## 8. 남는 위험 / Remaining risk

- **This is a statement about Hibernate 7.4.1.Final and nothing else.** Every codebase on
  Hibernate 5 still has the original defect, and the roadmap's description of `T2` was
  accurate when it was written. **A performance claim without a version is not a claim** —
  and this repository made one, in its own roadmap, until today.
- **A test that passed while asserting the opposite of the truth lived here for one
  commit.** `CollectionPagingTest` measured tuple reads as a delta over
  `pg_stat_user_tables` to prove the whole table was read. It passed. Those counters are
  cumulative and updated on a delay, so the delta was not the query's, and the conclusion it
  supported is false. It was found only because a *different* test captured the SQL. The
  lesson is not "avoid statistics views" — it is that **an assertion that confirms what you
  expected is the one to distrust**.
- **The dataset here is 20 × 50, inserted by the test.** Whether the derived-table rewrite
  holds its shape at three million rows, or whether the planner does something different
  with a large subquery, is **미측정**.
- **Only `left join fetch` with `Pageable` was measured.** `@EntityGraph`, `join fetch` with
  an explicit `where`, and Spring Data's derived query methods may take other paths.
  미측정.
- **`Set` instead of `List` was not measured at all.** It is the usual advice for the
  two-bag case and it changes duplicate semantics; this report names it without evidence.
- **No load was applied.** The claim is about generated SQL, and the cost of the join at
  scale — 5 roots × 3,000 attempts on the real dataset is 15,000 rows for one page — is
  **미측정** and is a real concern independent of where the page is applied.

## 9. 배운 것 / What I learned

**결함이 없다는 걸 재현하는 데 시간을 썼고, 그게 오늘 제일 값진 결과다.**

로드맵에 T2를 *"프레임워크가 전체를 가져와서 힙에서 자른다. 로그에 경고가 있고 에러는 없다"* 라고
적어놨다. 널리 알려진 사실이고, 나도 그렇게 알고 있었다. Hibernate 7.4.1은 그렇게 하지 않는다.
페이지를 파생 테이블에 넣어서 DB에 내려보낸다. 경고가 없는 이유는 **경고할 일이 없기 때문**이다.

내가 그걸 알아채기까지 두 단계를 헛디뎠다. 먼저 `pg_stat_user_tables`로 "전체 테이블이 읽혔다"를
단언했고 **통과했다.** 누적 카운터에 갱신 지연이 있어서 델타가 내 쿼리의 것이 아니었다. 기대한 답이
나왔으니 의심하지 않았다. 그 다음 로그에 경고가 없는 걸 보고 "경고가 없다"고 결론 낼 뻔했는데,
appender가 이벤트를 **하나도** 못 잡은 상태였다. 없는 것을 증명하려면 있는 것을 먼저 보여야 한다.
심은 로그 한 줄을 넣고 나서야 비로소 말할 자격이 생겼다.

**기대한 결과가 나온 단언이 제일 의심스럽다.** 오늘 그걸 몸으로 배웠다.

그리고 버전에 대해. 이 저장소는 측정 환경 블록을 모든 리포트에 강제하면서, 정작 **자기 로드맵에는
버전 없는 성능 주장**을 적어놨다. T2는 Hibernate 5에서 참이고 7.4.1에서 거짓이다. 조건 없는 성능
지식은 유통기한이 있고, 그 유통기한을 확인하는 유일한 방법은 지금 쓰는 버전에서 재보는 것이다.
