package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Spend that matched a categorisation rule for a category the budget sets no limit for. Both members
 * are always present, and marked required in the contract.
 *
 * <p>Reported rather than folded into a total or dropped: hiding it would make a budget look
 * healthier than it is (ADR-028).
 */
public record UnbudgetedSpendResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto spent) {}
