package io.github.naimelijah.finassist.gateway.composition;

import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.DispatcherType;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.firewall.CompositeRequestRejectedHandler;
import org.springframework.security.web.firewall.ObservationMarkingRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * The gateway's one security chain: OIDC sign-in, a session that keeps every token server-side,
 * CSRF for the browser, and deny by default (ADR-030).
 *
 * <p>After a sign-in the browser holds exactly two things from this service — the {@code
 * FINASSIST_SESSION} cookie, which is {@code HttpOnly} and therefore unreadable by script, and the
 * CSRF token, which is readable by script and useless without the session. The access and refresh
 * tokens stay in the session, because {@link KeycloakRegistration} stores them there.
 *
 * <p><b>Three choices here are the security of this service, and each undoes a Spring default:</b>
 *
 * <ul>
 *   <li><b>{@link ProblemAuthenticationEntryPoint} replaces {@code oauth2Login}'s redirect.</b> An
 *       unauthenticated request is answered {@code 401} with a problem, never {@code 302} to
 *       Keycloak. A sign-in begins only when the browser navigates to {@link
 *       BffRoutes#LOGIN_ENTRY}. {@code loginPage} is set to that same path, which also stops
 *       Spring generating a {@code /login} page nobody would use.</li>
 *   <li><b>{@code anyRequest().denyAll()}.</b> A path that is not written below is refused, HEAD
 *       and OPTIONS included. Adding a route is therefore a deliberate act rather than a
 *       side effect of adding a handler.</li>
 *   <li><b>A {@link NullRequestCache}.</b> Nothing about the request that was refused is
 *       remembered, so a sign-in cannot replay a request the browser made before it — and the
 *       session carries nothing an attacker could aim.</li>
 * </ul>
 *
 * <p>The CSRF cookie and header are renamed from Spring's {@code XSRF-TOKEN} and {@code
 * X-XSRF-TOKEN} because cookies ignore ports: every application on {@code localhost} shares one
 * cookie jar, so the defaults would collide with another local application's (ADR-030). {@code
 * csrf().spa()} is configured first, and both of the parts it installs are then replaced: the
 * renamed repository replaces its cookie repository, and {@link HeaderOnlyCsrfTokenRequestHandler}
 * replaces its handler, which would also accept the token as a {@code _csrf} request parameter.
 * The replacement keeps what {@code spa()} is for — the cookie written eagerly on every request,
 * and the header compared as the web client sends it.
 *
 * <p>The login entry is public and creates a session, so two parts bound what it can be made to
 * hold: {@link LoginEntryNavigationFilter} refuses, {@code 403}, a browser request whose Fetch
 * Metadata does not name a top-level navigation ({@code Sec-Fetch-Mode: navigate}, {@code
 * Sec-Fetch-Dest: document}) before any session exists, and {@link
 * PreSignInAuthorizationRequestRepository} gives the session it does create a short idle limit,
 * which the sign-in success handler lifts back to the configured one.
 *
 * <p>{@link HostAllowListFilter} still runs before all of this, at the highest filter precedence,
 * because a request under a foreign {@code Host} should never reach a security decision at all.
 */
@Configuration
@EnableWebSecurity
public class GatewaySecurityConfiguration {

    /** The script-readable cookie carrying the CSRF token. */
    static final String CSRF_COOKIE_NAME = "FINASSIST-XSRF-TOKEN";

    /** The header a write must send it back in. */
    static final String CSRF_HEADER_NAME = "X-FINASSIST-XSRF-TOKEN";

    /**
     * Actuator's health endpoint and its probes. Public because Compose and a future orchestrator
     * read them before any session could exist, and they carry no business data (ADR-030). A
     * trailing {@code /**} matches the parent path too, so {@code /actuator/health} is covered.
     */
    private static final String HEALTH_PATTERN = "/actuator/health/**";

    /** The relay prefixes, authenticated here and authorised by the service that answers them (ADR-032). */
    private static final String LEDGER_API_PATTERN = "/ledger/api/v1/**";

    private static final String ASSISTANT_API_PATTERN = "/assistant/api/v1/**";

    /**
     * The one origin every absolute URL the gateway hands out is built from,
     * {@code GATEWAY_PUBLIC_URL}. A bean so that {@link KeycloakRegistration} builds its callback
     * from the same value the redirects use; a mismatch between the two would fail only at the
     * callback, after the owner had already typed a password.
     */
    @Bean
    Origin webOrigin(@Value("${finassist.web.public-url}") String publicUrl) {
        return new Origin(publicUrl);
    }

