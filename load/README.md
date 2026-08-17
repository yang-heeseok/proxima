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

# 2. READ THE VERDICT. k6 exits 0 on a run it has itself declared unpublishable.
grep -q '^OK' steady-state.txt || echo "NOT STEADY STATE — do not cite this run"

# 3. Three times, and three OK verdicts. The report cites the median.
```

### Step 2 is not optional, and `R18` is why

The steady-state check compares the two halves of the measurement window. Until 2026-08-17
it **printed** `DO NOT PUBLISH THIS RUN` and let k6 exit `0` beside it — and it only looked
at one direction, so a run that *degraded* during the window passed in silence. Three of
`R18`'s fifteen measured runs were outside a symmetric band and one of them printed the
banner. **The very first run after the fix failed in the newly-watched direction.**

The enforcement is a file rather than a k6 threshold because a threshold is evaluated over
one metric, and this is a ratio between two that is known only once the run is over. That
is a limit of the tool, written down here rather than left as a gap. **The runner is the
enforcement, so the runner has to read the file.**

Output goes to `load/out/`, which is gitignored — the numbers live in the reports, where a
reader looks for them. A raw k6 summary in the tree would be a number without its
environment block, which `PUB-4` does not allow.

## Files

| File | Measures |
| --- | --- |
| `recommendations.js` | `GET /api/v1/learners/{id}/recommendations` under concurrency |
| *(to come)* `attempts-concurrent.js` | concurrent writes to one learner's mastery row |
| *(to come)* `attempts-paging.js` | deep paging, offset against keyset |
