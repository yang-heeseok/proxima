# load/

k6 scenarios. Every number in `docs/reports/` that mentions concurrency came from something
in this directory.

## What this directory does not own

| Question | Owner |
| --- | --- |
| What makes a number citable | `docs/explanation/measurement-discipline.md` |
| The numbers themselves | `docs/reports/` |

---

## The shape every scenario has

```
  30s ramp + hold   →   DISCARDED   (warm-up: JIT, class loading, pool fill, page cache)
  3min at target    →   MEASURED
  ×3 runs           →   median reported
```

The warm-up is not a courtesy. A JVM's first seconds are interpreted, then C1, then C2, and
the difference is large enough to reverse a ranking. `docs/explanation/measurement-discipline.md`
says why at length; the thresholds below encode it so a run cannot quietly skip it.

## Running

```bash
# The application must already be warm-started and the database ANALYZEd.
# A run against stale planner statistics measures the statistics.
PROXIMA_TOKEN_SECRET=... ./run.sh recommendations.js -- --env VUS=200

# Three times, and three passes. The report cites the median.
```

**`run.sh` is the way. `k6 run` on its own is not.** The wrapper exits non-zero when the
scenario has declared its own run unpublishable, and non-zero when no verdict was written at
all — k6 does neither.

### Why the wrapper exists — `OPEN-8`, closed by `ADR-008`

The steady-state check compares the two halves of the measurement window. Until 2026-08-17 it
**printed** `DO NOT PUBLISH THIS RUN` and let k6 exit `0` beside it, and it only looked at one
direction, so a run that *degraded* during the window passed in silence. Three of `R18`'s
fifteen measured runs were outside a symmetric band, and one of them printed the banner and
went into a published median anyway. **The very first run after the fix failed in the
newly-watched direction.**

`R18` fixed the check and left the enforcement as a `grep` in this file for the operator to
remember. `R17` is this repository's report on what becomes of a rule enforced that way —
three failures in seven days, every one caught by a person. So the enforcement moved into a
wrapper.

**It does not remove the person.** Someone still has to type `./run.sh`. What it removes is the
second step: the failure is loud where it happens rather than at report-writing time, which is
where `R18` actually found it — by re-reading a log two hours later. The alternatives and
their costs are in `ADR-008`.

Output goes to `load/out/`, which is gitignored — the numbers live in the reports, where a
reader looks for them. A raw k6 summary in the tree would be a number without its
environment block, which `PUB-4` does not allow. `steady-state.txt` is gitignored for the
same reason and is a run artefact, not a record.

## Files

| File | Measures |
| --- | --- |
| `recommendations.js` | `GET /api/v1/learners/{id}/recommendations` under concurrency |
| `run.sh` | not a scenario — the wrapper every scenario is run through. `ADR-008` |
| `ops/` | **not scenarios, and not run through `run.sh`.** Container limits, instance counts, health checks and shutdown — `R23` and `R24`. Their results are exit codes and connection counts, so there is no measurement window for the wrapper to have an opinion about. `ops/README.md` |
| *(to come)* `attempts-concurrent.js` | concurrent writes to one learner's mastery row |
| *(to come)* `attempts-paging.js` | deep paging, offset against keyset |
