package io.github.naimelijah.finassist.gateway.composition;

import java.net.http.HttpClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers what wraps everything else the gateway does: the Host allow-list, ahead of the
 * security chain, every route and every relay; and the HTTP version every outbound call speaks.
 *
 * <p>Mirrors the ledger's {@code LedgerConfiguration}. The gateway has no bounded context of its
 * own — it holds no domain and restates no DTO of the services it relays to (ADR-026) — so every
 * class it has lives in this {@code composition} package, the role {@code composition} plays in
 * the ledger. {@link GatewaySecurityConfiguration} assembles the rest: the sign-in, the session
 * and the security chain that runs behind this filter.
 */
@Configuration
public class GatewayConfiguration {

    /** The comma-separated host names the gateway answers to, bound from {@code GATEWAY_ALLOWED_HOSTS}. */
    static final String ALLOWED_HOSTS_PROPERTY = "finassist.http.allowed-hosts";

    /**
     * The Host allow-list, registered at the highest filter precedence (ADR-030). A refused
     * request is answered before any filter that traces, logs or dispatches has handled it, and
     * before {@code springSecurityFilterChain}, which Boot registers at order {@code -100} — so a
     * request under a foreign {@code Host} never reaches an authentication or a session lookup.
     * {@code GatewayApplicationIT} reads the chain the server actually built rather than trusting
     * those two numbers.
     */
    @Bean
    FilterRegistrationBean<HostAllowListFilter> hostAllowListFilter(
            @Value("${" + ALLOWED_HOSTS_PROPERTY + "}") List<String> allowedHosts) {
        FilterRegistrationBean<HostAllowListFilter> registration =
                new FilterRegistrationBean<>(new HostAllowListFilter(allowedHosts));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Pins every outbound call this process makes to HTTP/1.1, so that no cleartext connection
     * carries an h2c upgrade offer.
     *
     * <p>The JDK {@code HttpClient} underneath defaults to {@link HttpClient.Version#HTTP_2}, which
     * over cleartext means offering an upgrade on every request: {@code Upgrade: h2c}, an {@code
     * HTTP2-Settings} header, and a {@code Connection} header naming both. Tomcat ignores them, so
     * relaying to the ledger worked. The assistant runs uvicorn/h11, which rejects an upgrade
     * request that also carries a body with {@code 400} — so every POST the gateway relayed to the
     * assistant failed, and pricing a disposal was unusable from the web client.
     *
     * <p>Nothing in front of any of these services speaks TLS, so HTTP/2 was never reachable
     * anyway: the upgrade offer was pure cost, and h2c is the only way the JDK client can try for
     * it. The day a relayed hop runs over TLS, HTTP/2 becomes worth having again, and it arrives
     * through ALPN with no upgrade header at all — so this pin can be lifted then, not before.
     *
     * <p>Boot exposes no property for the HTTP version: {@code spring.http.clients.*} carries
     * {@code connect-timeout}, {@code read-timeout}, {@code redirects}, {@code cookie-handling},
     * {@code ssl.bundle}, {@code imperative.factory} and {@code reactive.connector}, and none of
     * them names one — so a bean it is, and this is the extension point Boot publishes for
     * exactly that. {@code
     * ImperativeHttpClientAutoConfiguration} applies it to the detected {@code
     * ClientHttpRequestFactoryBuilder}, which is the bean {@code spring.http.clients}'s timeouts
     * are applied on top of, and the bean both the {@code RestClient.Builder} behind the relay's
     * {@code RestClientProxyExchange} and the {@code RestTemplateBuilder} {@link
     * KeycloakRegistration} uses are configured from. One bean, every outbound call this process
     * makes.
     *
     * <p>Typed to the JDK builder because that is what Boot detects here — no HttpComponents,
     * Jetty or Reactor client is on the classpath. If one ever were, Boot would detect it instead
     * and this customizer would stop applying; {@code GatewayLoginFlowIT}'s two upgrade-offer
     * assertions are what turns red if that happens.
     *
     * <p>One header this pin does not reach: Spring Cloud Gateway's {@code
     * RemoveHopByHopRequestHeadersFilter} strips an incoming {@code Upgrade} and {@code
     * Connection} but not {@code HTTP2-Settings}, so a client-supplied one still reaches the
     * upstream. Harmless today — nothing in any of the three codebases parses that header, and
     * neither Tomcat nor h11 acts on an upgrade offer without the {@code Upgrade} and {@code
     * Connection: upgrade} headers this filter does strip — but it would matter the day an
     * upstream started treating a bare {@code HTTP2-Settings} header as an offer on its own.
     */
    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<JdkClientHttpRequestFactoryBuilder> relayHttpVersion() {
        return builder ->
                builder.withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1));
    }
}
