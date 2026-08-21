# R20. The graph was read one level deep, and the second level costs 138 statements

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: `b97cd6b` — the application-side walk, and its number
> **Green commit**: `24b959a` — the same closure in one statement
> **Migration**: `V4__concept_edge_by_concept.sql`, `eb7c445`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 (JDK 21 toolchain, pinned in gradle.properties)
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14, default shared_buffers
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Counter        : Hibernate `Statistics.prepareStatementCount`, via `StatementCounter`
  Dataset        : `concept_edge` at seed value 20260810, Scale.FULL — 3,000 concepts,
                   8,994 edges. Rebuilt inside the container by `SeedConceptGraph`, and
                   checked against the seed module's own figures before every run
  Load           : none. These are counts, plans and row counts, not a load run
  Repetitions    : 3 runs, median, for every duration. Counts are exact and single-valued
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`concept_edge` has existed since the first commit. `V1__baseline.sql` creates it with
`prerequisite_id`, `concept_id`, a weight, a uniqueness constraint on the pair, and a table
comment about acyclicity. The generator emits 8,994 edges and `GeneratorTest` asserts the
DAG property with Kahn's algorithm.

Exactly one thing reads it, and it reads one level:

```sql
and not exists (
      select 1
        from concept_edge e
        left join mastery pm
               on pm.concept_id = e.prerequisite_id
              and pm.learner_id = :learnerId
       where e.concept_id = m.concept_id
         and (pm.score is null or pm.score < 0.700)
    )
```

That is `RecommendationQueries.findRecommendations`, and *every prerequisite of that concept
is already mastered* means **the immediate prerequisites**. Nothing here has ever asked what
sits two levels below a concept.

**Three properties of this table are identical at depth 1 and different at depth 2**, and
none of them was measured before this report:

| | depth 1 | measured, depth 14 |
| --- | --- | --- |
| concepts reached | 3 | 606 |
| walks to reach them | 3 | 7,174,452 |
| statements an application-side walk issues | 1 | 1 per concept visited |

## 2. 재현 / Reproduction

```bash
# the shipped graph's own shape, in the seed module, no database
./gradlew :seed:test --tests net.gseek.proxima.seed.PrerequisiteDepthTest

# statement counts and the two recursive forms
./gradlew :api:test --tests net.gseek.proxima.concept.PrerequisiteTraversalCountTest

# the index comparison, both arms in one session
./gradlew :api:test --tests net.gseek.proxima.concept.PrerequisiteIndexTest
```

All three go through WSL2; Docker is native inside it and Windows cannot reach the daemon.

## 3. 계측 / Measurement

### 3.1 What the shipped graph actually is

Read out of `Scale.FULL`'s `concept_edge.tsv`, by `PrerequisiteDepthTest`:

```
concepts                    : 3000
edges                       : 8994
longest chain               : 294 edges
concepts with a prerequisite: 2999
mean prerequisites, of those: 2.999
```

From concept 3000, the deepest place to stand:

```
  depth   reachable        walks
      1           3                3
      2          11               12
      3          29               39
      4          70              120
      5         137              363
      6         202            1,092
      7         252            3,279
      8         312            9,840
      9         365           29,523
     10         416           88,572
     11         462          265,719
     12         511          797,160
     13         558        2,391,483
     14         606        7,174,452
```

**The two columns are the same number at depth 1 and 11,839× apart at depth 14.** A
prerequisite graph is a DAG whose concepts share ancestors; the number of distinct *walks*
of length `d` is not the number of concepts reachable in `d` steps. On a tree they would be
equal, and every `WITH RECURSIVE` example on the internet is written for a tree.

The longest chain is **294 edges**, so a genuinely complete transitive closure needs
`maxDepth = 294`, at which the walk count is not a number this report can print.

### 3.2 The application-side walk, in statements

`PrerequisiteGraph` holds the two shapes anybody writes on discovering that JPQL has no
recursive query. Counted with `StatementCounter`, at depth 6 from concept 3000, which is
**202 concepts**:

| | statements | grows with |
| --- | --- | --- |
| `closureByNodeWalk` — one query per node | **138** | the size of the answer |
| `closureByLevelWalk` — one query per level | **6** | the depth asked for |
| `PrerequisiteQueries.closure` — one statement | **1** | nothing |

138 is `1 + 3 + 8 + 18 + 41 + 67`: the concepts newly found at depths 0..5, each of them its
own round trip. It is not 202, because the 65 concepts found at depth 6 are never themselves
queried — a difference an upper bound would have hidden, which is `R8` §3.2's argument for
`assertEquals` arriving on a second axis.

At depth 3 the same walk costs **12** statements, and the recursive statement still costs 1.

