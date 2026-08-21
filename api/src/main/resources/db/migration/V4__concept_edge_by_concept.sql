-- V4 — the index a prerequisite traversal reads on.
--
-- READ docs/decisions/adr/ADR-002-schema-tells-the-story.md BEFORE ADDING AN INDEX HERE.
-- The measurement that justifies this one is docs/reports/R20.
--
-- WHY concept_edge WAS NOT ALREADY COVERED, THOUGH IT LOOKS LIKE IT WAS.
--
--   `uk_concept_edge unique (prerequisite_id, concept_id)` has existed since V1, and
--   PostgreSQL implements a unique constraint as a B-tree. So there IS an index over both
--   of these columns, and it has been there from the first commit.
--
--   It leads on `prerequisite_id`. A prerequisite traversal restricts on the other one --
--   "which concepts must be understood before this one" is `where concept_id = ?` -- and a
--   B-tree cannot restrict on its second column. This is R3's finding arriving in a second
--   table: COLUMN ORDER IS LOAD-BEARING, and the wrong order is invisible until something
--   reads the other way. Nothing read the other way until now, because
--   RecommendationQueries consults this table exactly one level deep as a NOT EXISTS, and
--   a NOT EXISTS over `e.concept_id = m.concept_id` was answered by scanning 8,994 rows
--   without anyone minding.
--
--   It is also R16's finding with its sign flipped. There, a constraint added for
--   correctness turned out to be worth 15x to a read path nobody had measured it against.
--   Here, a constraint added for correctness looks like it covers a read path and does not.
--
-- MEASURED, median of three, against the shipped graph -- 3,000 concepts, 8,994 edges --
-- on the machine in measurement-discipline.md. Query: the transitive prerequisite closure
-- of one concept, `PrerequisiteQueries.closure`. `rows fed` is rows x loops through every
-- node that reads concept_edge, which is the machine-independent half of this table.
--
--   depth 6                      rows fed   exec ms   plan
--     no index                     44,973     3.887   Seq Scan, Hash Join, x5 iterations
--     (concept_id)                    546     0.500   Index Scan, Nested Loop   <- this migration
--     (concept_id, prerequisite_id)   546     0.538   Index Only Scan
--
--   depth 12                     rows fed   exec ms
--     no index                     98,937    10.526
--     (concept_id)                  5,424     3.466   <- this migration
--     (concept_id, prerequisite_id) 5,424     3.621
--
--   Sizes: (concept_id) 163,840 bytes. (concept_id, prerequisite_id) 303,104 bytes.
--
-- WHY NOT THE COVERING VARIANT, AND THE CONDITION THAT WOULD CHANGE THE ANSWER.
--
--   It produces an Index Only Scan and is not faster: 85% more bytes for a difference
--   inside the run-to-run spread, and on this measurement in the WRONG direction. That is
--   the same verdict R3 reached about INCLUDE columns on `attempt`, reached again by
--   measurement rather than carried over.
--
--   The mechanism is worth stating because it is not about this table. An index-only scan
--   is only index-only when the VISIBILITY MAP says a page holds nothing but rows visible
--   to everyone, and the visibility map is set by VACUUM and by nothing else. Measured at
--   depth 12 on the covering variant:
--
--     before vacuum   Heap Fetches 5,424   buffers 5,454   3.381 ms
--     after  vacuum   Heap Fetches     0   buffers 3,618   3.032 ms
--
--   `seed/` runs generate, load (a COPY), and analyze. IT NEVER RUNS VACUUM -- deliberately,
--   because T4 needed stale statistics -- so the state every measurement in this repository
--   is taken in is the first row. If a VACUUM step is ever added to the load path, the
--   covering variant becomes worth re-measuring; today it would buy 10% for 85% more space
--   in a state the loader does not produce.
--
-- WHAT THE ADVANTAGE DOES NOT DO: hold up with depth. The index feeds 500x fewer rows at
-- depth 3, 82x at depth 6, and 18.2x at depth 12, because the unindexed cost grows with the
-- number of recursive iterations while the indexed cost grows with the frontier -- and the
-- frontier is what depth makes large. An index is not a substitute for a depth bound.

create index ix_concept_edge_concept on concept_edge (concept_id);

comment on index ix_concept_edge_concept is
    'A prerequisite traversal restricts on concept_id, and uk_concept_edge leads on '
    'prerequisite_id, so the constraint''s B-tree cannot serve it. The single column is '
    'deliberate: the covering pair produces an Index Only Scan, costs 85% more, and is not '
    'faster until a VACUUM has set the visibility map -- which the seed loader never runs. '
    'See docs/reports/R20.';
