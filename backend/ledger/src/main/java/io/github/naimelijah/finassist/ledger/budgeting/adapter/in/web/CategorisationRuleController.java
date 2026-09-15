package io.github.naimelijah.finassist.ledger.budgeting.adapter.in.web;

import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.AddCategorisationRuleUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.application.port.in.ListCategorisationRulesUseCase;
import io.github.naimelijah.finassist.ledger.budgeting.domain.category.CategorisationRule;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categorisation rules over HTTP: add one and list them, in the order a review will use them
 * (ADR-028).
 *
 * <p>It calls in-ports only and is component-scanned, for the reasons {@link BudgetController} gives.
 * An add answers 201 with the rule and no {@code Location} header — a rule has no id at all, because
 * a review matches by fragment rather than by looking one up. Writes accept {@code application/json}
 * only (ADR-027).
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/categorisation-rules", produces = MediaType.APPLICATION_JSON_VALUE)
public class CategorisationRuleController {

    private final AddCategorisationRuleUseCase adder;
    private final ListCategorisationRulesUseCase lister;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public CategorisationRuleController(AddCategorisationRuleUseCase adder, ListCategorisationRulesUseCase lister) {
        this.adder = Objects.requireNonNull(adder, "adder");
        this.lister = Objects.requireNonNull(lister, "lister");
    }

    /** Every rule, in the order it was added — the order a review folds over. */
    @GetMapping
    public List<CategorisationRuleResponse> listCategorisationRules() {
        return lister.rules().stream().map(CategorisationRuleResponse::from).toList();
    }

    /** Adds a rule, after every rule already added. A blank fragment or category is 400. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CategorisationRuleResponse addCategorisationRule(@Valid @RequestBody AddCategorisationRuleRequest request) {
        CategorisationRule rule = request.toRule();
        adder.add(rule);
        return CategorisationRuleResponse.from(rule);
    }
}
