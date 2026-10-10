package io.github.naimelijah.finassist.ledger.composition;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The ledger's one security chain: every {@code /api/v1} request needs a bearer token, and every
 * token needs a permission its role grants, or the request is refused (ADR-030).
 *
 * <p>A standalone {@code @Configuration} rather than a nested part of {@code LedgerConfiguration},
 * so that {@code @WebMvcTest} slices — which load no scanned configuration — can {@code @Import} it
 * by name through {@code WebSliceSecurity}. Every class it wires stays in {@code composition}: a
 * top-level {@code security} package would be treated as a bounded context by
 * {@code contexts_do_not_depend_on_each_other}, and this configuration has no context of its own to
 * be one.
 *
 * <p><b>Where each rule below comes from, and why it undoes a Spring default:</b>
 *
 * <ul>
 *   <li><b>{@code anyRequest().denyAll()}.</b> A route this chain does not name is refused, HEAD and
 *       OPTIONS included, so adding one is a deliberate act rather than an accident of a method
 *       matcher falling through;
 *   <li><b>{@code dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()}, not {@code
 *       requestMatchers("/error").permitAll()}.</b> {@code AuthorizationFilter}'s {@code
 *       filterErrorDispatch} defaults to {@code true} and Boot 4.1.1 ships no {@code
 *       ErrorPageSecurityFilter}, so under deny-by-default a genuine 500 the container forwards to
 *       {@code /error} would itself be answered 403, hiding the real failure behind a status that
 *       means something else. Permitting the dispatcher type rather than the path is tighter: a
 *       direct {@code GET /error} still arrives as {@code DispatcherType.REQUEST} and stays denied,
 *       where ADR-032's URL rules list the path as public. Nothing legitimate calls {@code
 *       /error} directly — it only ever exists as a forward target — so nothing is lost by keeping
 *       it behind {@code denyAll} rather than opening an always-public endpoint an attacker could
 *       poke on purpose;
 *   <li><b>{@link ProblemAuthenticationEntryPoint} is set twice</b> — once on {@code
 *       oauth2ResourceServer(...)} itself and again on the outer {@code exceptionHandling(...)} —
 *       because it answers two different filters. A request whose token fails to decode or
 *       validate — expired, wrong issuer, unreachable JWKS — is caught and answered <em>inside</em>
 *       {@code BearerTokenAuthenticationFilter} itself, through whichever entry point {@code
 *       oauth2ResourceServer(...).authenticationEntryPoint(...)} gave it; that filter never reaches
 *       {@code ExceptionTranslationFilter} at all for this failure. Setting only the outer one
 *       leaves every decode and validation failure answered by Spring's own {@code
 *       BearerTokenAuthenticationEntryPoint} instead, silently, since nothing about that path fails
 *       to compile or start. A request with <em>no</em> token, by contrast, reaches {@code
 *       AuthorizationFilter} as an anonymous authentication and is denied there with an {@code
 *       AccessDeniedException} that {@code ExceptionTranslationFilter} does not, on its own, tell
 *       apart from a real principal's denial — {@link ProblemAccessDeniedHandler} makes that
 *       distinction itself, by reading the anonymous authentication back out of the {@code
 *       SecurityContext}, and delegates to the entry point rather than answering 403 for a caller
 *       who was never given the chance to authenticate;
 *   <li><b>{@link #stopSwallowingJwksOutages} corrects the built chain's own {@code
 *       BearerTokenAuthenticationFilter} instance.</b> That filter's constructor installs an {@code
 *       AuthenticationEntryPointFailureHandler} whose {@code rethrowAuthenticationServiceException}
 *       defaults to {@code true} — deliberately, on Spring's part, so an infrastructure failure is
 *       not silently answered as if it were a bad credential. A JWKS outage is exactly that failure:
 *       {@code NimbusJwtDecoder} surfaces it as a plain {@code JwtException}, not the {@code
 *       BadJwtException} every genuine token rejection is, and {@code JwtAuthenticationProvider}
 *       wraps that in {@code AuthenticationServiceException} rather than {@code
 *       InvalidBearerTokenException}. Left at the default, the filter would re-throw it, and it
 *       would escape the entire chain uncaught rather than answering 503;
 *   <li><b>Stateless, with CSRF, {@code httpBasic} and {@code formLogin} all disabled.</b> The
 *       ledger reads a bearer token and nothing else; a cookie-carried session or a second way to
 *       authenticate would be a way in ADR-030 does not describe.
 * </ul>
 *
 * <p>{@link HostAllowListFilter} still runs before all of this, at the highest filter precedence,
 * because a request under a foreign {@code Host} should never reach a security decision at all.
 */
