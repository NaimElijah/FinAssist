package io.github.naimelijah.finassist.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the gateway: the backend-for-frontend that keeps the browser's tokens
 * server-side and will relay authenticated requests to the ledger and the assistant.
 *
 * <p>What it does today: {@code composition.HostAllowListFilter} refuses a request under a foreign
 * {@code Host} before anything else sees it; {@code composition.GatewaySecurityConfiguration} signs
 * the owner in through Keycloak with OIDC and PKCE, keeps the access and refresh tokens in the
 * server-side session, gives the browser only an {@code HttpOnly} session cookie and a CSRF token,
 * and denies everything it was not told to allow; and {@code composition.GatewayProblems} answers
 * every failure as an RFC 9457 problem, never a redirect.
 *
 * <p>What it does not do yet: relay anything. No route to the ledger or the assistant exists, so
 * the paths the security chain authenticates answer 404 once past it - the task that adds the
 * routes fills them in. Nor can a sign-in actually complete: the Keycloak realm this gateway is
 * configured against does not exist until the task that commits it.
 *
 * <p>Starting this serves Actuator's {@code health} endpoint, the {@code /bff/} endpoints and
 * nothing else, gated by {@code application.yml} and that security chain. It needs {@code
 * GATEWAY_CLIENT_SECRET} and refuses to start without it. See {@code
 * docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
