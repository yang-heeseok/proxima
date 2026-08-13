# R11. Authenticated, and not authorised

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
> **Covers**: `T9` strands two and three — *an endpoint that authenticates and does not
> authorise*, and *token expiry and clock skew*. Strand one is `R10`.
> **Red commit**: **none, for the same reason `R10` §5 gives.** The `red` arm here is an
> endpoint that hands every learner's data to every caller; publishing a commit of that to a
> public repository is a worked example for the wrong reader. It runs from one property in
> this commit, exactly as `T1`'s `entities` arm does, and §2 says how.
> **Green commit**: this one — `authorisation: owner`, `expiry-policy: skewed`, and three
> gates

```
측정 환경 / Measurement environment
  Hardware   : Intel Core Ultra 7 258V, 8 cores / 8 threads, 31.5 GB RAM
  OS         : Windows 11 Home 10.0.26200 + WSL2 Ubuntu 24.04
  Runtime    : Spring Boot 4.1.0, JDK 21.0.12+8
  PostgreSQL : Testcontainers postgres:16-alpine -- server 16.14
  Client     : java.net.http.HttpClient, same host
  Signature  : HmacSHA256, javax.crypto.Mac
  Clock      : injected. Strand three moves it rather than waiting
  Load       : none. These are status codes and verdicts
```

> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

Before this commit, `GET /api/v1/learners/{learnerId}/recommendations` served anyone who
asked. That is **not** the defect `T9` names. It is the flat absence underneath it, and
nobody who saw it would call it secure.

The defect is one step further on, and it is the step that looks finished: **a token is
required, it is verified, an invalid one is refused — and the endpoint still hands over any
learner's data to any caller.**

That configuration passes every review heuristic anyone applies at a glance. There is a
filter. There is a signature. There are 401s in the logs. The missing line is a comparison
between two numbers the application is already holding.

## 2. 재현 / Reproduction

```bash
./gradlew :api:test --tests "net.gseek.proxima.security.*"
```

Both arms live in one binary — `R4` §2's argument, because two builds means two
configurations sitting beside the difference being measured:

| property | arm |
| --- | --- |
| `proxima.security.authorisation` | `none` — the path names the learner, the verified caller is ignored. **`red`** |
| | `owner` — the path must name the caller, or 403. **ships** |
| `proxima.security.expiry-policy` | `ignored` — the signature is checked and the timestamps are not. **`red`** |
| | `strict` — expired and not-yet-valid refused, zero tolerance |
| | `skewed` — the same, 30 seconds of leeway. **ships** |

**`RequestToken` is a measurement fixture, not an authentication design.** No rotation, no
revocation, no issuer, no audience, no login. It exists because two of `T9`'s three strands
are about the gap between verifying a caller and deciding what they may reach, and neither
exists without something to verify. A reader who takes the token instead of the measurements
has taken the wrong thing, and its KDoc says so first.

## 3. 계측 / Measurement

### 3.1 What adding authentication broke

Measured before fixing anything, because the number is the point:

```
56 tests completed, 1 failed
recommendation.ConnectionHoldingGateTest
  401 {"error":"missing-token"}  ==> expected: <200> but was: <401>
```

**One test out of fifty-six.** The filter is registered under `/api/v1` rather than for the
whole application, and `ConnectionHoldingGateTest` was the only test in the repository that
called that path. `R10`'s three management gates were untouched, which was the reason for
scoping the filter that way rather than mapping it everywhere and calling it stricter.

It cost something. That gate now depends on the token verifier working, so a broken verifier
turns `T1`'s gate red as well as `T9`'s, and a failure there no longer localises on its own.
Its assertion message prints the response body and names both possibilities — which is how
the 401 above was diagnosed in one read.

### 3.2 The control: authentication is actually happening

Without this, a `200` on a cross-learner request cannot be told apart from an application
that never checked anything.

```
T9-AUTHN >>> the filter is doing its job
  no Authorization header : 401  {"error":"missing-token"}
  tampered signature      : 401  {"error":"bad-signature"}
```

### 3.3 The measurement: an authenticated learner reads another learner's data

```
T9-IDOR >>> proxima.security.authorisation = none
  alice's token -> alice's learner id : 200
  alice's token -> bob's learner id   : 200
  and the body carries bob's item     : true
```

**The assertion is on the item code, not the status.** A `200` carrying `[]` and a `200`
carrying a leak are the same status code, and this repository has already shipped one test
that proved nothing because its scope was wrong (`R8` §3.1). Each learner in the fixture owns
exactly one item code, so "bob's data" is a string that can only have come from bob.

Under the shipped `owner` arm the same request is **403**, and alice's own request is still
**200 with alice's item** — `AuthorisationGateTest`, and §7 explains why that second assertion
is not optional.

### 3.4 Expiry, and what a clock-skew allowance actually does

