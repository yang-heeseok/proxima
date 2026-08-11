package proxima.planted;

import org.springframework.transaction.annotation.Transactional;

/**
 * Violates {@code TRANSACTIONAL_METHODS_CAN_BE_OVERRIDDEN}, twice.
 *
 * <p>The class itself is perfectly proxyable. These two methods are not: a proxy advises by
 * overriding, and neither a {@code private} nor a {@code final} method can be overridden.
 * Spring proxies the class, the bean looks entirely correct, and the boundary is missing on
 * exactly these methods.
 *
 * <p>Java for the same reason as {@link PlantedFinalService}.
 *
 * <p>Nothing calls {@code privateBoundary()} on purpose. Calling it from inside this class
 * would also violate the self-invocation rule, and each planted class is meant to violate
 * exactly one rule so that a self-test failure names the rule that broke.
 */
public class PlantedUnproxyableMethods {

    @Transactional
    private void privateBoundary() {
        // nothing -- `private` is the violation
    }

    @Transactional
    public final void finalBoundary() {
        // nothing -- `final` is the violation
    }
}
