# Why these nine

> **Created**: 2026-08-22
> **Updated**: 2026-08-22

**Status:** Written after the fact, on 2026-08-22, because the original research trail was not
kept. Every source below was found **now** and corroborates the nine; **not one of them is
evidence of what was consulted when the nine were chosen.** That distinction is the whole
subject of this document and it is load-bearing — see §The state of the trail.

This document owns **where the nine traps came from** — their provenance, and the limits of
what can honestly be claimed about it.

## What this document does not own

| Question | Owner |
| --- | --- |
| **The selection rule** — what makes a defect eligible | `docs/roadmap.md` §The premise |
| **Why the items run in the order they do** | `docs/roadmap.md` §Order, and why this order |
| What each trap turned out to be, once measured | `docs/reports/` |
| What makes a number citable | `docs/explanation/measurement-discipline.md` |
| Whether anything may be published | `docs/decisions/publication-readiness.md` |

The roadmap states the rule and the order. This document restates neither, and where a trap is
named below it is named to identify the row, not to re-argue it.

---

## The state of the trail

The author's account is that the nine were assembled from three places: **questions that recur
in practice in Java/Spring communities**, **topics that appear in every advanced curriculum**,
and **the places upstream repositories actually argue about in issues and pull requests.**

That account existed only in the author's head. A search for the original research trail was
run on **2026-08-22**, and it found nothing:

| Where the search looked | What it returned |
| --- | --- |
| All local Claude Code session history, every project | **Zero** web-search or web-fetch calls whose query mentions Spring, JPA, Hibernate, OSIV, QueryDSL, or Testcontainers |
| The author's session history for 2026-08-08 → 08-13, the window in which the trap catalogue was written | 482 web calls, **all recruitment-related**; one keyword hit, a false positive |
| Codex session history for 2026-08-09 and 08-10, the two days the catalogue was drafted | 54 files mention the traps; **zero community or upstream URLs** — two unrelated links only. Reasoning content is stored encrypted and could not be read |

Two things follow, and they are stated here rather than left to be inferred.

**First: every row in §Provenance is `corroborated`, never `recovered`.** A corroborated source
is one found today that independently shows the trap is real. A recovered source would be one
demonstrably consulted in August. **No row here is the second kind, and none ever can be** — the
trail is gone, and a document that let a reader believe otherwise would be the exact defect
`R19` catalogues: a claim written about one instant and read as a standing one.

**Second: the search is itself the evidence, and it is a null result rather than an absence of
looking.** It is recorded above with what it covered, so that a later reader can tell those two
apart. This is the `미측정` rule applied to provenance: the trail was not preserved, and saying
so is the price of not inventing one.

### How the sources below were produced

Three phases, all on 2026-08-22:

1. **Find** — one worker per trap, given the trap's own wording from `docs/roadmap.md`, each
   returning two or three candidate sources with a verbatim fragment from the page it opened.
2. **Refute** — one checker per candidate, given only the source and the claim it was cited
   for, instructed to break the pairing and to default to `refuted` when uncertain.
3. **Write** — this document.

**Forty-five pairings were checked and twenty-five were refuted.** They were dropped rather
than softened, which is why several traps below carry fewer than three sources and why
§What could not be anchored runs longer than §Provenance. That rate is the most useful number
this exercise produced: **a majority of citations that looked right did not say what they were
cited for.**

---

## Provenance, per trap

Every row carries **kind** · **URL** · **retrieval date** · what the page *actually* shows.
Every row is **`corroborated`** — found 2026-08-22, not recovered from the original work.

Where a "what it shows" line names a limit, the limit is part of the claim. Each source is
listed for what it carries and no further.

