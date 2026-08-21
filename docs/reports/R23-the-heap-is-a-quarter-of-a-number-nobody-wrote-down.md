# R23. The heap is a quarter of a number nobody wrote down

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: `4e8b117` — a test asserting that a 512 MB container is a 512 MB heap
> **Green commit**: `c4d6578` — the measured ergonomics, and which component refuses first
> **Answers**: `ADR-004`'s closing line — *"whether any number within a report was taken on a
> different day from the others… 미측정, and it is the obvious next hole"* — by opening a
> different one beside it: **every number in this repository was taken on one process with no
> container limits at all**, and nothing said so.

```
측정 환경 / Measurement environment
  Hardware       : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS             : Windows 11 Home 10.0.26200
                   WSL2 Ubuntu 24.04, kernel 6.6.87.2-microsoft-standard-WSL2, 15.4 GiB
  Docker         : Docker Engine 29.5.3, NATIVE INSIDE WSL2 — not Docker Desktop
  JVM            : Temurin 21.0.12+8 — BIND-MOUNTED into the container from `java.home`,
                   not pulled. §2 says why
  Base image     : ubuntu:24.04
                   sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517
  Container      : memory=512m / 1g / 2g, memory-swap EQUAL to memory, cpus unset
  Instances      : 1 container. No database and no application
  Heap           : the variable
  Dataset        : none — this measures a JVM, not proxima
  Repetitions    : 1 per arm, and §3.4 argues why that is enough HERE and nowhere else
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`docs/explanation/measurement-discipline.md` has carried this correction since before the
first measurement:

> **It said `-Xmx512m`.** Nothing sets that. The heap flag is 미측정 as a property of these
> runs, and the field is left out until a report actually pins it — a stated JVM flag that no
> run used is worse than no flag, because it looks checkable and is not.

The correction removed the flag. It left the belief that made writing the flag feel harmless:
that a container given 512 MB is a JVM with a 512 MB heap, so `-Xmx512m` beside a 512 MB limit
is writing down what was going to happen anyway.

Nothing in this repository has ever run inside a memory limit, so nothing has ever contradicted
it.

## 2. 재현 / Reproduction

`ContainerHeapErgonomicsTest`, in `:api:test`. One container per limit, `sleep infinity` as its
command, and every measurement an `exec` into a known cgroup — so no container start sits
inside a number.

```
JAVA_HOME=~/.jdks/jdk-21.0.12+8 ./gradlew :api:test \
  --tests "net.gseek.proxima.ops.ContainerHeapErgonomicsTest"
