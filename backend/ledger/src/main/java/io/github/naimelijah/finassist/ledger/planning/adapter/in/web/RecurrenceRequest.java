package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

import io.github.naimelijah.finassist.ledger.planning.domain.plan.Cadence;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * How often a planned flow is to happen, as a client sends it: a kind and the members that kind needs
 * (ADR-028).
 *
 * <p>Flat on the wire, and the one place the flat shape can hold a combination the domain's sealed
 * {@link Recurrence} makes unrepresentable. {@link #isCompleteForKind()} refuses those as a bean
 * validation failure, so a recurrence missing a member or carrying another kind's is a 400 with {@code
 * errors[]}, like any other malformed body, and never reaches {@link #toRecurrence()}.
 *
 * <p>The numeric rules — an interval and a count of at least one — are left to the domain's own
 * constructors, whose {@link IllegalArgumentException} the composition fallback answers 422. They are
 * rules about a well-formed value, the same kind as a positive amount or an end after the start, and a
 * {@code @Positive} here would split that one family of refusals across two statuses.
 *
 * @param kind which variant this is
 * @param cadence how often it repeats; {@code UNTIL} and {@code TIMES} only
 * @param interval how many cadences between occurrences; {@code UNTIL} and {@code TIMES} only
 * @param until the exclusive end date; {@code UNTIL} only
 * @param count how many occurrences in total; {@code TIMES} only
 */
public record RecurrenceRequest(
        @NotNull RecurrenceKind kind, Cadence cadence, Integer interval, LocalDate until, Integer count) {

    /**
     * Whether exactly the members this kind needs are present.
     *
     * <p>A JavaBeans {@code is} getter, which is how Hibernate Validator finds a constraint on a method,
     * so the violation is reported against {@code recurrence.completeForKind}. Package-private, so
     * Jackson's default visibility does not see it and it never appears as a property in the contract.
     * With no kind it holds vacuously: {@code @NotNull} already reports that, once.
     */
    @AssertTrue(
            message = "must carry exactly its kind's members: ONCE none; UNTIL cadence, interval and until; "
                    + "TIMES cadence, interval and count")
    boolean isCompleteForKind() {
        if (kind == null) {
            return true;
        }
        boolean repeats = cadence != null && interval != null;
        return switch (kind) {
            case ONCE -> cadence == null && interval == null && until == null && count == null;
            case UNTIL -> repeats && until != null && count == null;
            case TIMES -> repeats && count != null && until == null;
        };
    }

    /**
     * Only called once validation has passed, so every member the kind needs is present.
     *
     * @throws IllegalArgumentException if the interval or the count is below one
     */
    Recurrence toRecurrence() {
        return switch (kind) {
            case ONCE -> new Recurrence.Once();
            case UNTIL -> new Recurrence.Until(cadence, interval, until);
            case TIMES -> new Recurrence.Times(cadence, interval, count);
        };
    }
}
