package io.github.naimelijah.finassist.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the gateway: the backend-for-frontend that will keep the browser's tokens
 * server-side and relay authenticated requests to the ledger and the assistant.
 *
 * <p>Two defences exist today: {@code composition.HostAllowListFilter} refuses a request under a
 * foreign {@code Host}, and {@code composition.GatewayProblems} answers every failure this
 * service can raise so far as an RFC 9457 problem. No route, no OIDC login and no Spring Security
 * filter chain exist yet - login, the CSRF cookie and the relayed routes arrive with the tasks
 * that need them. This task otherwise still only proves the Spring Cloud Gateway (Server Web MVC)
 * dependency resolves against Spring Boot 4.1.1 and that the service builds, boots and reports
 * health, the same way {@code LedgerApplication} does for the ledger.
 *
 * <p>Starting this serves only Actuator's {@code health} endpoint, gated by {@code
 * application.yml}. See {@code docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
