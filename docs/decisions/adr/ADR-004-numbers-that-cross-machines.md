# ADR-004 — Numbers that cross machines

> **Created**: 2026-08-13
> **Updated**: 2026-08-14
> **Status**: Accepted
> **Closes**: `OPEN-5`

## Context

`OPEN-5` asked how the measurement environment is pinned in CI, and framed the hazard in one
direction: **CI must not publish numbers**, because a figure from a shared runner of unstated
size is not comparable to one from the machine in `measurement-discipline.md`. Its deadline
was *before CI runs a load lane*.

That deadline has not arrived. **The hazard did.**

`R9` §3.6 needed to say what a PostgreSQL container costs against what a test run costs. The
container start was measured here — 1,506 ms, three runs, median. The test run it was compared
against was read off the **workflow API**: the `api tests` step of commit `96ad9bb` took 65 s.
The report then divided one by the other and published *"about 2%"*.

Two things are wrong with that, and only one of them was noticed at the time.

**Noticed**: `R9` §8 records that the ratio "divides a local number by a remote one" and calls
it the weakest claim in the report.

**Not noticed**: it breaks `measurement-discipline.md` **rule 3** — *before and after come
from the same run conditions; different machine, different day without re-baselining, the
comparison is not made.* The rule existed, it was written before the first measurement on
purpose, and it was broken in a report whose own §8 was uneasy about the same sentence for a
different reason.

**`OPEN-5` was guarding the gate CI would come through. The number walked out the other one.**

## Decision

**Three rules, and one step that makes the third possible.**

1. **This CI lane does not publish measurements.** Unchanged. It reports pass or fail.

2. **Assertions that run in CI must be machine-independent.** Counts, statuses, exact row
   totals, statement counts, verdicts. `R12`'s gate asserts `attempts_count == 1000` and
   `score == 1.000`; those are as true on a laptop as on a runner. **No CI assertion may be a
   duration**, and the durations that CI tests *print* — `R12`'s milliseconds,
   `ContainerStartupCostTest`'s figures — are log output, not results.

3. **A report may quote a number from a CI run, and when it does it carries the run's
   environment block and its run id.** Not "GitHub Actions". The block.

4. **A number from one machine is never combined arithmetically with a number from another.**
   Rule 3 of `measurement-discipline.md`, which already said this. Restating it here because
   it was broken by someone who had read it.

The step that makes (3) possible is in `.github/workflows/build.yml`: every run prints its
runner image, kernel, CPU model and count, memory, disk, Docker version and JDK before any
test executes. It costs about a second, and it converts a figure that escapes from the lane
from a memory into a citable measurement.

`measurement-discipline.md` gains **rule 9** saying the same thing where a reader will look
for it.

## Consequences

**What this buys.** The comparison `R9` wanted to make is now *makeable*. Someone who wants to
know what a container costs as a share of a CI run can measure both on the runner, quote that
run's block, and have a number that satisfies rule 3. Today nobody can, and `R9` §3.6 says so.

**What this costs, and it is small but real.** A step in every build, and a rule that makes
the easy version of a comparison unavailable. `R9` §3.6's "about 2%" was useful and slightly
wrong; the honest replacement is either two measurements on one machine or no ratio at all.

**What this rules out.** A load lane in CI publishing p99s, unless it carries the block per
run — which the step now provides, so that is a decision about noise and cost rather than
about honesty. And it rules out the specific move `R9` made: reaching for a number from
somewhere else because it was the only one available.

**What it does not fix.** `R9` §3.6 stays as it is, annotated. The ratio is wrong by this
document's own rule and the report that contains it is the record of how the rule was found to
be broken. Rewriting it would delete the evidence.

## What was not measured

- **Whether the runner's environment is stable between runs.** GitHub rotates images and
  hardware. The step records what each run had; nothing here establishes that two runs a week
  apart are comparable, and that is exactly why the block is per-run rather than written down
  once.
- **The cost of the step itself.** It is a handful of shell commands and was not timed.
- ~~**Whether any other report quotes a cross-machine number.**~~ **Audited 2026-08-14. See
  below.**

## The audit, 2026-08-14

The bullet above said the other reports had not been checked. They have been now, and the
result is worth recording precisely because it is boring — an audit that finds nothing is
evidence only if what it looked for is written down.

**What was looked for**, across `R0`–`R12`:

1. any report quoting a figure produced on a machine other than the one in its environment
   block — CI, the workflow API, another report's run;
2. any ratio or difference computed from figures with different provenance;
3. any report publishing numbers with no environment block at all.

**What was found:**

| | result |
| --- | --- |
| Reports quoting a CI number | **one — `R9` §3.6**, already annotated above. Every other mention of `.github/workflows/build.yml` names it as the thing that runs a gate and carries no figure |
| Ratios computed across provenances | **none.** `R12`'s 3.4× and 4.6×, `R6`'s 5.1×, `R3`'s 8.3× and 660×, `R9`'s 10× are each computed from arms measured in the same execution. `R4`'s 1.3× is a chosen threshold and says so |
| Cross-report citations | present and **attributed rather than combined** — `R12` §5 quotes *"`R6` measured it: 623 of 1,000"* and does no arithmetic with it |
| Reports with no environment block | **one — `R0`**, deliberately. It carries `근거 / Evidence base` instead, because everything it counts is machine-independent: commits, tests, which control caught which failure. **Recorded here so that nobody later "corrects" it** by pasting in a hardware block that none of its numbers came from |

**So the violation rate is one in thirteen, and the one was already known.** That does not make
the rule safe — it makes the sample small. `R9` §3.6 happened because a number was needed and
only one was available, and nothing in this audit says that pressure has gone away.

**What this audit did not check:** whether any number *within* a report was taken on a
different day from the others in the same table. Report headers carry a single environment
block and a `Created` date, and neither records per-figure timestamps, so the question cannot
be answered from the tree as it stands. **미측정, and it is the obvious next hole.**