### `T1` — a connection pool exhausted by a default

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| upstream issue or PR | https://github.com/spring-projects/spring-framework/issues/34138 | 2026-08-22 | An **open, unmerged** PR from an outside contributor (opened 2024-12-22; 14 reactions, 0 issue comments and 8 review comments; untriaged after 608 days as of 2026-08-22) adding a release-connection-after-transaction option. Its stated rationale is that with open-in-view on, "connections are not released for a long time, which can lead to connection pool starvation" — its example being a controller that makes a REST call after its query. **Evidence the problem is reported and unfixed upstream; not a maintainer's position** | `corroborated` |
| upstream issue or PR | https://github.com/spring-projects/spring-boot/issues/47547 | 2026-08-22 | A request to flip the `spring.jpa.open-in-view` default **for the Spring Boot 4.x line**, closed `not_planned` / `status: declined` on 2025-10-15 (1 comment, 3 reactions as of 2026-08-22). The maintainer **deferred rather than rejected** — "we'd like to review the default setting … at some point" — which is what leaves the default in place | `corroborated` |
| official documentation | https://docs.spring.io/spring-boot/reference/data/sql.html | 2026-08-22 | Page states **Spring Boot 4.1.1**; the URL is unversioned and no pinned equivalent is served while 4.1 is the current line. Establishes only that the interceptor is registered **by default** in web applications and must be disabled explicitly. **Says nothing about connections, pools, or exhaustion** — its stated rationale is lazy loading in web views | `corroborated` |

### `T2` — a page that is paginated in memory

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/cfg/QuerySettings.html | 2026-08-22 | Javadoc set states **7.4.6.Final**. The in-memory fallback is **conditional**: it applies "and the database does not support `LIMIT` inside a subquery". That PostgreSQL falls on the other side of that condition follows from Hibernate's dialect, **not from this page** | `corroborated` |
| official documentation | https://docs.hibernate.org/orm/6.2/javadocs/org/hibernate/cfg/AvailableSettings.html | 2026-08-22 | The same sentence **without** the conditional — established by a complete read, not a truncated one: the word "subquery" occurs zero times in the whole page. Read against the row above, the pair is the wording change. **But it brackets the change rather than locating it**: the unconditional form held from 6.2 through 7.3, and the qualifier arrived in 7.4 | `corroborated` |
| upstream issue or PR | https://hibernate.atlassian.net/rest/api/2/issue/HHH-20588 | 2026-08-22 | Closed/Fixed; fixVersions 8.0, 8.1 and 7.4.3; created 2026-06-18. In reporting an alias defect it **prints Hibernate's generated SQL for a paged collection fetch** — a derived table over the root carrying `OFFSET ? ROWS FETCH FIRST ? ROWS ONLY`, with the collection joined outside it. The slice reaches the database. **The human-facing `/browse/` URL renders nothing to a fetcher**; the REST endpoint above is the readable one. Shows **one** collection join, not two | `corroborated` |

### `T3` — a transaction annotation that does nothing

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html | 2026-08-22 | Page states **Spring Framework 7.0.9**; URL unversioned, and no pinned equivalent is served for the current line. In a note: in the default proxy mode only external calls through the proxy are intercepted, so self-invocation "does not lead to an actual transaction at runtime", and the proxy must be fully initialised, so initialisation code cannot rely on it. **The page does not call either failure silent** — the silence is this repository's measurement, not the page's claim | `corroborated` |

**One source. The other three strands are unanchored** — see §What could not be anchored.

### `T4` — an index that exists and is not used

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://www.postgresql.org/docs/16/queries-limit.html | 2026-08-22 | PostgreSQL 16, §7.6. States the mechanism: rows skipped by `OFFSET` "still have to be computed inside the server; therefore a large `OFFSET` might be inefficient". **Names no threshold for "large" and never mentions keyset paging** — the comparison, and the depth at which it bites, are this repository's measurement | `corroborated` |
| official documentation | https://www.postgresql.org/docs/16/populate.html | 2026-08-22 | PostgreSQL 16, §14.4.8. `ANALYZE` is "strongly recommended" after "bulk loading large amounts of data into the table", and "with no statistics or obsolete statistics, the planner might make poor decisions during query planning". The same paragraph notes autovacuum "might run `ANALYZE` automatically" — **so the gap is a bounded window rather than a durable state**, and a measurement taken before `ANALYZE` has to be taken inside it | `corroborated` |
| official documentation | https://www.postgresql.org/docs/16/indexes-index-only-scans.html | 2026-08-22 | PostgreSQL 16, §11.9. An index-only scan checks the visibility-map bit per heap page and, when it is unset, "the heap entry must be visited … so no performance advantage is gained over a standard index scan". Documents the `INCLUDE` covering construct. **Never mentions `VACUUM`, and never names the `Heap Fetches` field** — the link from *unvacuumed* to *heap fetches* is this repository's, not this page's | `corroborated` |

