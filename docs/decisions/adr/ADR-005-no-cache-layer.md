# ADR-005 — No cache layer, and the experiment that was already run

> **Created**: 2026-08-13
> **Updated**: 2026-08-13
> **Status**: Accepted
> **Closes**: `OPEN-4`

## Context

`OPEN-4` asked whether a cache layer is in scope at all, and gave the reason it was open:

> Caching would improve every number in this repository, and would also hide which of them
> were bad for structural reasons. Deciding to leave it out is a real decision and should be
> recorded as one.

The deadline was *before any report claims a latency ceiling*. **Nothing here claims one**, so
unlike `OPEN-5` this row is being closed on its own terms rather than after the hazard
arrived. That is worth saying plainly, because it is the only one of the five that was.

The temptation is to close it with the argument above and nothing else. This repository's
whole objection to that shape of reasoning is that it sounds finished. So: **has a cache ever
changed a conclusion here, and by how much?**

It has. Twice, measurably, without anyone deciding to add one.

## The evidence, from measurements that already exist

### 1. There is already a cache, and it already misled this repository

PostgreSQL's buffer cache is a cache. `R2` §3 measured the same recommendation statement
against it in both states:

| | median |
| --- | --- |
| cold buffers | **576.8 ms** |
| warm | **140 ms** |

**4.1×, from caching alone, on a statement nobody had changed.** And `R2` §9 records what
happened next: the cold figure was reported to the PO as fact, and a proposal to change `T1`'s
design was sent on the strength of it.

That is not a hypothetical about cache layers. It is this repository, on day one, having a
conclusion moved by a cache it did not choose, did not configure, and did not notice.

### 2. What a cache would have hidden is precisely what the reports found

`R3` measured a query at **36.6 ms**, added the right index, and measured **0.056 ms** — 653×.
The defect was a sequential scan over three million rows.

A cache in front of that query makes the repeated reads instant. It does nothing for the first
read, nothing for a miss, and nothing for the scan itself. **The 36.6 ms would have been true
the whole time and visible to nobody**, and there would have been no number with which to
justify `V2`. `ADR-002` argues the schema must ship naive so the defect can be measured; a
cache is the same argument in reverse — it makes a naive schema *look* fixed.

`R4` is the same shape. p99 **9064 ms → 5919 ms** came from not holding a connection across a
slow call. A cache reduces how many requests reach the pool, which improves the number while
leaving every uncached request holding a connection exactly as before.

### 3. At most half of the endpoint is cacheable anyway

`R2` §5, measured:

```
query 140 ms  vs  gateway 150 ms   →  the held connection is half the story
```

The recommendation request spends roughly as long in a non-database call as in the database. A
cache over the query addresses **at most half** of it, and the half it does not address is the
half `R4` turned out to be about.

### 4. And it cannot touch the write path at all

`R12` measured **196 of 1,000** concurrent recordings applied before the fix and 1,000 after.
That is a write-side defect. No cache participates in it. The single largest correctness
number in this repository lies entirely outside what caching can reach.

## Decision

**No cache layer. Not now, and not as a performance remedy for anything currently measured.**

`OPEN-4` framed this as a scope question. The measurements above make it a methodological one:
**a cache is an instrument that changes what the other instruments read.** Adding one to a
repository whose product is defect measurements would not be a feature, it would be a fault in
the apparatus — and §1 shows the fault occurring before anyone chose it.

## What would reopen this

Not "it would be faster". That is agreed and is not the question.

| trigger | why it changes the answer |
| --- | --- |
| **A report claiming a latency ceiling** — that some number here cannot be improved | `OPEN-4`'s original deadline. "Cannot go faster" is false while an unbuilt cache is on the table, so the claim cannot be made until this decision is revisited |
| **A read path whose cost is dominated by the database and whose data tolerates staleness** | Neither is true of the recommendation read today: §3 measures it as roughly half gateway, and a recommendation that ignores an attempt the learner just submitted is wrong rather than stale |
| **Cache behaviour becoming the subject of a report** rather than a means to a number | Measuring a cache — hit ratio, stampede, invalidation under concurrency — is a legitimate trap of exactly the kind this roadmap collects. That is a different decision from *adding one to go faster*, and this ADR does not rule it out |

## Consequences

**What this buys.** Every number in this repository is a number about the system, not about
its cache warmth. `R3`'s 653× exists because nothing was hiding the scan. `R4`'s p99 exists
because every request reached the pool.

**What this costs, and it is real.** These numbers are worse than the system could be, and a
reader comparing them to a cached system is not making a comparison. The repository should not
be read as *this is how fast this design goes* — it is *this is how fast it goes with nothing
hiding anything*, which is a different and narrower claim.

It also means the one genuinely useful thing a cache would do here — absorb the repeated
`ContentGateway` call, which is 150 ms of non-database latency per request — is left on the
table, deliberately, and `R4`'s p99 carries that cost.

**What this rules out.** A second-level Hibernate cache, a query cache, and an application
cache in front of any read that a report measures. `R8` §8 already names the first of those as
the thing that would make its statement counts flaky, which is the same objection from a
different direction.

## What was not measured

- **What a cache would actually buy.** Nothing was built, so nothing was measured. Every
  figure above is about a cache *displacing* a measurement, not about its benefit. **미측정.**
- **Whether the recommendation result is cacheable at all** given that it must reflect an
  attempt recorded a moment ago. Argued in the trigger table, not measured.
- **The `ContentGateway` call's real cost.** It is a `Thread.sleep(150)` standing in for
  something that does not exist. Whether a real content service would be cacheable is
  unknowable from here, and §3's *half the request* figure inherits that.
