# R27. The digest nothing pulls, and the tag that moved eight days ago

> **Created**: 2026-08-21
> **Updated**: 2026-08-21
> **Red commit**: **the tree, as it stands.** There is no commit that introduced this and no
> commit that fixed it: the tree did not change, the registry did. §2 is why that makes the
> red state undatable and §7 is what this slice may not do about it.
> **Green commit**: none. **This report does not fix anything** — the one-line fix is in a
> file another slice owns this round, and it is escalated rather than reached for.
> **Found by**: the `ADR-014` sweep, and **not by any `미측정` in it.** Nothing had written
> this down. `ADR-014` §*What the sweep found that no `미측정` marks* is the entry

```
근거 / What the evidence here is
  Not durations, except where a container start is quoted, and none is compared.
  This report measures two images, the registry that serves them, and 23 tracked
  files that name one of them.

  Images       : two, both pinned by digest, both `postgres:16-alpine` by tag
                 RECORDED  postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
                           created 2026-07-07T17:47:20.839800572Z
                 TODAY     postgres@sha256:075f7ba66bc9b3ce7d6b8b635208ff61cd7cf1a67d71ec530eec5d7ae0cbe571
                           created 2026-08-13T19:18:09.513955733Z
  Registry     : docker.io/library/postgres:16-alpine, read 2026-08-21 with
                 `docker buildx imagetools inspect`. Output quoted verbatim in section 3.1
  Host         : Docker Engine 29.5.3 (API 1.54), native inside WSL2 Ubuntu 24.04,
                 kernel 6.6.87.2-microsoft-standard-WSL2. Windows 11 Home 10.0.26200
  JVM          : Temurin 21.0.12+8, Gradle test worker at -Xmx512m
  Corpus       : `git ls-files` at a417ce3 -- 23 tracked files name postgres:16-alpine,
                 20 name 16.14, 5 carry the digest
  Repetitions  : the image comparison is deterministic -- one run. The registry lookup
                 is a point in time by construction and is dated
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`docs/explanation/measurement-discipline.md` owns the rule that makes a number citable here,
and its environment block records the database twice:

```
  PostgreSQL     : postgres:16-alpine — server 16.14
                   sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777
```

It then says why both lines are there:

> The image digest is recorded alongside the tag because `16-alpine` is a moving tag. Two
> people running `postgres:16-alpine` a month apart are not necessarily running the same
> server, and the digest is what makes the row citable.

**The hazard is named exactly. The remedy is recorded in prose. And nothing acts on it.**
`TestcontainersConfiguration.kt` pins the tag:

```kotlin
const val POSTGRES_IMAGE = "postgres:16-alpine"
```

So the digest lives in five documents and in no artefact, and the thing that decides which
server every test in this repository meets is the string a registry resolves at pull time.

This report asks the only question that settles whether that matters: **has the tag moved?**

## 2. 재현 / Reproduction

Two halves, and the first is a shell command rather than a test because the subject is a
registry rather than a database:

```bash
docker buildx imagetools inspect postgres:16-alpine
docker inspect postgres@sha256:57c72fd2… --format 'created={{.Created}}'
docker inspect postgres@sha256:075f7ba6… --format 'created={{.Created}}'
```

```bash
wsl -e bash -lc 'cd /mnt/c/project/airtown/proxima-c \
  && export JAVA_HOME=$HOME/.jdks/jdk-21.0.12+8 \
  && ./gradlew :api:test --tests "net.gseek.proxima.collation.ImageTagDriftTest" \
       --no-daemon --console=plain -i'
```

**There is no red commit to name and that is a property of the defect rather than a gap in the
record.** Every state this repository has ever been in is a state in which the symptom is
present, because the symptom is that a tag is being trusted; and no commit made it true,
because what changed is outside the tree. `R9` had no red commit for a different reason — the
defect had been closed by a framework default — and `R16`'s two arms are neither red nor green
because nothing in the application differed. This is a third shape: **the defect is in the
relationship between the tree and something the tree does not contain.**

## 3. 계측 / Measurement

### 3.1 What the tag resolves to today

Verbatim, 2026-08-21:

```
$ docker buildx imagetools inspect postgres:16-alpine
Name:      docker.io/library/postgres:16-alpine
MediaType: application/vnd.oci.image.index.v1+json
Digest:    sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685

