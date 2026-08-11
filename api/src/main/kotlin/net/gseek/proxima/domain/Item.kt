package net.gseek.proxima.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/** One problem. */
@Entity
@Table(name = "item")
class Item(

    @Column(nullable = false, updatable = false)
    var code: String,

    /**
     * `LAZY`, deliberately and everywhere in this codebase.
     *
     * `@ManyToOne` defaults to `EAGER` in the JPA specification, which means every read of
     * an `Item` would also read its `Concept` whether or not anyone asked. That default is
     * how a query that looks like one `SELECT` becomes many, and `T7` is a test that counts
     * exactly that. Making it lazy is not an optimisation here — it is the condition under
     * which the counting means anything.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concept_primary_id", nullable = false)
    var conceptPrimary: Concept,

    /** `smallint` in the schema, so `Short` here — `validate` checks that they agree. */
    @Column(nullable = false)
    var difficulty: Short,

    /**
     * A retired problem. Present because a query that forgets to filter it returns rows it
     * should not, and that is a difference which only appears against data that actually
     * contains inactive rows — the generator emits roughly 5% of them for that reason.
     */
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

) : BaseEntity() {

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: Instant? = null
}
