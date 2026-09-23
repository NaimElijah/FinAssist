package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListPlannedFlowsUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.TransitionPlanUseCase;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
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
 * Planned flows over HTTP: record an intention, list every one, and move one through its lifecycle
 * (ADR-028).
 *
 * <p>It calls in-ports only. {@code LedgerArchitectureTest.inbound_adapters_reach_only_in_ports} fails
 * the build if it reaches a repository, an outbound adapter or a concrete service. It is
 * component-scanned rather than built in {@code LedgerConfiguration}, the one bounded exception ADR-028
 * makes to hand-built wiring, so that a {@code @WebMvcTest} slice loads it.
 *
 * <p>A plan answers 201 with the flow and no {@code Location} header, because there is no single-item
 * read for one to name. A lifecycle change is a {@code POST} to a sub-resource carrying its date in the
 * body, because no server reads a clock, and answers the updated flow. Every write accepts {@code
 * application/json} only, so a form post from a cross-site page is refused with 415 before this runs
 * (ADR-030).
 *
 * <p>A plan is not a fact (ADR-015): the amount travels positive and the direction carries the sign, in
 * both directions. Nothing here negates an amount.
 *
 * <p>Every plan id this controller sees is in the path, so {@link PlanningProblemAdvice} maps the
 * failures: an unknown plan is 404 {@code PLAN_NOT_FOUND}, and a change the current state does not allow
 * is 409 {@code ILLEGAL_TRANSITION}. A rule the domain refuses — a non-positive amount, a recurrence
 * ending before it starts, a realisation dated before its commitment — is the domain's {@link
 * IllegalArgumentException}, which the composition fallback answers 422 {@code RULE_VIOLATED}.
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/planned-flows", produces = MediaType.APPLICATION_JSON_VALUE)
public class PlannedFlowController {

    private final PlanFlowUseCase planner;
    private final ListPlannedFlowsUseCase lister;
    private final TransitionPlanUseCase lifecycle;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public PlannedFlowController(
            PlanFlowUseCase planner, ListPlannedFlowsUseCase lister, TransitionPlanUseCase lifecycle) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.lister = Objects.requireNonNull(lister, "lister");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Every planned flow, start date then id, in the order the port guarantees, whatever its state. */
    @GetMapping
    public List<PlannedFlowResponse> listPlannedFlows() {
        return lister.all().stream().map(PlannedFlowResponse::from).toList();
    }

    /**
     * Records an intention, which starts planned. A recurrence whose members do not fit its kind is 400;
     * a recurrence the domain refuses, or a non-positive amount, is 422 {@code RULE_VIOLATED}.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PlannedFlowResponse planFlow(@Valid @RequestBody PlanFlowRequest request) {
        return PlannedFlowResponse.from(planner.plan(request.toCommand()));
    }

    /** Commits a planned flow on the date given. */
    @PostMapping(path = "/{id}/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlannedFlowResponse commitPlannedFlow(
            @PathVariable UUID id, @Valid @RequestBody PlanTransitionRequest request) {
        return PlannedFlowResponse.from(lifecycle.commit(new PlanId(id), request.on()));
    }

    /** Realises a committed flow on the date given, which may not precede the commitment. */
    @PostMapping(path = "/{id}/realise", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlannedFlowResponse realisePlannedFlow(
            @PathVariable UUID id, @Valid @RequestBody PlanTransitionRequest request) {
        return PlannedFlowResponse.from(lifecycle.realise(new PlanId(id), request.on()));
    }

    /** Cancels a planned or committed flow on the date given, for the reason given. */
    @PostMapping(path = "/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PlannedFlowResponse cancelPlannedFlow(@PathVariable UUID id, @Valid @RequestBody PlanCancelRequest request) {
        return PlannedFlowResponse.from(lifecycle.cancel(new PlanId(id), request.on(), request.reason()));
    }
}