### 3.3 `union` is not a spelling of `union all`, and it is invisible in review

Both recursive forms are **one statement**, both return the right concepts, and every
functional test passes on either. The word in the middle is the whole difference:

| depth | `union all` rows | `union` rows | ratio |
| --- | --- | --- | --- |
| 1 | 3 | 3 | 1.0× |
| 3 | 39 | 30 | 1.3× |
| 5 | 363 | 181 | 2.0× |
| 7 | 3,279 | 547 | 6.0× |
| 9 | **29,523** | **1,103** | **26.8×** |

The `union all` column is checked against `PrerequisiteDepthTest.walksTo`, which counts
distinct walks in a different module, over an in-memory graph, without a database. **Two
independent computations agreeing is what makes it a measurement rather than a printout.**

**And the deduplicating form is still not one row per concept.** 1,103 rows carry 365
concepts at depth 9 — 3.0× over-carry — because `union` deduplicates whole *rows* and a
concept reachable at depth 4 and at depth 7 is two different rows once a depth counter is
projected. That mechanism is `R21`'s subject and it is first visible here.

### 3.4 `concept_edge` had an index over both its columns and could not use it

`uk_concept_edge unique (prerequisite_id, concept_id)` is implemented as a B-tree. It leads
on `prerequisite_id`. A prerequisite traversal restricts on `concept_id`.

Verbatim, `EXPLAIN (ANALYZE, BUFFERS)` at depth 6, without the index:

```
Sort  (cost=2118.10..2118.60 rows=200 width=12) (actual time=4.766..4.775 rows=202 loops=1)
  Buffers: shared hit=405
  CTE walk
    ->  Recursive Union  (cost=0.00..2100.88 rows=303 width=12) (actual time=0.358..4.521 rows=343 loops=1)
          ->  Seq Scan on concept_edge e  (cost=0.00..179.43 rows=3 width=12) (actual time=0.354..0.356 rows=3 loops=1)
                Filter: (concept_id = 3000)
                Rows Removed by Filter: 8991
          ->  Hash Join  (cost=0.80..191.84 rows=30 width=12) (actual time=0.608..0.647 rows=90 loops=6)
                Hash Cond: (e_1.concept_id = w_1.prerequisite_id)
                ->  Seq Scan on concept_edge e_1  (cost=0.00..156.94 rows=8994 width=16) (actual time=0.002..0.368 rows=8994 loops=5)
Execution Time: 4.943 ms
```

`rows=8994 loops=5`. The recursive term is re-executed once per iteration, so **the cost of
the missing index is multiplied by depth rather than merely present at it.**

With `ix_concept_edge_concept`:

```
->  Index Only Scan using ix_concept_edge_concept on concept_edge e_1  (cost=0.29..10.56 rows=3 width=16) (actual time=0.001..0.001 rows=3 loops=181)
      Index Cond: (concept_id = w_1.prerequisite_id)
      Heap Fetches: 543
Execution Time: 0.599 ms
```

Median of three, at three depths:

| depth | rows fed, no index | rows fed, index | exec ms, no index | exec ms, index |
| --- | --- | --- | --- | --- |
| 3 | 17,991 | 36 | 1.973 | 0.170 |
| 6 | 44,973 | 546 | 3.887 | 0.468 |
| 12 | 98,937 | 5,424 | 10.526 | 3.506 |

`rows fed` is `rows × loops` summed over every node reading `concept_edge`, and it is
`3 + 8994 × (depth − 1)` exactly in the unindexed arm.

### 3.5 The index's advantage shrinks with depth, and the buffer column points the wrong way

Two things in that table are not what the obvious story predicts.

**The row-count advantage falls away**: 500× at depth 3, 82× at depth 6, **18.2× at depth
12.** The unindexed cost grows with the number of iterations and the indexed cost grows with
the frontier, and depth is what makes the frontier large. **An index is not a substitute for
a depth bound.** The first version of the assertion here was a flat *more than 20× at every
depth*; it was refused at depth 12, and lowering it to get a green would have deleted the
finding.

**The faster plan touches more buffers, and the sign depends on depth:**

| depth | buffers, no index | buffers, index |
| --- | --- | --- |
| 3 | 204 | 36 |
| 6 | 402 | 550 |
| 12 | 804 | 5,454 |

Buffers were chosen *because* rule 9 forbids asserting a duration and a buffer count is a
property of the plan rather than of the machine. On this query they invert between depth 3
and depth 6 and are 6.8× the wrong way by depth 12. The sequential scan reads 67 pages and
pushes 8,994 rows per iteration through a hash join; the index probes 1,808 times, touching
a page each. **Pages touched is not work done, and a machine-independent metric is not
automatically the right metric.**

