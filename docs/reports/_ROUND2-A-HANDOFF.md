# Round 2, slice A — handoff

> Transient integration note. It carries no `Updated` line on purpose: the orchestrator edits
> around it and a date on it goes stale the moment they do.

## 1. What this slice opened

The Round 2 plan said *put a prerequisite graph into the domain*. **The graph was already
there** — `V1__baseline.sql` has created `concept_edge` since the first commit, the generator
emits 8,994 edges, and `GeneratorTest` asserts the DAG property with Kahn's algorithm. What
did not exist was a read of it deeper than one level: `RecommendationQueries` consults it once,
as a `NOT EXISTS` over direct prerequisites, and **at depth 1 every defect in this slice has
the same answer as a healthy graph.** So the slice is *the graph is read at depth 1 — open
depth `d`, and the defects that only appear with depth*: an application-side walk that is N+1
by depth rather than by row, a one-word difference between `union` and `union all` that is
26.8× at depth 9 and invisible in review, a unique constraint that indexes `concept_edge` in
the direction nobody reads, three different deaths on a cyclic graph of which two are
indistinguishable from a healthy one, and a page that is internally perfect while 90% of the
answer cannot be reached by any page. Two migrations landed (`V4`, `V5`), and `V5` corrects a
sentence `V1` has carried since day one.

## 2. Reports and ADRs

| # | Title | The one-line finding |
| --- | --- | --- |
| `R20` | The graph was read one level deep, and the second level costs 138 statements | A depth-6 closure costs **138 statements** in an application walk and **1** as one recursive statement — and `union` against `union all` is **26.8× working rows at depth 9**, one word, same answer, every test green either way |
| `R21` | A cycle kills three ways, and the two that reach the database look identical | `union all` times out with `57014` **whether or not there is a cycle**; `union` protects only until a `depth` column is projected; the application walk never reports at all and holds a connection — and the fix is a **row predicate**, because `V1`'s *a CHECK cannot express acyclicity* is true in general and false about this schema |
| `R22` | The page stopped meaning anything when the graph opened, and every page was perfect | `limit` inside the recursion and after it share **1 item of 20**, **182 of 202 items** are unreachable by any page, and the domain's ordering loses **5 of 20** rows off page 1 when `maxDepth` moves by one |
| `ADR-010` | A cycle is refused by a row predicate, not by a guard | A trigger costs **2.33 ms/row** and is not race-safe (the same statement is accepted, then refused, after another transaction commits); a read-side path array costs **324× at depth 12 on a graph with no cycle**; `check (prerequisite_id < concept_id)` costs a `bigint` comparison and needs no snapshot |
| `ADR-011` | The closure is computed on read, and the depth bound is liveness rather than domain | A materialised closure is a cache and `ADR-005` already refused those with numbers; `maxDepth` gets **no default**, because before `V5` it was the only reason the shipped read survived a cycle at all |

## 3. New 미측정 items

| What | What would be needed |
| --- | --- |
| The transitive closure read **under load** — every figure in `R20`/`R21` is a single-query median of three at concurrency 1 | a k6 scenario; and `R16` §3.4's caution applies, since four requests in five on this dataset answer with an empty list |
| `COPY` with a row trigger (`ADR-010`, `R21` §3.6) | the PostgreSQL driver is `runtimeOnly` in `api/build.gradle.kts`, so `CopyManager` is not on the test compile classpath. An edit to that file, which slice A was not entitled to make |
| The write-side trigger's race **at real concurrency** — reproduced at two connections | a concurrency harness on the write path; `ADR-009` notes the write path under HTTP load is already 미측정 |
| A materialised `concept_closure` table's size, rewrite cost and read latency | `ADR-011` declines it on `ADR-005`'s reasoning and a write rate of zero, **not** on a measurement of the thing declined |
| Keyset paging over `(depth, difficulty, id)` under a changing `maxDepth` | `R22` §8. The mechanism says the tuple only grows at the end; mechanism is not a measurement |
| Whether re-measuring `R3`'s covering-index rejection **after a `VACUUM`** changes it | `R20` §3.6 found the load path never runs `VACUUM`, so every index-only scan here pays heap fetches. **This needs a judgement about the load path, not only work — it belongs in `docs/decisions/open.md`, which slice C owns** |
| Closure cost from any start other than the last concept | `R20` §8. Concept 3000 is the deepest and therefore most expensive place to stand |
| Counts at the shipped item scale — `R22` uses one item per concept, the real dataset has 100,000 items over 3,000 concepts | `R22` §8. The ratios are what that report claims; the absolute counts scale with `item_concept`'s fan-out |

