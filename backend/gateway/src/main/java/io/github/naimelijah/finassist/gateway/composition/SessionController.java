package io.github.naimelijah.finassist.gateway.composition;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code GET /bff/me}: who is signed in, for the web client's own use (ADR-030).
 *
 * <p>The only endpoint the gateway implements itself. Everything else it serves is a filter — the
 * login entry, the callback and the sign-out — and everything else it answers at all is relayed to
 * the ledger or the assistant, which is why the gateway restates no DTO of theirs (ADR-026).
 *
 * <p>The claims come from the ID token, because the registration names no user-info URI, so this
 * costs no call to Keycloak.
 *
 * <p><b>Roles are reported, not granted.</b> The list is filtered to the three FinAssist knows
 * about (ADR-032), so a role added to the realm for some other client cannot reach the web client
 * through here. Nothing about it is a permission check: the gateway authorises nothing, and each
 * service decides for itself what a role may do, on every request. A token whose {@code roles}
 * claim is absent, or is not a list, yields an empty list rather than an error — the claim is the
 * realm's to shape, and a session with no role is a signed-in user who may do nothing, which the
 * services will say plainly when asked.
 */
@RestController
class SessionController {

    /**
     * The claim Keycloak's realm-roles mapper writes, flat rather than nested under
     * {@code realm_access} (ADR-031).
     */
    private static final String ROLES_CLAIM = "roles";

    /**
     * The roles FinAssist defines (ADR-032). Anything else in the claim is dropped here, and would
     * grant nothing at a service either.
     */
    private static final Set<String> KNOWN_ROLES = Set.of("owner", "viewer", "assistant");

    /**
     * @param user the signed-in principal; never null, because the security chain requires
     *     authentication on this path before a handler runs
     * @return the subject, the preferred username and the recognised roles
     */
    @GetMapping(value = BffRoutes.ME, produces = MediaType.APPLICATION_JSON_VALUE)
    SessionResponse me(@AuthenticationPrincipal OidcUser user) {
        return new SessionResponse(
                user.getSubject(), user.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME), rolesOf(user));
    }

    /**
     * Reads the claim by hand rather than through {@code getClaimAsStringList}, which throws when
     * the value is not convertible to a list. What a token carries is the realm's business, not
     * this gateway's, so a shape it did not expect is an empty list and not a 500.
     */
    private static List<String> rolesOf(OidcUser user) {
        Object claim = user.getClaims().get(ROLES_CLAIM);
        if (!(claim instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(KNOWN_ROLES::contains)
                .toList();
    }
}