```

**Three things about the setup are load-bearing and none of them is taste.**

**`memory-swap` is set equal to `memory`.** Docker's default when only `--memory` is given
allows swap up to twice the limit. A JVM that can swap does not die — the limit is enforced by
nothing observable, the page faults are paid quietly, and the measurement reports that the trap
does not exist. `/sys/fs/cgroup/memory.swap.max` reads `0` under this setting and the test
asserts it, so the control is measured rather than trusted.

**The JVM is bind-mounted, not pulled.** `eclipse-temurin` has **no `21.0.12` tag** on Docker
Hub — queried 2026-08-21 against `hub.docker.com/v2/repositories/library/eclipse-temurin/tags`,
the list stops at `21.0.11_10`. Pulling the nearest published image would put a different JVM
build inside the container from the one every other number in this repository was taken with,
and `measurement-discipline.md` rule 3 is exactly about that. `System.getProperty("java.home")`
is the JVM the test itself is running on, and it goes in read-only over a bind mount. **This
requires the Docker daemon to share a filesystem namespace with the test JVM** — it does here,
natively inside WSL2, and it would not on Docker Desktop.

**The base image has no job except holding a cgroup.** `ubuntu:24.04`, plain glibc, no JVM of
its own and no init that could reap the process under test. `R9` §3.3 is why the libc is named:
`postgres:16-alpine` is musl-built and that turned out to decide a collation result nobody had
asked about.

The `red` commit `4e8b117` contains one assertion, which is the sentence above stated as code.

## 3. 계측 / Measurement

### 3.1 The heap ceiling is a fraction of the cgroup

```
limit  512 MiB -> MaxHeapSize 134217728  (128 MiB)
limit 1024 MiB -> MaxHeapSize 268435456  (256 MiB)
limit 2048 MiB -> MaxHeapSize 536870912  (512 MiB)
```

Exactly a quarter, at every limit. `UseContainerSupport` defaults to `true` and
`MaxRAMPercentage` to `25.000000`, both read out of `-XX:+PrintFlagsFinal` rather than from any
documentation.

The `red` commit's failure is the same fact stated as a refusal:

```
a 512 MB container was given this much heap. If this fails, the sentence `-Xmx512m` in
measurement-discipline.md was not merely unsourced -- it was describing a JVM that does not
exist, and the report has to say what does ==> expected: <536870912> but was: <134217728>
```

**The control, which is the half that makes the above mean anything:**

```
any limit, -XX:-UseContainerSupport -> MaxHeapSize 4129292288  (3938 MiB)
```

3938 MiB is a quarter of this machine's 15.4 GiB. Without that arm, *"the heap tracks the
limit"* would also be satisfied by a JVM that ignores the cgroup on a host whose RAM happens to
be four times it — and 512 MB × 4 is 2 GiB, which is a plausible laptop. The test asserts
`unaware > limit` for that reason.

### 3.2 Who refuses, in one 512 MB container

| arm | heap ceiling | workload | outcome | exit | cgroup `oom_kill` |
| --- | ---: | ---: | --- | ---: | ---: |
| ergonomic (no flag) | 128 MiB | 400 MiB live | `java.lang.OutOfMemoryError: Java heap space` | **1** | unchanged |
| `-Xmx512m` | 494 MiB | 440 MiB live | survived, 6 full GCs | 0 | unchanged |
| `-Xmx512m` | 494 MiB | 460 MiB live | **no output at all** | **137** | **+1** |
| `-Xmx512m` | 494 MiB | 480 MiB live | **no output at all** | **137** | **+1** |
| `-Xmx1g` | 989 MiB | 600 MiB live | **no output at all** | **137** | **+1** |
| `-XX:-UseContainerSupport` | 3938 MiB | 600 MiB live | **no output at all** | **137** | **+1** |

**Two of those six rows are in `ContainerHeapErgonomicsTest` and four are not**, and the
difference matters to anyone re-running this. The test asserts the first row (ergonomic, 400
MiB) and the fourth (`-Xmx512m`, 480 MiB), because those two are the ones whose outcome is
structural — 400 MiB cannot fit in a 128 MiB heap and 480 MiB cannot fit in a 512 MB cgroup, on
any machine. **The other four were measured by hand in the same session, through the same
containers**, and are here because the sweep is what located the boundary in §3.3. They are not
gated and are not offered as constants.

`137` is `128 + 9`: `SIGKILL`. The kernel's own counter in `/sys/fs/cgroup/memory.events` is
what makes that attribution rather than inference — an exit code can be produced by anything,
and `oom_kill 1` is the kernel saying it did this:

```
low 0
high 0
max 24
oom 1
oom_kill 1
oom_group_kill 0
```

**A heap flag does not change how much memory there is. It changes who tells you.** The
ergonomic ceiling refuses inside the JVM, where there is a stack trace, a heap dump if one was
configured, a log line that ships, and a process still alive to fail its own health check and be
drained. `-Xmx` at or above the limit moves the refusal to the kernel, where the artefact is a
number in `docker inspect` and nothing else.

### 3.3 `-Xmx512m` in a 512 MB container is on the second row

The pairing the discipline document printed survives 440 MiB of live data and is killed at 460.
The gap between the flag and the limit — 494 MiB of heap inside 512 MB of container — is 18 MiB,
and metaspace, code cache, thread stacks, GC structures and the JVM's own native allocations all
live in it.

**Between 440 and 460 MiB the failure mode changes from a survivable one to a silent one**, and
nothing in the configuration marks the boundary. The exact crossing point is a property of this
workload and this machine and is not a number to carry anywhere; **which side of it a given
deployment is on is what nobody can answer from the configuration alone.**

### 3.4 Why one run per arm, when rule 5 says three

Rule 5 exists for figures that vary — a run that looked good is a run that looked good. Nothing
in §3.1 varies: `MaxHeapSize` is `limit / 4` by construction, printed by the JVM about itself
before any work happens. §3.2's exit codes are decided by whether a fixed number of megabytes
fits inside a fixed cgroup with swap disabled.

**The two figures here that ARE machine-dependent are labelled as such and are not carried
anywhere**: 3938 MiB is a fact about this host's 15.4 GiB, and the 440/460 MiB crossing point is
a fact about this workload. Both appear once, in the paragraph that explains them.

The whole of §3.1 and §3.2 re-runs on every `:api:test`, which is a stronger repetition claim
than three runs on one afternoon.

## 4. 원인 / Mechanism

A JVM sizes its heap from *available memory*, and before container support existed it read that
from `/proc/meminfo` — the **host's** memory, which inside a container is the memory of a
machine the process cannot have. `UseContainerSupport` makes it read the cgroup limit instead,
and `MaxRAMPercentage` (default 25) is the fraction of that it will use as a maximum heap.

So the ergonomic ceiling is **deliberately conservative**: three quarters of the container is
left for everything a Java process needs that is not heap. Metaspace, the code cache, compressed
class space, one stack per thread, GC bookkeeping, direct byte buffers, and the JVM binary
itself all come out of the remaining 384 MB in the 512 MB arm.

`-Xmx512m` deletes that reservation without replacing it. The heap is allowed to grow to the
whole container and the non-heap cost is still there, so the process exceeds its cgroup — and a
cgroup breach is not a Java event. There is no exception to throw because nothing in the JVM
noticed anything: the kernel picks the largest consumer and sends `SIGKILL`, which cannot be
caught, logged, or shut down gracefully from.

That is why §3.2's table is arranged by *who refuses* rather than by *how much memory*. The
memory is the same in every row.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Set `-Xmx` to the container limit | Uses the whole box | **the failure becomes a `SIGKILL`.** §3.2, rows 3–4 | no |
| Set `-Xmx` below the limit with a measured margin | Uses more than 25 % and still throws rather than being killed | the margin is a number nobody can compute — it is metaspace plus code cache plus stacks plus whatever the workload allocates natively, and it moves with the workload | no, **and 미측정**: what margin this application needs was not measured |
| Set `-XX:MaxRAMPercentage` | The same lever, expressed as the fraction it actually is, so it cannot exceed the container | one more property to keep in step with the limit | no — nothing here needs more than 25 % yet, and §8 |
| **Leave it ergonomic and record what that resolves to** | The refusal stays inside the JVM. The heap is stated as a *measurement* in the environment block, not as a flag | 75 % of the container is reserved for non-heap and most of it is unused | **yes** |

**Why leaving it alone is a decision and not an omission.** `R4` §7's distinction applies
exactly: a heap flag is a **capacity** lever and the refusal's location is a **correctness**
one, and they are not alternatives. An application that genuinely needs more than a quarter of
its container should raise `MaxRAMPercentage` and keep the ceiling derived from the cgroup —
which is a different act from writing `-Xmx` at the limit, even when the two produce the same
number today, because only one of them still holds after somebody halves the container.

**What would have made a different option correct:** a measured heap ceiling this application
hits. It does not — `R23` measures a JVM, and the application's own working set under load is
**미측정**.

### The managed equivalent

There is no managed-database analogue of a JVM heap flag, and the shape is not the point — the
shape is **a ceiling derived from memory nobody typed, which changes when the box changes.**
Managed PostgreSQL has exactly that, and it is the parameter `R24` §3.1 is about:

- **Amazon RDS for PostgreSQL** — `max_connections`, allowed `6–262143`, default
  `LEAST({DBInstanceClassMemory/9531392}, 5000)`. Checked 2026-08-21 in
  *Quotas and constraints for Amazon RDS* → *Maximum number of database connections*,
  `https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html`. The same page
  warns that `DBInstanceClassMemory` "automatically subtracts the amounts reserved to the
  operating system and the RDS processes", so the divisor is applied to a number smaller than
  the instance class's advertised GiB — the ceiling is not computable from the price list.
- **Cloud SQL for PostgreSQL** — `max_connections`, default "depends on the amount of memory of
  the largest instance in the chain of primaries", tabulated from **25** at ~0.5 GB to **1,000**
  at ≥120 GB, with **100** at 3.75–6 GB. Checked 2026-08-21 at
  `https://docs.cloud.google.com/sql/docs/postgres/flags`.

