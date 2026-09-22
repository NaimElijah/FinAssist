package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import java.util.Objects;

/**
 * The authenticated caller a journal write is attributed to (ADR-033).
 *
 * <p>Holds a non-blank {@code subject} of at most {@link #MAX_SUBJECT_LENGTH} characters — a
 * validated JWT's {@code sub}, and nothing else about the caller. An {@code Actor} travels
 * alongside a command, never inside one: it is who acted, not what the command names, so it does
 * not touch the rule that identity is generated inside the service and never accepted on a command
 * (ADR-021). {@code TransactionController} is the only place one is built, from {@code
 * java.security.Principal#getName()}.
 */
public record Actor(String subject) {

    /**
     * Mirrors the 255-character length limit in {@code V1_2__journal_recorded_by.sql}, kept in
     * sync by review. The {@code isBlank()} check is stricter than the SQL constraint, safe
     * since {@code Actor} is the only path to the database.
     */
    public static final int MAX_SUBJECT_LENGTH = 255;

    public Actor {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("An actor's subject must not be blank");
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("An actor's subject must be at most " + MAX_SUBJECT_LENGTH
                    + " characters, but was " + subject.length());
        }
    }
}