Verifier's clock fixed; the issuer's clock moved around it. No sleeping.

```
T9-EXPIRY >>> verifier clock fixed at 2026-08-13T12:00:00Z, skew tolerance 30s
  scenario                   ignored        strict         skewed
  fresh, clocks agree        trusted        trusted        trusted
  expired 1 second ago       trusted        expired        trusted
  expired 60 seconds ago     trusted        expired        expired
  issuer's clock 10s ahead   trusted        not-yet-valid  trusted
  issuer's clock 60s ahead   trusted        not-yet-valid  not-yet-valid
```

Three things, and the third is the one worth the report.

**`ignored` means no token in the system can ever be outlived.** The signature is valid, so
the answer is yes, forever. Revocation-by-waiting — the only revocation this design has — does
not exist. Every row of that column is `trusted`, including a token that expired a minute ago.

**`strict` refuses valid tokens.** A machine whose clock is ten seconds ahead mints a token
that arrives from the future, and a verifier with zero tolerance cannot distinguish that from
a forgery. Nothing is wrong with the token, the user, the signature, or the code. This is the
outage that gets diagnosed as "intermittent auth failures" for a week.

**And `skewed` buys the fix in one direction only by paying for it in both.** The same
thirty seconds that rescues the ten-seconds-ahead token also keeps a token that expired one
second ago **trusted**. That is not a bug in the tolerance; it is what a tolerance *is*. It is
worth stating because the mistake is not choosing one — it is choosing one without noticing it
is a fraction of the token's life:

```
T9-TOLERANCE >>> what 30 seconds of leeway costs at each token lifetime
  ttl 30S  + 30s tolerance -> usable for 1M      (100% longer)
  ttl 5M   + 30s tolerance -> usable for 5M30S   (10% longer)
  ttl 1H   + 30s tolerance -> usable for 1H30S   (1% longer)
```

**A thirty-second token with a thirty-second tolerance is a sixty-second token.** Shortening a
credential's life and keeping a generous skew allowance are not two independent hardening
steps; below a couple of minutes they are the same dial pulling opposite ways.

### 3.5 Two failures on the way in, neither about security

Recorded because both are the same shape as the entity-scan failure in `ADR-003`, on the same
day: **something readable was inferred instead of read.**

| what happened | what it cost |
| --- | --- |
| A KDoc quoted the filter's url pattern. A slash followed by a star **opens a nested block comment in Kotlin**, so the comment never closed | the file did not compile |
| A `Clock` bean was written for the verifier, with a KDoc arguing why it must be injected. `ProximaConfiguration` already had that bean, with the same argument, for the same reason | `BeanDefinitionOverrideException`, **37 of 56 tests failed**, none of them about security |

The second is the instructive one. The reasoning was sound and it was re-derived rather than
looked for — and *being obvious enough to reach twice* is precisely what makes it likely
somebody already reached it.

## 4. 원인 / Mechanism

**Authentication answers `who is calling`. Authorisation answers `may this caller have this`.**
They are answered in different places, by different code, and the first produces an artefact —
a verified subject sitting in the request — that makes the second look already done. `T3`'s
annotation that does nothing has the same shape: the presence of the mechanism is mistaken for
the effect of it.

Expiry is arithmetic on two timestamps taken from two clocks that are never the same clock.
Any tolerance for that difference is symmetric, because the verifier cannot tell which
direction the drift is in — it has one clock and a number, and `now - exp` does not say whose
fault it is.

## 5. 처방 / Remedy

| Option | Why not |
| --- | --- |
| check ownership in the filter | the filter does not know what a learner id in a path means; it would have to parse routes, and it would be wrong for the next endpoint with a different shape |
| a framework's method security | a real answer, and one that needs a dependency this measurement does not need. `R11` is about the gap, not about which library closes it. **Recorded as the thing to reach for in a real system, not as something measured here** |
| return 404 rather than 403 | a real technique with a real cost — it makes an authorisation failure and a typo indistinguishable in the logs. Not taken, and said out loud in the controller so that an unexamined 404 does not later look considered |
| **compare the verified subject with the resource owner, in the handler** | **✔** |
| **and pick an expiry policy deliberately, with the tolerance written down as a fraction** | **✔** |

## 6. 재계측 / Re-measurement

The same request, same fixture, shipped configuration:

| | `authorisation: none` | `authorisation: owner` |
| --- | --- | --- |
| alice's token → alice | 200, alice's item | 200, alice's item |
| alice's token → bob | **200, bob's item** | **403** |

| | `expiry-policy: ignored` | `expiry-policy: skewed` |
| --- | --- | --- |
| token expired an hour ago | trusted | **401 `{"error":"expired"}`** |
| token minted now | trusted | 200 |

## 7. 회귀 게이트 / Regression gate

