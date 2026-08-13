package net.gseek.proxima.security

import org.springframework.jdbc.core.JdbcTemplate

/**
 * One learner with exactly enough graph behind them to make the recommendation return
 * something.
 *
 * **A `200` carrying an empty list would make every assertion in `T9`'s second strand
 * vacuous.** "Learner A read learner B's recommendations" and "learner A read nothing, which
 * is also what B has" are the same status code. So the fixture plants an item code that
 * belongs to exactly one learner, and the tests assert on **that string in the body** rather
 * than on the status alone — `R4`'s rule, and the same reason `ConnectionHoldingGateTest`
 * checks for `item-990001`.
 *
 * The rows are committed, because the defect being measured lives in a real HTTP request and
 * a test that shares a transaction with the code under test cannot observe one. Cleanup is
 * therefore the caller's job; [deleteLearners] does it by prefix.
 */
internal object LearnerFixtures {

    /** Everything created here is prefixed so that cleanup cannot touch anything else. */
    const val PREFIX = "t9-"

    /**
     * @return the learner's id, and the item code only that learner can be recommended.
     */
    fun seedLearner(jdbc: JdbcTemplate, tag: String): Pair<Long, String> {
        val learnerId = jdbc.queryForObject(
            "insert into learner (external_ref) values (?) returning id",
            Long::class.java,
            "$PREFIX$tag",
        )!!
        val conceptId = jdbc.queryForObject(
            "insert into concept (code, name, grade_band) values (?, ?, 'G5-6') returning id",
            Long::class.java,
            "$PREFIX$tag", "Concept $tag",
        )!!
        val itemCode = "$PREFIX$tag-item"
        val itemId = jdbc.queryForObject(
            "insert into item (code, concept_primary_id, difficulty, is_active) " +
                "values (?, ?, 5, true) returning id",
            Long::class.java,
            itemCode, conceptId,
        )!!
        jdbc.update("insert into item_concept (item_id, concept_id, weight) values (?, ?, 1.000)", itemId, conceptId)
        jdbc.update(
            "insert into mastery (learner_id, concept_id, score, attempts_count, version, updated_at) " +
                "values (?, ?, 0.500, 0, 0, now())",
            learnerId, conceptId,
        )
        return learnerId to itemCode
    }

    fun deleteLearners(jdbc: JdbcTemplate) {
        jdbc.update("delete from mastery where learner_id in (select id from learner where external_ref like ?)", "$PREFIX%")
        jdbc.update("delete from item_concept where item_id in (select id from item where code like ?)", "$PREFIX%")
        jdbc.update("delete from item where code like ?", "$PREFIX%")
        jdbc.update("delete from concept where code like ?", "$PREFIX%")
        jdbc.update("delete from learner where external_ref like ?", "$PREFIX%")
    }
}
