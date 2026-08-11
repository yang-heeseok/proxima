package net.gseek.proxima.recommendation

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Stands in for a call to something that is not this database.
 *
 * Every real system has one: a content service that knows how a problem is rendered, a
 * feature-flag lookup, an authorisation check, a recommendation model behind an HTTP call.
 * **The only property `T1` needs from it is that it takes time and does not touch
 * PostgreSQL.**
 *
 * It is a `Thread.sleep`, and that is not a simplification of a blocking network call — it
 * is exactly what one does to the thread that made it. The thread is parked and holds
 * whatever it was holding. Substituting a real HTTP call would add DNS, TLS and a second
 * process to the measurement without changing the mechanism under test.
 *
 * The delay is a property so that the same code serves two different measurements: a long
 * one, where a single request is enough to see whether a connection is held, and a
 * realistic one for the load run.
 */
@Component
class ContentGateway(
    @Value("\${proxima.content-gateway.delay-ms:150}")
    private val delayMillis: Long,
) {

    fun renderHints(itemIds: List<Long>): Map<Long, String> {
        if (delayMillis > 0) {
            Thread.sleep(delayMillis)
        }
        return itemIds.associateWith { "render-$it" }
    }
}
