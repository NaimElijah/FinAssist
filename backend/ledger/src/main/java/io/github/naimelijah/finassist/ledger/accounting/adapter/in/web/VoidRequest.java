package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a transaction was voided and the reason, without which a void is refused. */
public record VoidRequest(
        @NotNull
        @Schema(
                description = "The date of the void. It may not be before the transaction occurred, nor, for a"
                        + " cleared one, before it cleared.")
        LocalDate on,

        @NotBlank String reason) {}
