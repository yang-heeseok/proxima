package net.gseek.proxima.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/**
 * A person learning. A generated reference and nothing else — no name, no contact, no
 * demographic. `PUB-7`.
 */
@Entity
@Table(name = "learner")
class Learner(

    /** `learner-000001`. Opaque, and shaped so it cannot be mistaken for a real one. */
    @Column(name = "external_ref", nullable = false, updatable = false)
    var externalRef: String,

) : BaseEntity() {

    /**
     * Written by the database default, never by the application.
     *
     * `insertable = false` is what makes that true rather than merely intended: without
     * it Hibernate sends `null` for the column on insert and the `not null` constraint
     * fires, which is a confusing failure for a column that has a perfectly good default.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null
}