@Configuration
@EnableWebSecurity
public class LedgerSecurityConfiguration {

    /** {@code JwtTimestampValidator}'s allowance either side of {@code exp}/{@code nbf} (ADR-030). */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(5);

    private static final String API_PATTERN = "/api/v1/**";
    private static final String HEALTH_PATTERN = "/actuator/health/**";
    private static final String INFO_PATTERN = "/actuator/info";
    private static final String PROMETHEUS_PATTERN = "/actuator/prometheus";
    private static final String METRICS_PATTERN = "/actuator/metrics/**";
    private static final String API_DOCS_PATTERN = "/v3/api-docs";
    private static final String API_DOCS_YAML_PATTERN = "/v3/api-docs.yaml";

    /**
     * Read once, in the composition root, and passed down to {@link JwtTimestampValidator#setClock}
     * — the same "pass the clock in" rule the domain follows, applied at the token-validation edge.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Verifies RS256 signatures against keys fetched over HTTP, with a bounded wait: a slow or dead
     * JWKS source must surface as {@code AUTHENTICATION_UNAVAILABLE} within seconds, never hang the
     * request. {@code jwsAlgorithm(RS256)} means an HS256-signed token is refused before any key is
     * even looked up — the classic algorithm-confusion attack finds no algorithm to be confused
     * with. {@link EmptyKeySetIsAnOutage} wraps the key selector Spring builds, so a key set holding
     * no key at all is that same outage rather than a refused token (ADR-037).
     *
     * @param jwkSetUri the internal JWKS URI ({@code finassist.security.jwks-uri}, from {@code
     *     LEDGER_OIDC_JWKS_URI}). Internal because it is fetched service-to-service; the token's own
     *     {@code iss} is always the public issuer, which is why the two are separate properties
     * @param issuer the expected {@code iss} ({@code finassist.security.issuer}, from {@code
     *     LEDGER_OIDC_ISSUER})
     * @param audience the expected {@code aud} ({@code finassist.security.audience}) — fixed by
     *     ADR-030, not driven by an environment variable, but still read from {@code
     *     application.yml} so every property this decoder needs comes from the same place
     * @param clock the clock {@link JwtTimestampValidator} checks {@code exp} and {@code nbf}
     *     against
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${finassist.security.jwks-uri}") String jwkSetUri,
            @Value("${finassist.security.issuer}") String issuer,
            @Value("${finassist.security.audience}") String audience,
            Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(JWKS_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(JWKS_READ_TIMEOUT);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(restTemplate)
                .jwtProcessorCustomizer(processor ->
                        processor.setJWSKeySelector(EmptyKeySetIsAnOutage.around(processor.getJWSKeySelector())))
                .build();
        decoder.setJwtValidator(tokenValidator(issuer, audience, clock));
        return decoder;
    }

    /**
     * Every check ADR-030 requires beyond signature and algorithm, composed so a caller learns
     * exactly one thing from a failure: which check it failed. Each delegate's {@link OAuth2Error}
     * carries the reason category ADR-030 names as its description — {@link
     * ProblemAuthenticationEntryPoint} reads that back to log the reason without ever touching the
     * token — because none of Spring's own validators describe a failure that way on their own.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience, Clock clock) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                timestampValidator(clock),
                issuedAtValidator(clock),
                issuerValidator(issuer),
                audienceValidator(audience),
                SUBJECT_VALIDATOR));
    }

    /**
     * Wraps the framework's own {@link JwtTimestampValidator} — its clock-skew arithmetic is exactly
     * what ADR-030 asks for — but replaces its free-text failure description ({@code "Jwt expired at
     * ..."}) with the fixed {@code lifetime} category, so a log line never carries the token's own
     * timestamp claims.
     *
     * <p>{@code setAllowEmptyExpiryClaim(false)} is the whole reason {@code exp} is required rather
     * than merely checked when present. {@link JwtTimestampValidator}'s constructor initialises that
     * flag to {@code true} — confirmed by disassembling the installed 7.1.1 jar — and {@code
     * NimbusJwtDecoder} installs a no-op {@code JWTClaimsSetVerifier}, so nothing else in the chain
     * would ever notice the claim's absence: a token signed by the realm's key with no expiry at all
     * would be valid forever. ADR-030 requires {@code exp} of both resource servers, and the
     * assistant already refuses this shape; {@code nbf} is deliberately left optional, since ADR-030
     * requires only that it be honoured when present.
     */
    private static OAuth2TokenValidator<Jwt> timestampValidator(Clock clock) {
        JwtTimestampValidator delegate = new JwtTimestampValidator(CLOCK_SKEW);
        delegate.setClock(clock);
        delegate.setAllowEmptyExpiryClaim(false);
        return jwt -> {
            OAuth2TokenValidatorResult result = delegate.validate(jwt);
            return result.hasErrors()
                    ? OAuth2TokenValidatorResult.failure(error(ProblemAuthenticationEntryPoint.LIFETIME))
                    : result;
        };
    }

