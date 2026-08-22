# R21. A cycle kills three ways, and the two that reach the database look identical

> **Created**: 2026-08-21
> **Updated**: 2026-08-22
> **Red commit**: `1fffc79` — the four arms, on a graph with three cycles in it
> **Green commit**: `39f69ca` — the shipped read survives, and both defences are priced
> **Migration**: `V5__concept_edge_forward_only.sql`, `4a136c0`

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 -- RECORDED, not pinned. This line said "pinned in
                   gradle.properties" until 2026-08-22; that file pins language version
                   21 only, and nothing requests Temurin or this patch. The JVM above is
                   what ran and is unchanged. measurement-discipline.md owns the retraction
  PostgreSQL     : Testcontainers postgres:16-alpine — server 16.14, default shared_buffers
  Framework      : Spring Boot 4.1.0, Hibernate 7.4.1.Final, Kotlin 2.3.21
  Dataset        : `concept_edge` at seed value 20260810, Scale.FULL — 3,000 concepts,
                   8,994 edges — plus 3 injected back-edges, `Generator(backEdges = 3)`
  statement_timeout : 3,000 ms. NOTHING IS CONCLUDED FROM THIS NUMBER. It separates
                   "reaches an answer" from "does not", which is categorical
  Load           : none. Row counts, SQLSTATEs, and single-query medians of three
  Repetitions    : 3 runs, median, for every duration
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`V1__baseline.sql` carries this on the table, and has since the first commit:

```
'prerequisite_id must be understood before concept_id. A DAG, not a tree.
 Acyclicity is asserted by the generator and checked by a test -- a CHECK constraint
 cannot express it, and that gap is recorded rather than hidden.'
```

The test it names asserts that **the generator** produces no cycle. Nothing asserts what
happens when something else does, and nothing prevents it: `concept_edge` accepts a
backwards edge without complaint, because `uk_concept_edge` sees a pair no forward edge
produces and `ck_concept_edge_no_self` is satisfied.

`R20` opened a transitive read of that table. This is what a cycle does to it.

## 2. 재현 / Reproduction

`Generator` gains an **opt-in** parameter, used by tests and by nothing else:

```kotlin
Generator(Scale.FULL, backEdges = 3)
```

It appends three edges running from a high concept id to a low one, each closing a cycle of
a different length by walking a real prerequisite chain down and joining its ends.
`Scale.FULL` and `Scale.TINY` are byte-identical at the default of `0` — `SeedDigestTest`'s
SHA-256 pins are green, and `CyclicGeneratorTest` adds the control the pins cannot give by
comparing `backEdges = 0` against not passing the parameter at all.

```bash
./gradlew :seed:test --tests net.gseek.proxima.seed.CyclicGeneratorTest
./gradlew :api:test  --tests net.gseek.proxima.concept.CycleTraversalTest
./gradlew :api:test  --tests net.gseek.proxima.concept.CycleGuardCostTest
```

In the api container the graph is rebuilt by `SeedConceptGraph`, which reports before every
run:

```
reproduction matches the shipped graph: 8994 edges, reachable [3, 11, 29, 70, 137, 202]
cycles injected: 1590 of 3000 concepts are unorderable, back-edges [(3000, 2889), (2250, 2121), (1500, 1327)]
```

**Since `V5` the cycle test has to remove a constraint to run at all**, and it does so in the
open — §5.

## 3. 계측 / Measurement

Every line below is the server's own output.

```
union all,   unbounded, cyclic   57014 -- ERROR: canceling statement due to statement timeout
union all,   unbounded, acyclic  57014 -- ERROR: canceling statement due to statement timeout
union+depth, unbounded, cyclic   57014 -- ERROR: canceling statement due to statement timeout
union+depth, unbounded, acyclic  completed, 303,948 rows
union node-only, unbounded, cyclic   completed, 2,790 rows
path-guarded, unbounded, cyclic  57014 -- ERROR: canceling statement due to statement timeout
path-guarded, depth 6,   cyclic  completed, 1,092 rows
application walk, no seen set    4,000 statements, capped, still going
application walk, seen set       1,855 concepts in 1,811 statements
```

