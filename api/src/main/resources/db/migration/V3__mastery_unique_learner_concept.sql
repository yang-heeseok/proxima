-- V3 — the constraint the domain has required since V1.
--
-- A learner has exactly one mastery of one concept. V1 said so in a comment and left it
-- unenforced on purpose (ADR-002), so that the race could be measured instead of asserted.
-- It was: eight concurrent requests produced eight rows, with no exception anywhere.
-- docs/reports/R7.
--
-- WHY THIS IS NOT OPTIONAL EVEN WITH CORRECT APPLICATION CODE. The application check runs
-- in one transaction and the insert in another; nothing connects them. There is no version
-- of "look, then leap" that closes the gap, because the gap is between two statements and
-- only the database can be inside it.
--
-- DEDUPLICATION FIRST. The seed generates one mastery per (learner, concept), so a freshly
-- loaded database is clean -- but any database this migration meets in the wild may not be,
-- and CREATE UNIQUE INDEX would fail on it. The delete below keeps the lowest id of each
-- group, which is the earliest row and the one every prior read was most likely to have
-- returned. It is deliberately not a merge: combining scores would be a domain decision
-- that this migration is not entitled to make, and doing it silently would be worse than
-- dropping the duplicates loudly in a report.

-- WHY THIS IS A JOIN AGAINST ONE AGGREGATE PASS AND NOT A CORRELATED SUBQUERY.
--
--   It was the obvious form first:
--
--       delete from mastery m
--        where m.id > (select min(m2.id) from mastery m2
--                       where m2.learner_id = m.learner_id
--                         and m2.concept_id = m.concept_id);
--
--   That reads correctly and IS correct. It is also quadratic: `mastery` carries no index on
--   (learner_id, concept_id) -- this migration is what creates one -- so the planner scans the
--   table once per row to evaluate the subquery. Measured against the seeded database, 600,000
--   rows:
--
--       correlated subquery   cost 9,139,221,232   never completed
--       this statement        cost        34,214   768 ms, 0 rows removed
--
--   About 267,000x. The first form passed every test in this repository, because every test
--   applies this migration to an EMPTY schema -- the dedup runs against nothing. It was found
--   when the application was pointed at the seeded database for a load run and Flyway sat on
--   "Migrating schema to version 3" until the harness gave up. docs/reports/R15.
--
--   Semantics are unchanged: keep the lowest id of each (learner_id, concept_id) group.
--   MigrationDeduplicationTest reads this statement out of this file, runs it against planted
--   duplicates, and asserts that the lowest id is what survives.
delete from mastery m
 using (
     select learner_id, concept_id, min(id) as keep
       from mastery
      group by learner_id, concept_id
 ) k
 where m.learner_id = k.learner_id
   and m.concept_id = k.concept_id
   and m.id > k.keep;

alter table mastery
    add constraint uk_mastery_learner_concept unique (learner_id, concept_id);

comment on constraint uk_mastery_learner_concept on mastery is
    'The domain rule, enforced where it can actually be enforced. Absent from V1 on purpose '
    'so the race could be measured -- see ADR-002 and docs/reports/R7.';
