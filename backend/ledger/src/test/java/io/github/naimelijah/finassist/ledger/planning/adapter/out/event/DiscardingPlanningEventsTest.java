package io.github.naimelijah.finassist.ledger.planning.adapter.out.event;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.finassist.ledger.planning.domain.event.PlanningEvent;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The mirror of {@code DiscardingAccountingEventsTest}, for planning's publisher. */
class DiscardingPlanningEventsTest {

    private final DiscardingPlanningEvents events = new DiscardingPlanningEvents();

    @Test
    void Given_an_event_When_published_Then_it_is_accepted() {
        PlanningEvent cancelled =
                new PlanningEvent.PlanCancelled(PlanId.generate(), LocalDate.of(2026, 2, 10), "too expensive");

        assertThatNoException().isThrownBy(() -> events.publish(cancelled));
    }

    @Test
    void Given_no_event_When_published_Then_it_is_refused() {
        assertThatThrownBy(() -> events.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("event");
    }
}
