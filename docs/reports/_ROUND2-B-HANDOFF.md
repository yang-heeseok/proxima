# Round 2, slice B — handoff

> Transient integration note. It deliberately carries no *last-updated* date line: it goes stale
> the moment the integrator edits around it, and a date on it would be a claim nobody maintains.

Branch `round2/boundary`, base `a417ce3`.

---

## 1. What this slice opened

**Every number this repository had ever taken came from one process, on a machine with no
edges, and nothing said so.** `ADR-004` closed by naming the hole one level down — whether a
report's figures were all taken on the same day — and left the larger one unremarked: no
measurement here had ever run inside a memory limit, and none had ever involved a second
instance. This slice put the application in a 512 MB container and then put two more beside it,
and measured four things at that boundary: what heap a container-aware JVM actually takes, what
`pool size × instance count` does to a database's `max_connections`, what `/actuator/health`
says while the database is gone, and what a deployment does to a request in flight on the write
path. Three of the four do not reproduce in the form the plan described, and in each case what
holds them shut is a **framework or library default** rather than anything this repository
wrote — so the slice's fourth deliverable, beside the two reports and the two ADRs, is a gate
that goes red when one of those defaults moves.

---

## 2. Reports and ADRs

| # | Title | The finding, in one line |
| --- | --- | --- |
| `R23` | The heap is a quarter of a number nobody wrote down | A 512 MB container gives the JVM **134217728 bytes**, exactly `MaxRAMPercentage` of the cgroup — and a heap flag does not change how much memory there is, it moves the refusal from the JVM (`OutOfMemoryError`, exit 1, container alive) to the kernel (`SIGKILL`, exit 137, no stack trace and no log line) |
| `R24` | Three instances, and the database is the only thing that refuses | Past `max_connections` the **database** refuses 28 times, the application logs nothing, every HTTP request answers 200, and the first casualty is the operator's own `psql`; both halves of the health trap are live at once on two different URLs; a deployment cuts nothing on Boot 4.1.0's default and 1837 of 4000 recordings on the previous one, **and never tears a transaction in any arm** |
| `ADR-012` | The health-check boundary is measured and not shipped | Both halves of the remedy have to be chosen together against a probe interval this repository does not have, so the measurement ships and the setting does not — and the gate asserts the current state **including the part that is a defect** |
| `ADR-013` | One endpoint, bought by one measurement | `ADR-009` is superseded on the condition it named itself; one authorised `POST`, and `R14` §8's unchosen status is answered `200`-with-per-item-outcome and argued against `207` and `4xx` |

---

## 3. New 미측정 items

**Carried into `R23` §8 and `R24` §8. Repeated here as a checklist for whoever schedules Round 3.**

| Item | What would be needed |
| --- | --- |
| **CPU limits, entirely** | `cpus` is unset on every container in both reports, so `ActiveProcessorCount` is the host's 8 in all three instances at once. GC threads, the common `ForkJoinPool` and Tomcat's sizing all derive from it. **This is the largest single gap in the environment block** and is one `--cpus` flag away |
| What heap **this application** needs | `R23` measures a JVM, not proxima. The real jar started and served in 512 MB, which is an existence proof, not headroom. A load run with `-Xlog:gc` inside the limit |
| Swap left at Docker's default | `R23` disabled it to make the limit observable. Most production containers do not, and the `-Xmx512m` arm would probably have survived by paging — invisibly. A latency measurement under `memory-swap` unset |
| The load at which `R24` §3.1 becomes visible | 120 of 120 requests answered 200 with the fleet 80 connections over the ceiling. Arm E tried writes and found the contended row's lock binding before the pool. A workload whose `Cm` is genuinely > 1 |
| Why the pool split is unequal | 53/32/15, 48/32/20, 59/25/16, 60/19/21 across four runs. The inequality reproduces; **nothing explains the distribution** |
| Write-path **latency** under HTTP | `ADR-013` narrows `ADR-009`'s 미측정 to shutdown only. A k6 scenario — `load/README.md` still lists `attempts-concurrent.js` as *(to come)* |
| The probe interval at which the readiness storm begins | `R24` §3.2 fired 210 at once because that is one more than `server.tomcat.threads.max`. A steady poll is the realistic shape |
| What `connection-timeout=2000` costs a real request | Measured against a database that was **gone**, where it is a straight improvement. Against a saturated pool it fails requests that would have succeeded |
| Maximum request body | 8000 recordings in 832001 bytes worked. Nothing limits or rejects an oversized batch |
| Kubernetes' `terminationGracePeriodSeconds` | `R24` §3.3 arm E is about **two independent timers**, not about the value 10. There is no Kubernetes here, deliberately |
| Every earlier report's PostgreSQL digest | `postgres:16-alpine` moved mid-slice: `sha256:cf78e766…`, server **16.15**, against `measurement-discipline.md`'s `sha256:57c72fd2…`, server **16.14**. `R24`'s block records what it ran on; **no earlier report was re-baselined** |

