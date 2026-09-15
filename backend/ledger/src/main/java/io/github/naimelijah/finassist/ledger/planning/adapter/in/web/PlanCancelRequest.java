package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a planned flow was cancelled and the reason, without which a cancellation is refused. */
public record PlanCancelRequest(
        @NotNull LocalDate on, @NotBlank String reason) {}
