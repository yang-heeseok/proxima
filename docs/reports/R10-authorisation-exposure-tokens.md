# R10. Authorisation, exposure, tokens

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
> **Status**: **one strand of three.** This report covers *management endpoints exposed
> wholesale*. The other two — *an endpoint that authenticates and does not authorise* and
> *token expiry and clock skew* — landed the same day in **`R11`**. §8's first bullet was
> written while they were still outstanding and is left as it stood; `R11` is the answer to it.
> **Red commit**: **none.** The shipped `application.yml` has restricted the surface since it
> was written, so the `red` state here is a **test property override**, not a commit. §5 says
> why pushing a wide-open surface to a public repository would be the wrong trade.
> **Green commit**: this one — the gate, its control, and the measurements below

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Runtime    : Spring Boot 4.1.0, spring-boot-actuator 4.1.0, JDK 21.0.12+8
  PostgreSQL : Testcontainers postgres:16-alpine -- server 16.14
  Client     : java.net.http.HttpClient, same host, no proxy
  Load       : none. These are status codes and file contents, not durations
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

`management.endpoints.web.exposure.include: "*"` is one line. It appears in tutorials, in
Stack Overflow answers, and in the `application.yml` of anyone who once wanted `/actuator/env`
during an incident and never took it out.

The roadmap describes what it expected to find: *management endpoints exposed wholesale,
including the one that dumps the heap and everything that was in it.*

