package net.gseek.proxima.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

/**
 * What a learner is currently believed to know about one concept.
 *
 * **The domain requires exactly one row per `(learner, concept)` and the database does not
 * enforce it yet.** `V1` omits that unique constraint deliberately, so the race it permits
 * can be reproduced and measured — two concurrent requests both run an application-level
 * existence check, both pass, and both insert. The constraint arrives in the same commit
 * as the failing test and the report. See `ADR-002` and `T6`.
 *
 * Nothing in this class compensates for that absence. Pretending otherwise here would
 * quietly make the report unwritable.
 */
@Entity
@Table(name = "mastery")
class Mastery(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "learner_id", nullable = false)
    var learner: Learner,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concept_id", nullable = false)
    var concept: Concept,

    /** `numeric(4,3)` — `BigDecimal`, not `Double`. A score compared against a threshold. */
    @Column(nullable = false)
    var score: BigDecimal = BigDecimal("0.000"),

    @Column(name = "attempts_count", nullable = false)
    var attemptsCount: Int = 0,

) : BaseEntity() {

    /**
     * Optimistic locking.
     *
     * Present from `V1` because comparing locking strategies is one of the things this
     * schema exists to support. **It is not yet doing any work that has been measured** —
     * `T5` counts lost updates first, then compares optimistic against pessimistic against
     * a single atomic statement, on both correctness and throughput.
     */
    @Version
    @Column(nullable = false)
    var version: Long = 0

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH
}