### 3.1 Death one — `union all`, and the symptom that does not name the cause

An unbounded `union all` recursion over a cyclic graph cannot terminate: there is always
another walk. It is cancelled with SQLSTATE `57014`.

**The same query dies the same way on the acyclic graph.** `R20` §3.1 measured 7,174,452
walks at depth 14 and a longest chain of 294 edges; finite is not the same as reachable.

So an operator holding a `57014` from this query knows that the recursion did not finish and
**nothing whatever about whether the data has a cycle in it** — which is the diagnosis they
will spend the outage looking for. The control is the acyclic arm: the same statement, the
same connection settings, the same data minus three rows.

### 3.2 Death two — `union`, which is the cycle defence, disabled by the column that makes it useful

PostgreSQL's recursive `union` discards a row that duplicates one already produced. That is
the standard cycle defence and it is real: projecting the node alone, the same unbounded
recursion **completes on the cyclic graph, 2,790 rows.**

It defends **rows**, not nodes. A row carrying `depth` is never a duplicate of the same node
at a different depth, so on a cycle the counter climbs for ever and every row is new.

**The column that makes the result useful is the column that removes the protection**, and
nothing warns. The two queries differ by `, depth` in two places.

And the safe arm is not the cheap arm: `union` + depth on the **acyclic** graph completes
with **303,948 rows** to describe 3,000 concepts, because the longest chain is 294 edges and
a concept is carried once per distinct depth at which it is reachable.

### 3.3 Death three — the application walk, which the database never reports

No statement is slow. No statement times out — `statement_timeout` cannot fire on a two-row
index lookup. The loop simply does not end, inside a `@Transactional(readOnly = true)`
method, **holding one pooled connection for the whole of it.**

`closureByUnguardedWalk` reached its 4,000-statement cap with 10,217 visits and was still
going. The same walk with a `seen` set returned 1,855 concepts in 1,811 statements.

That is `T1`'s mechanism arriving from the other side. `R2` measured a request holding a
connection while it *slept*; this is a request holding one while it *works*. The database is
idle and the pool drains, which is the shape `R2` §1 had to correct once already.

**The `seen` set makes the application walk the only arm here that is safe on a cyclic graph
for free** — and `R20` §5 chose against the application walk on statement counts. §5 below
is about how uncomfortable those two sentences are together.

### 3.4 The path array stops the cycle and does not stop the cost

A path array — `not (x = any(w.path))` — is what every reference recommends. It works: the
recursion cannot revisit a concept **along one walk**.

It says nothing about the same concept being reached along a thousand different walks, which
is what a DAG with shared ancestors is anyway. Unbounded on the cyclic graph it still times
out. Bounded to depth 6 it carries **1,092** rows, and `PrerequisiteDepthTest` computes
**1,092** walks at depth 6 on the acyclic graph. Equal is the finding: the guard bought
termination and not one row of saving.

**And the first version of that measurement was taken in the one place where it means
nothing.** A path array is seeded with the start concept by the base term, so a cycle whose
head *is* the start is refused on first sight. Starting one edge above a cycle's tail
instead:

| start | cyclic graph | acyclic graph |
| --- | --- | --- |
| the cycle's own head | 1,092 rows | 1,092 rows |
| one edge above its tail | **1,202 rows** | 1,092 rows |

Without that control this report would have published *the path guard costs nothing* — a
true sentence about one starting concept.

### 3.5 And the path array defeats `union` entirely, on a graph with no cycle in it

The read-side defence has to be priced where it will actually run, which is a graph that is
acyclic today and will be acyclic on almost every request for ever.

| depth | `union` + depth | `union` + depth + path | outer `distinct` |
| --- | --- | --- | --- |
| 6 | 343 rows, 2.897 ms | 1,092 rows, 4.607 ms | 202 rows, 2.926 ms |
| 12 | 2,209 rows, 4.994 ms | **797,160 rows, 1,616.873 ms** | 511 rows, 4.438 ms |

