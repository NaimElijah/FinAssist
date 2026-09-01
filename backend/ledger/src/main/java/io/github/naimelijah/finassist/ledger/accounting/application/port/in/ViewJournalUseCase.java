package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import java.time.LocalDate;
import java.util.List;

/** Reads the journal as flat lines. */
public interface ViewJournalUseCase {

    /**
     * Every posted entry in the half-open range, one {@link JournalLine} per entry.
     *
     * <p><strong>Voided transactions are excluded</strong>, exactly as {@code Balance.of} excludes
     * them: their entries survive as history and move no money. "Posted" in the name rather than
     * "recorded" is what says so, because a caller counting spend must not have to know to filter.
     *
     * @param fromInclusive the first date in range
     * @param toExclusive the first date past it
     * @throws AccountNotFoundException if a line references an account that no longer exists, which
     *     the append-only chart of accounts makes impossible and which is therefore a corrupt store
     *     rather than a caller error
     */
    List<JournalLine> postedBetween(LocalDate fromInclusive, LocalDate toExclusive);
}
