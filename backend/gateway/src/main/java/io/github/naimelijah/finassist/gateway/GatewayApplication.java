package io.github.naimelijah.finassist.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the gateway: the backend-for-frontend that keeps the browser's tokens
 * server-side and relays authenticated requests to the ledger and the assistant.
 *
 * <p>What it does today: {@code composition.HostAllowListFilter} refuses a request under a foreign
 * {@code Host} before anything else sees it; {@code composition.GatewaySecurityConfiguration} signs
 * the owner in through Keycloak with OIDC and PKCE, keeps the access and refresh tokens in the
 * server-side session, gives the browser only an {@code HttpOnly} session cookie and a CSRF token,
 * and denies everything it was not told to allow; a Spring Cloud Gateway Server Web MVC route
 * relays every authenticated {@code GET}/{@code POST} under {@code /ledger/api/v1/**} and {@code
 * /assistant/api/v1/**} with its prefix stripped, the browser's {@code Cookie} and CSRF header
 * removed, and the session's access token attached, refreshing it first if it had expired; and
 * {@code composition.GatewayProblems} and {@code composition.GatewayRelayProblems} answer every
 * failure they see as an RFC 9457 problem, never a redirect — the one exception is a failed
 * sign-in itself, which {@code composition.SignInFailureHandler} sends back to the web client as
 * a redirect instead.
 *
 * <p>What it does not do yet: a sign-in cannot actually complete outside a test, because the
 * Keycloak realm this gateway is configured against does not exist in this repository until the
 * task that commits it. Nor does the ledger or the assistant check the token this gateway hands
 * it — both still answer whatever a request carries, exactly as they do for a direct call.
 *
 * <p>Starting this serves Actuator's {@code health} endpoint, the {@code /bff/} endpoints, and
 * the relay routes, gated by {@code application.yml} and that security chain. It needs {@code
 * GATEWAY_CLIENT_SECRET} and refuses to start without it. See {@code
 * docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
