package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Where a planned flow has got to: a status, the date it got there, and for a cancellation the reason
 * (ADR-028).
 *
 * <p>The domain's sealed {@link PlanState} is mapped here by hand, for the reasons {@link
 * RecurrenceResponse} gives. A planned flow has no {@code on}, and only a cancelled one has a {@code
 * reason}; each absent member is omitted rather than written as null. The contract marks {@code status}
 * required and leaves the rest optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanStateResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlanStatus status,

        @Schema(description = "The date the plan reached this status; absent while it is planned.")
        LocalDate on,

        String reason) {

    /**
     * The discriminator a client branches on, one constant per state.
     *
     * <p>Not named {@code Status}: {@code web_dto_names_are_unique_across_contexts} compares simple names,
     * and accounting's {@code TransactionStateResponse.Status} already holds that one.
     */
    public enum PlanStatus {
        PLANNED,
        COMMITTED,
        REALISED,
        CANCELLED
    }

    static PlanStateResponse from(PlanState state) {
        return switch (state) {
            case PlanState.Planned ignored -> new PlanStateResponse(PlanStatus.PLANNED, null, null);
            case PlanState.Committed committed ->
                new PlanStateResponse(PlanStatus.COMMITTED, committed.committedOn(), null);
            case PlanState.Realised realised -> new PlanStateResponse(PlanStatus.REALISED, realised.realisedOn(), null);
            case PlanState.Cancelled cancelled ->
                new PlanStateResponse(PlanStatus.CANCELLED, cancelled.cancelledOn(), cancelled.reason());
        };
    }
}
