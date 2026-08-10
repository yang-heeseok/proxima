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
# 1. The application must already be warm-started and the database ANALYZEd.
#    A run against stale planner statistics measures the statistics.
k6 run --env BASE_URL=http://localhost:8080 --env VUS=200 recommendations.js

# 2. Three times. The report cites the median.
```

Output goes to `load/out/`, which is gitignored — the numbers live in the reports, where a
reader looks for them. A raw k6 summary in the tree would be a number without its
environment block, which `PUB-4` does not allow.

## Files

| File | Measures |
| --- | --- |
| `recommendations.js` | `GET /api/v1/learners/{id}/recommendations` under concurrency |
| *(to come)* `attempts-concurrent.js` | concurrent writes to one learner's mastery row |
| *(to come)* `attempts-paging.js` | deep paging, offset against keyset |
