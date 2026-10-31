package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a planned flow was cancelled and the reason, without which a cancellation is refused. */
public record PlanCancelRequest(
        @NotNull
        @Schema(
                description =
                        "The date of the cancellation. For a committed plan, it may not be before the commitment.")
        LocalDate on,

        @NotBlank String reason) {}
