package net.gseek.proxima.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Establishes **who is calling**, and nothing else.
 *
 * That "nothing else" is `T9`'s second strand in one sentence. This filter answers *is this a
 * caller I can name?* A great deal of code — including this repository's, before the commit
 * that measured it — then behaves as though the answer to *may this caller have the thing
 * they asked for?* came with it. It does not. The two questions are answered in different
 * places, and only one of them is answered here.
 *
 * A refusal carries a machine-readable reason, because `T9`'s third strand is entirely about
 * telling `expired` apart from `not-yet-valid` apart from `bad-signature`. A filter that
 * answers 401 with an empty body makes that measurement impossible and makes the production
 * incident it corresponds to unreadable.
 */
class TokenAuthenticationFilter(private val tokens: RequestToken) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith(BEARER)) {
            return response.refuse("missing-token")
        }

        when (val verdict = tokens.verify(header.removePrefix(BEARER))) {
            is RequestToken.Verdict.Refused -> response.refuse(verdict.reason)
            is RequestToken.Verdict.Trusted -> {
                request.setAttribute(RequestToken.SUBJECT_ATTRIBUTE, verdict.subject)
                chain.doFilter(request, response)
            }
        }
    }

    private fun HttpServletResponse.refuse(reason: String) {
        status = HttpServletResponse.SC_UNAUTHORIZED
        contentType = "application/json"
        writer.write("""{"error":"$reason"}""")
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

/**
 * Wiring, and the one decision in it that matters.
 *
 * **The filter is registered under `/api/v1` only, not for every path.** A filter mapped to
 * the whole application would put the management surface behind this token as well, which
 * sounds stricter and is a worse arrangement to measure: `R10`'s gate asserts what
 * `/actuator` answers, and it would then be asserting what this filter answers instead. The
 * two controls stay separable.
 *
 * (The url pattern is written in code below rather than quoted here: a slash-star sequence
 * inside a KDoc block opens a **nested comment** in Kotlin, and this file did not compile the
 * first time for exactly that reason.)
 *
 * It also bounds the blast radius of adding authentication to a repository that had none —
 * only requests under `/api/v1` change, and `R11` §3.1 records which existing test that broke.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    // THERE IS NO `Clock` BEAN HERE, AND THERE WAS ONE FOR AN AFTERNOON.
    //
    //   `RequestToken` reads an injected Clock so that T9's clock-skew strand can be measured
    //   by moving time rather than by waiting for it. A @Bean was written here to supply it,
    //   with a KDoc arguing why it had to be injected.
    //
    //   `ProximaConfiguration` already had that bean, with the same argument written out for
    //   the same reason -- "so that '30 days ago' is a value a test can fix". Spring refused
    //   the duplicate and 37 of 56 tests failed with BeanDefinitionOverrideException, none of
    //   them about security.
    //
    //   The justification was re-derived instead of looked for. That is cheaper to notice
    //   than to repeat: the reasoning being obvious enough to reach twice is exactly what
    //   makes it likely somebody already reached it.

    @Bean
    fun tokenAuthenticationFilter(tokens: RequestToken): FilterRegistrationBean<TokenAuthenticationFilter> =
        FilterRegistrationBean(TokenAuthenticationFilter(tokens)).apply {
            addUrlPatterns("/api/v1/*")
            order = Ordered_AUTHENTICATION
        }

    private companion object {
        /** Before anything that might want to know who is calling. */
        const val Ordered_AUTHENTICATION = -100
    }
}
