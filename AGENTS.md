# AGENTS.md

> **Created**: 2026-08-10
> **Updated**: 2026-08-10

**Status:** Settled.

This is the entry point for any agent working in this repository. It **names the documents
that bind you**, and holds a rule of its own only where no other document owns one — so
that a rule is never stated twice and never drifts between two tellings.

Where a rule below is repeated from elsewhere, it names its owner. **A repetition that
names its owner is a citation, not a second rule.**

## Read these first, in this order

| # | Document | For |
| --- | --- | --- |
| 1 | `README.md` | What this repository is, and what it is not |
| 2 | `docs/roadmap.md` | What is measured, in what order, and **what "done" means** |
| 3 | `docs/explanation/measurement-discipline.md` | **What makes a number citable.** Read before taking any measurement |
| 4 | `docs/decisions/publication-readiness.md` | `PUB-1`…`PUB-7` — what must be true to publish |
| 5 | `docs/decisions/open.md` | What is undecided. **Do not guess these shut** |
| 6 | `docs/decisions/adr/ADR-002-schema-tells-the-story.md` | Why `V1` is deliberately naive |
| 7 | `api/src/main/resources/db/migration/V1__baseline.sql` | The schema, and what it omits on purpose |

## What binds you

| Read it for | Owner |
| --- | --- |
| Whether a number may be written down, and in what form | `docs/explanation/measurement-discipline.md` |
| Whether a roadmap item may be called done | `docs/roadmap.md` §Definition of done |
| Whether anything may be published, and what may never enter the tree | `docs/decisions/publication-readiness.md` |
| Why a technical choice was made | `docs/decisions/adr/` |
| What the data means and where it comes from | `docs/explanation/domain-model.md` |
| The shape of every report | `docs/reports/_TEMPLATE.md` |
| Where to send a vulnerability | `SECURITY.md` |

Three of those bind before you write anything, and are repeated here because they bind
from the moment this file is open:

- **A number carries the environment it was taken in, or it is not written down.** A number
  that was not taken is called `미측정` — never estimated, never carried over from a similar
  run. *(measurement discipline)*
- **No data file is committed, ever.** The seed is code, generated from a fixed value. This
  is not a tidiness rule; the records this project models describe minors, and history
  cannot be erased. *(`PUB-7`)*
- **A report whose *남는 위험 / Remaining risk* section is empty is not finished.** A report
  that found nothing left to worry about has usually stopped looking. *(`PUB-4`)*

## Session conduct

**This section is the only place this file owns a rule**, because no other document here
does. Anything that grows past a paragraph moves out into a document of its own.

### Language

Split the language you think in.

- **Documents, commit messages, code comments, test names → think in English, write English.**
- **Reports to the PO in conversation → think in Korean, write Korean.**

Korean output is composition, not translation. Do not build an English sentence and swap
the words. Nothing written to a file changes language because a conversation did.

### Explaining to the PO

**The purpose of this repository is only visible in the explanation.** A number handed over
without the reasoning that produced it is a status update; this project is not a status
update. The PO is here to *learn the material* — so an explanation that is correct but
shallow has failed even when nothing in it is wrong.

Every substantive answer to the PO therefore carries four things:

- **Depth to where the mechanism lives.** Not "the index was not used" — which index, what
  the planner estimated against what it actually returned, and what in the schema made that
  choice reasonable. Stop descending only when the next layer would not change what the PO
  concludes.
- **The alternative that was not taken.** A decision explained without its rejected sibling
  is an announcement, not an explanation. Say what the other path would have cost.
- **The learning objective, met.** After reading, the PO should be able to predict the *next*
  case unaided. If the explanation only covers the case in hand, it is not finished.
- **The edge of what is known.** Where the explanation runs out, say so, and say whether the
  missing piece is `미측정` or merely unasked. *(evidence, below)*

Depth is not verbosity, and length is not depth. Cut the restatement, the hedge, and the
preview of what you are about to say — then spend that room on the mechanism. When the
honest explanation is long, it is long; **do not compress the reasoning away to look
decisive.**

**The vocabulary is part of the material.** Use the settled technical term — *index-only
scan*, *N+1*, *write amplification*, *p99*, *connection pool saturation* — and carry it in
English once even inside Korean prose, because that is the string the PO will type into a
search box and hear in a code review. A paraphrase that avoids the term teaches the
situation but not its name, and **the name is the part that transfers.** Define it the first
time it appears; do not define it the third time.

**Show the working method, not only the working code.** How this is done on a real team is
part of the answer: what a reviewer would flag and what they would let pass, which tool is
reached for first and why that one (`EXPLAIN (ANALYZE, BUFFERS)` before a guess, `git
bisect` before a theory), which convention is load-bearing and which is merely taste.
Reasoning that holds at toy scale but would not survive a production review is not a
shortcut; it is a detour that has to be walked back.

Both of those serve one aim: **the PO is climbing a learning curve, and the job is to make
that climb short.** Short is not small. A simplified model that must later be retracted
costs more than the true one told properly the first time — so **never teach something that
will have to be unlearned**, and never defer with "you will understand this later". Spend
the words on what generalises to the next problem; skip what is true only of this one.

This is not reserved for when the PO asks "why". It is the default shape of the answer.

### Evidence

No speculation. Verify against code, logs, or a query plan before asserting. **Say you do
not know when you do not know** — in this repository that has a name, and the name is
`미측정`.

A summarised log line or a paraphrased `EXPLAIN` is an opinion. Quote them verbatim.

### Commits

- **`red` and `green` are separate commits.** The state in which a defect was observed, and
  the state in which it was not. This is what makes the history evidence rather than
  assertion — see `ADR-002`.
- **An AI draft and the human correction of it are separate commits.**
- **Never squash.** The sequence is the argument.
- Convention: `<type>(<scope>): <subject>`
  - types — `feat` · `fix` · `perf` · `test` · `refactor` · `docs` · `chore` · `ci`
  - scopes — `api` · `seed` · `load` · `web` · `infra` · `db` · `docs` · `ci`
- The subject says **why**, and carries the number when there is one. `fix bug` is not a
  commit message.

### Claiming completion

**The session that did the work may claim at most that it self-tested.** Whether an item is
*done* is decided against `docs/roadmap.md` §Definition of done by someone who was not the
one doing it, and who names the commit they checked.

This matters more here than in most repositories, because the thing being claimed is
usually a measurement, and the person best placed to be fooled by a measurement is the one
who took it.

### Scope

Do the roadmap item in front of you. **Building infrastructure ahead of the code it
protects is how this repository has already lost a day** — a guard that protects nothing
yet is not free, it is unbanked. If something outside the current item looks necessary,
say so and wait rather than starting it.

## What this document does not own

| Question | Owner |
| --- | --- |
| Every rule about measurement | The measurement discipline |
| Every rule about publication | The publication readiness document |
| What the modules and tables are | The domain model |
| What order the work happens in | The roadmap |
| Whether something is implemented | Tests and reports. **Never prose** |
