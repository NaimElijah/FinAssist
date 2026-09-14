package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.DefineBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListBudgetsUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ReviewBudgetUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.domain.budget.BudgetId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budgets over HTTP: define one, list every one, and review how it is doing (ADR-028).
 *
 * <p>It calls in-ports only. {@code LedgerArchitectureTest.web_adapters_reach_no_outbound_port} fails
 * the build if it reaches a repository, an outbound adapter or a concrete service. It is
 * component-scanned rather than built in {@code LedgerConfiguration}, the one bounded exception ADR-028
 * makes to hand-built wiring, so that a {@code @WebMvcTest} slice loads it.
 *
 * <p>A define answers 201 with the budget and no {@code Location} header, because there is no
 * single-item read for one to name. Writes accept {@code application/json} only, so a form post from a
 * cross-site page is refused with 415 before this runs (ADR-027). {@link BudgetingProblemAdvice} maps
 * the failures a budget id in the path can raise; a rule the domain refuses reaches the composition
 * fallback as 422 {@code RULE_VIOLATED}.
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/budgets", produces = MediaType.APPLICATION_JSON_VALUE)
public class BudgetController {

    private final DefineBudgetUseCase definer;
    private final ListBudgetsUseCase lister;
    private final ReviewBudgetUseCase reviewer;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public BudgetController(DefineBudgetUseCase definer, ListBudgetsUseCase lister, ReviewBudgetUseCase reviewer) {
        this.definer = Objects.requireNonNull(definer, "definer");
        this.lister = Objects.requireNonNull(lister, "lister");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
    }

    /** Every budget, period start descending then id, in the order the port guarantees. */
    @GetMapping
    public List<BudgetResponse> listBudgets() {
        return lister.all().stream().map(BudgetResponse::from).toList();
    }

    /** Defines a budget. A rule the domain refuses — two limits for one category, e.g. — is 422 {@code RULE_VIOLATED}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse defineBudget(@Valid @RequestBody DefineBudgetRequest request) {
        return BudgetResponse.from(definer.define(request.toCommand()));
    }

    /** How the budget is doing, derived fresh on every call. An unknown budget is 404 {@code BUDGET_NOT_FOUND}. */
    @GetMapping("/{id}/review")
    public BudgetReviewResponse reviewBudget(@PathVariable UUID id) {
        return BudgetReviewResponse.from(reviewer.review(new BudgetId(id)));
    }
}