**One procedural debt, and it is deliberate rather than forgotten.** `R24` §8 falsifies
`R18` §8's *"`max_connections=100` was never reached but was never far… how close it came is
미측정"* — it was reached, 28 times. `_TEMPLATE.md` §8 requires that annotation **beside the
sentence in `R18`**, and `R18` was outside this work's file contract, so it is not there.
`R19` §3.4 measured what happens when a falsification lives only in the new report: twelve
bullets gone stale, three saying so nowhere. **This is knowingly the fourth, and discharging it
is a one-line edit to `docs/reports/R18-the-pool-was-not-the-explanation.md` §8.**

---

## 4. Rows for the integrator to paste

### `docs/roadmap.md` — *After the traps* table

**Required, not cosmetic: `docs-consistency.yml` check 3 fails on this branch until both rows
exist.** Verified locally — `CHECK 3 FAIL: R23 R24`. Everything else in that workflow passes.

```markdown
| **R24** | **Three instances, and the database is the only thing that refuses.** `R2` and `R18` both sized a connection pool on **one** instance; `R18` §8 recorded how close `max_connections` came as 미측정, because measuring it meant querying inside a measurement window | **done** — `R24`, no red/green for §3.1 and §3.2: one jar, one session, five arms and a drift control. **Past the ceiling the DATABASE refuses — 28 `FATAL: sorry, too many clients already` — and the application logs nothing, every one of 120 requests answering `200`.** The instance that starts **last** starves (60/19/21, and the split is a race), and the first connection refused is the operator's own `psql`, so the person arriving to diagnose it is the first casualty. `superuser_reserved_connections=3` bought nothing: the image makes the application a **superuser**. Both halves of the health trap are live at once on two URLs — readiness answers `200` in 3 ms while every request `500`s, and `/actuator/health` answers `503` in **30.013 s**, which at 210 concurrent probes starves the instance completely. A deployment cuts **nothing** on Boot 4.1.0's `graceful` default and **1837 of 4000** recordings on the previous one — **and no arm ever tore a transaction**, `attempt` and `mastery.attempts_count` agreeing under `SIGKILL` at 657 of 4000. Arm E: **exit 137 from a `docker stop`**, because Spring waits 30 s and docker waits 10 |
| **R23** | **The heap is a quarter of a number nobody wrote down.** `measurement-discipline.md` corrected `-Xmx512m` out of its environment block and left the belief underneath: that a 512 MB container is a 512 MB heap | **done** — `R23`, red `4e8b117` / green `c4d6578`. **134217728 bytes — exactly a quarter**, at 512m, 1g and 2g alike, with `-XX:-UseContainerSupport` returning the host's 4129292288 as the control that stops the claim being satisfiable by a JVM ignoring the cgroup. The classic trap does **not** reproduce; `UseContainerSupport=true` and `MaxRAMPercentage=25` hold it shut. What is still a trap is one flag wide: **a heap flag does not change how much memory there is, it changes who tells you** — the ergonomic ceiling throws `OutOfMemoryError` and exits `1` with the container alive, `-Xmx512m` at the same limit is `SIGKILL`, exit `137`, `oom_kill +1`, no stack trace and no log line |
```

### `README.md` — Results table

```markdown
| A 512 MB container's JVM heap — *2026-08-21* | **536870912, asserted by nothing** | **134217728, measured** — and the flag that "fixes" it moves the refusal from the JVM to the kernel | [`R23`](docs/reports/R23-the-heap-is-a-quarter-of-a-number-nobody-wrote-down.md) |
| 3 instances × pool 60 against `max_connections=100` — *2026-08-21* | **28 database refusals, 0 application errors, 120/120 `200`** | **3 × 25: 0 refusals**, and a gate that fails on the arithmetic | [`R24`](docs/reports/R24-three-instances-and-the-database-is-the-only-thing-that-refuses.md) |
| A deployment against 4,000 recordings in flight — *2026-08-21* | **2,163 landed, empty reply** (`server.shutdown=immediate`) | **4,000 landed, every outcome returned** — and no arm ever tore a transaction | [`R24`](docs/reports/R24-three-instances-and-the-database-is-the-only-thing-that-refuses.md) |
```

