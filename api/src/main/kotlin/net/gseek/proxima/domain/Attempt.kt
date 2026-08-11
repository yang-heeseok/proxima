package net.gseek.proxima.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * One learner meeting one item once. **The hot table** — three million rows, append-only,
 * every read scoped to one learner and ordered by time.
 *
 * Those four facts decide most of the indexing work in this repository, and `V1` carries
 * no index on `(learner_id, attempted_at)` on purpose so that the first `EXPLAIN` shows a
 * sequential scan. See `ADR-002`.
 */
@Entity
@Table(name = "attempt")
class Attempt(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learner_id", nullable = false)
    var learner: Learner,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    var item: Item,

    @Column(nullable = false)
    var correct: Boolean,

    /**
     * Time on task.
     *
     * Kept because accuracy alone cannot distinguish "knew it" from "guessed it" — the
     * variance of this column carries more signal than its mean.
     */
    @Column(name = "elapsed_ms", nullable = false)
    var elapsedMs: Int,

    /**
     * Whether a hint was opened before answering. A correct answer after a hint is a
     * different event from a correct answer without one.
     */
    @Column(name = "hint_used", nullable = false)
    var hintUsed: Boolean = false,

    @Column(name = "attempted_at", nullable = false)
    var attemptedAt: Instant,

) : BaseEntity()
