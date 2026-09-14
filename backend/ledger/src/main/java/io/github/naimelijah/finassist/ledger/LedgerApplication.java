package io.github.naimelijah.finassist.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ledger service.
 *
 * <p>It sits in the root package so component scanning reaches every context beneath it. The domain
 * and application packages carry no annotation and are wired by constructor rather than by the
 * container, which {@code LedgerArchitectureTest.the_application_layer_depends_on_no_framework} now
 * enforces instead of merely asserting here.
 *
 * <p>The wiring itself lives in {@code composition}, which is Spring-aware by design and is the only
 * package permitted to see two contexts at once (ADR-022).
 *
 * <p>Starting this exposes Actuator and the springdoc-generated OpenAPI document at {@code
 * /v3/api-docs} — there are no business endpoints yet. See {@code
 * docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
