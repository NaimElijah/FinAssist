package io.github.naimelijah.finassist.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ledger service.
 *
 * <p>It sits in the root package so component scanning reaches every context beneath it. The domain
 * and application packages carry no annotation and are wired by constructor rather than by the
 * container, which {@code LedgerArchitectureTest.the_application_layer_depends_on_no_framework}
 * enforces.
 *
 * <p>The wiring itself lives in {@code composition}, which is Spring-aware by design and is the only
 * package permitted to see two contexts at once (ADR-022). Controllers are the exception: they are
 * component-scanned from each context's {@code adapter.in.web} (ADR-028).
 *
 * <p>Starting this serves the ledger's API under {@code /api/v1}, Actuator, and the springdoc-generated
 * OpenAPI document at {@code /v3/api-docs}. See {@code docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class LedgerApplication {

    /**
     * Starts the ledger.
     *
     * @param args command-line arguments, read by Spring Boot as property overrides
     */
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
