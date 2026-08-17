# R{n}. {제목}

> **Created**: YYYY-MM-DD
> **Updated**: YYYY-MM-DD
> **Red commit**: `{sha}` — the state in which this was observed
> **Green commit**: `{sha}` — the state in which it was not

```
측정 환경 / Measurement environment
  Hardware       :
  OS             :
  JVM            :
  PostgreSQL     :
  Connection pool:
  Dataset        : seed value 20260810
  Load           : k6, 30s warm-up DISCARDED, 3min measurement window
  Repetitions    : 3 runs, median reported
```

<!--
  IF ANY NUMBER IN THIS REPORT CAME FROM A CI RUN, it carries that run's own environment
  block and its run id -- measurement-discipline rule 9, ADR-004. The lane prints one before
  it runs anything, so there is no excuse for quoting "on GitHub Actions" and stopping there.

  AND A NUMBER FROM ONE MACHINE IS NEVER COMBINED ARITHMETICALLY WITH ONE FROM ANOTHER.
  Rule 3 already said so and was broken anyway, in R9 §3.6, by someone who had read it: a
  container-start figure measured locally divided by a step timing read off the workflow API.
  If the number you want does not exist on this machine, the honest output is 미측정 -- not a
  ratio assembled from two places.

  A report that measures no durations -- a scorecard, an audit -- replaces this block with
  what its evidence actually is, rather than pasting hardware none of its numbers came from.
  R0 does this on purpose.
-->


> Rules for every number below: `docs/explanation/measurement-discipline.md`.
> **미측정 means not measured.** It never means "roughly the same".

---

## 1. 증상 / Symptom

What was observed, before any theory about why. No cause here — a symptom section that
already names the cause is a section written backwards, after the answer was known.

## 2. 재현 / Reproduction

The procedure someone else follows to see the same thing. Exact commands, exact
configuration, exact concurrency. Names the red commit.

## 3. 계측 / Measurement

Numbers and logs, **verbatim**. A summarised log line is not evidence.

| Metric | Value |
| --- | --- |
| p50 / p95 / p99 @ {N} VU | |
| Error rate | |
| {domain-specific metric} | |

```
{log output, unedited}
```

## 4. 원인 / Mechanism

Why it happens. Not "because OSIV was on" — *what OSIV does*, and why that produces this
symptom under this load. Links to the upstream documentation or issue that establishes it.

## 5. 처방 / Remedy

Options, compared. A single option presented as the answer is a decision that was not made.

| Option | Effect | Cost | Chosen |
| --- | --- | --- | --- |
| | | | |

Why this one — including what would have made a different option correct.

## 6. 재계측 / Re-measurement

Identical conditions to §3. If the conditions changed, they are re-baselined and that is
stated.

| Metric | Before | After |
| --- | --- | --- |
| | | |

## 7. 회귀 게이트 / Regression gate

What turns red if this defect returns. A test, an assertion, an ArchUnit rule, a CI check —
named by file.

**If this row is empty, the defect is not fixed. It is currently absent.**

## 8. 남는 위험 / Remaining risk

**This section may not be empty.** `PUB-4`.

- What is still wrong.
- What the remedy traded away.
- **What was not measured**, stated as 미측정 rather than omitted.
- What would break this conclusion — the condition under which the number above stops
  being true.
- **Which earlier §8 bullet this report falsifies** — annotated in that report, beside the
  sentence, not summarised here. A bullet is written about the tree at one instant and read
  as a standing risk, and nothing marks the difference: R19 §3.4 found three that had gone
  false with nothing anywhere saying so, and in two cases the correcting commit had the
  stale file open. This line is procedure and is enforced by nothing; R19 §7 is why.
- **Whether any bullet here needs a judgement rather than only work.** If it does it is a
  row in docs/decisions/open.md, not a risk -- a risk is something someone chose to live
  with. OPEN-6 is the precedent and R19 is what it costs when nobody asks.

## 9. 배운 것 / What I learned

One paragraph, in the first person, not polished. What was surprising, what the
documentation did not say, what would have been believed for years if this had not been
run.

Written the same day as the measurement. Written a week later it becomes a summary of the
conclusion, and the useful part — the part where it was not yet obvious — is gone.