**Both are the JVM's `MaxRAMPercentage` with a different unit.** Resize the instance and the
ceiling moves under an application that never mentioned it; pin the value by hand and it stops
moving, including in the direction you needed. The container's 512 MB and RDS's
`DBInstanceClassMemory` are the same kind of number, and neither appears in any code.

## 6. 재계측 / Re-measurement

| | Before — `4e8b117` | After — `c4d6578` |
| --- | --- | --- |
| What the tree claims a 512 MB container's heap is | 536870912, asserted | 134217728, measured and asserted as `limit / 4` |
| Whether anything checks it | nothing | `ContainerHeapErgonomicsTest`, on every `:api:test` |
| Who refuses when the heap outgrows the container | unstated | measured: the JVM at the default, the kernel with a flag |
| What the environment block records about containers | nothing | three lines, `measurement-discipline.md` rule 10 |

**The application was not changed and does not need to be.** Both states are the same jar; the
red commit is a test and the green commit is a corrected test plus the document it refutes.

## 7. 회귀 게이트 / Regression gate

`ContainerHeapErgonomicsTest` — two tests, both machine-independent:

- **the ratio**, at three limits, plus the `-XX:-UseContainerSupport` control that stops it from
  passing on a host whose RAM happens to be four times the limit;
- **who refuses**, asserted as exit `1` with `OutOfMemoryError` and an unchanged `oom_kill`
  counter for the ergonomic arm, and exit `137` with `oom_kill + 1` and **no** `OutOfMemoryError`
  for the flagged one.

