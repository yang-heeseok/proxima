/**
 * The entity shapes `R39` compares. **Five classes, three equality implementations, one
 * question: what does one `==` cost?**
 *
 * ## The package is `net.gseek.fixtures`, and that is not a style choice
 *
 * `ProximaApplication` is `@SpringBootApplication` in `net.gseek.proxima`, so Spring Boot's
 * entity scan roots there, and test sources share the classpath with main sources. An
 * `@Entity` written anywhere under `net.gseek.proxima` — including nested inside a test class
 * — joins the application's persistence unit, fails `ddl-auto=validate` against a table Flyway
 * never built, and takes every `@SpringBootTest` in the module down with it. That is measured
 * history, not caution: commit `8e5843a` did exactly this and **34 of 52 tests failed across
 * six classes, none of them the one that caused it.** `PersistenceUnitGateTest` is what
 * notices, and it names this package in its failure message.
 *
 * The same placement also keeps these classes away from `TransactionBoundaryRulesTest`, whose
 * import is `DO_NOT_INCLUDE_TESTS` **and** `importPackages("net.gseek.proxima")`. Two of the
 * classes below are `@Entity` `data class`es, which is precisely what
 * `ENTITIES_ARE_NOT_DATA_CLASSES` refuses. They are excluded twice over, deliberately: the
 * production rule must go on refusing that shape while this file measures what the shape
 * costs.
 *
 * ## The tables live in their own schema
 *
 * `R39`'s measurement runs on a standalone `SessionFactory` with `hbm2ddl.auto=create`,
 * pointed at the same container the Spring context uses so that no second PostgreSQL has to
 * start. Creating these tables in `public` would break `BaselineMigrationTest`, which asserts
 * that `information_schema.tables` in `table_schema = 'public'` holds **exactly** the seven
 * baseline tables and nothing else. So the factory sets `hibernate.default_schema` and every
 * table below lands outside the schema that assertion reads.
 *
 * ## Why the annotation targets differ between the two halves
 *
 * The `open class` shapes annotate exactly where the shipped entities annotate — `@Id` in the
 * class body as `BaseEntity` does, `@ManyToOne` on a constructor property as `Attempt` does —
 * because copying a shape this build already runs is the way to be sure it maps.
 *
 * The `data class` shapes cannot put `@Id` in the class body: the whole point is that `id` is
 * a **constructor** property, since that is what puts it inside the generated `equals` and
 * `hashCode`. They therefore use explicit `@field:` targets, which place the annotation on the
 * backing field where Hibernate reads it, rather than relying on this build's
 * `-Xannotation-default-target=param-property` to resolve to the same place. That is a
 * deliberate refusal to write a compiler behaviour from memory.
 */
package net.gseek.fixtures.basics

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.Hibernate

/**
 * A parent that **overrides `equals`**, by id, through `Hibernate.getClass` — the shape
 * `BaseEntity` ships.
 *
 * Whether the associated class overrides `equals` is not a detail. Hibernate's lazy
 * initializer inspects exactly that when a proxy is asked to compare itself, and the two
 * answers cost different numbers of statements. [PlainParent] is the other answer.
 */
@Entity
@Table(name = "t_basics_parent_eq")
open class EqParent(

    @Column(name = "label", nullable = false)
    open var label: String = "",

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id != null && id == (other as EqParent).id
    }

    override fun hashCode(): Int = Hibernate.getClass(this).hashCode()
}

/**
 * A parent that **does not override `equals`**, so comparison falls back to object identity.
 *
 * This is not a straw man. It is what every entity looks like before somebody decides
 * equality is worth writing, which is most entities in most codebases.
 */
@Entity
@Table(name = "t_basics_parent_plain")
open class PlainParent(

    @Column(name = "label", nullable = false)
    open var label: String = "",

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null
}

/**
 * **The shape that ships.** Identity by `id`, hash constant per type, type compared through
 * `Hibernate.getClass` so a proxy and a loaded instance of one row agree on their class.
 *
 * Nothing in its `equals` reads an association, so nothing in its `equals` can issue a query.
 * That is the property `R39` prices the alternative against.
 */
@Entity
@Table(name = "t_basics_child_id")
open class IdEqualsChild(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    open var parent: EqParent,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "secondary_id", nullable = false)
    open var secondary: EqParent,

    @Column(name = "label", nullable = false)
    open var label: String = "",

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id != null && id == (other as IdEqualsChild).id
    }

    override fun hashCode(): Int = Hibernate.getClass(this).hashCode()
}

/**
 * **A `data class` entity with `id` in the primary constructor, and two lazy associations.**
 *
 * Kotlin generates `equals` and `hashCode` over every constructor property, in declaration
 * order, with `equals` short-circuiting at the first mismatch. So this one class carries both
 * halves of the trap at once:
 *
 * - `hashCode` includes `id`, which is `null` before persist and a number after. An instance
 *   filed in a `HashSet` before saving is filed under a hash that no longer exists.
 * - `equals` reaches `parent` and `secondary`, which are lazy proxies, so comparing two
 *   instances of **the same row** walks into the association and Hibernate has to make the
 *   proxies answer.
 *
 * Two associations rather than one, because the question `R39` is asked to answer is *how
 * many* queries an equality check costs, and a single association cannot distinguish "one" from
 * "one per association reached".
 */
@Entity
@Table(name = "t_basics_child_data")
data class DataClassChild(

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "parent_id", nullable = false)
    var parent: EqParent? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "secondary_id", nullable = false)
    var secondary: EqParent? = null,

    @field:Column(name = "label", nullable = false)
    var label: String = "",
)

/**
 * The same `data class`, associated with the parent that **does not** override `equals`.
 *
 * The arm exists because the statement count of an equality check is not a property of the
 * `data class` alone — it is a property of the pair. What changes between this and
 * [DataClassChild] is not the class being compared but the class being compared *to*, and the
 * result differs in both the number of statements and the answer.
 */
@Entity
@Table(name = "t_basics_child_data_plain")
data class DataClassChildPlainParent(

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "parent_id", nullable = false)
    var parent: PlainParent? = null,

    @field:Column(name = "label", nullable = false)
    var label: String = "",
)