### `T5` — updates lost under concurrency

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| upstream issue or PR | https://github.com/spring-projects/spring-framework/issues/35584 | 2026-08-22 | **Open**, milestone 7.0.x, opened 2025-10-08 by a Spring maintainer; 10 comments, last activity 2026-07-05, with an open PR attached, as of 2026-08-22. Spring's own words: "we have received questions regarding how `@Retryable` works when combined with other proxy-based features", with `@Transactional` among the four named. A contributor states the correctness condition — retry applied outside the transaction, so "each retry attempt starts a fresh transaction". **It reports no bug and shows no instance of anyone getting this wrong**; it evidences that the composition confuses people, and states which order is correct | `corroborated` |

**One source. The optimistic-locking arm is unanchored** — see §What could not be anchored.

### `T6` — a uniqueness check two requests both pass

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://www.postgresql.org/docs/16/tutorial-transactions.html | 2026-08-22 | PostgreSQL 16, §3.4. "`ROLLBACK TO` is the only way to regain control of a transaction block that was put in aborted state by the system due to an error, short of rolling it back completely and starting again." **A tutorial page carrying a normative claim** — and the only page in the whole manual that states this proposition. It says "an error" generically and **never names a unique-constraint violation** | `corroborated` |
| official documentation | https://www.postgresql.org/docs/16/sql-release-savepoint.html | 2026-08-22 | PostgreSQL 16, SQL-command reference — the reference-level counterpart to the row above: "It is not possible to release a savepoint when the transaction is in an aborted state; to do that, use `ROLLBACK TO SAVEPOINT`." Confirms the aborted state exists and what restores control. **Also never names a unique violation** | `corroborated` |

### `T7` — a test that counts queries

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://docs.hibernate.org/orm/7.4/userguide/html_single/#best-practices-logging | 2026-08-22 | Guide states **7.4.6.Final**. §31.2 recommends this trap's practice in prose: "you can assert the number of executed statements at test time. This way, you can have the integration tests fail when a N+1 query issue is automatically detected." **No code example**, and the instrument it names is a DataSource proxy rather than the statistics counters. Cite with the anchor — the bare page is 3.8 MB and truncates in automated fetchers | `corroborated` |
| official documentation | https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/FetchMode.html | 2026-08-22 | Javadoc set states **7.4.6.Final**. Upstream names the defect and owns it as a default: `SELECT` "is vulnerable to the "N+1 selects" bugbear", and the same block calls `SELECT` "the default fetching strategy for any association or collection in Hibernate, unless … explicitly marked for eager fetching". **Establishes that the hazard is real and is default behaviour; says nothing about tests, counts, or CI** | `corroborated` |

### `T8` — what an in-memory database does not tell you

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation | https://www.h2database.com/html/features.html | 2026-08-22 | H2's own manual; the page pins to **2.4.240** through its navigation, and the sentence is byte-identical in 1.4.200, so it is not an artefact of either major line. The lead-in to H2's mode list disclaims the modes: "For certain features, this database can emulate the behavior of specific databases. However, **only a small subset of the differences between databases are implemented in this way.**" The PostgreSQL-mode subsection further down enumerates what *is* emulated and **never enumerates what is not** | `corroborated` |
| official documentation | https://docs.hibernate.org/orm/7.4/userguide/html_single/#batch | 2026-08-22 | Guide states **7.4.6.Final**; the same sentence is present at tag 7.4.1, the version this repository runs. An `IMPORTANT` admonition, placed directly after the instruction to set a batch size: "Hibernate disables insert batching at the JDBC level transparently if you use an identity identifier generator." **The guide says "transparently", not "silently", and asserts nothing about whether the downgrade is reported anywhere** | `corroborated` |

### `T9` — authorisation, exposure, tokens

