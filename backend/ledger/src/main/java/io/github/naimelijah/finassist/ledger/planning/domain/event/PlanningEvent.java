package io.github.naimelijah.finassist.ledger.planning.domain.event;

import io.github.naimelijah.finassist.ledger.planning.domain.forecast.ScenarioId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Something that happened to what the owner intends, stated in the past tense.
 *
 * <p>The mirror of {@code AccountingEvent}, and deliberately so: two lifecycles that behave
 * differently for no reason are two things to remember. Raised by the service rather than the
 * aggregate, for the reason ADR-023 gives — {@code PlannedFlow} is a record whose value semantics
 * {@code Scenario} relies on when it refuses to list the same flow twice.
 *
 * <p>These describe intentions, never facts. A {@code PlanRealised} says the owner marked a plan as
 * having happened; the transaction that actually moved the money is accounting's, and nothing here
 * names it (ADR-015).
 */
public sealed interface PlanningEvent {

    /** An intention was recorded. It starts {@code Planned}. */
    record FlowPlanned(PlanId plan, String description, Money amount, Direction direction, LocalDate startsOn)
            implements PlanningEvent {

        public FlowPlanned {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(startsOn, "startsOn");
        }
    }

    /** The owner committed to the plan. It still counts towards a projection. */
    record PlanCommitted(PlanId plan, LocalDate committedOn) implements PlanningEvent {

        public PlanCommitted {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(committedOn, "committedOn");
        }
    }

    /** The money moved. The plan is frozen and stops counting towards a projection. */
    record PlanRealised(PlanId plan, LocalDate realisedOn) implements PlanningEvent {

        public PlanRealised {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(realisedOn, "realisedOn");
        }
    }

    /** The owner abandoned the plan. It is kept as history and stops counting. */
    record PlanCancelled(PlanId plan, LocalDate cancelledOn, String reason) implements PlanningEvent {

        public PlanCancelled {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(cancelledOn, "cancelledOn");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A named strategy was defined over a set of flows. */
    record ScenarioDefined(ScenarioId scenario, String name, Currency currency, List<PlanId> flows)
            implements PlanningEvent {

        public ScenarioDefined {
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(currency, "currency");
            flows = List.copyOf(flows);
        }
    }
}
