package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.BudgetReview;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * How a budget is doing: every budgeted category, and the two kinds of spend that fell outside it.
 * Every member is always present, and marked required in the contract.
 *
 * <p>{@code categories} follows {@link BudgetReview}'s own order. {@code unbudgeted} is an ordered
 * map on the domain side and travels as an array, in the same order — RFC 8259 leaves an object's
 * member order unspecified, so a map whose order matters cannot be an object (ADR-028).
 */
public record BudgetReviewResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID budget,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BudgetPeriodDto period,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currency currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CategorySpendResponse> categories,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UnbudgetedSpendResponse> unbudgeted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto uncategorised) {

    static BudgetReviewResponse from(BudgetReview review) {
        List<UnbudgetedSpendResponse> unbudgeted = review.unbudgeted().entrySet().stream()
                .map(entry -> new UnbudgetedSpendResponse(entry.getKey().name(), MoneyDto.from(entry.getValue())))
                .toList();
        return new BudgetReviewResponse(
                review.budget().value(),
                BudgetPeriodDto.from(review.period()),
                review.currency(),
                review.categories().stream().map(CategorySpendResponse::from).toList(),
                unbudgeted,
                MoneyDto.from(review.uncategorised()));
    }
}
