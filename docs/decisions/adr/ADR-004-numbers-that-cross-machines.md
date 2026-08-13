# ADR-004 — Numbers that cross machines

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
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
- **Whether any other report quotes a cross-machine number.** `R9` §3.6 was found by looking
  for it after `R12` was written. **The other eleven reports were not audited**, and that is a
  gap this decision does not close.
