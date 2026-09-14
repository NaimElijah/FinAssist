package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * The date a planned flow was committed or realised, supplied by the client because no server reads a
 * clock (ADR-028). Named for planning because accounting's {@code TransitionRequest} holds the shorter
 * name, and springdoc keeps only one schema per simple name.
 */
public record PlanTransitionRequest(@NotNull LocalDate on) {}
