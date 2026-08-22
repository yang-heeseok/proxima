# ADR-017 — The image is pinned by digest, the documents are corrected rather than re-baselined, and the tag is watched

> **Created**: 2026-08-22
> **Updated**: 2026-08-22
> **Status**: Accepted
> **Closes**: `OPEN-10`

## Context

`postgres:16-alpine` was repointed on 2026-08-13, from a July build of PostgreSQL 16.14 to an
August build of 16.15. **Nothing in this tree changed**, so nothing here could go red —
`docs-consistency.yml` compares a document's `Updated` date against when the file last changed,
and no file changed. `R27` found it by asking the registry.

What made it a defect rather than an untidiness: `build.yml` has no image cache, so **every CI
runner since that date pulled 16.15 while this machine's Docker cache still held the July
image.** Local and CI were running different servers, and every environment block described the
local one.

And the record already knew better than the build did. `measurement-discipline.md` has carried
the digest since 2026-08-10 and calls it *"what makes the row citable"* —
`TestcontainersConfiguration.kt` pinned the **tag**, so the digest reached no artefact.

`OPEN-10` was two questions: **does the image get pinned**, and **if it does, do the documents
get corrected or do their numbers get re-baselined on 16.15?**

## Decision

**Pinned by digest. Documents corrected. Nothing re-baselined. The tag is watched on a
schedule.**

1. `TestcontainersConfiguration.POSTGRES_IMAGE` is
   `postgres@sha256:cf78e766…` — the **index** digest — with `POSTGRES_TAG` kept beside it as
   a constant rather than a comment, so a check can read it. `8dec7e6`.
2. Every environment block that led with the moved tag now leads with the version and points
   at the digest already printed beneath it.
3. `.github/workflows/image-pin.yml` compares the pin against what the tag resolves to, on a
   `cron` as well as on pushes that touch the pin.

## Why not re-baseline

`R27` §3.2 compared **twelve facts** across the two images and found **three differences, all
of them the same fact** — the version string. Migrations apply and ordering holds identically
on both, and alpine 3.24.1 / musl 1.2.6-r2 are unchanged, which is what keeps `R25` and `R26`
standing.

Re-measuring would spend this repository's scarcest resource on a difference it has already
established is invisible, and `R18` measured a **1.27× drift band** on this machine over
seventy minutes — most of what would be re-measured sits inside its own noise.

## Why the correction was six lines and not twenty documents

`R27` §5 speaks of *"twenty documents"*. Counted while doing the work:

| | |
| --- | --- |
| files mentioning `16.14` | **44** — including `.study` and round two's own reports |
| files carrying an environment-block identifier line | **8** |
| of those, already saying 16.15 | **2** |
| identifier lines actually corrected | **6** |

The other mentions are prose *about* this finding and are correct as written. **Correcting
them all would have been a sweep, and the sweep was never the work.**

**And no block was ever wrong about what it ran on.** Every one naming 16.14 carries the digest
on the following line. The defect was that they **led with the moving name** — which is the
string a reader copies into a terminal. The identifier line now leads with the version, points
at the digest, and records the tag as history. Nothing was falsified to fix it.

## Why the guard can exist now, when `R27` refused it

`R27` §5 weighed exactly this check and rejected it:

> *"it is red today and would stay red, because the tag has already moved. A check nobody can
> make green is a check somebody disables."*

**Correct when written, and spent by the pin.** The pin was taken from the tag's current value,
so the two agree and the job starts green — verified before committing it. `R27` named the
ordering itself: *"pin first, then guard the pin."* This is the second step, and it was not
available until the first one happened.

**Scheduled and not push-only**, for the reason this whole row exists: the tag moves with no
commit here. `study-consistency.yml` carries a `cron` for the identical shape and says so.

## Consequences

- A deliberate image bump is now a commit, and an accidental one is a scheduled failure with
  instructions rather than a silent divergence.
- **A red `image-pin` job does not mean anything is broken.** The suite still runs the pinned
  digest and every number here is still reproducible; what it asks for is a decision, and the
  failure message says to measure with `ImageTagDriftTest` before moving the pin.
- The pin freezes CI on an image that stops receiving base-layer patches. **For a measurement
  repository that is the right trade and it is not free** — the scheduled job is what stops the
  freeze from being silent.
- `ImageTagDriftTest`'s *"why there is no gate here"* section is discharged and annotated; the
  class stays as an instrument that asserts nothing.

## What would flip this

- **A tag move that is not a patch release.** If `alpine` moves to 3.25 the **musl version
  changes**, and `R25` and `R26` rest on this image being musl-built. That is a re-baseline,
  not a pin bump, and the guard's failure message says so.
- **A CVE in the pinned base that matters to a test database.** The trade above is
  reproducibility over currency; a security fix inverts it for as long as it takes to
  re-measure.

## What was not measured

- **Whether CI actually pulled 16.15.** It follows from `build.yml` having no image cache and
  the tag having moved, and it is not confirmed against the Actions API — `gh` is not on this
  machine. `R27` §8 marks it `미측정` and this ADR does not upgrade it.
- What the pin costs in staleness over time. There is no number here, only the schedule.