---

## 5. Conditions my numbers were taken under

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8, bind-mounted into every container from the toolchain
  Base image     : ubuntu:24.04
                   sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517
  PostgreSQL     : postgres:16-alpine — server 16.15
                   sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
                   max_connections=100, superuser_reserved_connections=3, defaults, UNCHANGED
  Container      : memory=512m memory-swap=512m cpus=<unset>  PER APPLICATION INSTANCE
  Instances      : 1, 2 or 3 — the variable in R24 §3.1
  Heap           : ergonomic, no -Xmx. 134217728 at 512m, measured
  Connection pool: HikariCP 7.0.2, maximum-pool-size 10 / 25 / 60, connection-timeout 30000
  App            : ONE jar, sha256 b8a1aac402b647a94f08b848b42674cf2a2688b596bf1e00bd670fa82a11ec81
  Dataset        : NOT the seeded 3,963,719 rows — one learner, concept, item, mastery row
  Repetitions    : 1 per arm, with a drift control re-running arm A last
  Session        : 2026-08-21, one WSL invocation per trap script
```

### What I changed in the runtime environment

**Read this before comparing any slice-A or slice-C number with any slice-B number.**

| Change | Where | Does it affect A or C? |
| --- | --- | --- |
| **`application.yml` — not touched at all** | — | **No.** I own the file and did not edit it. `open-in-view`, the management surface, `recording.*` and `security.*` are exactly as at `a417ce3` |
| **`TestcontainersConfiguration.kt` — not touched at all** | — | **No.** The one shared-file hazard the contract warned about did not materialise. `ContainerHeapErgonomicsTest` needs a memory-limited container and creates its own `GenericContainer` locally rather than mutating the shared one, which is what the contract asked for |
| **`application-test.yml` — not touched** | — | **No** |
| **New main-source class** `RecordingController` | `api/src/main/kotlin/.../recording/` | **One new bean and one new HTTP mapping in every Spring context.** Any test counting beans, mappings, or handler methods sees one more. `ArchUnit` rules see one more request handler — `AuthorisationRules` passes because it authorises |
| **New tests** `ContainerHeapErgonomicsTest`, `DeploymentBoundaryGateTest`, `RecordingEndpointTest` | `api/src/test/kotlin/.../ops/`, `.../recording/` | **`:api:test` gets longer.** The heap test starts three `ubuntu:24.04` containers with memory limits; the other two share the existing Spring context (their annotations match `AuthorisationGateTest`'s deliberately). **If A or C quote a `:api:test` wall-clock figure, mine is not comparable with theirs** |
| **`load-harness.yml`** — the executable-bit check now loops over `load/**/*.sh` instead of naming `load/run.sh`, plus a `bash -n` step | `.github/workflows/` | **Only if A or C add a `load/` script.** It would now have to be committed `100755` |
| **`measurement-discipline.md`** — rule **10**, three container lines in the environment block, and the `-Xmx512m` bullet annotated | `docs/explanation/` | **Yes, for their reports.** Rule 10 applies only to a number taken inside a container, and neither A nor C takes one, so their existing blocks stay conforming. It is stated explicitly in the document that everything before 2026-08-21 is one process with no limits |
| **`ADR-009`** marked superseded by `ADR-013` and annotated | `docs/decisions/adr/` | **Only if they cite it.** Its reasoning is intact; its status line and its 미측정 paragraph changed |
| **Nothing under `db/`, `seed/`, `domain/`, `concept/`, `recommendation/`, and no migration** | — | **No.** The schema is `V1..V3` exactly as at `a417ce3`. `R24`'s numbers were taken on that schema and **not** on A's `V4`/`V5` |

**One thing that is not a file change and matters more than several that are.** The traps in
`load/ops/` leave containers named `proxima-db` and `proxima-app-*` and a network
`proxima-boundary`. Every script tears them down on exit, and `harness_up` now removes **all**
of them before an arm rather than only the ones it is about to start — that was a real defect
(`ae5401b` → `2be3443`) found by the drift control. **If A or C ran a Testcontainers suite while
one of my trap scripts was mid-flight, their containers were competing for a database that had
taken 100 of 100 connection slots.** The runs are timestamped in the commit messages; my
last container was removed before the verification run.
