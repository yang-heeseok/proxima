package proxima.planted;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Violates {@code ENTITIES_CAN_BE_SUBCLASSED}.
 *
 * <p>Hibernate builds a lazy proxy by generating a subclass at run time, exactly as Spring
 * builds an AOP proxy. A {@code final} entity cannot be subclassed, so every association to
 * it loads eagerly — with no error, no warning, and one {@code SELECT} quietly becoming
 * several.
 *
 * <p>Java, because {@code kotlin("plugin.jpa")} opens {@code @Entity} classes at compile
 * time. That was measured rather than assumed at {@code 0a05991}: removing that plugin
 * makes Kotlin entities final and strips their no-arg constructor, while removing
 * {@code kotlin("plugin.spring")} changes neither. This file is the shape the Kotlin
 * entities would have if that plugin were dropped.
 */
@Entity
public final class PlantedFinalEntity {

    @Id
    private Long id;

    public Long getId() {
        return id;
    }
}
