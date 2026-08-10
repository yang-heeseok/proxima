# Publication readiness

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Requirements settled. The checklist is in progress — this repository currently
holds documents, guards, and a baseline schema, and no application code yet.

This document owns **the standing requirements for publication, and what discharges each.**

Adapted from `you-tility` at `8f0d788`, which adapted it from `hanok` at `839f9d3`. `PUB-1`
to `PUB-5` are carried. **`PUB-6` (health data) does not apply here and is dropped;
`PUB-7` is new and is this project's own** — neither predecessor modelled a person's
learning behaviour.

## What this document does not own

| Question | Owner |
| --- | --- |
| When to publish | The PO. Not systematised |
| What the modules are | `docs/explanation/domain-model.md` |
| How a number in a report was produced | `docs/explanation/measurement-discipline.md` |
| Where to report a vulnerability | `SECURITY.md` |
| What is still undecided | `docs/decisions/open.md` |
| Why a technical choice was made | `docs/decisions/adr/` |

---

## Public first, private later — what that actually costs

The PO's decision is that this repository is **public from early in its life**, with a
possible switch to private later. That ordering is not symmetric, and the asymmetry is
worth stating before it is discovered.

**What a later switch to private does NOT undo:**

| | Why |
| --- | --- |
| **A secret that was ever pushed** | Public commits are scraped by automated harvesters within minutes. The credential is compromised at push time, not at discovery time. Rotation is the only remedy — `PUB-1` |
| **Learner data that was ever pushed** | The same, and worse: a credential can be rotated and a disclosure cannot be un-made — `PUB-7` |
| **An existing fork** | A fork made while the repository was public survives the switch. The upstream owner cannot delete it |
| **The Apache-2.0 grant on what was published** | The licence on a published version is irrevocable for that version. Going private stops future publication; it does not claw back what was released |
| **Cached copies** | Search-engine caches, archive services, and package proxies each hold their own copy on their own schedule |

**What a later switch to private DOES change, and should be budgeted for:**

| | Effect |
| --- | --- |
| **GitHub Actions minutes** | Free for public repositories; metered for private ones. The workflows here run on every push, and this project's CI will grow a Testcontainers lane that is not cheap. A CI design built assuming free minutes becomes a line item |
| **`gitleaks-action` licensing** | Free for public repositories and individual accounts. A private repository **under an organisation** requires a `GITLEAKS_LICENSE` secret. Verify before any such move — the note is repeated in the workflow itself |
| **Hosted secret scanning and push protection** | Availability differs by plan for private repositories. This is the stated reason the scanner runs *inside* the workflow rather than as a hosted feature |

**The practical consequence.** Treat the first public push as the irreversible act it is.
Every guard in `.github/workflows/` exists to be in place *before* it, not after.

---

## Standing requirements

**If any one of these breaks, it is not *"not yet time to publish"* — it is a defect to fix
now.**

| # | Requirement |
| --- | --- |
| `PUB-1` | **The repository contains no secrets** — history cannot be erased |
| `PUB-2` | **The licence holds** |
| `PUB-3` | **The documentation explains itself** — an outside reader learns the purpose, structure, and state from the repository alone |
| `PUB-4` | **The state of decisions and code is honest** — and, in this repository, **so is the state of every measurement** |
| `PUB-5` | **A security contact exists** — a reader who finds a vulnerability knows where to send it, and what response to expect |
| `PUB-7` | **No real learner data is present** — not in the tree, not in history, not in a test fixture |

### Why `PUB-4` is stricter here than in its predecessors

This repository's product is not code. It is **numbers**, and numbers are the easiest thing
in a repository to state without having established. A prose claim can be checked against
the tree; a claim that something ran at 210ms cannot be checked against anything unless the
conditions were written down.

So `PUB-4` is extended: **a number published here carries the environment it was taken in,
and a number that was not taken is called 미측정 rather than estimated.** The rules are in
`docs/explanation/measurement-discipline.md` and the report template enforces their shape.

