# ADR-002 — The schema tells the story

> **Created**: 2026-08-10
> **Updated**: 2026-08-10
> **Status**: Accepted

## Context

This repository's product is a set of measurements: what a defect cost, what the fix
recovered, and what the fix did not solve. A measurement of a defect requires the defect to
have existed in a state that can be checked out and run.

The obvious way to build the schema is to get it right the first time — every index the
queries will need, every constraint the domain requires, all in `V1`. That produces a
correct schema and makes every report in this repository unwritable, because there is no
commit at which the slow plan or the losable update can be observed.

## Decision

**`V1__baseline.sql` ships the schema in its naive state. Each performance index and each
concurrency-protecting constraint arrives in its own later migration, in the same commit as
the failing test and the report that justifies it.**

Concretely, `V1` omits:

| Omission | Recovered by | Report |
| --- | --- | --- |
| Any index on `attempt (learner_id, attempted_at)` | a later migration | indexing / paging |
| Covering columns on that index | a later migration | indexing / paging |
| `unique (learner_id, concept_id)` on `mastery` | a later migration | unique-constraint race |

Every omission carries a comment at its site in the SQL saying it is deliberate and naming
this ADR. An omission that is not commented is a bug.

## Consequences

**What this buys.** `git log` becomes the evidence. Each pair reads:

```
red:   <what was observed, with a number>
green: <what changed, with the number after>
```

A reader can check out the red commit and reproduce the number themselves. That is a
different kind of claim from a repository that merely asserts it knows about N+1 problems.

**What this costs, and it is a real cost.**

`V1` is, for a window of commits, a schema with a known correctness hole. Anyone who forked
at that point and ran it against real traffic could lose updates. That is acceptable **only
because** this repository has no users, the hole is documented at its site, and the
migration that closes it is not far behind. It would be an unacceptable pattern in a system
serving anyone.

It also means the migration sequence is not the sequence a greenfield project would
produce. Someone reading `db/migration/` to learn how to design this schema would be
learning the wrong lesson from the order. Hence this ADR, and hence the pointer to it at
the top of `V1`.

**What this rules out.** Squashing migrations. The sequence is the argument; a squashed
baseline would delete it.

## Alternatives considered

**Ship the correct schema, then remove things in branches to demonstrate the defects.**
Rejected: a defect demonstrated on a branch nobody merged is a rehearsal. The commit
sequence on the main line is what a reader actually reads, and it should be the true one.

**Ship the correct schema and describe the defects in prose.** Rejected: this repository's
entire premise is that a described defect and a reproduced defect are different claims. It
would be inconsistent to make an exception for the schema, which is where the most
interesting ones live.
