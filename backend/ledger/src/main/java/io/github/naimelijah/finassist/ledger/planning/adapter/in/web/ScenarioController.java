package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.DefineScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenarios over HTTP: define a named strategy and list every one (ADR-028).
 *
 * <p>It calls in-ports only and is component-scanned, for the reasons {@link PlannedFlowController} gives.
 * A define answers 201 with the scenario and no {@code Location} header, and accepts {@code
 * application/json} only (ADR-030).
 *
 * <p>A flow named in the body is part of what the request asks for, not the resource it is about, so one
 * that does not exist is 422 {@code UNKNOWN_PLAN} rather than the 404 {@link PlanningProblemAdvice} gives a
 * plan named in a path (ADR-028). That mapping is a handler on this controller, which Spring MVC consults
 * before any advice. A flow in another currency, or listed twice, is the domain's {@link
 * IllegalArgumentException}, answered 422 {@code RULE_VIOLATED} by the composition fallback.
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScenarioController {

    private final DefineScenarioUseCase definer;
    private final ListScenariosUseCase lister;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public ScenarioController(DefineScenarioUseCase definer, ListScenariosUseCase lister) {
        this.definer = Objects.requireNonNull(definer, "definer");
        this.lister = Objects.requireNonNull(lister, "lister");
    }

    /** Every scenario, name then id, in the order the port guarantees. */
    @GetMapping
    public List<ScenarioResponse> listScenarios() {
        return lister.all().stream().map(ScenarioResponse::from).toList();
    }

    /**
     * Defines a scenario over recorded flows, possibly none. A growth rate that is not plain decimal text is
     * 400; one at or below minus one is 422 {@code RULE_VIOLATED}.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResponse defineScenario(@Valid @RequestBody DefineScenarioRequest request) {
        return ScenarioResponse.from(definer.define(request.toCommand()));
    }

    /** A flow in the body names no plan: the request is well-formed and refused (ADR-028). */
    @ExceptionHandler(PlanNotFoundException.class)
    ProblemDetail unknownPlan(PlanNotFoundException exception) {
        return PlanningProblems.problem(
                HttpStatus.UNPROCESSABLE_CONTENT, PlanningProblems.UNKNOWN_PLAN, exception.getMessage());
    }
}
