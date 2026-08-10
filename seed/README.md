# seed

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Generator and loader work at full scale. Verified 2026-08-10 — 3,963,719 rows
generated and loaded.

This module owns **how the dataset is produced and how it gets into PostgreSQL.**

## What this document does not own

| Question | Owner |
| --- | --- |
| What the data means, and the row counts | `docs/explanation/domain-model.md` |
| Why no dataset is committed | `docs/decisions/publication-readiness.md` — `PUB-7` |
| What makes a number taken against this data citable | `docs/explanation/measurement-discipline.md` |
| The schema it loads into | `api/src/main/resources/db/migration/` |

---

## The claim this module has to earn

**No dataset is committed here, so `seed/` is the only reason any number in this repository
can be checked by someone else.** If the generator is not reproducible, the reports are
anecdotes about a machine.

That is why determinism is a test rather than an intention, and why the seed value
(`20260810`) is a constant in the source rather than a command-line default.

## Reproducing the dataset

Everything runs inside WSL2. **Docker Engine here is native to WSL2 rather than Docker
Desktop, so Windows cannot reach the daemon at all** — a build started from Windows fails
at the first container.

```bash
export JAVA_HOME=$(echo ~/.jdks/jdk-21*)

# 1. the generator
./gradlew :seed:installDist

# 2. write the files. seed-out/ is gitignored and must stay that way (PUB-7)
./seed/build/install/seed/bin/seed generate --out seed-out --scale full

# 3. a database, and the schema
docker run -d --name proxima-db \
  -e POSTGRES_PASSWORD=... -e POSTGRES_USER=postgres -e POSTGRES_DB=proxima \
  -p 55432:5432 postgres:16-alpine

PROXIMA_DB_URL=jdbc:postgresql://localhost:55432/proxima \
PROXIMA_DB_USER=postgres PROXIMA_DB_PASSWORD=... \
  ./gradlew :api:bootRun          # Flyway applies V1, then stop it

# 4. the load
./seed/build/install/seed/bin/seed load \
  --url jdbc:postgresql://localhost:55432/proxima \
  --user postgres --password ... --out seed-out
```

`analyze` and `counts` are separate commands on the same binary.

## What it cost, measured

Taken on the machine in `docs/explanation/measurement-discipline.md`, 2026-08-10. One run
each — these are operational timings, not a published result, and a report that quotes them
takes them again under its own conditions.

| Step | Time |
| --- | --- |
| `generate`, 3,963,719 rows across 7 files (181 MB) | **5.5 s** |
| `load` — `COPY` of all 7 tables | **141.6 s** |
| of which `attempt`, 3,000,000 rows | 111.4 s |
| `analyze` | 0.4 s |

The files are written to `/mnt/c`, a Windows filesystem seen through WSL2, and read back
from it during the load. That is the slowest available choice and it was made on purpose:
`seed-out/` lives beside the repository so that the gitignore rule protecting it is the
same rule a reader sees. A WSL2-native path would be faster and would put the dataset
somewhere no guard is watching.

## Why `analyze` is not part of `load`

`T4` measures a query against the statistics a bulk `COPY` leaves behind, **before**
`ANALYZE` runs, because that gap is a finding rather than a mistake. A loader that helpfully
analysed at the end would destroy the state the report is about — and would do it silently,
leaving a report that could no longer be reproduced from its own instructions.

The window is real but short: autovacuum will eventually analyse the tables on its own. The
state is verifiable while it lasts —

```sql
select relname, last_analyze, last_autoanalyze, analyze_count from pg_stat_user_tables;
select relname, reltuples, relpages from pg_class where relname = 'attempt';
```

— where `reltuples = -1` and `relpages = 0` is PostgreSQL's sentinel for *never analysed*,
and is what a genuinely stale table looks like.

## What the generator guarantees, and what it does not

**Guaranteed, and tested in `GeneratorTest`:**

- byte-identical output for the same seed value, on any JDK — `java.util.Random` has its
  algorithm fixed by specification, which `kotlin.random.Random` does not
- `concept_edge` is acyclic, by construction rather than by rejection: every edge runs from
  a lower concept id to a higher one
- `mastery` holds one row per `(learner_id, concept_id)`, which `V1` does not yet enforce
  and deliberately so — see `ADR-002`
- no generated identifier can be mistaken for a real one — `learner-000001`

**Not guaranteed:**

- **The data is not realistic, only structured.** Correctness is drawn from a logistic
  function of a stable per-learner ability and the item's difficulty, which is enough to
  make the recommendation query select different items for different learners. It is not a
  model of how anyone learns, and no conclusion about learning should be drawn from it.
- **Distributions are uniform where a real system's are not.** Every learner has exactly
  the same number of attempts, spread evenly across 18 months. Real usage is bursty and
  heavy-tailed, and a heavy tail is exactly what breaks pagination and caching. Any report
  whose finding depends on skew has to say that this dataset has none.
