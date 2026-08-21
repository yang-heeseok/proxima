# load/ops/

**The boundary harness.** Two or three application containers and one database container,
with the container limits as the variable.

> **Created**: 2026-08-21
> **Updated**: 2026-08-21

## What this directory does not own

| Question | Owner |
| --- | --- |
| What makes a number citable | `docs/explanation/measurement-discipline.md` |
| The numbers themselves | `docs/reports/R23-*.md`, `docs/reports/R24-*.md` |
| Latency under concurrency | `load/recommendations.js`, run through `load/run.sh` |

---

## Why this is beside `load/` and not inside it

Everything else in `load/` measures **latency under concurrency against one process with no
limits**. `R2`, `R4`, `R16` and `R18` are all that shape, and `ADR-004`'s audit ends by naming
what it leaves unanswered: nothing in this repository had ever varied the number of processes
or the size of the box one runs in.

The four questions here are not percentiles:

| Question | Report |
| --- | --- |
| What heap a JVM gets when the container is limited, and who refuses when it is exceeded | `R23` §3.1, §3.2 |
| What `pool size × instance count` does to the database's `max_connections` | `R24` §3.1 |
| What `/actuator/health` says while the database is gone, and what the probes say | `R24` §3.2 |
| What a deployment does to a request that is in flight on the write path | `R24` §3.3 |

**So none of them goes through `load/run.sh`.** That wrapper exists to refuse a run whose
steady-state verdict says `DO NOT PUBLISH`, and a run whose result is an exit code has no
measurement window to be steady over. Putting these behind it would make the wrapper mean two
different things — and `ADR-008` chose it precisely because it means one.

## Requirements

- **Docker sharing a filesystem namespace with the shell.** The application container is
  `ubuntu:24.04` with the toolchain JDK and the boot jar **bind-mounted in**, not an image
  this repository builds. `eclipse-temurin` has no `21.0.12` tag on Docker Hub — queried
  2026-08-21, the list stops at `21.0.11_10` — so a Dockerfile would put a *different JVM
  build* inside the container from the one every other number here was taken with, and
  `measurement-discipline.md` rule 3 is about exactly that. The engine here is native inside
  WSL2, so the bind resolves; on Docker Desktop nothing starts.
- `JAVA_HOME` (or `PROXIMA_JDK`) pointing at the toolchain JDK.
- `openssl` and `curl`, for minting a token and driving requests.

## Running

```bash
export JAVA_HOME=~/.jdks/jdk-21.0.12+8

./load/ops/harness.sh env      # the environment block every number below belongs to
./load/ops/harness.sh build    # boot jar, staged where the daemon can bind it

./load/ops/trap-pool.sh        # R24 §3.1 -- pool x instances against max_connections
./load/ops/trap-health.sh      # R24 §3.2 -- the health check and the database
./load/ops/trap-shutdown.sh    # R24 §3.3 -- a deployment and a request in flight

./load/ops/harness.sh down     # remove every container and the network
```

Each trap script prints the environment block first and tears the fleet down last. They are
independent: any one of them can be run alone.

## The fixture is not the seeded dataset, and that is a limit on every number here

`seed/` produces 3,963,719 rows from seed value 20260810, and every latency figure in this
repository is against those. `harness.sh fixture` inserts **one** learner, concept, item and
mastery row.

That is deliberate and it is a trade. Nothing in this directory measures latency, and the
three questions above are about exit codes, connection counts and HTTP statuses — none of
which the row count moves. What it does cost is stated rather than hidden: **a read against
this fixture returns an empty list in single-digit milliseconds**, so it needs almost no
connections and cannot exercise a pool. That is why `trap-pool.sh`'s decisive arm drives
**writes** at one contended row instead, and why no number produced here may be compared with
one from `load/recommendations.js`.

## Files

| File | What it is |
| --- | --- |
| `harness.sh` | the environment: build, up, down, fixture, token, connection counts. Sourced by the others |
| `trap-pool.sh` | `R24` §3.1 — five arms and a drift control |
| `trap-health.sh` | `R24` §3.2 — the three health URLs, with the database up and gone |
| `trap-shutdown.sh` | `R24` §3.3 — `SIGTERM` and `SIGKILL` against a batch in flight |