## 4. Rows for the integrator to paste

### `docs/roadmap.md` — three rows for the **After the traps** table

```markdown
| **R22** | **The page stopped meaning anything when the graph opened.** `R20` opened a transitive read of `concept_edge`; *the next 20* over an expanded closure is not one thing | **done** — `R22`, red `39f69ca`, **no green commit and §5 says why**: there is no fix, there is a choice, and `ADR-011` makes it. `limit` inside the recursion and `limit` after it share **1 item of 20**; page 1 is full, pages 2–5 are empty, and **182 of the answer's 202 items** can never be paged to, with no row repeated, none invented and nothing erroring. The domain's ordering — easiest first — loses **5 of 20** rows off page 1 when `maxDepth` moves from 6 to 7; the stable ordering leads with `depth` and is no longer *easiest*. **The same defect was then found by accident in this repository's own suite**: `CollectionPagingTest` pages `LearnerPageQueries`, which has no `order by`, and it had been green for eleven days because the plan happened not to move |
| **R21** | **A cycle kills three ways, and two of them look identical.** `V1`'s table comment says acyclicity cannot be a `CHECK` constraint and is checked by a test — a test that asserts *the generator* makes no cycle, while `concept_edge` accepts a backwards edge without complaint | **done** — `R21`, red `1fffc79` / green `39f69ca` / migration `V5__concept_edge_forward_only.sql`. `union all` unbounded gives `57014` **on the acyclic graph too**, so the symptom names nothing; `union` — the standard defence — stops defending the moment a `depth` column is projected, and the acyclic arm it does survive returns **303,948 rows** for 3,000 concepts; the application walk never reaches the database at all and holds one pooled connection while looping. A path array terminates the cycle and costs **324× at depth 12 on a graph with no cycle**, because a path column defeats `union`'s row deduplication — the third time in this slice a column added for a good reason does that. **`V1`'s comment is true in general and false about this schema**: the generator already guarantees a stronger, per-row property, and `V5` states it |
| **R20** | **The graph was read one level deep.** `concept_edge` has been in `V1` since the first commit and `RecommendationQueries` consults it exactly once, as a `NOT EXISTS`. At depth 1, walks and reachable concepts are the same number | **done** — `R20`, red `b97cd6b` / green `24b959a` / migration `V4__concept_edge_by_concept.sql`. A depth-6 closure costs **138 statements** as an application walk, **6** batched by level, and **1** as one recursive statement. **`union` against `union all` is 26.8× working rows at depth 9** — one word, both one statement, both correct, every functional test green either way. `uk_concept_edge` gave `concept_edge` a B-tree over both columns since day one and it leads on the wrong one: **98,937 rows fed through the recursive term become 5,424**, 10.526 ms → 3.506 ms. The covering pair was measured and rejected — 85% more space, not faster, because `Heap Fetches: 5,424` and **the load path never runs `VACUUM`**. And the index's advantage *falls* with depth, 500× → 82× → 18.2×: an index is not a substitute for a depth bound |
```

### `README.md` — three rows for the **Results** table

```markdown
| A depth-6 prerequisite closure — statements per read — *2026-08-21* | 138 | **1, at any depth** | [`R20`](docs/reports/R20-the-graph-was-read-one-level-deep.md) |
| The same closure at depth 12 — rows fed through `concept_edge` — *2026-08-21* | 98,937 | **5,424**, and 10.526 ms → 3.506 ms | [`R20`](docs/reports/R20-the-graph-was-read-one-level-deep.md) |
| A prerequisite cycle, against four forms of the same read — *2026-08-21* | **three deaths, two reporting `57014` whether or not a cycle exists** | **unrepresentable** — one `CHECK` `V1` said could not exist | [`R21`](docs/reports/R21-a-cycle-kills-three-ways-and-two-look-identical.md) |
| The standard cycle guard, on a graph with no cycle — *2026-08-21* | 2,209 rows / 4.994 ms at depth 12 | **797,160 rows / 1,616.873 ms — 324× to defend against nothing** | [`R21`](docs/reports/R21-a-cycle-kills-three-ways-and-two-look-identical.md) |
| "The next 20" over an expanded closure — *2026-08-21* | **1 of 20 items shared between two correct queries; 182 of 202 unreachable by any page** | **no paged API, and `ADR-011` says why** | [`R22`](docs/reports/R22-the-page-stopped-meaning-anything-when-the-graph-opened.md) |
```

