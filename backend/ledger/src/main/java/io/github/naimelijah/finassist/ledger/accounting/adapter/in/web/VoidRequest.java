package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a transaction was voided and the reason, without which a void is refused. */
public record VoidRequest(@NotNull LocalDate on, @NotBlank String reason) {}