    /**
     * Spring's own {@link JwtTimestampValidator} checks only {@code exp} and {@code nbf} —
     * confirmed by disassembly, it carries no reference to {@code getIssuedAt} at all — so a token
     * claiming to have been issued in the future, or carrying no {@code iat} whatsoever, would
     * otherwise be admitted. This checks both, against the same injected {@link Clock} and the same
     * {@link #CLOCK_SKEW} the timestamp validator already uses: never a second clock, never a bare
     * {@link Instant#now()}. ADR-030 requires {@code iat} rather than merely tolerating
     * its absence, so a missing claim fails this exactly as a future one does.
     */
    private static OAuth2TokenValidator<Jwt> issuedAtValidator(Clock clock) {
        return jwt -> {
            Instant issuedAt = jwt.getIssuedAt();
            Instant latestAllowed = Instant.now(clock).plus(CLOCK_SKEW);
            return issuedAt != null && !issuedAt.isAfter(latestAllowed)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error(ProblemAuthenticationEntryPoint.LIFETIME));
        };
    }

    /**
     * Compares the raw {@code iss} claim as a string, not {@code Jwt#getIssuer()}'s parsed {@link
     * java.net.URL}: a token whose issuer is not a well-formed URL must fail this check, not throw
     * out of it and turn a bad token into a 500.
     */
    private static OAuth2TokenValidator<Jwt> issuerValidator(String issuer) {
        return jwt -> issuer.equals(jwt.getClaimAsString(JwtClaimNames.ISS))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error(ProblemAuthenticationEntryPoint.ISSUER));
    }

    /** Admits a token whose {@code aud} names this audience among others, not only one naming it alone. */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error(ProblemAuthenticationEntryPoint.AUDIENCE));
    }

    /** {@code sub} identifies who acted; a token with none or a blank one names nobody. */
    private static final OAuth2TokenValidator<Jwt> SUBJECT_VALIDATOR =
            jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error(ProblemAuthenticationEntryPoint.SUBJECT));

    private static OAuth2Error error(String reasonCategory) {
        return new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, reasonCategory, null);
    }

    /** {@code sub} is the principal; the permission table lives in {@link LedgerPermissions}. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        converter.setJwtGrantedAuthoritiesConverter(new LedgerPermissions());
        return converter;
    }

    /**
     * Assembles the chain, registered as the {@code springSecurityFilterChain} servlet filter, after
     * {@link HostAllowListFilter}.
     *
     * @throws Exception if the builder rejects the configuration, which is a start-up failure
     */
    @Bean
    SecurityFilterChain ledgerSecurityFilterChain(
            HttpSecurity http, JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint();
        ProblemAccessDeniedHandler accessDeniedHandler = new ProblemAccessDeniedHandler(entryPoint);
        SecurityFilterChain chain = http.authorizeHttpRequests(
                        requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                                .permitAll()
                                .requestMatchers(
                                        HEALTH_PATTERN,
                                        INFO_PATTERN,
                                        PROMETHEUS_PATTERN,
                                        METRICS_PATTERN,
                                        API_DOCS_PATTERN,
                                        API_DOCS_YAML_PATTERN)
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, API_PATTERN)
                                .hasAuthority(LedgerPermissions.LEDGER_READ)
                                .requestMatchers(HttpMethod.POST, API_PATTERN)
                                .hasAuthority(LedgerPermissions.LEDGER_WRITE)
                                .anyRequest()
                                .denyAll())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                // Neither exists to be disabled for tidiness: a bearer-token API takes no cookie, so
                // there is nothing for a cross-site page to ride along with, and Basic or a form
                // login would be a second way in that no ADR describes.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
        stopSwallowingJwksOutages(chain, entryPoint);
        return chain;
    }

    /**
     * {@code AuthenticationEntryPointFailureHandler} — the failure handler {@code
     * BearerTokenAuthenticationFilter}'s own constructor installs, and which {@code
     * oauth2ResourceServer(...).authenticationEntryPoint(...)} only feeds, never replaces —
     * defaults {@code rethrowAuthenticationServiceException} to {@code true}. Confirmed by
     * disassembling it: a JWKS outage reaches {@code JwtAuthenticationProvider} as a plain {@code
     * JwtException}, which it wraps in {@link AuthenticationServiceException}, and that default
     * means the filter re-throws it rather than calling the entry point, so it would otherwise
     * escape the whole filter chain uncaught rather than answering 503.
     *
     * <p>This walks the already-{@code build()}-assembled chain's filter list and corrects the one
     * {@code BearerTokenAuthenticationFilter} instance directly, rather than an {@code
     * HttpSecurity.objectPostProcessor(...)} registered on this same builder — not because no
     * supported path exists, but because the one that does has two hazards this one lacks, both
     * read from Spring Security 7.1.1's own sources rather than assumed:
     *
     * <ul>
     *   <li>{@code AbstractConfiguredSecurityBuilder.objectPostProcessor(ObjectPostProcessor)} only
     *       reassigns {@code HttpSecurity}'s own field; it never reaches a configurer already
     *       applied. Each configurer instead snapshots that field into its own composite post
     *       processor the moment it is first applied — {@code
     *       AbstractConfiguredSecurityBuilder.with(configurer, customizer)}, which {@code
     *       HttpSecurity.getOrApply(...)} calls for {@code .oauth2ResourceServer(...)}, runs {@code
     *       configurer.addObjectPostProcessor(this.objectPostProcessor)} exactly once, at that
     *       moment. {@code OAuth2ResourceServerConfigurer.configure(...)} then builds the new
     *       {@code BearerTokenAuthenticationFilter} and passes it through that snapshot ({@code
     *       postProcess(filter)}), never the builder's live field. A processor registered through
     *       {@code http.objectPostProcessor(...)} <em>after</em> {@code .oauth2ResourceServer(...)}
     *       has already run therefore has no effect on the filter it built — the zero invocations
     *       actually observed when that route was tried here first;
     *   <li>registering the processor <em>before</em> {@code .oauth2ResourceServer(...)} instead,
     *       so the snapshot taken at that point is the new one, would work — but was deliberately
     *       not adopted. {@code http.objectPostProcessor(...)} <em>replaces</em> the builder's
     *       field outright rather than composing with it, and the field it would replace is {@code
     *       @EnableWebSecurity}'s own ambient processor ({@code
     *       AutowireBeanFactoryObjectPostProcessor}, from {@code ObjectPostProcessorConfiguration}),
     *       which autowires and initialises every security object the DSL constructs. Replacing
     *       rather than composing would silently drop that for every object built afterwards — a
     *       failure nothing here would necessarily catch, since none of this configuration's own
     *       beans happen to need field injection today — and it is order-sensitive besides,
     *       correct only for as long as it runs strictly before {@code .oauth2ResourceServer(...)}
     *       in this method, silently reverting to a no-op if the chain were ever reordered.
     * </ul>
     *
     * <p>The filter-list walk has neither hazard: it acts on the fully built, already-autowired
     * filter instance regardless of DSL ordering. {@code LedgerKeySourceOutageIT} is what would
     * turn red — the 503 becoming an uncaught exception again — if this method stopped running.
     */
    private static void stopSwallowingJwksOutages(SecurityFilterChain chain, AuthenticationEntryPoint entryPoint) {
        for (var filter : chain.getFilters()) {
            if (filter instanceof BearerTokenAuthenticationFilter bearerTokenFilter) {
                AuthenticationEntryPointFailureHandler failureHandler =
                        new AuthenticationEntryPointFailureHandler(entryPoint);
                failureHandler.setRethrowAuthenticationServiceException(false);
                bearerTokenFilter.setAuthenticationFailureHandler(failureHandler);
            }
        }
    }
}