    /**
     * The CSRF token repository, renamed. {@code withHttpOnlyFalse} is what makes the cookie
     * readable by the web client's script, which is the whole mechanism: a cross-site page can
     * cause a request to be sent with the session cookie, but it cannot read a cookie from another
     * origin to put the token in a header.
     *
     * <p>{@code secure} and {@code SameSite} are set to match the session cookie rather than left
     * to Spring's default of "whatever the current request's scheme is", which behind a
     * TLS-terminating proxy the gateway deliberately does not trust would be the wrong answer.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${finassist.http.cookie-secure}") boolean cookieSecure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE_NAME);
        repository.setHeaderName(CSRF_HEADER_NAME);
        repository.setCookieCustomizer(cookie -> cookie.secure(cookieSecure).sameSite("Lax"));
        return repository;
    }

    /**
     * Answers a request Spring Security's firewall refuses as a {@code 400} problem rather than
     * Boot's error body. {@code WebSecurity} installs a {@link RequestRejectedHandler} bean on the
     * filter chain proxy in place of its own default; that default also marks the current
     * observation as failed, so this one keeps doing that before writing the problem.
     *
     * @param observations the registry the default handler would have marked the rejection in
     * @return the handler {@code FilterChainProxy} calls for every rejected request
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ObservationRegistry observations) {
        return new CompositeRequestRejectedHandler(
                new ObservationMarkingRequestRejectedHandler(observations), new ProblemRequestRejectedHandler());
    }

    /**
     * Assembles the chain.
     *
     * @param http Boot's pre-configured builder for this application
     * @param csrfTokenRepository the renamed repository, replacing the one {@code spa()} installs;
     *     its handler is replaced too, by {@link HeaderOnlyCsrfTokenRequestHandler}
     * @param registrations the {@code keycloak} registration, read by the sign-out handler
     * @param webOrigin where every redirect this chain issues points
     * @param sessionCookieName {@code server.servlet.session.cookie.name}, so the cookie the
     *     container sets and the cookie sign-out deletes are named in exactly one place
     * @param tokenResponseClient the bounded authorization-code client {@link KeycloakRegistration}
     *     builds, replacing the unbounded one this configurer would otherwise construct itself
     * @return the chain, which is also registered as the {@code springSecurityFilterChain} servlet
     *     filter, after {@link HostAllowListFilter}
     * @throws Exception if the builder rejects the configuration, which is a start-up failure
     */
    @Bean
    SecurityFilterChain gatewaySecurityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            ClientRegistrationRepository registrations,
            Origin webOrigin,
            @Value("${server.servlet.session.cookie.name}") String sessionCookieName,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient)
            throws Exception {
        SecurityFilterChain chain = http.authorizeHttpRequests(requests -> requests
                        // The container's own forward to /error, and only that: a direct request
                        // to /error arrives as DispatcherType.REQUEST and is still denied. Without
                        // this, denyAll would answer 403 for every error the gateway raised,
                        // hiding a 500 behind a status that means something else entirely.
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        // Public: the login entry and the callback - nothing else under /bff/ -
                        // and the health probes. Deny by default means a new path under /bff/ has
                        // to be permitted on purpose rather than inherited from a prefix.
                        .requestMatchers(BffRoutes.LOGIN_ENTRY_PATTERN, BffRoutes.CALLBACK_PATTERN, HEALTH_PATTERN)
                        .permitAll()
                        .requestMatchers(BffRoutes.ME)
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, LEDGER_API_PATTERN, ASSISTANT_API_PATTERN)
                        .authenticated()
                        .requestMatchers(HttpMethod.POST, LEDGER_API_PATTERN, ASSISTANT_API_PATTERN)
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
                        .accessDeniedHandler(new ProblemAccessDeniedHandler()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .oauth2Login(login -> login.loginPage(BffRoutes.LOGIN_ENTRY)
                        .authorizationEndpoint(endpoint -> endpoint.baseUri(BffRoutes.AUTHORIZATION_BASE)
                                .authorizationRequestRepository(new PreSignInAuthorizationRequestRepository()))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(BffRoutes.CALLBACK_PATTERN))
                        .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenResponseClient))
                        .successHandler(signInSuccessHandler(webOrigin))
                        .failureHandler(new SignInFailureHandler(webOrigin)))
                .logout(logout -> logout.logoutUrl(BffRoutes.LOGOUT)
                        .logoutSuccessHandler(new SpaLogoutSuccessHandler(registrations, webOrigin))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(sessionCookieName))
                // Neither exists to be disabled for tidiness: a browser that could send Basic
                // credentials, or post a form login, would be a second way in that no ADR
                // describes and nothing here would authorise.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(new LoginEntryNavigationFilter(), OAuth2AuthorizationRequestRedirectFilter.class)
                .build();
        stopAnsweringAnUnknownLoginEntryWithA500(chain);
        return chain;
    }

    /**
     * Gives {@link OAuth2AuthorizationRequestRedirectFilter} the failure handler it would otherwise
     * not have, so that a login entry naming a registration that does not exist is refused rather
     * than answered {@code 500} with Boot's error body and a stack trace in the log (see {@link
     * LoginEntryFailureHandler}, which says what the answer is and why).
     *
     * <p>Three things rule out configuring it instead of correcting the built filter, each read
     * from Spring Security 7.1.1's own sources rather than assumed:
     *
     * <ul>
     *   <li>{@code oauth2Login().failureHandler(..)} reaches the login filter alone. {@code
     *       AbstractAuthenticationFilterConfigurer} applies it to {@code this.authFilter} — the
     *       {@code OAuth2LoginAuthenticationFilter} that handles the callback — and {@code
     *       OAuth2LoginConfigurer.configure(B http)} never calls {@code
     *       setAuthenticationFailureHandler} on the redirect filter it builds beside it;
     *   <li>{@code AuthorizationEndpointConfig}, which configures that redirect filter, exposes
     *       only {@code baseUri}, {@code authorizationRequestResolver}, {@code
     *       authorizationRequestRepository} and {@code authorizationRedirectStrategy}. There is no
     *       failure-handler setter to call;
     *   <li>narrowing {@link BffRoutes#LOGIN_ENTRY_PATTERN} to the exact login entry would not help
     *       either: this filter runs far ahead of {@code AuthorizationFilter}, so it throws before
     *       any authorization rule is evaluated.
     * </ul>
     *
     * <p>Walking the already-{@code build()}-assembled chain and correcting the filter instance is
     * the shape the ledger already uses for the same class of problem ({@code
     * LedgerSecurityConfiguration.stopSwallowingJwksOutages}): it acts on the fully built,
     * already-autowired filter regardless of how this method is ordered.
     */
    private static void stopAnsweringAnUnknownLoginEntryWithA500(SecurityFilterChain chain) {
        for (var filter : chain.getFilters()) {
            if (filter instanceof OAuth2AuthorizationRequestRedirectFilter redirectFilter) {
                redirectFilter.setAuthenticationFailureHandler(new LoginEntryFailureHandler());
            }
        }
    }

    /**
     * A completed sign-in always lands on the web client's root, never on wherever the browser
     * happened to be going: {@code alwaysUseDefaultTargetUrl} is what makes that unconditional,
     * and with a {@link NullRequestCache} there is nothing saved to go back to anyway.
     *
     * <p>It first gives the session back the idle limit every new session gets. The session was
     * created to hold the authorization request, with {@link
     * PreSignInAuthorizationRequestRepository#PRE_SIGN_IN_IDLE_LIMIT}, and the sign-in rotates its
     * id but keeps that limit, so without this the owner would be signed out after a few idle
     * minutes rather than ADR-030's half hour. The limit is read from the servlet context, where
     * Boot has already applied {@code server.servlet.session.timeout} in its own unit, seconds for a
     * bare number, and rounded as the container rounds it, rather than converted again here.
     */
    private static AuthenticationSuccessHandler signInSuccessHandler(Origin webOrigin) {
        SimpleUrlAuthenticationSuccessHandler redirect = new SimpleUrlAuthenticationSuccessHandler(webOrigin.root());
        redirect.setAlwaysUseDefaultTargetUrl(true);
        return (request, response, authentication) -> {
            Duration containerIdleLimit =
                    Duration.ofMinutes(request.getServletContext().getSessionTimeout());
            request.getSession().setMaxInactiveInterval(Math.toIntExact(containerIdleLimit.toSeconds()));
            redirect.onAuthenticationSuccess(request, response, authentication);
        };
    }
}