Manifests:
  Name:        docker.io/library/postgres:16-alpine@sha256:075f7ba66bc9b3ce7d6b8b635208ff61cd7cf1a67d71ec530eec5d7ae0cbe571
  MediaType:   application/vnd.oci.image.manifest.v1+json
  Platform:    linux/amd64
  Annotations:
    org.opencontainers.image.base.digest:     sha256:79ff19e9084a00eece421b2523fb93e22d730e2c0e525905de047e848e56d95f
    org.opencontainers.image.base.name:       alpine:3.24
    org.opencontainers.image.created:         2026-08-13T19:16:04Z
    org.opencontainers.image.revision:        9d15534160ade17f2b6c455a39ee967c49b1937d
    org.opencontainers.image.source:          https://github.com/docker-library/postgres.git#9d15534160ade17f2b6c455a39ee967c49b1937d:16/alpine3.24
```

| | |
| --- | --- |
| digest recorded in `measurement-discipline.md` | `sha256:57c72fd2…` |
| digest `postgres:16-alpine` resolves to, linux/amd64, 2026-08-21 | **`sha256:075f7ba6…`** |
| the recorded image's `Created` | **2026-07-07T17:47:20Z** |
| the current image's `Created` | **2026-08-13T19:18:09Z** |
| the current image's build annotation | `2026-08-13T19:16:04Z` |

**The tag moved on 2026-08-13.** The recorded digest still resolves — it is a repo digest and
`docker pull postgres@sha256:57c72fd2…` fetches it — so the *citation* in the document is
sound. What is not sound is that nothing uses it.

**2026-08-13 is the day `R9` was written.** The report whose §8 put a risk on the container
tag was written on the day the tag moved, and neither the report nor anything else noticed.

### 3.2 What the two images differ in

Both started as containers, both asked the same twelve questions:

| | RECORDED | TODAY |
| --- | --- | --- |
| **`version()`** | `PostgreSQL 16.14 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit` | **`PostgreSQL 16.15 on x86_64-pc-linux-musl, compiled by gcc (Alpine 15.2.0) 15.2.0, 64-bit`** |
| **`server_version`** | `16.14` | **`16.15`** |
| **`server_version_num`** | `160014` | **`160015`** |
| `datcollate` | `en_US.utf8` | `en_US.utf8` |
| `datlocprovider` | `c` | `c` |
| ICU collations | 908 | 908 |
| libc collations | 3 | 3 |
| `shared_buffers` | `128MB` | `128MB` |
| `work_mem` | `4MB` | `4MB` |
| `max_connections` | `100` | `100` |
| `random_page_cost` | `4` | `4` |
| `max_parallel_workers_per_gather` | `2` | `2` |

**Twelve facts compared, three differ, and all three are the same fact.** The move is one
PostgreSQL patch release, 16.14 → 16.15, on the same Alpine base and the same compiler.

And the two things this repository actually depends on:

```
RECORDED | migrations applied: 3
RECORDED | order by v        : Apple,Banana,apple,cherry
TODAY    | migrations applied: 3
TODAY    | order by v        : Apple,Banana,apple,cherry
```

`V1`→`V3` apply on both. Ordering is byte-wise on both, so `R25`'s finding is unaffected by
the drift and `R25`'s arm A is the recorded image regardless.

**Nothing measured here got worse.** That is the outcome, and it is not the finding.

### 3.3 What the documents say, counted

At `a417ce3`, the base of this round:

| | tracked files |
| --- | --- |
| naming `postgres:16-alpine` | **23** |
| naming `16.14` | **20** |
| carrying the digest `sha256:57c72fd2…` | **5** |

The five are `measurement-discipline.md`, `R1`, `R2`, `R3`, `R4`. **The digest that the
governing document says is *"what makes the row citable"* appears in four of twenty reports**,
all four written in the first three days; every report from `R5` onward names the tag and
stops.

So there are two claims in the tree today, and they have been incompatible since 2026-08-13:

- twenty documents say the PostgreSQL is **16.14**;
- `.github/workflows/build.yml` has no image cache, so a GitHub runner pulls
  `postgres:16-alpine` fresh on every run, and since 2026-08-13 that is **16.15**.

**The local machine and CI have been running different database servers, and the environment
block describes the local one.** The developer machine holds the 2026-07-07 image in its
Docker cache and Testcontainers' default pull policy does not re-check a tag it already has,
so nothing here would ever have surfaced it.

### 3.4 Why no existing guard could see this

`docs-consistency.yml` runs four checks and none of them can:

| check | why it is silent |
| --- | --- |
| 1 — every named artefact exists | `postgres:16-alpine` is not a tracked path and matches no artefact token |
| 2 — `Updated` matches the last substantive change | every one of the twenty documents is correctly dated. **Nothing edited them; the world moved** |
| 3 — every report has a roadmap row | unrelated |
| 4 — §8 is non-empty | unrelated |

**This is `R17` §8's second bullet, met by a case it did not anticipate.** That bullet says a
document *"that is genuinely up to date and simply says something false about the tree passes
all four checks"*, and the class it imagined was a sentence that went stale because the tree
changed underneath it. Here the tree did not change at all. **The claim went false while every
file was untouched and every date was right**, which is a strictly harder case than the one
`R17` names, and no proxy over dates can ever reach it.

## 4. 원인 / Mechanism

A Docker tag is a mutable pointer in a registry, not a version. `postgres:16-alpine` is
maintained to mean *"the current 16.x on the current Alpine"*, so it is repointed on every
PostgreSQL patch release and on every Alpine base bump. `docker pull` resolves the tag at pull
time; a host that already holds an image for that tag does not re-resolve it unless asked, and
Testcontainers' `DefaultPullPolicy` does not ask.

That produces the split in §3.3 exactly: **a machine that pulled once in July keeps July's
server forever, and a fresh runner gets whatever the tag means this morning.** Neither is
wrong; they are simply not the same, and nothing in the repository can tell.

The remedy the registry offers is the digest, which is content-addressed and immutable — and
which this repository records in prose and passes to nothing.

## 5. 처방 / Remedy

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| Leave the tag | the drift continues, and the next move could be `alpine:3.25`, a `shared_buffers` default change, or 17.x under a re-cut `16` line | none | |
| Pin the digest in `TestcontainersConfiguration.kt` and keep the tag beside it as a comment | every machine and every runner meet the image the environment blocks name. A deliberate bump becomes a commit | **one line, and it belongs to a file this slice may not edit** | **the recommendation** |
| Pin the digest **and** re-baseline every number on 16.15 | the documents would then describe what CI runs | hours of load runs, and `R18` measured a drift band of 1.27× over seventy minutes on this machine, so most of what would be re-measured is inside its own noise | |
| Add a check that the tag still resolves to the recorded digest | catches the next move at push time | **it is red today and would stay red**, because the tag has already moved. A check nobody can make green is a check somebody disables | |

**Recommended: pin the digest, and correct `16.14` to name both servers rather than
re-baselining.** The measured difference is one patch release with no observable effect on
migrations or ordering (§3.2), so re-measuring twenty documents would spend the repository's
scarcest resource — measurement time — on a difference it has just established is invisible.
**What is not defensible is leaving the documents saying 16.14 while the lane runs 16.15**,
because that is a `PUB-4` prose claim the tree contradicts, and `R17` is the report on what
those cost.

**Why the fourth option loses even though it is the most in this repository's style.** A guard
that is red on arrival is not a guard, it is a broken build with a lesson attached; and the
honest ordering is *pin first, then guard the pin.* Once the digest is in
`TestcontainersConfiguration.kt`, the guard is free — the constant **is** the pin, and any
drift becomes a deliberate edit.

## 6. 재계측 / Re-measurement

Not applicable. Nothing was changed, so there is no after — and §5's recommendation is a
recommendation precisely because this slice may not make it.

## 7. 회귀 게이트 / Regression gate

**None, and this section says so rather than pointing at something that does not gate it.**

`ImageTagDriftTest` is a recorder, not a gate: it asserts nothing, because both outcomes are
findings — a difference is one and an absence of difference is one too, and the absence would
be a fact about eight days rather than about the mechanism.

**The gate that should exist is one line and it is not this slice's to write.** Pinning the
digest in `TestcontainersConfiguration.kt` makes the constant itself the guard: the pull is
content-addressed, so a moved tag can no longer change what any test meets, and a version bump
becomes a diff somebody reviews. That file is owned by another slice this round, so **this is
escalated in the handoff rather than reached for**, and until it lands the finding here is a
description and not a fix.

This is the same position `R10` and `R11` took for a different reason — a finding with no
remedy commit, stated as such — and it carries the same obligation: the report is not
evidence that anything was repaired.

## 8. 남는 위험 / Remaining risk

- **The defect is present as this report is filed.** Nothing here changed a file. Twenty
  documents say `16.14`, a runner pulls `16.15`, and `TestcontainersConfiguration.kt` still
  pins a tag. **This report is a description of an open defect**, and if the recommendation in
  §5 is not taken it will be a description of it a month from now too.
- **Only two images were compared, and only twelve facts.** §3.2 asked what a configuration
  dump exposes. Planner behaviour, `pg_stat` semantics, locale tables, and every one of the
  bug fixes in 16.15 are **미측정** — and 16.14 → 16.15 is the *smallest* move this tag can
  make. The next one could cross an Alpine major, which is where the musl version and
  therefore `R25`'s entire subject lives.
- **The four earlier reports carry the digest and the sixteen later ones do not.** §3.3. So
  the fix `measurement-discipline.md` prescribes was already being dropped by the reports
  themselves after three days, and nothing noticed that either. **Whether a discipline is
  followed is not a thing this repository has ever measured**, and this is one data point
  suggesting it decays.
- **미측정 — when CI first ran on 16.15.** The tag moved on 2026-08-13 and workflow runs after
  that date pulled it, but this report did not read the Actions API to name the first run. It
  needs an authenticated call, and `R19` §8 records the same blocker — `gh` is not on this
  machine. The inference is very likely right and it is an inference.
- **미측정 — whether any published number would change on 16.15.** §3.2 shows the migrations
  apply and the ordering holds; no latency was re-measured, and doing so is `R18`'s drift band
  wide. `README.md`'s results table is therefore a table of numbers taken on a server the
  build no longer uses, and how much that matters is unknown rather than small.
- **The environment block's digest was checked for being *a* valid repo digest, not for being
  the amd64 manifest.** `docker inspect` reports it as this host's `RepoDigests` entry and the
  pull succeeds, which is what "citable" requires. Whether it is the multi-platform index
  digest or the linux/amd64 manifest digest is **미측정**, and a reader on arm64 might get a
  different answer.
- **This report's own instrument starts two containers and cost 52.910 s** in the run that
  produced these numbers, absorbing Docker and Ryuk initialisation because it ran first. It is
  a recorder that asserts nothing, so it is pure cost on every CI run. **Whether it should
  survive as a test at all, rather than being a one-off transcript in this report, is a
  judgement** — and it is the one bullet here that is closer to a decision than to a risk. It
  is not filed as a row in `docs/decisions/open.md` because §7's escalation supersedes it: if
  the digest is pinned, the drift test's subject becomes a constant and the class should be
  deleted rather than kept.
- **What would break this conclusion**: a tag move that changes something. Everything above
  says this one did not, and *"a moving tag has so far moved harmlessly"* is a statement about
  eight days and one patch release. The mechanism has no bound on the next move, which is the
  entire reason `measurement-discipline.md` wrote the digest down in the first place.

## 9. 배운 것 / What I learned

**규칙을 적어놓은 문서가, 그 규칙을 지키지 않는 유일한 방법을 같이 적어놨다.**

`measurement-discipline.md`는 `16-alpine`이 움직이는 태그라고 명시했다. 왜 다이제스트를 같이 적는지도
설명했다 — *"the digest is what makes the row citable"*. 문장은 완벽하게 옳다. 그런데 그 다이제스트는
문서 다섯 개에 적혀 있고 **아무것도 그걸로 이미지를 받지 않는다.** `TestcontainersConfiguration.kt`은
태그를 박아놨다.

그래서 태그가 8일 전에 움직였고, 아무도 몰랐다. 로컬 머신은 7월에 받아둔 16.14를 Docker 캐시에 들고
있고, GitHub 러너는 매번 새로 받으니 8월 13일 이후로는 16.15를 받았다. **같은 저장소가 두 대의 서로
다른 데이터베이스 위에서 돌고 있었고, 환경 블록은 그중 로컬 쪽을 적고 있었다.**

여기서 배운 게 두 개다.

**첫째, `PUB-4`가 막으려는 거짓 문장은 트리가 바뀌어야 생기는 게 아니다.** R17 §8은 *"날짜가 맞고
내용이 틀린 문서"*를 최대 구멍으로 지목했는데, 그때 상상한 건 트리가 바뀌었는데 문장이 안 따라간
경우였다. 이건 **파일이 하나도 안 바뀌었는데 문장이 거짓이 된** 경우다. `docs-consistency.yml`의 네
검사 중 어느 것도 원리적으로 볼 수 없다 — 검사할 diff 자체가 없으니까. 날짜를 프록시로 쓰는 방법은
여기서 끝난다.

**둘째, 그리고 이게 더 불편한데 — 규율은 지켜지다 만다.** 다이제스트를 적은 리포트는 R1, R2, R3, R4다.
전부 첫 사흘이다. R5부터 열여섯 개는 태그만 적었다. 규칙은 그대로 있었고, 아무도 규칙을 바꾸지 않았고,
그냥 조용히 안 하게 됐다. **이 저장소는 "숫자가 정직한가"는 여러 번 쟀지만 "규율이 지켜지고 있는가"는
한 번도 재본 적이 없다.** ADR-014를 쓰면서 `미측정`을 세어본 게 처음이었던 것과 같은 종류의 공백이고,
이번에 발견된 건 `미측정`이 표시해준 게 아니라 **표시가 없는 자리에서 나왔다.**
