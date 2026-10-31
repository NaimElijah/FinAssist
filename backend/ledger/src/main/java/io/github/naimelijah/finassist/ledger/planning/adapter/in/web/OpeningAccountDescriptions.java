package io.github.naimelijah.finassist.ledger.planning.adapter.in.web;

/**
 * What the contract says about the accounts a projection opens from, shared by the projection and the
 * comparison so the two cannot describe one selection two ways.
 *
 * <p>Each value is a plain string on the wire, so the description is all a client has. The refusals are
 * the ones {@code ProjectScenarioCommand}, {@code CompareScenariosCommand} and the composition root's
 * translation make, each answered 422 {@code RULE_VIOLATED} (ADR-051).
 */
final class OpeningAccountDescriptions {

    private static final String SELECTION = " Name each account by its account id in the ledger, and repeat the"
            + " parameter to name several; leave it out to open that line at zero. A reference that is not an"
            + " account id, names no account, is held in a currency other than the scenario's, or is named more"
            + " than once across cash and invested is refused 422 RULE_VIOLATED.";

    static final String CASH_PARAMETER = "An account whose balance opens the cash line." + SELECTION;

    static final String INVESTED_PARAMETER = "An account whose balance opens the invested line." + SELECTION;

    private OpeningAccountDescriptions() {}
}