| Kind | Source | Retrieved | What the page actually shows | Status |
| --- | --- | --- | --- | --- |
| official documentation — **standards body, not a vendor** | https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/ | 2026-08-22 | API1:2023, still the **#1 entry of the current edition** — no 2024/2025/2026 API Security edition exists (the 2025 OWASP list is the *web application* Top 10, a different project). Defines this trap's exact shape: an **authenticated** caller reaching another user's object "by manipulating the ID of an object that is sent within the request", expressly distinguished from unauthenticated endpoint access, which OWASP files separately as API5 | `corroborated` |
| official documentation — **IETF specification** | https://www.rfc-editor.org/rfc/rfc7519.txt | 2026-08-22 | Proposed Standard, May 2015; **not obsoleted**; updated by RFC 7797 and RFC 8725, **neither of which amends §4.1.4**. §4.1.4: a JWT "MUST NOT be accepted for processing" on or after `exp`, and implementers "MAY provide for some small leeway, usually no more than a few minutes, to account for clock skew". **The expiry half is normative; the skew bound is a `MAY` plus a descriptive hedge, with no ceiling stated anywhere.** The RFC never discusses the *symmetry* of that tolerance — that a leeway forgiving a fast clock also keeps a just-expired token trusted — which is this repository's own finding, not the RFC's. Cite the `.txt` form: the bare `/rfc/rfc7519` path 302-redirects to the info page | `corroborated` |

---

## What could not be anchored

**An empty cell here is a result, not an omission.** Each one is listed individually.

### The third kind of source is anchored nowhere — zero of nine

The author's account names three kinds of source, and **one of the three has no row anywhere in
§Provenance**: *a question that demonstrably recurs.* Two independent reasons, both recorded:

- **The venue could not be opened.** `stackoverflow.com` and `api.stackexchange.com` are
  unreachable from this session's tooling — every worker that tried was refused at both the
  search and the fetch layer. No score or view count could be read off any page there, and the
  standing rule is that a source not opened is not cited.
- **Every substitute was refuted on the same axis.** A Hibernate forum thread with 1,639 views;
  another with 910; a Laravel issue with 7 reactions; a testing library with 539 stars; a CVSS
  base score of 9.1. Each of those is **one asking, or a popularity figure, or a severity
  rating.** None of them counts questions, and a number that measures the wrong quantity is not
  an anchor merely because it is a number.

One near-miss is recorded because it is the closest anything came. A checker's own search of
the Hibernate forum for the `T8` mechanism returned **six distinct topics between 2020 and
2025**, which would support recurrence. It is not in the table, because it was established by a
search this session ran rather than by any page a reader can open — and this document does not
cite its own searching as a source.

### Per trap

