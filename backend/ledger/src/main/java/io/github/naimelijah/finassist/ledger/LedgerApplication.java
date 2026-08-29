package io.github.naimelijah.finassist.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ledger service.
 *
 * <p>Deliberately the only Spring-annotated class outside {@code adapter}. It sits in the root
 * package so component scanning covers the adapters beneath it; the domain and application
 * packages contain no annotations and are wired by constructor, not by the container.
 *
 * <p>Starting this exposes Actuator only — there are no business endpoints yet. See {@code
 * docs/architecture/architecture.md}.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