> **The integrator must paste the roadmap rows.** `docs/roadmap.md` is outside slice A's
> frozen contract and was not edited. `docs-consistency.yml` check 3 — *every report has a
> roadmap row* — **fails on the merge commit** until they land. Verified by running that
> check's own logic on this branch: `missing from roadmap: R20 R21 R22`.

## 5. Conditions my numbers were taken under

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
                   8,994 edges. NOT the loaded 3,963,719-row dataset; see below
  Load           : none. Counts, plans, row counts, SQLSTATEs, and single-query medians
  Repetitions    : 3 runs, median, for every duration. Counts and row counts are exact
```

**What makes another slice's numbers non-comparable with mine, stated so nobody has to
discover it:**

1. **No number in slice A is a load number.** Everything is at concurrency 1, in a
   Testcontainers container, with no k6 involved. **Do not put any figure of mine in the same
   sentence as a p99.** Rule 3 forbids the comparison and rule 9 forbids the shortcut.
2. **My dataset is `concept_edge` only, rebuilt inside the container by `SeedConceptGraph`.**
   It is the shipped graph — same generator loop, same RNG stream, checked against the seed
   module's measured figures before every run — but `attempt`, `learner`, `mastery` and `item`
   are **not** the loaded 3,963,719-row dataset. A slice quoting a `mastery` or `attempt`
   figure is standing on different data.
3. **`ExpandedPagingTest` installs 3,000 items and 3,000 `item_concept` rows** into the shared
   test container for the life of that class, and removes them afterwards. It analyses **four
   named tables** and deliberately not the database — the first version ran a bare `analyze`
   and moved `CollectionPagingTest`'s arbitrary page. **If another slice adds a fixture that
   analyses globally, expect the same class of failure somewhere unrelated.**
4. **`CycleTraversalTest` and `CycleGuardCostTest` drop `ck_concept_edge_forward` and put it
   back.** They restore from `pg_constraint`'s own definition and restore in a `finally`. If
   `BaselineMigrationTest` ever fails saying that constraint is missing, one of those two
   aborted between the drop and the restore, and **that is the first thing to check** rather
   than `V5`.
5. **Durations here drift by up to 19% between sessions on this machine.** Depth-3 medians of
   three, two sessions ~40 minutes apart: 1.807 ms and 1.973 ms unindexed, 0.203 ms and 0.170
   ms indexed. `R18`'s drift control found 1.27× on identical configuration seventy minutes
   apart; my figures are in the same territory and **any ratio of mine under about 1.3× is
   inside the noise.** Every ratio I claim is far outside it, and the categorical numbers —
   statement counts, rows fed, row counts, SQLSTATEs — carry no drift at all.
6. **117 tests, 45 classes, 0 failures**, `:api:test` and `:seed:test` **executed rather than
   restored from cache**. If another slice reports a test total, ours will need re-counting
   after the merge rather than adding — I did not measure the pre-merge baseline on this
   branch.

## 6. Escalations

- **`docs/roadmap.md` needs three rows and is outside my contract.** §4 has them verbatim.
  This is the only thing standing between the merged branch and a green `docs consistency`.
- **`README.md`'s Results table and its `77 tests / 36 classes` status line are outside my
  contract.** §4 has the Results rows. The status line now says a number that is not true of
  the merged tree; I did not edit it and I did not measure what it should say for the *whole*
  merge, only for this branch.
- **`docs/decisions/open.md` is slice C's.** One item of mine belongs there rather than in a
  §8 bullet: whether `R3`'s covering-index rejection should be re-measured after a `VACUUM`,
  given that `seed/`'s load path never runs one. It needs a judgement about the load path.
  `R20` §8 records it as remaining risk **and says it belongs in `open.md`**, which is the
  `OPEN-6` precedent `R19` established.
- **`api/build.gradle.kts` blocks one measurement.** The PostgreSQL driver is `runtimeOnly`,
  so no test can reach `CopyManager` and the `COPY`-with-trigger figure stays 미측정. I did not
  edit it. If a later round wants that number, moving the driver to `testImplementation` is
  the change, and it is a change to a file `R9`'s H2 comment shows is load-bearing.
- **`V5` reserves nothing further.** Both reserved migration numbers are used. A sixth
  migration needs a number outside slice A's range.
