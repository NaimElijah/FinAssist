package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a lifecycle change took effect, supplied by the client because no server reads a clock (ADR-028). */
public record TransitionRequest(
        @NotNull
        @Schema(
                description = "The date the change took effect, sent because no server reads a clock. Clearing may"
                        + " not be dated before the transaction occurred, nor reconciling before it cleared.")
        LocalDate on) {}