A report with no *남는 위험 / Remaining risk* section fails `PUB-4`. Not because the
section is mandatory paperwork, but because a report that found nothing left to worry
about has almost certainly stopped looking.

### Why `PUB-7` is a standing requirement and not a review item

The pressure to commit a real dataset comes from the work going **well**. The moment a
measurement looks interesting, the fastest way to let someone reproduce it is to attach the
rows it came from. A requirement that depends on nobody feeling that pressure is not a
requirement.

A learning record is a behavioural record: what someone got wrong, how long they took, when
they gave up, which concept they were not ready for. In this domain that someone is
frequently a minor, and under Korean PIPA a child's record carries a higher bar.

"Anonymised" is a judgement call, and this repository makes no such judgement. **The seed
is code, not data** — a generator with a fixed seed value, so a reader reproduces the
dataset by running something rather than by downloading rows. That is why the guard blocks
the file class rather than reviewing the files.

## Checklist

**A "state" column is a declaration, and declarations drift.** Each row therefore says how
its state is established. Rows marked **observed** are checked by a workflow and cannot
silently stop being true. Rows marked **reviewed** are established by a person looking, and
are the weaker kind — a row that stays "reviewed" for long is a candidate for being made
observable.

| Discharges | Item | State |
| --- | --- | --- |
| `PUB-1` | A scanner runs inside the workflow, not as a hosted feature, so it survives a switch to private | **reviewed** — in place 2026-08-10; nothing yet stops its removal |
| `PUB-1` | Full-history scan is clean, or every finding carries a rotation date | **observed** — the scheduled lane of `secret-scan.yml` |
| `PUB-1` | The commit that introduces the scanner was itself scanned | **reviewed** — a workflow running on push cannot cover the commit that adds it. Run `gitleaks detect` locally against the tree before the first public push; record the result here. **Not yet done** |
| `PUB-2` | `LICENSE` is the canonical Apache-2.0 text | **observed** — checksum guard, pinned 2026-08-10 |
| `PUB-3` | `README.md` states purpose, structure, and current state | **reviewed** — written 2026-08-10 |
| `PUB-3` | Every managed document carries `Created` and `Updated` | **reviewed** — no workflow enforces it yet |
| `PUB-4` | Every document states what it does not own | **reviewed** |
| `PUB-4` | No prose claims an implementation state that does not exist | **reviewed** — the repository currently has no application code, and every document says so |
| `PUB-4` | **Every published number carries its measurement environment** | **reviewed** — the report template carries the block; **no workflow enforces it yet.** This row is a candidate for becoming observable |
| `PUB-4` | **Every report has a non-empty *남는 위험* section** | **reviewed** — a machine can see the heading exists, not that it is honest. Making the heading observable is worth doing anyway |
| `PUB-4` | Every undecided item is recorded as undecided | **observed by presence** — `docs/decisions/open.md` |
| `PUB-5` | `SECURITY.md` names a contact and a response expectation | **observed by presence, reviewed for accuracy** — in place 2026-08-10 |
| `PUB-5` | The contact is an address the PO intends to publish | **reviewed** — `gseek@gseek.net`, carried from hanok's settled decision. A published address is permanently scrapeable |
| `PUB-7` | No data-class file is tracked anywhere | **observed** — `no-learner-data.yml`, and the guard is self-tested against planted violations |
| `PUB-7` | No `.sql` outside the migration directory | **observed** — same workflow. Migrations carry schema; a `.sql` elsewhere is usually rows |
| `PUB-7` | Nothing shaped like a personal identifier is tracked | **observed** — pattern check, self-tested |
| `PUB-7` | The seed is produced by code from a fixed seed value | **reviewed** — the design; see `docs/explanation/domain-model.md`. Becomes observable once the generator exists and CI regenerates it |
| `PUB-7` | No generated identifier can be mistaken for a real one | **reviewed** — a machine can see a shape, not an intention |
