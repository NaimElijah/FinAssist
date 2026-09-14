package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * How often a planned flow happens: a kind, and the members that kind carries (ADR-028).
 *
 * <p>The domain's sealed {@link Recurrence} is mapped here by hand, one variant at a time, rather than by
 * a Jackson polymorphism annotation, which the domain may not carry. The switch is exhaustive over the
 * sealed interface, so a new variant does not compile until it has a wire shape. A member a variant
 * lacks is omitted rather than written as null: a once-off flow has no cadence, and only one of {@code
 * until} and {@code count} is ever present.
 *
 * <p>The shape stays flat, not a {@code oneOf} per variant, as {@code TransactionStateResponse} is in
 * accounting. The contract marks {@code kind} required and leaves the rest optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecurrenceResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RecurrenceKind kind,
        Cadence cadence,
        Integer interval,
        LocalDate until,
        Integer count) {

    static RecurrenceResponse from(Recurrence recurrence) {
        return switch (recurrence) {
            case Recurrence.Once ignored -> new RecurrenceResponse(RecurrenceKind.ONCE, null, null, null, null);
            case Recurrence.Until until ->
                new RecurrenceResponse(RecurrenceKind.UNTIL, until.cadence(), until.interval(), until.until(), null);
            case Recurrence.Times times ->
                new RecurrenceResponse(RecurrenceKind.TIMES, times.cadence(), times.interval(), null, times.count());
        };
    }
}
