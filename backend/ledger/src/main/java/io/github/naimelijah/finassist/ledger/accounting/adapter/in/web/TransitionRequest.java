package io.github.naimelijah.finassist.ledger.accounting.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The date a lifecycle change took effect, supplied by the client because no server reads a clock (ADR-028). */
public record TransitionRequest(@NotNull LocalDate on) {}
