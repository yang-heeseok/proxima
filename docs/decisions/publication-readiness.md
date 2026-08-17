# Publication readiness

> **Created**: 2026-08-10
> **Updated**: 2026-08-17

**Status:** Requirements settled and the checklist is live. **Nineteen reports (`R0`–`R18`),
`T1`–`T9` measured, 74 tests, five workflows.** `PUB-4`'s prose row is `partly observed` as of
2026-08-17 and every other row's state is in the table below, established the way it says.

> **This line said *"No defect has been reproduced and no report exists yet"* until
> 2026-08-17** — true when written on 2026-08-10, false from the first report onward, and
> **still there in the commit that added the guard against exactly this.** `R17` §8 names the
> hole in the second bullet: nothing here reads a claim, so a document whose date is correct
> and whose sentence is false passes every check. It took under an hour for that bullet to
> acquire an example, and the example is this paragraph, in the document that owns `PUB-4`.

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

### A push is the publication — so the scan happens before it

An earlier draft of this document justified the local scan by claiming that *a workflow
running on push cannot cover the commit that adds it.* **That is not true of GitHub
Actions** — workflow definitions are read from the ref being pushed, so the commit that
introduces a scanner is scanned by it. The claim has been removed rather than quietly
softened, because a requirement resting on a false premise is worse than no requirement:
it gets satisfied, and nothing was checked.

The real reason is simpler and does not depend on any CI platform's semantics:

```
git push  ─────────────►  published, irreversibly
                             │
                             └──►  CI starts  ──►  scanner finds it  ──►  notification
```

A credential in a public commit is compromised at push time, not at discovery time.
Automated harvesters read public commits within minutes. **Every CI check is after the
event it was supposed to prevent** — on a public repository the push lane is not
prevention, it is incident detection with a short head start.

Prevention has to happen where the irreversible act has not happened yet, which is
locally. Hence the row above, and hence it names a date and a version rather than an
intention.

### What the self-test found

The rules were tested the way everything else here is tested: by planting what they are
supposed to catch and watching whether they refuse it. **Three secrets were planted and
the custom rules caught one.** Two defects, both invisible to any amount of reading:

1. **The rules matched only flattened property keys.** `spring.datasource.password=…`
   matched; the nested YAML form — where the key on the line is just `password` — matched
   nothing. That is the form Spring configuration actually takes. The repository scan had
   been passing throughout, because there was nothing in the tree to find.
2. **The placeholder allowlist matched substrings.** Any secret whose text happened to
   contain `example` or `placeholder` anywhere inside it was exempted. The planted secret
   that exposed this was named `s3cr3t-not-a-placeholder-value`, and it would have been
   waved through on the strength of its own name. *An allowlist that can be defeated by
   naming a variable well is not an allowlist.*

There was a third, found by writing the test itself: the first version of the job embedded
its planted secrets as literals, and the working-tree scan promptly reported the workflow
file. Correctly — **a scanner cannot tell a fixture from a leak, and should not try.** The
choice was to allowlist that file or to stop putting secrets in it. Allowlisting would have
exempted the one file in the repository whose purpose is to contain secret-shaped text,
which is a hole shaped exactly like the thing it catches. The values are generated at run
time instead.

All three are fixed. **The fixed state carries its own number, because a claim that
something was repaired is exactly the kind of claim `PUB-4` refuses to take on trust:**

| | Planted | Reported | |
| --- | --- | --- | --- |
| Before — custom rules as first written | 3 secrets | **1** | two missed outright |
| After — positive control | 4 secrets | **4** | JDBC-inline, nested YAML password, nested YAML signing key, flattened property |
| After — **negative control** | 4 non-secrets | **0** | `postgres`, `${VAR}`, `<your-token-here>`, `xxxx` |
| After — repository itself | — | **0** | git history and working tree, gitleaks v8.30.1 |

The negative control is half the test, not a courtesy. A rule set that reports everything is
as useless as one that reports nothing: findings that are routinely wrong are findings
nobody reads, which is the same outcome as not scanning.

