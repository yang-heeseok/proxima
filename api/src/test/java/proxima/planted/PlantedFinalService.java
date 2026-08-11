package proxima.planted;

import org.springframework.transaction.annotation.Transactional;

/**
 * Violates {@code TRANSACTIONAL_CLASSES_CAN_BE_SUBCLASSED}.
 *
 * <p><b>Written in Java, and that is not a stylistic choice.</b> This violation cannot be
 * planted in Kotlin: {@code kotlin("plugin.spring")} opens any class carrying
 * {@code @Transactional} at compile time, so a Kotlin version of this file would arrive at
 * the rule already fixed and the self-test would prove nothing.
 *
 * <p>Java classes are not touched by the Kotlin compiler plugins, so this reaches the rule
 * in the shape a Kotlin class would have if that plugin were ever removed — which is the
 * failure the rule exists to catch.
 */
public final class PlantedFinalService {

    @Transactional
    public void boundary() {
        // nothing -- the `final` on the class is the violation
    }
}
