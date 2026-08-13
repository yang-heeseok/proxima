/**
 * **The package is `net.gseek.fixtures`, not `net.gseek.proxima`, and that is the whole
 * reason this file exists separately from the test that uses it.**
 *
 * `ProximaApplication` is annotated `@SpringBootApplication` in `net.gseek.proxima`, so
 * Spring Boot's entity scan takes that package as its root. Test sources are on the same
 * classpath as main sources when tests run, so **an `@Entity` written anywhere under
 * `net.gseek.proxima` — including nested inside a test class — joins the application's
 * persistence unit.** Hibernate then validates its table against the schema Flyway built,
 * does not find it, and every `@SpringBootTest` in the module fails to start.
 *
 * That is not a hypothesis. These four classes were nested inside `IdentifierGenerationTest`
 * in commit `8e5843a`, whose KDoc claimed they could not join the persistence unit because
 * they were measured through a standalone `SessionFactory`. The claim was about who *uses*
 * them and the defect is about who *finds* them. CI went red with
 * `Schema validation: missing table [t_open3_identity]` and **34 of 52 tests failed**, six
 * classes' worth, none of them the one that introduced the problem.
 *
 * `PersistenceUnitGateTest` is what notices if this is undone. Moving these classes back
 * under `net.gseek.proxima` — which looks like tidying — turns it red immediately, with a
 * message rather than with thirty-four schema-validation stack traces.
 */
package net.gseek.fixtures.open3

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table

/** `ADR-003` arm 1 — the generator that ships. The database assigns the key. */
@Entity
@Table(name = "t_open3_identity")
class IdentityRow(var payload: String = "") {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}

/** `ADR-003` arm 2 — a sequence at its **default** allocation size, which wins nothing. */
@Entity
@Table(name = "t_open3_seq_1")
class SequenceRowAllocate1(var payload: String = "") {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "g1")
    @SequenceGenerator(name = "g1", sequenceName = "seq_open3_1", allocationSize = 1)
    var id: Long? = null
}

/** `ADR-003` arm 3 — the same sequence, allocating in blocks. This is the one that is fast. */
@Entity
@Table(name = "t_open3_seq_50")
class SequenceRowAllocate50(var payload: String = "") {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "g50")
    @SequenceGenerator(name = "g50", sequenceName = "seq_open3_50", allocationSize = 50)
    var id: Long? = null
}

/** The seeded-database collision hazard `docs/decisions/open.md` recorded on 2026-08-10. */
@Entity
@Table(name = "t_open3_seeded")
class SeededRow(var payload: String = "") {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "gs")
    @SequenceGenerator(name = "gs", sequenceName = "seq_open3_seeded", allocationSize = 1)
    var id: Long? = null
}
