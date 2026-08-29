package io.github.naimelijah.finassist.ledger.budgeting.domain.category;

import java.util.Locale;
import java.util.Objects;

/**
 * Assigns a category to a transaction whose description contains a given fragment.
 *
 * <p>Matching is case-insensitive on a normalised fragment, so a rule written as "Supermarket"
 * matches a bank's shouted "SUPERMARKET LTD 4432".
 *
 * <p>The rule takes a description — a {@link String} — and not a transaction. Accepting an accounting
 * {@code Transaction} here would make budgeting depend on accounting's model, which
 * {@code LedgerArchitectureTest.contexts_do_not_depend_on_each_other} forbids. Translating a
 * transaction into the arguments this needs is the application layer's job.
 */
public record CategorisationRule(String fragment, Category category) {

    public CategorisationRule {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(fragment, "fragment");
        if (fragment.isBlank()) {
            throw new IllegalArgumentException("A categorisation rule needs a fragment to match on");
        }
        fragment = fragment.strip().toLowerCase(Locale.ROOT);
    }

    public boolean matches(String description) {
        Objects.requireNonNull(description, "description");
        return description.toLowerCase(Locale.ROOT).contains(fragment);
    }
}
