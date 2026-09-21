package io.github.naimelijah.finassist.gateway.composition;

import java.util.List;

/**
 * The signed-in user as the web client sees them, the body of {@code GET /bff/me}.
 *
 * <p>Three members, and deliberately nothing else: no token, no session id, no claim beyond these.
 * The web client uses the name to greet the owner and the roles to decide which controls to show;
 * what those roles actually permit is each service's own decision, enforced on every request it
 * receives (ADR-032). Hiding a control the server would refuse is a courtesy, never a control.
 *
 * @param subject the token's {@code sub}, the identity the ledger's journal records against a row
 * @param name the {@code preferred_username} claim, which is what the realm shows a person
 * @param roles the roles the gateway recognises, in the order the token listed them; empty when
 *     the token carries none it knows
 */
record SessionResponse(String subject, String name, List<String> roles) {}