**Half of that is wrong on Spring Boot 4.1.0**, and finding out which half is the report.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.management.*"
```

Three classes and three configurations, because the question needs all three:

| class | configuration | what it answers |
| --- | --- | --- |
| `ManagementSurfaceTest` | `include: "*"` | what widening exposure alone reaches |
| `HeapDumpContentTest` | `include: "*"` **and** `management.endpoint.heapdump.access=unrestricted` | what the second switch is protecting |
| `ManagementSurfaceGateTest` | the shipped `application.yml` | the gate |

## 3. 계측 / Measurement

### 3.1 What `include: "*"` reaches

Thirteen endpoints listed by `/actuator`:

```
beans, conditions, configprops, env, flyway, health, info,
loggers, mappings, metrics, sbom, scheduledtasks, threaddump
```

| endpoint | status | |
| --- | --- | --- |
| `threaddump` | 200 | reachable |
| `env` | 200 | reachable |
| `configprops` | 200 | reachable |
| `loggers` | 200 | reachable |
| `mappings` | 200 | reachable |
| `beans` | 200 | reachable |
| **`heapdump`** | **404** | **NOT reachable** |
| **`prometheus`** | **404** | **NOT reachable** |

Two surprises, in opposite directions.

### 3.2 Exposure and access are two gates, and `include` opens one

`heapdump` answering 404 with exposure wide open is not a quirk. From the framework's own
`spring-configuration-metadata.json`, read out of `spring-boot-actuator-4.1.0.jar` rather than
from documentation:

```json
{ "name": "management.endpoint.heapdump.access",      "defaultValue": "none" }
{ "name": "management.endpoint.httpexchanges.access", "defaultValue": "unrestricted" }
```

Every other endpoint defaults to `unrestricted` and appears the moment exposure widens.
**`heapdump` alone defaults to `none`.** Reaching it takes a second, separate, deliberate
line. (`management.endpoint.<id>.enabled` has been deprecated in favour of `access` since
3.4.0, and `management.endpoints.access.max-permitted` — default `unrestricted` — is a global
cap that can close everything at once.)

So the roadmap's premise does not reproduce. **This is the fourth time in this repository**
that a defect everyone knows about turned out to be closed by the framework already: `R5`'s
in-memory pagination, `R9`'s embedded-database substitution, `R3`'s stale statistics, and now
this. That pattern is itself worth naming, and §9 does.

### 3.3 What the second gate is protecting

`heapdump` opened with one property. Distinctive credentials, so that a hit means something —
see §3.5, which is where this measurement was wrong the first time:

```
the credential being searched for          : PROXIMA-T9-DB-PASSWORD-9c4e
GET /actuator/heapdump                     : 200
size                                       : 156 MB
contains the planted canary   (CONTROL)    : true
contains the datasource password           : true
contains the datasource username           : true
contains the JDBC url                      : true
/actuator/env does NOT print that password : true
```

**`/actuator/env` masks the datasource password. `/actuator/heapdump` hands over the same
password in plain bytes.**

That is the whole reason the second gate exists, and it is the argument against reasoning
about a management surface endpoint by endpoint. The masking in `env` is what makes a wide
surface feel survivable; one other endpoint on the same surface makes the masking irrelevant.
156 MB is not a leak of a configuration value — it is a copy of everything the process could
see, including anything that was ever decrypted in it.

The dump measured 156 MB here and **168 MB** on an earlier run of the same test. Heap size is
not the subject and was not controlled; both are quoted rather than the tidier one.

### 3.4 One of these is not a view

The other twelve endpoints answer questions. `loggers` takes instructions:

```
POST /actuator/loggers/net.gseek.proxima.t9probe   -> 204
configuredLevel before   -> null
configuredLevel after    -> TRACE
restored to              -> null
```

Anyone who can reach it can turn on `DEBUG` for the SQL logger and read every statement and
every bound parameter the application executes from that moment on, through whatever ships the
logs — without a heap dump and without restarting anything.

**That sentence existed in this repository as a comment before it was a measurement.** It was
written into `ManagementSurfaceGateTest`'s KDoc as a justification, noticed, and measured.

### 3.5 The control passed and the measurement was still wrong

The first successful run of §3.3 reported:

```
contains the datasource password           : true
/actuator/env does NOT print that password : false
```

which says `env` leaks the password. It does not. `PostgreSQLContainer` defaults to username
`test` and password `test`; the active Spring profile is `test`; the database in the JDBC url
is called `test`. **The search found the word `test` in a 168 MB heap dump**, which it would
have found in any heap dump of any test run of anything.

The canary control had passed. It proved the file was a real dump and the byte search worked.
It could not prove the needle was *specific*, which is a different property, and that was the
one that was broken.

`DistinctiveCredentialPostgres` now gives this test a container whose credentials cannot occur
by accident, and the test `check`s that it is talking to that container before believing a
hit. §9.

### 3.6 What the shipped configuration answers

| endpoint | status |
| --- | --- |
| `health` | 200 |
| `info` | 200 |
| `metrics` | 200 |
| **`prometheus`** | **404** |

`application.yml` exposes `prometheus`. **This build has no such endpoint** — it is 404 even
with `include: "*"` in §3.1, so it is absent rather than closed. `micrometer-registry-prometheus`
is on the runtime classpath and the endpoint still does not exist, which means the exposure
list has been naming something imaginary since it was written.

Harmless, and worth knowing: a configuration line that has never done anything is a line
nobody has ever checked.

## 4. 원인 / Mechanism

Two properties, in sequence, deciding two different things:

1. **`management.endpoints.web.exposure.include`** — may this endpoint be published over HTTP?
2. **`management.endpoint.<id>.access`** — and may it be used?

Widening the first is what people do. The second is what stops `heapdump`, and only
`heapdump`. Everything else has `unrestricted` sitting behind it, so for twelve of thirteen
endpoints the second gate is already open and the first is the only one there is.

## 5. 처방 / Remedy

Nothing to remedy in the application. `application.yml` has listed four ids since it was
written; what was missing is anything that notices if that changes.

| Option | Why not |
| --- | --- |
| widen exposure and rely on `access` defaults | §3.2 — that default protects exactly one endpoint, and §3.3 shows twelve others still answer |
| widen exposure and put the management port behind a firewall | a real control, and not one this repository can assert anything about. Recorded as out of scope rather than assumed |
| commit a wide-open `application.yml` as the `red` state | **rejected.** Every other trap here ships a `red` commit, and this one does not: a public repository with a wide-open actuator surface in its history is a worked example for the wrong reader, and the finding is entirely reproducible from a test property. `ADR-002` argues the schema should ship naive so defects can be measured; that argument is about a database with no users, not about a live management surface |
| **keep the narrow list, and gate it over HTTP** | **✔** |

## 6. 재계측 / Re-measurement

Not applicable — the application did not change. What changed is what CI knows.

## 7. 회귀 게이트 / Regression gate

`api/src/test/kotlin/net/gseek/proxima/management/ManagementSurfaceGateTest.kt`, run by
`.github/workflows/build.yml`:

- eleven endpoints — `beans`, `conditions`, `configprops`, `env`, `flyway`, `heapdump`,
  `loggers`, `mappings`, `sbom`, `scheduledtasks`, `threaddump` — must not answer 200
- **and `health` must**, which is the control: if the actuator were absent, misconfigured, or
  remapped, every path would 404 and the assertion above would pass over an application with
  no management surface at all

It asserts **effects over HTTP, not properties.** Reading the exposure list back from the
configuration would assert that a string in a file is the string in that file, which is the
failure that cost this repository six days (`R4` §7).

`ManagementSurfaceTest` is a second trip-wire: it asserts `heapdump` returns **404** with
exposure widened, so a future Boot that changes that access default fails the build and says
which assumption expired.

## 8. 남는 위험 / Remaining risk

- **Two of three strands are not done.** *An endpoint that authenticates and does not
  authorise*, and *token expiry and clock skew*, are untouched. This application currently has
  **no authentication of any kind**: `GET /api/v1/learners/{learnerId}/recommendations` takes
  the learner id from the path and serves anyone who asks. That is not the trap the roadmap
  names — it is the flat absence beneath it — and it is stated here rather than left for a
  reader to discover.
- **Nothing here is about a real deployment.** The gate proves the *default* configuration is
  narrow. A deployment sets `SPRING_APPLICATION_JSON`, an environment variable, or a config
  server value, and no test in this repository sees any of that. **The gate covers the file,
  not the running system.**
- **The management port is the application port.** `management.server.port` is unset, so every
  endpoint shares the application's connector and its exposure is decided entirely by these
  two properties. Whether a separate management port would be the better control is
  **미측정** and is a real option that was not compared.
- **The heap dump was searched for three strings.** Password, username, JDBC url. What else a
  156 MB dump holds — session state, request bodies, anything decrypted in memory — was not
  enumerated, and "contains the password" is a floor rather than a description.
- **`env` masking was measured for one value.** The datasource password is masked. Whether
  every other secret-shaped property is, on this version, with this sanitizer configuration,
  is 미측정.
- **`loggers` was measured as writable for one logger name.** Whether the same `POST` reaches
  a logger that is actually noisy — Hibernate's SQL logger — was not tried, and the sentence
  in §3.4 about reading bound parameters is therefore **mechanism, not measurement**.
- **Two extra Spring contexts.** `ManagementSurfaceTest` and `HeapDumpContentTest` each carry
  a distinct property set, so neither shares the cached context. `HeapDumpContentTest` also
  starts its own container for §3.5's reason. That is roughly two application startups and one
  container per CI run, paid on every build. `ManagementSurfaceGateTest` was deliberately
  written with no property override so that it costs nothing.
- **What would break the conclusion**: a Spring Boot release that changes
  `management.endpoint.heapdump.access` to `unrestricted`, or that adds a new endpoint with an
  open default. The first is caught by `ManagementSurfaceTest`. **The second is not caught by
  anything here** — the gate names eleven endpoints, and an endpoint that does not exist yet
  is not on the list.

## 9. 배운 것 / What I learned

**대조군이 살아 있다고 해서, 내가 옳은 것을 겨누고 있다는 뜻은 아니다.**

힙 덤프에 카나리 문자열을 심고, 그게 나오는 걸 확인하고, 그래서 "이 계측은 믿을 수 있다"고 넘어갔다.
바로 다음 줄에서 비밀번호를 찾았고 `true`가 나왔다. 그런데 그 비밀번호는 Testcontainers 기본값
`test`였다. 프로파일도 `test`, 데이터베이스 이름도 `test`, 소스 세트도 `test`. **156 MB짜리 덤프에서
`test`를 찾은 것이고, 그건 어떤 프로그램의 어떤 덤프에서도 나온다.**

카나리가 증명한 건 *덤프가 진짜고 검색이 돈다*는 것이었다. 망가진 건 *바늘이 특정한가*였고, 그건
다른 속성이다. R5·R8·R9에서 세 번 배운 게 "계측기가 죽었는지 확인하라"였는데, 이번엔 **계측기가
멀쩡한 채로 결론이 틀렸다.** 대조군은 통과 여부를 알려줄 뿐, 무엇을 통과했는지는 알려주지 않는다.

**그리고 네 번째다 — "모두가 아는 결함"이 이미 막혀 있었다.**

R5의 인메모리 페이징, R3의 낡은 통계, R9의 임베디드 DB 치환, 그리고 이번 힙 덤프. 네 번 다 로드맵이
"이건 이렇게 터진다"고 적어둔 대로 재현하려다 **프레임워크가 이미 고쳐놨다는 걸 발견**했다. 그럴 때
할 일은 항목을 지우는 게 아니라 **무엇이 막고 있는지를 재는 것**이다. `heapdump.access`의 기본값이
`none`이라는 사실은 문서가 아니라 jar 안의 메타데이터에서 읽었고, 그래서 이제 그 기본값이 바뀌면
빌드가 빨간불이 된다. **"이미 안전하다"로 끝냈으면 그 안전이 무엇에 의존하는지 아무도 몰랐을 것이다.**

마지막으로, 주석으로 써놓은 주장은 주장일 뿐이라는 것. `loggers`가 쓰기 가능하다고 게이트 주석에
적어놓고, 재지 않은 걸 알아채고 재봤다. 204에 `null -> TRACE`. **맞는 말이었지만, 맞는 말이라는 걸
알게 된 건 재고 나서다.**
