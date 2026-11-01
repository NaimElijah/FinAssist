package io.github.naimelijah.finassist.ledger.budgeting.application.port.out;

import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Money that went out, in budgeting's own words.
 *
 * <p><strong>This names no accounting type.</strong> Not a {@code Transaction}, not an {@code Entry},
 * and not an {@code AccountId} either — {@code contexts_do_not_depend_on_each_other} admits no
 * exception and applies at every layer. What budgeting needs in order to review a budget is a
 * description to match a rule against and an amount to add up. The date the spend fell on travels
 * with them, though {@code BudgetReviewService} does not read it. The account it came from is
 * accounting's business and none of budgeting's.
 *
 * <p>The amount may be negative. A refund is spend that came back, and dropping it would overstate
 * what a category has consumed.
 *
 * <p>See ADR-021 for why the consumer owns this port, and ADR-022 for where the translation lives.
 */
public record SpendRecord(String description, Money amount, LocalDate on) {

    public SpendRecord {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(on, "on");
    }
}
