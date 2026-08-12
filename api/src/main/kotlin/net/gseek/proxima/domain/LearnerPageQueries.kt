package net.gseek.proxima.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/**
 * The four ways to ask for "a page of learners, with their attempts".
 *
 * They exist side by side because `T2` is a comparison, and a comparison needs the losing
 * options to be runnable rather than described. Each one is what somebody actually writes.
 *
 * **None of these is the shipped read path.** Nothing in the application calls them yet;
 * they are the subject of a report. When one is chosen it moves out of here.
 */
interface LearnerPageQueries : Repository<Learner, Long> {

    /**
     * **The defect.** A collection fetch and a page in one query.
     *
     * This is the natural thing to write: the page is what the caller asked for, the fetch
     * join is there to avoid N+1, and both are supposedly the framework's job. What
     * Hibernate does with it is in the report — and it is not an error, and it is not a
     * failure, and the query returns the right answer.
     */
    @Query("select l from Learner l left join fetch l.attempts")
    fun pageWithAttempts(pageable: Pageable): List<Learner>

    /**
     * **Two collections at once.** The roadmap names this separately because it fails
     * differently — loudly, at startup of the query rather than quietly at run time.
     */
    @Query("select l from Learner l left join fetch l.attempts left join fetch l.masteries")
    fun pageWithAttemptsAndMasteries(pageable: Pageable): List<Learner>

    /**
     * The fix people reach for first, because the warning mentions duplicates and `distinct`
     * makes duplicates go away. It changes what is returned; whether it changes what
     * Hibernate does with the page is the question the report answers.
     */
    @Query("select distinct l from Learner l left join fetch l.attempts")
    fun pageWithAttemptsDistinct(pageable: Pageable): List<Learner>

    /**
     * Step one of the two-query approach: page the roots alone, with no join at all, so the
     * database applies the page. The collections are fetched afterwards, by id.
     */
    @Query("select l from Learner l")
    fun pageRootsOnly(pageable: Pageable): List<Learner>

    @Query("select l from Learner l left join fetch l.attempts where l.id in :ids")
    fun fetchAttemptsFor(ids: Collection<Long>): List<Learner>
}
