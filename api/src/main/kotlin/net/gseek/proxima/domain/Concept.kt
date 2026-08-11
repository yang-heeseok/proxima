package net.gseek.proxima.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** One mathematical idea. */
@Entity
@Table(name = "concept")
class Concept(

    @Column(nullable = false, updatable = false)
    var code: String,

    @Column(nullable = false)
    var name: String,

    @Column(name = "grade_band", nullable = false)
    var gradeBand: String,

) : BaseEntity() {

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null
}