| Trap | What has no source, and why |
| --- | --- |
| `T1` | Nothing missing at the trap level. Note only that **no single source shows the mechanism and the pool consequence together**: the documentation row shows the default, the two upstream rows assert the consequence, and neither upstream row is a maintainer's finding |
| `T2` | **The second strand — two collection joins at once — is unanchored.** `HHH-20588` shows one collection join. No source showing `MultipleBagFetchException` was found that survived checking |
| `T3` | **Three of four strands are unanchored.** *The `final`-class strand*: the Spring Boot Kotlin page was dropped — it states the proxying prerequisite and never mentions `@Transactional` or any failure — and the AOP proxying page was dropped because it states no consequence at all, while in practice Spring fails **loudly** there, with an exception at bean creation. *The rollback-only strand*: `spring-boot#43228` was dropped because its recurrence claim rested on a `for: stackoverflow` label that cannot carry it, and the transaction-propagation page was dropped because it documents a **deliberately loud** failure — `UnexpectedRollbackException` is thrown, in that page's own words, "to indicate clearly that a rollback was performed instead". This trap's claim is that *nothing reports it*; a page about a loud failure cannot evidence a silent one. *The `data class` / lazy-proxy strand*: no candidate was found at all |
| `T4` | **The low-cardinality strand is unanchored.** The multicolumn-indexes page was dropped: its only cardinality sentence is about a **GiST** index, while this trap's case is a B-tree on a boolean, and that page says nothing about B-tree and cardinality anywhere. **The keyset half is unanchored too** — the surviving OFFSET row never mentions keyset paging |
| `T5` | **The lost-update arm and the optimistic-locking arm are unanchored.** The Jakarta Persistence javadoc was dropped: it states that a conflict marks the transaction for rollback, but not the retry consequence attributed to it. `applevel-consistency.html` was dropped: its second quote is a lock-*lifetime* porting caveat, not the lost-update anomaly. `transaction-iso.html` was dropped on a sharper point — its serialization-failure text describes a **REPEATABLE READ** server-side abort, while this repository's optimistic arm is JPA `@Version` at **READ COMMITTED**. Those are different mechanisms, and no arm here can produce the error that page describes. **PostgreSQL's manual never uses the phrase "lost update", nor "optimistic", nor "pessimistic"** — that vocabulary is the literature's, not the database's |
| `T6` | **The check-then-insert race is unanchored.** Both surviving sources say "an error" generically. `pgjdbc#423` was dropped: it was opened by an outside user rather than by the maintainers as claimed, and the error in its transcript is a missing function, not a duplicate key. `laravel/framework#48143` was dropped: it contains no concurrency race, and the only transferable part of it is PostgreSQL behaviour already better sourced from PostgreSQL. The PL/pgSQL error-trapping page was dropped for scope — it documents a **server-side procedural language's** exception blocks, never mentions savepoints or clients, and its worked example is a stored-function upsert loop that the manual itself tells applications to replace with `ON CONFLICT` |
| `T7` | **No first-party facility that ships the assertion was anchored.** Hibernate's `Statistics` counter was dropped: it documents observability, contains no occurrence of *test* or *assert*, and is **off by default**. Hibernate's own test-infrastructure class was dropped as a kind — raw source on a moving branch is not documentation. QuickPerf was dropped: a star count measures a tool's popularity rather than a question's recurrence, and its last release is 2021. What survives is a **prose recommendation**, plus upstream acknowledging that N+1 is real |
| `T8` | **Five of six strands are unanchored** — upsert syntax, types, collation-dependent ordering, reserved words, and container reuse. The surviving H2 row is a generic disclaimer, and the Hibernate row covers identifier generation only. The H2 mailing-list post calling `MODE=` unmaintained was dropped twice over: on currency, since PostgreSQL-mode fixes shipped through H2 2.4.240 in 2025, and on representativeness, since H2's lead maintainer reaches the opposite conclusion later in the same thread |
| `T9` | **The actuator strand is unanchored, and looking for it turned up the opposite.** Spring Boot's own actuator page states: "By default, access to all endpoints except for `shutdown` and `heapdump` is unrestricted." On the version this repository runs, **wholesale exposure does not reach the heap dumper** — that additionally needs a separate `access` setting. `spring-boot#45624` was dropped for the same reason: it is the hardening change that made this true, shipped in 3.5.0 and still in force in 4.1.x, and it was closed **unmerged** with its commit hand-applied. `GHSA-8v8j-3hxp-93wr` was dropped as a different mechanism, on a version range this repository sits outside of. All of that agrees with what `R10` already recorded — the premise is half wrong on Boot 4.1.0 — and it is written here rather than quietly omitted |

### And the standing limit

**No source in this document establishes that any of it was consulted in 2026-08.** That is why
every row says `corroborated`. It is not a hedge; it is the only claim the evidence supports.

---

## The candidate pool

**It cannot be established from anything in this tree, and no list is reconstructed here.**

What was checked, on 2026-08-22:

| Where | What it holds |
| --- | --- |
| Git history of `docs/roadmap.md` | The nine arrive **complete and already tiered** in `4cf6292`, this repository's second commit. There is no earlier state of the file to diff, and no later commit removes a row |
| A pre-execution plan | **None is in the tree** |
| The roadmap's *Deferred, deliberately* table | **Not a rejected-candidate list.** Its rows are scope items — a learned recommendation model, a cache layer, a full frontend, container orchestration, a coverage percentage — not defects that were weighed as traps and cut |
| `docs/decisions/adr/` | No ADR records a selection among trap candidates. `ADR-001` records two candidates for a **library**, which is a different kind of choice |
| Community or upstream URLs anywhere in the tracked tree | **None**, outside two cloud-provider limits pages cited in `R23` and `R24`, and the image source cited in `R27` |

So the question *"what else was considered, and why was it cut?"* has no answer in this
repository, and this document does not supply one. **A rejected-candidate list assembled now
from memory or from plausibility would be indistinguishable from an invented one**, and it
would be worse than the empty cell it replaced: an empty cell can be filled later by evidence,
and a fabricated list cannot be un-read.

If the pool is ever wanted, the honest route runs forward rather than backward — record the
candidates for round three as they are considered, before the choosing is done.
