package io.github.naimelijah.finassist.ledger.planning.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
     * Every command from every state, on the aggregate itself — the mirror of {@code TransactionTest}'s
     * matrix, as ADR-015 asks of two lifecycles of nearly one shape. The four legal transitions reach
     * the state they name; the other eight are refused with {@link IllegalPlanTransitionException}
     * naming the command and the state. {@code Realised} refusing all three is its freeze, held where
     * {@link PlannedFlow} enforces it rather than by the absence of a method on the state type.
     */
    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
        "Planned,   commit,  Committed",
        "Planned,   realise, refused",
        "Planned,   cancel,  Cancelled",
        "Committed, commit,  refused",
        "Committed, realise, Realised",
        "Committed, cancel,  Cancelled",
        "Realised,  commit,  refused",
        "Realised,  realise, refused",
        "Realised,  cancel,  refused",
        "Cancelled, commit,  refused",
        "Cancelled, realise, refused",
        "Cancelled, cancel,  refused"
    })
    void Given_a_plan_in_a_state_When_a_command_is_applied_Then_only_the_legal_transitions_succeed(
            String from, String command, String outcome) {
        PlannedFlow flow = inState(from);

        if (outcome.equals("refused")) {
            assertThatThrownBy(() -> apply(command, flow))
                    .isInstanceOf(IllegalPlanTransitionException.class)
                    .hasMessage("Cannot " + command + " a plan that is " + from);
        } else {
            assertThat(apply(command, flow).state().name()).isEqualTo(outcome);
        }
    }

    /** Every earlier transition in January, so a March command can never be refused for its date. */
    private static PlannedFlow inState(String state) {
        return switch (state) {
            case "Planned" -> aPurchase();
            case "Committed" -> aPurchase().commit(JANUARY);
            case "Realised" -> aPurchase().commit(JANUARY).realise(JANUARY);
            case "Cancelled" -> aPurchase().cancel(JANUARY, "no longer needed");
            default -> throw new IllegalArgumentException("no such state in this matrix: " + state);
        };
    }

    private static PlannedFlow apply(String command, PlannedFlow flow) {
        return switch (command) {
            case "commit" -> flow.commit(MARCH);
            case "realise" -> flow.realise(MARCH);
            case "cancel" -> flow.cancel(MARCH, "changed my mind");
            default -> throw new IllegalArgumentException("no such command in this matrix: " + command);
        };
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
