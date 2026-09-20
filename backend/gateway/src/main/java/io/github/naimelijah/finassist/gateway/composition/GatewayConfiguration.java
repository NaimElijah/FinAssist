package io.github.naimelijah.finassist.gateway.composition;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Assembles the gateway's cross-cutting defences: what must run before a route, a login or a
 * relay exists for it to protect.
 *
 * <p>Mirrors the ledger's {@code LedgerConfiguration}: the one place in this service, beyond
 * {@code GatewayApplication} itself, that knows Spring exists. The gateway has no bounded
 * context of its own to compose yet — {@code GatewayApplication}'s Javadoc says why — so this
 * package holds only what every request must pass through first, the same role {@code
 * composition} plays in the ledger.
 */
@Configuration
public class GatewayConfiguration {

    /** The comma-separated host names the gateway answers to, bound from {@code GATEWAY_ALLOWED_HOSTS}. */
    static final String ALLOWED_HOSTS_PROPERTY = "finassist.http.allowed-hosts";

    /**
     * The Host allow-list, registered at the highest filter precedence (ADR-030). A refused
     * request is answered before any filter that traces, logs or dispatches has handled it, and
     * — once the task that adds Spring Security registers its filter chain — before that chain
     * too.
     */
    @Bean
    FilterRegistrationBean<HostAllowListFilter> hostAllowListFilter(
            @Value("${" + ALLOWED_HOSTS_PROPERTY + "}") List<String> allowedHosts) {
        FilterRegistrationBean<HostAllowListFilter> registration =
                new FilterRegistrationBean<>(new HostAllowListFilter(allowedHosts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
