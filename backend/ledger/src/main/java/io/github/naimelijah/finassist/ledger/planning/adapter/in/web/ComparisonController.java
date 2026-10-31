package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.CompareScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ListScenariosUseCase;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ScenarioNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Horizon;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Scenario;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scenarios compared side by side over HTTP: a side-effect-free {@code GET} whose query names the
 * scenarios, the baseline, one horizon, one step and one opening position for all of them (ADR-028).
 *
 * <p>It calls in-ports only and is component-scanned, for the reasons {@link PlannedFlowController} gives.
 * The response lists the scenarios in the order the request named them.
 *
 * <p>{@code Comparison} keys its projections, and {@code Comparison.divergenceFrom} its baseline, by
 * scenario <em>name</em>, while the wire speaks in ids. Names are resolved through {@link
 * ListScenariosUseCase}, an in-port, and are unique within a comparison because the service refuses two
 * scenarios sharing one.
 *
 * <p>Three refusals are the adapter's own, before anything is projected:
 *
 * <ul>
 *   <li>fewer than {@value #MINIMUM_SCENARIOS} scenarios, or more than {@value #MAXIMUM_SCENARIOS}, is
 *       400 naming the {@code scenario} parameter. A comparison of one is malformed on its face, as a
 *       transaction of one entry is in accounting, so it is checked here as well as by the domain; the
 *       maximum bounds the work, since each scenario is projected in full;
 *   <li>a baseline that is not among the scenarios is 422 {@code RULE_VIOLATED}: the request is well-formed
 *       and asks for a divergence from something it did not compare.
 *   <li>a blank {@code scenario} value is 400 naming the parameter as well. Spring converts it to a null
 *       element rather than refusing it, so the list still counts two and passes its size check, and the
 *       null would otherwise fail building a {@code ScenarioId} as a 500.
 * </ul>
 *
 * <p>Split from {@link ProjectionController} by the not-found semantics it needs. The scenario ids are in
 * the query, so an unknown one is part of what the request asks for and is 422 {@code UNKNOWN_SCENARIO},
 * not the 404 {@link PlanningProblemAdvice} gives a scenario named in a path. A {@link
 * PlanNotFoundException} is a corrupt store, 500, as in a projection (ADR-028). Both are handled on this
 * controller, which Spring MVC consults before any advice; the 500 is logged here, because the composition
 * fallback that would otherwise log it never sees it.
 *
 * <p>Handler names are unique across the ledger, not only within this class, for the reason {@code
 * AccountController} gives.
 */
@RestController
@RequestMapping(path = "/api/v1/scenario-comparisons", produces = MediaType.APPLICATION_JSON_VALUE)
public class ComparisonController {

    /** A comparison needs something to compare against; {@code Comparison} refuses fewer too. */
    static final int MINIMUM_SCENARIOS = 2;

    /** {@code Comparison}'s own maximum, checked here too so that more is a 400 before anything is projected. */
    static final int MAXIMUM_SCENARIOS = Comparison.MAXIMUM_SCENARIOS;

    private static final Logger LOG = LoggerFactory.getLogger(ComparisonController.class);

    private final CompareScenariosUseCase comparer;
    private final ListScenariosUseCase lister;

    /** Takes each in-port by type; each has exactly one implementing bean (ADR-028). */
    public ComparisonController(CompareScenariosUseCase comparer, ListScenariosUseCase lister) {
        this.comparer = Objects.requireNonNull(comparer, "comparer");
        this.lister = Objects.requireNonNull(lister, "lister");
    }

    /**
     * Each scenario's projection, end state and divergence from the baseline, over one horizon. A horizon or
     * opening position the domain refuses is 422 {@code RULE_VIOLATED}.
     */
    @GetMapping
    public ComparisonResponse compareScenarios(
            @RequestParam @Size(min = MINIMUM_SCENARIOS, max = MAXIMUM_SCENARIOS) List<@NotNull UUID> scenario,
            @RequestParam UUID baseline,
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
        List<ScenarioId> scenarios = scenario.stream().map(ScenarioId::new).toList();
        ScenarioId reference = new ScenarioId(baseline);
        if (!scenarios.contains(reference)) {
            throw new IllegalArgumentException("The baseline " + baseline + " is not among the scenarios compared, "
                    + scenario + "; a divergence is measured from one of them");
        }

        Comparison comparison = comparer.compare(new CompareScenariosCommand(
                scenarios, new Horizon(from, to), step, accountRefs(cash), accountRefs(invested)));

        Map<ScenarioId, String> names = lister.all().stream()
                .filter(listed -> scenarios.contains(listed.id()))
                .collect(Collectors.toMap(Scenario::id, Scenario::name));
        return ComparisonResponse.from(comparison, scenarios, reference, names);
    }

    /** A scenario in the query names no scenario: the request is well-formed and refused (ADR-028). */
    @ExceptionHandler(ScenarioNotFoundException.class)
    ProblemDetail unknownScenario(ScenarioNotFoundException exception) {
        return PlanningProblems.problem(
                HttpStatus.UNPROCESSABLE_CONTENT, PlanningProblems.UNKNOWN_SCENARIO, exception.getMessage());
    }

    /** A compared scenario names a plan the store cannot find: the store is corrupt (ADR-028). */
    @ExceptionHandler(PlanNotFoundException.class)
    ProblemDetail planMissingFromComparedScenario(PlanNotFoundException exception) {
        LOG.error(
                "Answered 500 with a generic detail: a compared scenario names a planned flow the store lacks",
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
