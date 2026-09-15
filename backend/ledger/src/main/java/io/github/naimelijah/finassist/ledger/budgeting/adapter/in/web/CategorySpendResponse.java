package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.CategorySpend;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One budgeted category's review: what it allowed, what it consumed, what is left, and whether it
 * was overspent. Every member is always present, and marked required in the contract.
 *
 * <p>{@code remaining} may be negative, and {@code overspent} says so explicitly rather than making a
 * client infer it from the sign — an overspend is a fact about the month, not an error (ADR-028).
 */
public record CategorySpendResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto limit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto spent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto remaining,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean overspent) {

    static CategorySpendResponse from(CategorySpend spend) {
        return new CategorySpendResponse(
                spend.category().name(),
                MoneyDto.from(spend.limit()),
                MoneyDto.from(spend.spent()),
                MoneyDto.from(spend.remaining()),
                spend.isOverspent());
    }
}
