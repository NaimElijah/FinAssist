package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ProjectScenarioUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Projection;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A scenario projected forward over HTTP: a side-effect-free {@code GET} whose query names the horizon,
 * the step and the accounts it opens from (ADR-028).
 *
 * <p>It calls one in-port and is component-scanned, for the reasons {@link PlannedFlowController} gives.
 * Nothing is recorded, so the request is safe to repeat, and every call is derived afresh from the flows
 * as they stand: a plan cancelled since the scenario was defined stops counting at once.
 *
 * <p>The caller says which accounts are cash and which are invested, by repeating {@code cash} and {@code
 * invested}; either may be absent, which opens that line at zero. Each value is planning's own opaque
 * {@link AccountRef}. One named twice is refused as the command is built, before the scenario is looked
 * up, and the composition root turns one that is malformed, unknown or in another currency into an
 * {@link IllegalArgumentException}; each is answered 422 {@code RULE_VIOLATED} like a horizon that is
 * not a whole number of steps or does not start before it ends.
 *
 * <p>Split from {@link ScenarioController} by the not-found semantics it needs. The scenario is named in
 * the path, so an unknown one is 404 {@code SCENARIO_NOT_FOUND}, from {@link PlanningProblemAdvice}. A
 * {@link PlanNotFoundException} here means something else entirely: nothing is deletable, so a scenario
 * naming a plan the store cannot find means the store is corrupt, and that is a 500 (ADR-028). It is
 * handled on this controller, ahead of the advice's 404, and logged, because a controller-local handler
 * answers before the composition fallback that would otherwise log it.
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/scenarios", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProjectionController {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionController.class);

    private final ProjectScenarioUseCase projector;

    /** Takes the in-port by type; it has exactly one implementing bean (ADR-028). */
    public ProjectionController(ProjectScenarioUseCase projector) {
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    /**
     * The scenario's projected cash, invested and total holdings at every step boundary of the horizon,
     * {@code from} and {@code to} included, and the first boundary at which cash is negative, if any.
     */
    @GetMapping("/{id}/projection")
    public ProjectionResponse projectScenario(
            @PathVariable UUID id,
            @Parameter(description = HorizonDescriptions.FROM_PARAMETER)
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(description = HorizonDescriptions.TO_PARAMETER)
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(description = HorizonDescriptions.STEP_PARAMETER) @RequestParam Cadence step,
            @RequestParam(required = false) List<String> cash,
            @RequestParam(required = false) List<String> invested) {
        ScenarioId scenario = new ScenarioId(id);
        Projection projection = projector.project(new ProjectScenarioCommand(
                scenario, new Horizon(from, to), step, accountRefs(cash), accountRefs(invested)));
        return ProjectionResponse.from(scenario, projection);
    }

    /** A projected scenario names a plan the store cannot find: the store is corrupt (ADR-028). */
    @ExceptionHandler(PlanNotFoundException.class)
    ProblemDetail planMissingFromProjectedScenario(PlanNotFoundException exception) {
        LOG.error(
                "Answered 500 with a generic detail: a projected scenario names a planned flow the store lacks",
                exception);
        return PlanningProblems.internalError();
    }

    /** An absent repeated parameter arrives as null, and means no accounts of that kind. */
    private static List<AccountRef> accountRefs(List<String> references) {
        return references == null
                ? List.of()
                : references.stream().map(AccountRef::of).toList();
    }
}