**324× rows and 324× time, at depth 12, to defend against something that is not there.**

797,160 is exactly `PrerequisiteDepthTest.walksTo(3000, 12)`. A `path` column makes every
row unique — every walk has its own path — so `union` stops deduplicating and the query
degenerates into `union all`.

**This is the third time in this slice that a column added to the recursive term does it.**
`depth` in §3.2, `depth` again in `R20` §3.3, and `path` here — and `path` is precisely what
the standard advice adds. Following it silently removes the deduplication you already had.

### 3.6 The write-side guard: what it costs, and what it still cannot promise

A `BEFORE INSERT` trigger running a recursive reachability check. It refuses correctly:

```
refused: 23514 -- ERROR: concept_edge 3000 -> 2889 would close a prerequisite cycle
```

| | unguarded | guarded | added |
| --- | --- | --- | --- |
| 1,000 batched inserts | 46 ms | 2,473 ms | **2.33 ms/row** |
| one `insert … select` of 1,000 | 1,974 ms | 4,226 ms | **2.20 ms/row** |

The two ratios are **53.8×** and **2.1×** for the same trigger doing the same work; the
per-row deltas agree to 6%. The set-based arm's baseline is inflated by its own join. **The
ratio is a property of what the guard was added to; the per-row delta is a property of the
guard.**

`COPY` is **미측정**: the PostgreSQL driver is `runtimeOnly` in `api/build.gradle.kts`, so
`CopyManager` is not on the test compile classpath. A row trigger fires per row whatever the
statement shape, which the two rows above establish; the `COPY` number itself was not taken.

**And it is not race-safe.** Two transactions, two edges that are individually fine, neither
able to see the other's uncommitted row. Both commit. The graph has a cycle.

Then the sharpest observation in this report: **re-issue the statement the guard accepted a
moment ago, and it is refused** — `23514`, by the guard, because the other transaction's
edge is now visible.

```
cycle present: yes. re-issuing the accepted statement now gives:
  23514 -- ERROR: concept_edge 3001 -> 3002 would close a prerequisite cycle
```

The same statement, accepted and then refused, with nothing in between except somebody else
committing. That is the whole of what a per-row check can and cannot do about a whole-graph
property, in one observation.

This is `R7` with the difference that decides `ADR-010`. There the fix was a unique
constraint, because **uniqueness is a property of a row**. Acyclicity is not.

## 4. 원인 / Mechanism

**A recursive CTE terminates when its working table comes back empty.** `union all` never
empties it on a cycle. `union` empties it when every row it would produce has already been
produced — which is a statement about *rows*, so it holds only while the recursive term
projects nothing that changes each round. A depth counter changes each round. A path array
changes each round. Both are things people add for good reasons.

**A `BEFORE INSERT` trigger sees the transaction's snapshot**, and a snapshot does not
contain another transaction's uncommitted rows. No isolation level below `SERIALIZABLE`
changes that, and `SERIALIZABLE` converts the race into serialisation failures rather than
into correctness for free.

**An application loop issues statements the database considers unremarkable.** Nothing in
PostgreSQL can distinguish a traversal that will end from one that will not, because from
where it stands they are the same two-row index lookups.

## 5. 처방 / Remedy

The question is not *how do we stop it* but **where**, and `ADR-010` is the decision.

| Option | Effect on a cycle | Cost when there is none | Chosen |
| --- | --- | --- | --- |
| nothing | three deaths, §3 | none | ✘ |
| read-side path array | terminates, bounded | **324× at depth 12** | ✘ |
| read-side node-only `union` | terminates | loses the depth column entirely | ✘ |
| write-side trigger | reduces the window | 2.33 ms/row, **and not race-safe** | ✘ |
| **`V5` — `check (prerequisite_id < concept_id)`** | **unrepresentable** | **a row predicate; no snapshot, no traversal** | **✔** |