`DeploymentBoundaryGateTest` carries the same argument for `R24`'s three defaults.

**What it does not gate.** Nothing stops somebody adding `-Xmx` to a deployment: this repository
has no deployment, and a gate over a `docker run` that does not exist would be the unbanked
guard `AGENTS.md` §Scope and `ADR-007` are both about. The gate protects the *claim*, not the
practice.

**And it is worth saying which way this gate is pointed.** It asserts that the trap is **absent**.
Three of this repository's reports — `R5`, `R9`, `R10` — are about framework versions that had
already fixed the thing being documented, and the roadmap keeps their rows. A default is what is
holding this one shut, and a default moves under a version bump nobody reads.

## 8. 남는 위험 / Remaining risk

- **The trap does not reproduce in the form the roadmap describes, and what prevents it is two
  defaults.** `UseContainerSupport=true` and `MaxRAMPercentage=25`. Neither is set by this
  repository, both belong to Temurin 21.0.12+8, and §7's gate exists because a version bump
  could move either without anything else changing.
- **This measures a JVM and not this application.** No proxima process ran inside the 512 MB
  arm for this report. What heap the application actually needs under load — and therefore
  whether 128 MiB is comfortable or one request away from §3.2's first row — is **미측정**.
  `R24`'s harness ran the real jar in a 512 MB container and it started and served; that is an
  existence proof and not a headroom measurement.
- **CPU limits were not varied at all.** `cpus` is unset in every arm. `ActiveProcessorCount`
  reads `-1` (ergonomic) and the JVM sizes GC threads, the common `ForkJoinPool` and Tomcat's
  defaults from the processor count it infers — which under a CPU quota is a second
  container-awareness question with its own defaults. **미측정, and it is the obvious next hole
  in this report** the way the machine count was `ADR-004`'s.
- **The margin between `-Xmx` and the limit was measured for one workload.** 440 MiB survives
  and 460 MiB is killed, for a program that holds byte arrays and does nothing else. A real
  application's non-heap cost is dominated by metaspace, thread stacks and direct buffers, none
  of which this fixture has. **The crossing point does not transfer and is not offered.**
