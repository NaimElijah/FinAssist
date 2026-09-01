package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The plan lifecycle, and the invariants that keep an intention from being nonsense. */
class PlannedFlowTest {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final Money FIVE_HUNDRED = Money.of("500.00", Currency.ILS);

    private static PlannedFlow aPurchase() {
        return PlannedFlow.plan("New laptop", FIVE_HUNDRED, Direction.OUTFLOW, MARCH, new Recurrence.Once());
    }

    @Test
    void Given_a_negative_amount_When_a_flow_is_planned_Then_it_is_refused() {
        Money negative = Money.of("-500.00", Currency.ILS);

        assertThatThrownBy(
                        () -> PlannedFlow.plan("New laptop", negative, Direction.OUTFLOW, MARCH, new Recurrence.Once()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive amount");
    }

    @Test
    void Given_a_zero_amount_When_a_flow_is_planned_Then_it_is_refused() {
        assertThatThrownBy(() -> PlannedFlow.plan(
                        "New laptop", Money.zero(Currency.ILS), Direction.OUTFLOW, MARCH, new Recurrence.Once()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive amount");
    }

    @Test
    void Given_a_blank_description_When_a_flow_is_planned_Then_it_is_refused() {
        assertThatThrownBy(() -> PlannedFlow.plan("   ", FIVE_HUNDRED, Direction.OUTFLOW, MARCH, new Recurrence.Once()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("what it is for");
    }

    @Test
    void Given_a_recurrence_ending_before_it_starts_When_a_flow_is_planned_Then_it_is_refused() {
        Recurrence endsBeforeItBegins = new Recurrence.Until(Cadence.MONTHLY, 1, JANUARY);

        assertThatThrownBy(() -> PlannedFlow.plan("Rent", FIVE_HUNDRED, Direction.OUTFLOW, MARCH, endsBeforeItBegins))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never occur");
    }

    @Test
    void Given_a_planned_flow_When_committed_and_then_realised_Then_it_reaches_realised() {
        PlannedFlow realised = aPurchase().commit(JANUARY).realise(MARCH);

        assertThat(realised.state()).isEqualTo(new PlanState.Realised(MARCH));
    }

    /**
     * Deliberately unlike {@code Transaction.clear}, which refuses a date before the transaction
     * occurred. Deciding in January to buy something in March is the ordinary case, not an error.
     */
    @Test
    void Given_a_flow_planned_for_march_When_committed_in_january_Then_it_is_allowed() {
        assertThat(aPurchase().commit(JANUARY).state()).isEqualTo(new PlanState.Committed(JANUARY));
    }

    @Test
    void Given_a_planned_flow_When_realised_without_being_committed_Then_it_is_refused() {
        assertThatThrownBy(() -> aPurchase().realise(MARCH))
                .isInstanceOf(IllegalPlanTransitionException.class)
                .hasMessageContaining("Planned");
    }

    @Test
    void Given_a_committed_flow_When_realised_before_it_was_committed_Then_it_is_refused() {
        PlannedFlow committed = aPurchase().commit(MARCH);

        assertThatThrownBy(() -> committed.realise(JANUARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it was committed");
    }

    @Test
    void Given_a_realised_flow_When_cancelled_Then_it_is_refused() {
        PlannedFlow realised = aPurchase().commit(JANUARY).realise(MARCH);

        assertThatThrownBy(() -> realised.cancel(MARCH, "changed my mind"))
                .isInstanceOf(IllegalPlanTransitionException.class)
                .hasMessageContaining("Realised");
    }

    @Test
    void Given_a_cancelled_flow_When_cancelled_again_Then_it_is_refused() {
        PlannedFlow cancelled = aPurchase().cancel(JANUARY, "no longer needed");

        assertThatThrownBy(() -> cancelled.cancel(MARCH, "still not needed"))
                .isInstanceOf(IllegalPlanTransitionException.class)
                .hasMessageContaining("Cancelled");
    }

    @Test
    void Given_a_cancellation_with_no_reason_When_applied_Then_it_is_refused() {
        assertThatThrownBy(() -> aPurchase().cancel(JANUARY, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }

    @Test
    void Given_a_committed_flow_When_cancelled_before_it_was_committed_Then_it_is_refused() {
        PlannedFlow committed = aPurchase().commit(MARCH);

        assertThatThrownBy(() -> committed.cancel(JANUARY, "changed my mind"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before it was committed");
    }

    @Test
    void Given_a_planned_flow_When_asked_whether_it_counts_towards_a_projection_Then_it_does() {
        assertThat(aPurchase().countsTowardsProjection()).isTrue();
    }

    @Test
    void Given_a_committed_flow_When_asked_whether_it_counts_towards_a_projection_Then_it_does() {
        assertThat(aPurchase().commit(JANUARY).countsTowardsProjection()).isTrue();
    }

    @Test
    void Given_a_realised_flow_When_asked_whether_it_counts_towards_a_projection_Then_it_does_not() {
        assertThat(aPurchase().commit(JANUARY).realise(MARCH).countsTowardsProjection())
                .isFalse();
    }

    @Test
    void Given_a_cancelled_flow_When_asked_whether_it_counts_towards_a_projection_Then_it_does_not() {
        assertThat(aPurchase().cancel(JANUARY, "no longer needed").countsTowardsProjection())
                .isFalse();
    }

    /**
     * The compiler already refuses {@code realised.commit(...)}, because {@code Realised} declares no
     * such method — but a compile-time guarantee leaves no trace in the suite, so nothing would stop
     * someone restoring one. This asserts the absence itself.
     */
    @Test
    void Given_the_realised_state_When_inspected_Then_it_declares_no_transition_at_all() {
        assertThat(Arrays.stream(PlanState.Realised.class.getDeclaredMethods())
                        .filter(method -> PlanState.class.isAssignableFrom(method.getReturnType()))
                        .map(Method::getName))
                .as("Realised is frozen by the absence of a transition; see ADR-015")
                .isEmpty();
    }

    @Test
    void Given_a_description_padded_with_spaces_When_a_flow_is_planned_Then_it_is_stripped() {
        PlannedFlow flow = PlannedFlow.plan("  Rent  ", FIVE_HUNDRED, Direction.OUTFLOW, MARCH, new Recurrence.Once());

        assertThat(flow.description()).isEqualTo("Rent");
    }

    @Test
    void Given_a_plan_id_When_rendered_and_parsed_Then_the_round_trip_holds() {
        PlanId id = PlanId.generate();

        assertThat(PlanId.of(id.toString())).isEqualTo(id);
        assertThat(id.toString()).isEqualTo(id.value().toString());
    }

    @Test
    void Given_each_plan_state_When_named_Then_the_name_is_the_one_used_in_refusals() {
        assertThat(new PlanState.Planned().name()).isEqualTo("Planned");
        assertThat(new PlanState.Committed(MARCH).name()).isEqualTo("Committed");
        assertThat(new PlanState.Realised(MARCH).name()).isEqualTo("Realised");
        assertThat(new PlanState.Cancelled(MARCH, "Changed my mind").name()).isEqualTo("Cancelled");
    }

    @Test
    void Given_a_committed_flow_When_cancelled_Then_it_stops_counting_and_keeps_its_reason() {
        PlannedFlow cancelled = aPurchase().commit(JANUARY).cancel(MARCH, "Changed my mind");

        assertThat(cancelled.countsTowardsProjection()).isFalse();
        assertThat(cancelled.state()).isInstanceOf(PlanState.Cancelled.class);
    }
}