Two, both over HTTP against the shipped configuration, both carrying their own control. They
share `ConnectionHoldingGateTest`'s and `ManagementSurfaceGateTest`'s cached application
context — no property overrides, deliberately, so a gate costs no startup.

- `api/src/test/kotlin/net/gseek/proxima/security/AuthorisationGateTest.kt` — one learner's
  token must not read another's (**403**) **and** must read their own (**200**, with the item
  code). Only the pair is a claim: "refuses cross-learner access" passes on an application
  that refuses everything.
- `api/src/test/kotlin/net/gseek/proxima/security/TokenExpiryGateTest.kt` — a token that
  expired an hour ago must be refused **with reason `expired`** — not merely refused, because
  a wrong signature would also produce a 401 and the gate would be passing on an accident —
  **and** a current token must be served.

`TokenExpiryTest` is the third: no Spring, three policies, five clock scenarios. It proves the
arithmetic. It cannot prove the shipped configuration selects a policy that uses it, which is
why `TokenExpiryGateTest` exists — `expiry-policy: ignored` is a one-word edit that breaks no
unit test at all.

## 8. 남는 위험 / Remaining risk

- **There is no login, and no way to obtain a token.** `RequestToken.issue` is called by
  tests. Nothing issues one to a person, so the system this report measures is a verifier
  without an issuer. The defect measured is real and the system around it is not complete.
- **One endpoint is authorised.** `RecommendationController` compares subject to path.
  Everything added later starts unauthorised by default, and **nothing fails when it does** —
  the gate names one path. A structural rule in the `T3` style, asserting that every handler
  taking a `learnerId` also authorises, is the thing that would generalise it. Not written.
- **The token has no revocation and this report does not treat that as a defect.** With
  `skewed` the only way to end a session is to outlive it. That is a property of the fixture,
  not a finding, and a real system needs a different answer.
- **The secret is a single static key with no rotation.** Changing it invalidates every token
  at once. 미측정, and not a design anybody should copy.
- **Skew tolerance was measured at exactly 30 seconds against three token lifetimes.** The
  arithmetic generalises; the *right* tolerance for a deployment depends on how its clocks are
  actually synchronised, which is **미측정** and unmeasurable from here.
- **The clock scenarios are simulated.** A fixed `Clock` is a faithful model of a constant
  offset and says nothing about a clock that steps, drifts, or goes backwards during a
  request. Real NTP behaviour is not modelled.
- **`strict` was measured as refusing a ten-second-ahead token; no deployment was measured.**
  Whether ten seconds of drift is realistic on any particular fleet is a claim this report does
  not make.
- **What would break the conclusion**: adding a second resource whose owner is not the learner
  in the path — a teacher reading a learner's data, say. `authorisation: owner` is then wrong
  in the other direction, and §3.3's gate would be enforcing a rule the domain had outgrown.

## 9. 배운 것 / What I learned

**메커니즘이 있다는 것과 효과가 있다는 것은 다른 사실이다.**

필터가 있고, 서명이 검증되고, 401이 로그에 찍힌다. 리뷰에서 확인하는 것은 대개 거기까지다. 그런데
그 모든 게 참인 채로 앨리스의 토큰이 밥의 데이터를 200으로 받아왔다. **인증은 "누구인가"에 답하고,
그 답이 요청 안에 놓이는 순간 "무엇을 가져도 되는가"가 이미 답해진 것처럼 보인다.** T3의 아무 일도
하지 않는 애노테이션과 같은 형태다 — 장치의 존재를 효과로 착각하는 것.

**그리고 관용치는 한 방향으로만 살 수 없다.**

시계 오차 때문에 멀쩡한 토큰이 거절되는 걸 막으려고 30초를 넣으면, 만료된 토큰도 30초 더 산다.
버그가 아니라 정의다. 문제는 그걸 **고르는 줄 모르고 고른다는 것**이다. 30초짜리 토큰에 30초 관용치는
수명 2배다. "토큰 수명을 짧게"와 "시계 오차에 관대하게"는 독립된 두 개의 강화 조치처럼 들리지만,
몇 분 아래에서는 **같은 다이얼을 반대로 당기는 것**이다.

**마지막으로, 오늘 세 번 같은 실수를 했다.**

엔티티가 스캔 루트 안인지 확인 안 함. Kotlin 주석이 중첩되는지 모른 채 URL 패턴을 문서에 씀. `Clock`
빈이 이미 있는지 찾아보지 않고 정의함. 셋 다 **읽으면 알 수 있는 것을 추론으로 대체**했다. 세 번째가
제일 뼈아프다 — 나는 "시계는 주입해야 테스트가 시간을 고정할 수 있다"는 논증을 처음부터 다시 써냈고,
`ProximaConfiguration`에는 거의 같은 문장이 이미 적혀 있었다. **논증이 두 번 도달할 만큼 자명하다는
것은, 누군가 이미 도달했을 가능성이 높다는 신호다.**