- **Swap was disabled to make the limit observable, and most deployments do not disable it.**
  Under Docker's default (`memory-swap` unset, so twice `memory`) the `-Xmx512m` arm would
  likely have survived 480 MiB by paging — slowly, invisibly, and with no exit code to notice.
  **What that costs in latency is 미측정**, and it is the arm most likely to describe a real
  production container.
- **One JVM, one vendor, one version.** Temurin 21.0.12+8. `MaxRAMPercentage`'s default is not a
  specification, and a different vendor's build or a different major version may choose
  differently. Nothing here establishes otherwise.
- **What would break this conclusion:** any of `UseContainerSupport`, `MaxRAMPercentage`, or the
  cgroup version. The measurements were taken under cgroup v2 —
  `/sys/fs/cgroup/memory.max` and `/sys/fs/cgroup/memory.events` are v2 paths and the test reads
  them directly, so on a v1 host it would fail to read rather than mislead.
- **Which earlier §8 bullet this falsifies:** none of the numbered reports'. What it falsifies is
  a sentence in `docs/explanation/measurement-discipline.md`, and that sentence is annotated in
  place beside itself rather than summarised here — the procedure `_TEMPLATE.md` §8 asks for and
  `R19` §3.4 is the report on what it costs when nobody follows it.
- **Nothing here needs a judgement rather than work**, so nothing here is an `open.md` row. The
  one decision that *was* taken — leaving the heap ergonomic — is §5, and it is a decision with
  a measurement under it rather than a risk somebody chose to live with.

## 9. 배운 것 / What I learned

`-Xmx512m` 이 문서에서 지워진 이유는 *"아무도 그걸 설정하지 않는다"* 였습니다. 저는 그 교정이
끝난 일이라고 생각했고, 그 밑에 깔린 믿음 — 512 MB 컨테이너면 힙도 512 MB일 테니 그 플래그는
어차피 일어날 일을 적어둔 것뿐이다 — 은 건드려지지 않은 채로 남아 있었습니다. **재보니 128
MiB였습니다.** 정확히 4분의 1이고, 그건 `MaxRAMPercentage` 기본값입니다.

제일 놀란 건 숫자가 아니라 **누가 거절하느냐**였습니다. 플래그 없이 400 MiB를 잡으면
`OutOfMemoryError`에 exit 1이고 컨테이너는 살아 있습니다. `-Xmx512m`을 주고 480 MiB를 잡으면
**exit 137**, 스택 트레이스 없음, 로그 한 줄 없음, `docker inspect`의 숫자 하나가 전부입니다.
메모리 양은 똑같습니다. 플래그가 바꾼 건 **거절하는 주체**입니다. JVM이 거절하면 힙 덤프도
뜨고 헬스체크도 실패시키고 드레인도 됩니다. 커널이 거절하면 그 중 아무것도 없습니다.

그리고 대조군을 넣길 잘했습니다. `-XX:-UseContainerSupport`를 안 쟀으면 저는 *"힙이 컨테이너
한계를 따라간다"* 라고 썼을 텐데, 이 노트북 RAM이 15.4 GiB고 그 4분의 1이 3938 MiB니까
**512 MB × 4 = 2 GiB짜리 흔한 노트북에서는 두 설명이 같은 숫자를 냅니다.** 대조군이 없으면
구분이 안 되는 주장이었습니다. `R18`이 드리프트 대조군으로 자기 결론 하나를 죽인 게 계속
생각났습니다.

한 가지 더. eclipse-temurin에 `21.0.12` 태그가 **없습니다.** 없는 걸 확인하는 데 쿼리 한 번이
들었고, 그냥 `21-jre`를 썼으면 이 저장소의 다른 모든 숫자와 다른 JVM 빌드를 컨테이너에 넣고
비교하는 셈이었을 겁니다. 규칙 3이 금지하는 게 정확히 그건데, **그게 금지된 짓이라는 걸
알아채는 지점이 "이미지를 고른다"는 아주 사소해 보이는 줄이었습니다.** 호스트 JDK를
bind-mount 하는 쪽으로 바꾸고 나서야, 이 슬라이스에서 컨테이너는 JVM을 제공하는 게 아니라
**cgroup을 제공하는 물건**이라는 게 분명해졌습니다.
