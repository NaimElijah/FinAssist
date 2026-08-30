package io.github.naimelijah.finassist.ledger.planning.domain.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A strategy is a named set of flows in one currency, and the empty one is the baseline. */
class ScenarioTest {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

    private static PlannedFlow purchaseOf(String amount, Currency currency) {
        return PlannedFlow.plan(
                "New laptop", Money.of(amount, currency), Direction.OUTFLOW, MARCH, new Recurrence.Once());
    }

    @Test
    void Given_a_flow_in_another_currency_When_a_scenario_is_built_Then_it_is_refused() {
        List<PlannedFlow> foreign = List.of(purchaseOf("500.00", Currency.USD));

        assertThatThrownBy(() -> Scenario.of("Buy the laptop", Currency.ILS, foreign, GrowthAssumption.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("but the scenario is in ILS");
    }

    @Test
    void Given_no_flows_at_all_When_a_baseline_is_built_Then_it_is_legal() {
        Scenario baseline = Scenario.baseline("Do nothing", Currency.ILS, GrowthAssumption.none());

        assertThat(baseline.flows()).isEmpty();
        assertThat(baseline.name()).isEqualTo("Do nothing");
    }

    @Test
    void Given_a_blank_name_When_a_scenario_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> Scenario.baseline("   ", Currency.ILS, GrowthAssumption.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have a name");
    }

    @Test
    void Given_realised_and_cancelled_flows_When_forecastable_flows_are_taken_Then_only_the_live_ones_remain() {
        PlannedFlow live = purchaseOf("500.00", Currency.ILS);
        PlannedFlow realised = purchaseOf("100.00", Currency.ILS).commit(MARCH).realise(MARCH);
        PlannedFlow cancelled = purchaseOf("200.00", Currency.ILS).cancel(MARCH, "no longer needed");

        Scenario scenario =
                Scenario.of("Mixed", Currency.ILS, List.of(live, realised, cancelled), GrowthAssumption.none());

        assertThat(scenario.forecastableFlows()).containsExactly(live);
    }

    @Test
    void Given_the_same_flow_listed_twice_When_a_scenario_is_built_Then_it_is_refused() {
        PlannedFlow rent = purchaseOf("500.00", Currency.ILS);

        assertThatThrownBy(() -> Scenario.of("Double rent", Currency.ILS, List.of(rent, rent), GrowthAssumption.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appears twice");
    }

    @Test
    void Given_two_separate_flows_of_the_same_amount_When_a_scenario_is_built_Then_both_are_kept() {
        Scenario scenario = Scenario.of(
                "Two payments",
                Currency.ILS,
                List.of(purchaseOf("500.00", Currency.ILS), purchaseOf("500.00", Currency.ILS)),
                GrowthAssumption.none());

        assertThat(scenario.flows()).hasSize(2);
    }

    /**
     * BigDecimal equality is scale-sensitive, so an unnormalised rate would make two assumptions
     * that compute identically unequal — and Scenario, holding one, would inherit the split.
     */
    @Test
    void Given_growth_rates_differing_only_in_scale_When_compared_Then_they_are_one_assumption() {
        assertThat(GrowthAssumption.perStep("0.1")).isEqualTo(GrowthAssumption.perStep("0.10"));
        assertThat(GrowthAssumption.none()).isEqualTo(GrowthAssumption.perStep("0.00"));
        assertThat(GrowthAssumption.perStep("0.1")).hasSameHashCodeAs(GrowthAssumption.perStep("0.10"));
    }

    @Test
    void Given_a_growth_rate_at_minus_one_When_an_assumption_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> GrowthAssumption.perStep("-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than -1");
    }

    @Test
    void Given_an_assumed_decline_When_an_assumption_is_built_Then_it_is_allowed() {
        Money after = GrowthAssumption.perStep("-0.10").applyTo(Money.of("1000.00", Currency.ILS));

        assertThat(after).isEqualTo(Money.of("900.00", Currency.ILS));
    }

    @Test
    void Given_a_scenario_id_When_rendered_and_parsed_Then_the_round_trip_holds() {
        ScenarioId id = ScenarioId.generate();

        assertThat(ScenarioId.of(id.toString())).isEqualTo(id);
        assertThat(id.toString()).isEqualTo(id.value().toString());
    }
}
