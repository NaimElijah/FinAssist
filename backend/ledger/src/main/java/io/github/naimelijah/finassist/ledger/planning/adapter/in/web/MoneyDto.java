package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * An exact amount on the wire: its decimal text and its currency (ADR-028).
 *
 * <p>The amount is a string, never a JSON number, because most parsers read a number into a binary
 * float and because a string keeps scale: {@code "12.50"} survives the trip where {@code 12.5} would
 * not. The pattern admits plain decimal text only, so an exponent or a stray character is a 400 before
 * {@link Money} sees it; precision the currency does not admit is refused by {@link Money} itself, as a
 * 422.
 *
 * <p>Signed, because a projected cash balance and a divergence may be negative. A planned flow's amount
 * is still never negated to express its direction: the adapter passes the text through as sent, and
 * {@code PlannedFlow} refuses anything not positive (ADR-015).
 *
 * <p>Duplicated in each context's web adapter on purpose. The slice rule leaves no legal shared home
 * for it, and ADR-021 refused to let coupling accumulate in {@code shared}. This copy is identical to
 * accounting's and budgeting's, member for member and constraint for constraint, which is why springdoc
 * is allowed to fold the three into one {@code MoneyDto} schema (Ruling 21); {@code MoneyDtoShapesTest}
 * fails the build if it drifts.
 *
 * @param amount signed decimal text, such as {@code "-12.50"}
 * @param currency the one currency the amount is in
 */
public record MoneyDto(
        @NotNull @Pattern(regexp = MoneyDto.DECIMAL_TEXT) String amount,
        @NotNull Currency currency) {

    /**
     * An optional minus sign, digits, and an optional fraction. No exponent, no plus sign, no grouping.
     *
     * <p>Anchored at both ends. Bean validation matches the whole string either way, but the contract
     * publishes this as a JSON Schema {@code pattern}, which matches anywhere in the string unless it is
     * anchored, so a client validating against the document would otherwise accept {@code "x1y"}.
     */
    static final String DECIMAL_TEXT = "^-?\\d+(\\.\\d+)?$";

    static MoneyDto from(Money money) {
        return new MoneyDto(money.amount().toPlainString(), money.currency());
    }

    /**
     * @throws IllegalArgumentException if the amount carries more precision than the currency admits
     */
    Money toMoney() {
        return Money.of(amount, currency);
    }
}