`V1`'s comment is correct about acyclicity **in general** and wrong about **this schema**.
The generator already holds something stronger, and its own KDoc says so: *every edge runs
from a lower concept id to a higher one.* If that is true of every row, a prerequisite walk
strictly decreases the id at every hop and cannot return to its start. That is a proof of
acyclicity, and it is a per-row predicate.

**What it costs is not small and `V5`'s comment says so at length**: acyclicity is welded to
the surrogate key, so a new concept — which always gets a higher id — can never become a
prerequisite of an existing one. That is acceptable only because `concept_edge` has exactly
one writer, `seed/`'s loader. `ADR-010` names the condition that lifts it and `ADR-011` owns
the depth bound, which stays for a different reason.

### The uncomfortable part

`R20` chose one statement over the application walk on statement counts — 1 against 138 —
and this report finds the application walk is the arm a cycle cannot kill, because a
`HashSet` deduplicates on node identity and `union` deduplicates on row identity. **The
faster read is the more fragile one**, and neither report is wrong. `V5` is what makes the
choice free: it removes the fragility from the schema instead of paying for it in the query.

## 6. 재계측 / Re-measurement

| Metric | Before `V5` | After `V5` |
| --- | --- | --- |
| a backwards edge, single transaction | accepted | **refused, `ck_concept_edge_forward`** |
| a backwards edge, concurrent transactions | **both accepted, graph cyclic** | **refused, no snapshot involved** |
| cost of the defence, per inserted row | 2.33 ms with the trigger | **not measurable as a delta at this scale** |
| cost of the defence, per read at depth 12 | 1,616.873 ms with a path array | **4.994 ms — the read is unchanged** |
| `closure(top, 12)` on a cyclic graph | 미측정 | **512 concepts, 1 statement** |

That last row is measured **with `V5` lifted**, because with it applied the input cannot
exist. It matters because the depth bound is the only reason it holds — §3.2's identical
query, unbounded, does not return.

## 7. 회귀 게이트 / Regression gate

- `api/src/test/kotlin/net/gseek/proxima/db/BaselineMigrationTest.kt` — the check
  constraints on `concept_edge` are exactly `ck_concept_edge_forward`,
  `ck_concept_edge_no_self`, `ck_concept_edge_weight`, **and the constraint is watched
  refusing a planted backwards edge** rather than watched existing. `R9` §7 is about a gate
  that passes whether or not there is anything to substitute.
- `api/src/test/kotlin/net/gseek/proxima/concept/CycleTraversalTest.kt` — all four arms,
  including the acyclic controls that make each *the cycle killed it* claim falsifiable, and
  the assertion that the shipped read is one statement on a cyclic graph while the same
  query unbounded is not.
- `api/src/test/kotlin/net/gseek/proxima/concept/CycleGuardCostTest.kt` — the write arm's
  per-row cost, the race, and the read arm's cost on a graph with no cycle.
- `seed/src/test/kotlin/net/gseek/proxima/seed/CyclicGeneratorTest.kt` — the injection is
  additive and inert at its default, and Kahn's algorithm cannot order the result.
- `seed/src/test/kotlin/net/gseek/proxima/seed/SeedDigestTest.kt` — unchanged, and green,
  which is what says `Scale.FULL` did not move.

## 8. 남는 위험 / Remaining risk

- **`V5` forbids a curriculum edit that a real system needs.** A new concept can never be
  made a prerequisite of an older one. There is no editing path today, which is why this is a
  decision rather than a defect; the day one is designed, this constraint is the first thing
  it meets, and the migration that lifts it needs `concept.sequence_no` and its own report.
- **`ck_concept_edge_forward` is stronger than acyclicity.** Plenty of legal DAGs violate it
  — any DAG whose topological order disagrees with insertion order. This repository's graph
  does not, by construction, and no other graph has ever been in this schema. That is a
  narrow warrant for a permanent constraint and it is stated rather than implied.
- **The trigger was measured and is not shipped**, so its numbers describe an artefact that
  exists only inside `CycleGuardCostTest`. If `V5` is ever lifted, they are the starting point
  and not the answer — in particular the race in §3.6 is reproduced at two connections, and
  **what it does at 200 is 미측정.**