### 3.6 Why `V4` is one column and not two

| candidate | bytes | rows fed, depth 12 | exec ms, depth 6 / 12 | plan |
| --- | --- | --- | --- | --- |
| none | 0 | 98,937 | 4.434 / 10.413 | Seq Scan |
| `(concept_id)` | 163,840 | 5,424 | 0.500 / 3.466 | Index Scan |
| `(concept_id, prerequisite_id)` | 303,104 | 5,424 | 0.538 / 3.621 | Index Only Scan |

The covering pair costs **85% more** and is **not faster**. `R3` reached the same verdict
about `INCLUDE` columns on `attempt`; this is the same verdict reached again by measurement
rather than carried over.

The mechanism is in the plan and it is not about this table. `Heap Fetches: 5,424` — an
index-only scan is only index-only when the **visibility map** says a page holds nothing but
rows visible to everyone, and the visibility map is set by `VACUUM` and by nothing else.
Measured either side of one, depth 12:

```
before vacuum   Heap Fetches 5,424   buffers 5,454   3.381 ms
after  vacuum   Heap Fetches     0   buffers 3,618   3.032 ms
```

`seed/`'s `Main.kt` runs `generate`, `load` (a `COPY`), and `analyze`. **It never runs
`VACUUM`** — deliberately, because `T4` needed stale statistics — so the first row is the
state every measurement in this repository is taken in.

## 4. 원인 / Mechanism

Three separate mechanisms, and only the first is about the application.

**JPA has no recursive query.** `WITH RECURSIVE` is SQL:1999 and JPQL does not surface it,
so a transitive read is native SQL or an application loop. The loop is not a mistake anybody
makes out of carelessness; it is the only thing the type-safe API offers.

**A DAG is not a tree.** Each concept draws three prerequisites from the 60 concepts before
it, so the same concept is reached along many distinct paths. `union all` emits one row per
path. At depth 1 there is exactly one path to each neighbour and the distinction does not
exist.

**A B-tree restricts on a prefix of its columns.** `uk_concept_edge` covers both columns and
answers only the direction that leads with `prerequisite_id`. Nothing read the other
direction for eleven days, so nothing noticed.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| `closureByNodeWalk` | 138 statements at depth 6 | grows with the answer; holds a connection throughout | ✘ |
| `closureByLevelWalk` | 6 statements at depth 6 | grows with depth; still holds a connection throughout | ✘ |
| `closure` — `union all` | 1 statement | 797,160 working rows at depth 12 | ✘ |
| **`closure` — `union`** | **1 statement, 2,209 working rows at depth 12** | a depth column that costs cycle-immunity — `R21` | **✔** |
| a materialised closure table | O(1) reads | write amplification, and a second copy of the truth — `ADR-011` | ✘ |

The projection returns values, so nothing it hands back can trigger a round trip after the
transaction closes, which is `R4`'s condition for the connection actually returning to the
pool. There is deliberately **no `ConceptEdge` entity**: `PersistenceUnitGateTest` asserts an
exact set of five, and a lazy association here would hand the caller the N+1 `R8` §3.1 is
about.

`V4` adds `ix_concept_edge_concept on concept_edge (concept_id)`.

## 6. 재계측 / Re-measurement

| Metric | Before | After |
| --- | --- | --- |
| statements, closure to depth 6 | 138 | **1** |
| statements, closure to depth 12 | 미측정 — the node walk was not run that deep | **1** |
| working rows, depth 12 | 797,160 (`union all`) | **2,209** (`union`) |
| rows fed through `concept_edge`, depth 12 | 98,937 | **5,424** |
| exec ms, depth 12, median of 3 | 10.526 | **3.506** |

## 7. 회귀 게이트 / Regression gate

- `api/src/test/kotlin/net/gseek/proxima/concept/PrerequisiteTraversalCountTest.kt` — the
  closure is exactly **1** statement at depth 3 and at depth 12; the two walks are pinned at
  138 / 12 and 6 / 3; the `union all` and `union` working-table sizes are pinned at nine
  depths, and the `union all` column is cross-checked against the seed module's arithmetic.
- `api/src/test/kotlin/net/gseek/proxima/concept/PrerequisiteIndexTest.kt` — the planner
  chooses `Seq Scan` without `V4` and `Index Scan` with it, at three depths; the rows fed are
  exact; the ratio is required to **fall** with depth; the buffer inversion is pinned; and a
  control asserts the buffer figure is the root node's rather than the sum of every node's.
- `api/src/test/kotlin/net/gseek/proxima/db/BaselineMigrationTest.kt` — the migration set is
  `1..5` exactly and the performance-index set is `ix_attempt_learner_attempted_at`,
  `ix_concept_edge_concept` exactly. An index nobody measured fails here.
