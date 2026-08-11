package net.gseek.proxima

import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ProximaConfiguration {

    /**
     * Injected rather than called statically so that "30 days ago" is a value a test can
     * fix. A query whose result depends on the wall clock cannot be asserted on, and this
     * repository's dataset is generated around a fixed instant — `2026-08-10T00:00:00Z` —
     * for the same reason.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
