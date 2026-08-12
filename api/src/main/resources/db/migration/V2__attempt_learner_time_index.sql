-- V2 — the index every read of `attempt` needs.
--
-- Arrives here, and not in V1, because ADR-002 requires an index to come in the same commit
-- as the measurement that justifies it. That measurement is docs/reports/R3.
--
-- MEASURED, warm, median of three, against 3,000,000 rows on the machine in
-- measurement-discipline.md. Query: one learner's attempt history, newest first, limit 20.
--
--   no index                         36.6 ms    Parallel Seq Scan, 999,945 rows discarded
--   (learner_id, attempted_at)        0.056 ms  Index Scan          <- this migration
--   (attempted_at, learner_id)        0.197 ms  Index Scan ... but see below
--   (learner_id, attempted_at)
--     INCLUDE (correct, elapsed_ms,
--              item_id, id)           0.056 ms  Index Only Scan
--
-- WHY NOT THE OTHER COLUMN ORDER. It looks fine. On the shallow query it is used, and
-- 0.197 ms against 36.6 ms would pass any review. It collapses at depth: the same query at
-- OFFSET 2000 goes back to a Parallel Seq Scan and 37.3 ms, because a leading
-- `attempted_at` can satisfy the ORDER BY but cannot restrict to one learner, so the
-- planner walks the whole index and gives up. Both indexes are 90 MB. The difference is
-- visible only in a test that pages.
--
-- WHY NOT THE COVERING VARIANT. It produces an Index Only Scan for every query measured and
-- is not faster: 0.056 ms against 0.056 ms shallow, 0.256 ms against 0.326 ms at depth. It
-- costs 168 MB against 90 MB -- 87% more, for a gain at the edge of measurement noise. If a
-- future report shows heap access mattering under concurrency, this decision is worth
-- revisiting; it is not justified by anything measured today.
--
-- WHAT IS DELIBERATELY STILL ABSENT: an index on `correct`. Measured because it is the
-- obvious thing to add to a boolean anyone filters on -- 20 MB, and PostgreSQL never chose
-- it once. Two distinct values across three million rows cannot narrow anything.

create index ix_attempt_learner_attempted_at on attempt (learner_id, attempted_at);

comment on index ix_attempt_learner_attempted_at is
    'Every read of attempt is scoped to one learner and ordered by time. Column order is '
    'load-bearing: the reverse order still serves shallow queries and collapses to a '
    'sequential scan at depth. See docs/reports/R3.';