The general lesson is the one this whole repository is organised around. **The scan was
green before and after the defect was introduced.** Nothing about a passing check
distinguishes "there is nothing to find" from "this cannot find anything."

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
| `PUB-1` | **The tree and history were scanned locally, before the first public push** | **reviewed** — done 2026-08-10. gitleaks v8.30.1, history (2 commits) and working tree, both clean. Why this is not left to CI: §*A push is the publication* below |
| `PUB-1` | **The rules have been watched refuse a planted secret — and watched not refuse a non-secret** | **observed** — the `config-self-test` job of `secret-scan.yml`. It exists because running it for the first time found two real defects: §*What the self-test found* below |
| `PUB-2` | `LICENSE` is the canonical Apache-2.0 text | **observed** — checksum guard, pinned 2026-08-10 |
| `PUB-3` | `README.md` states purpose, structure, and current state | **reviewed** — written 2026-08-10 |
| `PUB-3` | Every managed document carries `Created` and `Updated` | **reviewed** — no workflow enforces it yet |
| `PUB-4` | Every document states what it does not own | **reviewed** |
| `PUB-4` | No prose claims an implementation state that does not exist | **partly observed since 2026-08-17** — `docs-consistency.yml`, four checks, self-tested. It failed three times in seven days first: three documents stale in the hour the build landed (2026-08-10); `roadmap.md` saying *"Nothing below is done"* and `domain-model.md` saying *"No domain entities exist yet"* (2026-08-14); and the pass correcting those leaving `roadmap.md`'s `T1` cell claiming no gate existed while `R4` §7 named the file. **All three were caught by a person, two of them by the PO asking.** Counting what the checks find on the tree that question landed on: **eighteen, of which the asking found two** — `R17`. **`partly` is the load-bearing word**: nothing here reads a sentence. A prose check was built and discarded (`R17` §5), so the class is caught through the proxy of a date, and **a document that is up to date and simply says something false still passes** |
| `PUB-4` | **Every published number carries its measurement environment** | **reviewed** — the report template carries the block; **no workflow enforces it yet.** This row is a candidate for becoming observable |
| `PUB-4` | **Every report has a non-empty *남는 위험* section** | **observed** — `docs-consistency.yml`, check 4, since 2026-08-17. **A machine can see the heading is non-empty, not that it is honest**, and that limit is unchanged: a report satisfies this with three lines of nothing. What it now stops is a report shipped with the template's empty section still in it |
| `PUB-4` | Every undecided item is recorded as undecided | **observed by presence** — `docs/decisions/open.md` |
| `PUB-5` | `SECURITY.md` names a contact and a response expectation | **observed by presence, reviewed for accuracy** — in place 2026-08-10 |
| `PUB-5` | The contact is an address the PO intends to publish | **reviewed** — `gseek@gseek.net`, carried from hanok's settled decision. A published address is permanently scrapeable |
| `PUB-7` | No data-class file is tracked anywhere | **observed** — `no-learner-data.yml`, and the guard is self-tested against planted violations |
| `PUB-7` | No `.sql` outside the migration directory | **observed** — same workflow. Migrations carry schema; a `.sql` elsewhere is usually rows |
| `PUB-7` | Nothing shaped like a personal identifier is tracked | **observed** — pattern check, self-tested |
| `PUB-7` | The seed is produced by code from a fixed seed value | **observed** — `SeedDigestTest` pins the SHA-256 of all seven files at both scales, including the full 3,963,719-row dataset, and `.github/workflows/build.yml` runs it. **Green on 2026-08-11 on a GitHub runner against digests produced on the machine in `measurement-discipline.md`**, so the claim is now byte-identity across two machines rather than self-consistency on one. `GeneratorTest` still carries the control that a *different* seed must differ, so a generator emitting constants cannot pass either test. **Limits:** both machines are x86_64 on JDK 21; other architectures, vendors, and locales are 미측정 |
| `PUB-7` | No generated identifier can be mistaken for a real one | **reviewed** — a machine can see a shape, not an intention |
