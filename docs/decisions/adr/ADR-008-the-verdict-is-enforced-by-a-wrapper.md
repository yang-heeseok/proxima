# ADR-008 — The steady-state verdict is enforced by a wrapper, not by a reader

> **Created**: 2026-08-18
> **Updated**: 2026-08-18
> **Status**: Accepted
> **Closes**: `OPEN-8`

## Context

`load/recommendations.js` splits its measurement window in half and compares the halves. If
they disagree by more than the band, the run was still warming or was degrading, and it is not
comparable to one that had settled. The scenario prints:

```
*** NOT STEADY STATE. The FIRST half of the measurement window was 1.41x slower,
*** DO NOT PUBLISH THIS RUN.
```

**And k6 exits `0`.** On 2026-08-17 that happened to arm B's third run in `R18`, and the run
went into a published median — found two hours later by re-reading a log, not by anything
refusing.

`R18` fixed the check itself. It made the band symmetric — the old test was `early / late >
1.3`, which fires only when a run gets *faster*, so a run whose second half was 1.33× slower
passed in silence, and **two of the fifteen measured runs did exactly that**. It moved the
verdict into `steady-state.txt`. And it left the enforcement as a `grep` in `load/README.md`
for the operator to remember.

`R18` §7 said so plainly, and `R18` §8 filed it as a risk. `R19` moved it to `OPEN-8`:

> `R17` is this repository's report on what becomes of a rule whose only enforcement is a
> person remembering it — **three failures in seven days, every one caught by a human.**

## Decision

**`load/run.sh` wraps `k6 run`, reads the verdict, and exits non-zero on `FAIL`. It is the
only documented way to run a scenario in `load/`.**

Three exits, and the third matters as much as the second:

| verdict | exit | why |
| --- | --- | --- |
| `OK` | k6's own status | nothing added |
| `FAIL` | **1** | the scenario declared its own run unpublishable |
| **no file at all** | **2** | the run never reached `handleSummary`. **A run with no verdict is not a passing run** — treating absence as consent is the vacuous-gate failure `R9` §7 and `R16`'s `rate>=0.0` threshold are both about |

`load/selftest-ok.js`, `-fail.js` and `-none.js` are planted scenarios that produce one verdict
each, and `.github/workflows/load-harness.yml` requires the wrapper to return 0, 1 and 2
respectively. **`ok` returning 0 is the negative control**: a wrapper that refused everything
would satisfy the other two and be worthless.

## What this does not do

**It does not remove the person.** Somebody still has to type `./run.sh`. Anyone determined to
run `k6 run` directly still can, and nothing stops them.

What changes is *when* the failure is loud. Before: the run finishes clean, the numbers look
fine, and the problem is found — if it is found — while writing the report. After: the shell
returns 1 at the moment it happens.

**That is a smaller claim than "enforced" and it is the honest one.** It is also why the
wrapper wraps `k6 run` rather than sitting beside it as a checker: a separate checker creates a
second thing to remember, and puts the problem back where it started.

## Alternatives, and their costs

| Option | Cost | Verdict |
| --- | --- | --- |
| **A k6 threshold** | — | **Not possible.** A threshold is evaluated over one metric; this is a ratio between two, known only once the run is over. `teardown` cannot read metric values either. This is a limit of the tool and is written into the scenario |
| **A CI load lane** | The seeded 3,963,719-row database on every run, and `publication-readiness.md` records that a switch to private meters the Actions minutes | **No, and for a reason beyond cost.** `ADR-004` forbids CI asserting a duration, because a shared runner of unstated size yields nothing comparable. **So a CI load lane cannot produce a citable latency number at all** — it could only run the verdict check, which makes it a lane whose sole purpose is enforcing the verdict of a measurement it may not take. That is circular |
| **Accept procedure permanently, and say so in one place** | zero | No. `R17` measured what that becomes, and this repository would be choosing it *after* reading its own report on it |
| **The wrapper** | one script, three planted scenarios, one small CI job | **Chosen** |

The CI job that tests the wrapper is *not* the rejected CI load lane and is not a step toward
it. It runs three one-iteration scenarios and no database; what it exercises is control flow,
which is machine-independent — exactly the kind of assertion `ADR-004` permits CI to make.

## The lane failed on its first run — once — and found a second defect by being read

> **This heading said *twice* until it was checked.** The lane failed **one** time, on defect 1
> below. Defect 2 sits after the line that failed and **was never executed**; it was found while
> diagnosing the first, by reading. Calling both *catches* credits a guard with work a person
> did, which is the exact accounting `R0` §4 refuses — and it was written into the section
> describing that refusal.

**1. `run.sh` was committed `100644`.** Not executable, so `./load/run.sh` could not run at all.
Invisible locally: the working tree is on a WSL2 drvfs mount where every file reads `0777`
whatever git recorded. **`R1` §9 is the same failure** — `gradlew` committed without its
executable bit, CI unable to start Gradle, and the same mount hiding it. Nothing had been built
to catch it in between, so it recurred at the first opportunity. The lane now asserts the
committed mode and says how to fix it, and it invokes `./load/run.sh` as a path rather than
`bash load/run.sh`, which would paper over the bit and let it rot.

**2. The negative control would have killed the step when it passed — latent, never reached.**

```bash
grep -q 'NOT STEADY' /tmp/ok.log && { echo "FAIL: ..."; exit 1; }
```

Under `set -eu`, a `grep` that finds nothing — **the correct outcome** — makes the whole
compound return non-zero and the shell exits. **It would have failed precisely when the thing
it guards behaved**, and it never got the chance: defect 1 killed the step nine lines earlier.
A gate that is always red ends the same way as one that is always green — uninstalled within a
week, for opposite-looking reasons — and this one would have been red on the first day the
wrapper worked.

**The common cause is one sentence.** The wrapper was tested locally three times; the
*workflow step's shell* was not run once. Testing the subject and not the instrument is the
recurring shape in this repository — `R5`'s appender, `R10`'s canary, `R16`'s `rate>=0.0`
threshold — and this time the instrument was seven lines of `bash` that nobody executed. The
step's logic is now run verbatim under `set -eu` before it is pushed.

## What would flip this

A k6 release that lets `handleSummary` set an exit status, or thresholds that can be expressed
over a derived value. Either would move the enforcement inside the scenario, where it belongs,
and make `run.sh` a convenience rather than a guard.