- **`COPY` with a row trigger is 미측정.** §3.6. The driver is `runtimeOnly`, so the test
  lane cannot reach `CopyManager`. Every figure about bulk write cost here is an `insert`.
- **The `statement_timeout` arms prove non-termination only within 3,000 ms.** A query that
  would have finished in 3,100 ms is recorded here as *did not terminate*. The mechanism says
  three of the four cannot terminate at all; the measurement bounds it at three seconds, and
  those are different claims.
- **Three cycles, all of length 3 to 5, all injected at one place each.** A cycle of length
  200, or a graph that is half cyclic, is 미측정. §3.4 already shows the *position* of a cycle
  changes what a guard costs by 10%; position and length are two axes and only one was walked.
- **What would break the conclusion:** a second writer for `concept_edge`. Every argument in
  §5 rests on there being exactly one, and `ADR-009` closed the equivalent question for the
  recording path by declining an endpoint. If a curriculum editor is ever built, `V5` is not
  a decision that survives it, and the trigger's 2.33 ms/row becomes the live number.
- **This report does not falsify an earlier §8 bullet.** `R7` §8 and `R9` §8 were read for
  bullets about constraints and about ordering; neither says anything this contradicts.

## 9. 배운 것 / What I learned

**스키마의 주석이 틀린 게 아니라, 일반론이었다.**

`V1`은 "acyclicity는 CHECK 제약으로 표현할 수 없다"고 썼다. 일반적인 그래프에 대해서는 완전히
맞는 문장이다. 그런데 이 스키마에서는 틀렸다 — 생성기가 이미 **더 강한 것**을 보장하고 있었고
(`prerequisite_id < concept_id`), 그건 행 하나로 판정되는 술어다. 열하루 동안 나는 그 주석을 읽고
"그럼 방법이 없구나"라고 넘어갔다. **참인 일반론이 참인 특수해를 가린 것**이고, 이 저장소가 잡으려는
결함이 이 저장소의 baseline 안에 있었다.

두 번째로 배운 것: **`union`은 행을 중복 제거하지, 노드를 중복 제거하지 않는다.** 이게 왜
중요하냐면, 사이클 방어를 위해 사람들이 추가하는 바로 그 컬럼들이 방어를 끈다는 뜻이기 때문이다.
depth를 넣으면 꺼지고, path 배열을 넣으면 꺼진다. 그리고 path 배열은 모든 레퍼런스가 권하는
방식이다. 깊이 12에서 2,209행이 797,160행이 됐다 — **사이클이 하나도 없는 그래프에서, 사이클을
막으려고.** 324배다.

세 번째, 대조군을 두 개 뒀는데 그 중 하나가 결론을 뒤집었다. path 배열이 사이클 그래프에서
1,092행을 냈고 비순환 그래프에서도 1,092행이었다. "path guard는 공짜다"라고 쓸 뻔했다. 그런데
출발 concept이 사이클의 머리였고, base term이 path 배열에 출발점을 넣기 때문에 그 back-edge는 첫
시도에 막힌다. **내가 잰 자리가 유일하게 아무 일도 안 일어나는 자리였다.** 꼬리 위쪽에서 재니까
1,202행 대 1,092행이다. `R10`에서 "대조군이 통과한 채로 결론이 틀린다"를 읽었는데, 이번 건 조금
다르다 — 대조군은 옳았고, **측정 지점이 틀렸다.**

마지막으로 트리거. 잘 동작했고, 정확한 메시지를 냈고, 2.33 ms/row였고, 그리고 **자기가 30
밀리초 전에 통과시킨 문장을 거부했다.** 그 한 관측이 R7 전체보다 짧게 같은 말을 한다. 행의 성질은
데이터베이스가 지킬 수 있고, 그래프의 성질은 못 지킨다. 그래서 답은 더 좋은 트리거가 아니라, 그래프
성질을 행 성질로 바꾸는 것이었다.