- `seed/src/test/kotlin/net/gseek/proxima/seed/PrerequisiteDepthTest.kt` — the shipped edge
  count, and that walks outgrow reachable concepts at depth 8.

## 8. 남는 위험 / Remaining risk

- **The api-side graph is a reproduction, not the seed.** `api` does not depend on `seed`,
  so `SeedConceptGraph` re-runs the generator's edge loop from the same stream. It is checked
  against the seed module's measured figures — 8,994 edges, reachable `[3, 11, 29, 70, 137,
  202]` — before every run, and a drift breaks the check and the digest pins together. It is
  still a second copy of an algorithm, and collapsing it needs an `api → seed` test
  dependency, which is an edit to `api/build.gradle.kts` this report was not entitled to make.
- **Every number here is one starting concept.** Concept 3000 is the deepest place to stand
  and therefore the most expensive; a closure from a mid-graph concept is 미측정. The shape
  of the finding does not depend on it and the *sizes* do.
- **No number here is a duration under load.** Every figure is a count, a plan, or a
  single-query median of three at concurrency 1. What a transitive read costs at 200 VU
  beside the recommendation query is **미측정**, and `R16` §3.4 is the reason it would not be
  a simple addition: four requests in five on this dataset answer with an empty list.
- **The depth bound is unchosen.** `maxDepth` is a caller's argument with no default and no
  policy. Depth 6 appears throughout this report because it is where the graph stops being
  trivial, not because anything decided it. `ADR-011`.
- **`V4` is measured at 8,994 edges.** A curriculum ten times the size would move every
  figure in §3.6, and the direction of the covering-index decision is the one most likely to
  flip — the wider index's loss is 85% space for a difference inside the run-to-run spread,
  and both halves of that scale.
- **The `VACUUM` finding is not acted on.** §3.6 shows the load path leaves the visibility
  map unset and every index-only scan in this repository paying heap fetches. `R3`'s covering
  variant was rejected under exactly that condition and has not been re-measured after a
  `VACUUM`. **미측정, and it needs a judgement about the load path rather than only work** —
  it belongs in `docs/decisions/open.md`, which this slice does not own.
- **What would break the conclusion:** a graph with a small branching factor. Every ratio
  here comes from three prerequisites per concept and heavy ancestor sharing. At branching
  factor 1 the graph is a chain, `union all` and `union` are the same query, and none of §3.3
  exists.

## 9. 배운 것 / What I learned

**깊이 1에서는 모든 것이 똑같아 보인다.**

`concept_edge`는 첫 커밋부터 있었고, DAG 테스트도 있었고, 문서에도 "DAG, not a tree"라고 적혀
있었다. 그런데 이 저장소가 그 테이블을 읽는 방식은 `NOT EXISTS` 한 겹뿐이었고, **깊이 1에서는
walk 수와 도달 concept 수가 같은 숫자다.** 그래서 `union all`과 `union`의 차이도, 인덱스 방향도,
depth bound도 전부 존재하지 않는 문제였다. 존재하지 않는 게 아니라 **보이지 않는** 것이었다.

`union`과 `union all`이 같은 SQL의 한 단어 차이라는 게 제일 무섭다. 둘 다 문장 하나고, 둘 다
정답을 돌려주고, 기능 테스트는 어느 쪽이든 통과한다. 리뷰어가 diff에서 볼 수 있는 건 단어 하나고,
깊이 9에서 26.8배다. `R8`이 "N+1은 diff에서 안 보인다"고 한 것과 정확히 같은 형태인데, 이번엔 행
축이 아니라 **깊이 축**이다.

그리고 계측 지표를 고르는 것에 대해 한 번 더 배웠다. rule 9가 CI에서 duration을 못 쓰게 하니까
buffers를 골랐다. 기계에 독립적이고, plan의 성질이고, 옳은 선택처럼 보였다. **그런데 이 쿼리에서
buffers는 반대 방향을 가리킨다** — 빠른 쪽이 6.8배 더 많은 버퍼를 만진다. 기계 독립적인 숫자가
자동으로 옳은 숫자는 아니다. 여기서 옳은 숫자는 `rows × loops`였고, 그건 내가 처음에 안 봤다.

마지막으로, 내가 쓴 임계값이 측정에 의해 거부당했다. "모든 깊이에서 20배 이상"이라고 썼는데 깊이
12에서 18.2배였다. **임계값을 낮춰서 초록으로 만들 수 있었고, 그러면 인덱스의 이점이 깊이에 따라
사라진다는 발견 자체가 사라졌을 것이다.** 숫자가 안 맞아서 살았다는 문장을 `R8` §9에서 읽었는데,
이번엔 내가 쓴다.
