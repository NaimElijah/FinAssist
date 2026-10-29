package io.github.naimelijah.finassist.ledger.planning.application.port.in;

import io.github.naimelijah.finassist.ledger.planning.application.port.out.AccountRef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The rule both forecast commands share about the accounts a projection opens from.
 *
 * <p>Planning's own invariant, not the translation's: it speaks only of planning's {@link AccountRef},
 * so planning refuses it wherever a command is built, whoever builds it (ADR-051).
 */
final class AccountSelection {

    private AccountSelection() {}

    /**
     * Refuses an account named twice, on one side or across both.
     *
     * <p>Refused rather than deduplicated. Naming an account twice, or on both sides, is a caller mistake
     * with no sensible reading - an account is cash or it is invested, not both - and quietly collapsing
     * it would hide the very slip that doubles an opening balance.
     *
     * @throws IllegalArgumentException naming the first reference repeated, cash before invested
     */
    static void requireEachNamedOnce(List<AccountRef> cash, List<AccountRef> invested) {
        Set<AccountRef> seen = new HashSet<>();
        for (AccountRef ref : Stream.concat(cash.stream(), invested.stream()).toList()) {
            if (!seen.add(ref)) {
                throw new IllegalArgumentException(
                        "Account reference " + ref + " is named more than once; it would be counted twice");
            }
        }
    }
}
