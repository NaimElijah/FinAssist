package io.github.naimelijah.finassist.ledger.planning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.naimelijah.finassist.ledger.planning.adapter.out.event.InMemoryPlanningEvents;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanFlowCommand;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.PlanNotFoundException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.IllegalPlanTransitionException;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The contract of recording an intention and moving it through its lifecycle.
 *
 * <p>The mirror of {@code TransactionServiceTest}, deliberately: ADR-015 warns that two lifecycles of
 * nearly the same shape will drift if only one is maintained, so they are tested the same way.
 */
class PlanServiceTest {

    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEB_10 = LocalDate.of(2026, 2, 10);
    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);

    private final PlannedFlowRepository flows = mock(PlannedFlowRepository.class);
    private final InMemoryPlanningEvents events = new InMemoryPlanningEvents();
    private final PlanService service = new PlanService(flows, events);

    @Test
    void Given_an_intention_When_planned_Then_it_is_stored_as_planned() {
        PlannedFlow planned = service.plan(newCar());

        assertThat(planned.state()).isEqualTo(new PlanState.Planned());
        verify(flows).save(planned);
        assertThat(planned.countsTowardsProjection()).isTrue();
    }

    @Test
    void Given_a_negative_amount_When_planned_Then_the_domain_refuses_it() {
        assertThatThrownBy(() -> service.plan(new PlanFlowCommand(
                        "New car",
                        Money.of("-40000.00", Currency.ILS),
                        Direction.OUTFLOW,
                        FEB_1,
                        new Recurrence.Once())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the direction carries the sign");
    }

    @Test
    void Given_a_planned_flow_When_committed_Then_the_new_state_is_stored() {
        PlannedFlow planned = stored(service.plan(newCar()));

        PlannedFlow committed = service.commit(planned.id(), FEB_10);

        assertThat(committed.state()).isEqualTo(new PlanState.Committed(FEB_10));
        verify(flows).save(committed);
        assertThat(committed.countsTowardsProjection()).isTrue();
    }

    @Test
    void Given_a_committed_flow_When_realised_Then_it_is_frozen_and_stops_counting() {
        PlannedFlow planned = stored(service.plan(newCar()));
        PlannedFlow committed = stored(service.commit(planned.id(), FEB_10));

        PlannedFlow realised = stored(service.realise(committed.id(), MARCH_1));

        assertThat(realised.countsTowardsProjection()).isFalse();
        assertThatThrownBy(() -> service.cancel(planned.id(), MARCH_1, "changed my mind"))
                .isInstanceOf(IllegalPlanTransitionException.class);
    }

    @Test
    void Given_a_planned_flow_When_realised_without_committing_Then_it_is_refused() {
        PlannedFlow planned = stored(service.plan(newCar()));

        assertThatThrownBy(() -> service.realise(planned.id(), MARCH_1))
                .isInstanceOf(IllegalPlanTransitionException.class);
    }

    @Test
    void Given_a_planned_flow_When_cancelled_Then_it_is_kept_and_stops_counting() {
        PlannedFlow planned = stored(service.plan(newCar()));

        PlannedFlow cancelled = service.cancel(planned.id(), FEB_10, "  too expensive  ");

        assertThat(cancelled.state()).isEqualTo(new PlanState.Cancelled(FEB_10, "too expensive"));
        assertThat(cancelled.countsTowardsProjection()).isFalse();
        // Kept, not removed: the port has no delete, and cancelling stores a new state.
        verify(flows).save(cancelled);
    }

    @Test
    void Given_a_blank_cancellation_reason_When_cancelled_Then_it_is_refused() {
        PlannedFlow planned = stored(service.plan(newCar()));

        assertThatThrownBy(() -> service.cancel(planned.id(), FEB_10, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }

    @Test
    void Given_an_unknown_plan_When_transitioned_Then_it_is_refused() {
        PlanId unknown = PlanId.generate();

        assertThatThrownBy(() -> service.commit(unknown, FEB_10)).isInstanceOf(PlanNotFoundException.class);
        assertThatThrownBy(() -> service.realise(unknown, FEB_10)).isInstanceOf(PlanNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(unknown, FEB_10, "typo")).isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void Given_each_transition_When_it_succeeds_Then_its_event_is_published_in_order() {
        PlannedFlow planned = stored(service.plan(newCar()));
        PlannedFlow committed = stored(service.commit(planned.id(), FEB_10));
        service.realise(committed.id(), MARCH_1);

        assertThat(events.published())
                .containsExactly(
                        new PlanningEvent.FlowPlanned(
                                planned.id(), "New car", Money.of("40000.00", Currency.ILS), Direction.OUTFLOW, FEB_1),
                        new PlanningEvent.PlanCommitted(planned.id(), FEB_10),
                        new PlanningEvent.PlanRealised(planned.id(), MARCH_1));
    }

    @Test
    void Given_a_padded_cancellation_reason_When_cancelled_Then_the_event_carries_the_stored_form() {
        PlannedFlow planned = stored(service.plan(newCar()));

        service.cancel(planned.id(), FEB_10, "  too expensive  ");

        assertThat(events.published()).contains(new PlanningEvent.PlanCancelled(planned.id(), FEB_10, "too expensive"));
    }

    /**
     * What the store would hand back on the next read.
     *
     * <p>A transition is a read-modify-write, so a test that runs two in sequence has to say what the
     * first one left behind. Stating it is the point: against a stateful fake the coupling was real
     * and invisible.
     */
    private PlannedFlow stored(PlannedFlow flow) {
        when(flows.findById(flow.id())).thenReturn(java.util.Optional.of(flow));
        return flow;
    }

    private static PlanFlowCommand newCar() {
        return new PlanFlowCommand(
                "New car", Money.of("40000.00", Currency.ILS), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
    }
}
