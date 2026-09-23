package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The role table ADR-032 fixes, and the three ways a token grants nothing: an unknown
 * role, a missing {@code roles} claim, and one that is not a list.
 */
class LedgerPermissionsTest {

    private final LedgerPermissions permissions = new LedgerPermissions();

    @Test
    void Given_the_owner_role_When_converted_Then_it_grants_read_and_write() {
        assertThat(authorityNames(List.of("owner")))
                .containsExactlyInAnyOrder(LedgerPermissions.LEDGER_READ, LedgerPermissions.LEDGER_WRITE);
    }

    @Test
    void Given_the_viewer_role_When_converted_Then_it_grants_read_only() {
        assertThat(authorityNames(List.of("viewer"))).containsExactly(LedgerPermissions.LEDGER_READ);
    }

    @Test
    void Given_the_assistant_role_When_converted_Then_it_grants_read_only() {
        assertThat(authorityNames(List.of("assistant"))).containsExactly(LedgerPermissions.LEDGER_READ);
    }

    @Test
    void Given_an_unknown_role_When_converted_Then_it_grants_nothing() {
        assertThat(permissions.convert(jwtWithRolesClaim(List.of("superuser")))).isEmpty();
    }

    @Test
    void Given_no_roles_claim_When_converted_Then_it_grants_nothing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "someone")
                .build();

        assertThat(permissions.convert(jwt)).isEmpty();
    }

    @Test
    void Given_a_roles_claim_that_is_not_a_list_When_converted_Then_it_grants_nothing() {
        assertThat(permissions.convert(jwtWithRolesClaim("owner"))).isEmpty();
    }

    @Test
    void
            Given_a_roles_list_naming_a_known_and_an_unknown_role_When_converted_Then_only_the_known_one_grants_anything() {
        assertThat(authorityNames(List.of("viewer", "superuser"))).containsExactly(LedgerPermissions.LEDGER_READ);
    }

    private List<String> authorityNames(Object rolesClaimValue) {
        return permissions.convert(jwtWithRolesClaim(rolesClaimValue)).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt jwtWithRolesClaim(Object rolesClaimValue) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(claims -> claims.putAll(Map.of("sub", "someone", "roles", rolesClaimValue)))
                .build();
    }
}
