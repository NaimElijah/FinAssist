package io.github.naimelijah.finassist.ledger.budgeting.domain.category;

import java.util.Locale;
import java.util.Objects;

/**
 * A spending category: groceries, rent, transport.
 *
 * <p>A value, not an entity. Two categories with the same name are the same category, so equality is
 * by name and the name is normalised — otherwise "Groceries" and "groceries" would silently become
 * two budget lines.
 *
 * <p>Categories belong to budgeting, not to accounting. The accounting context has expense accounts
 * and knows nothing about this type.
 */
public record Category(String name) {

    public Category {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Category name must not be blank");
        }
        name = name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a category; the name is stripped and lower-cased with {@code Locale.ROOT}.
     *
     * @throws IllegalArgumentException if the name is blank
     */
    public static Category of(String name) {
        return new Category(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
