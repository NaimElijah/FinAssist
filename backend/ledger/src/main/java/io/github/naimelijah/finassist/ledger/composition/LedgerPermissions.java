package io.github.naimelijah.finassist.ledger.composition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Grants a permission for each role a token's {@code roles} claim names, from the role table ADR-032
 * fixes (each service owns its own copy; the assistant's lives in {@code adapters/auth.py}).
 *
 * <p>An unknown role, a missing {@code roles} claim, or one that is not a list, each grant nothing —
 * denied by default rather than by a caught exception. {@code LedgerSecurityConfiguration} wires this
 * as the {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter}'s
 * authorities converter, so every request's {@link org.springframework.security.core.Authentication}
 * carries exactly the permissions this method returns; {@code LedgerAuthorizationIT} then checks each
 * URL rule against those permissions end to end.
 *
 * <p>Public, unlike most of this package, for the same reason {@code HostAllowListFilter} is:
 * {@code WebSliceSecurity} in the test tree's top-level package reuses this exact class as the
 * {@code jwt()} request post-processor's authorities converter, so a {@code @WebMvcTest} slice's
 * default owner authentication is derived the same way production derives it, rather than by a
 * second, hand-kept list of authority strings that could drift from this one.
 */
public final class LedgerPermissions implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Read access to every {@code GET /api/v1/**} route. */
    public static final String LEDGER_READ = "ledger:read";

    /** Write access to every {@code POST /api/v1/**} route. */
    public static final String LEDGER_WRITE = "ledger:write";

    private static final String ROLES_CLAIM = "roles";

    /** The role table. A role absent from this map grants nothing, which is the point of the rule. */
    private static final Map<String, Set<String>> PERMISSIONS_BY_ROLE = Map.of(
            "owner", Set.of(LEDGER_READ, LEDGER_WRITE),
            "viewer", Set.of(LEDGER_READ),
            "assistant", Set.of(LEDGER_READ));

    /**
     * Reads {@code roles} defensively rather than with {@code Jwt#getClaimAsStringList}: the claim
     * comes from a caller-controlled token, and a claim that exists but is not a JSON array must grant
     * nothing. The accessor converts the claim through Spring Security's {@code ClaimConversionService},
     * whose {@code ObjectToListStringConverter} (7.1.2) turns a value that is not a collection into a
     * one-element list of its {@code toString}, so {@code "roles":"owner"} would grant {@code owner}.
     *
     * @param jwt a token that has already passed signature and claim validation
     * @return the permissions the token's roles grant, empty when none does; never null
     */
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object claim = jwt.getClaim(ROLES_CLAIM);
        if (!(claim instanceof List<?> roles)) {
            return Set.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(PERMISSIONS_BY_ROLE::get)
                .filter(Objects::nonNull)
                .flatMap(Set::stream)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
    }
}
