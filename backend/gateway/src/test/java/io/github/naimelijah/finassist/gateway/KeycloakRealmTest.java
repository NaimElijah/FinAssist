package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code ops/keycloak/finassist-realm.json} as data, without starting Keycloak, and pins
 * the security posture ADR-031 records: sign-in protection, each client's flow and audience, the
 * owner's forced first sign-in, and - the one guarantee that matters most in a file the build
 * never encrypts - that every secret and credential in it is a placeholder, never a real value.
 *
 * <p>{@link KeycloakRealmIT} is this file's other half: it proves Keycloak actually reads the
 * realm the way these assertions assume. This test cannot catch a wrong assumption about
 * Keycloak's own behaviour, only a wrong or drifted realm file.
 */
class KeycloakRealmTest {

    /** Set by surefire and failsafe in {@code pom.xml} to the repository's {@code ops/} directory. */
    private static final String OPS_DIR_PROPERTY = "finassist.ops.dir";

    private static final String REALM_FILE = "finassist-realm.json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A placeholder and nothing else: {@code ${SOME_VAR}}, with no {@code :default} - the realm
     * has no defaults, so a missing variable is caught at start-up (`:?` in docker-compose.yml)
     * rather than inside Keycloak's own import.
     */
    private static final Pattern BARE_PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z_][A-Z0-9_]*}$");

    private static JsonNode realm() {
        String directory = System.getProperty(OPS_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(OPS_DIR_PROPERTY
                    + " is not set. Failsafe and surefire set it in pom.xml; run this test through Maven, or"
                    + " pass -D" + OPS_DIR_PROPERTY + "=<repository>/ops.");
        }
        Path file = Path.of(directory, "keycloak", REALM_FILE).toAbsolutePath().normalize();
        try {
            return JSON.readTree(Files.readString(file));
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    private static String rawText() {
        String directory = System.getProperty(OPS_DIR_PROPERTY);
        Path file = Path.of(directory, "keycloak", REALM_FILE).toAbsolutePath().normalize();
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        for (JsonNode client : realm.path("clients")) {
            if (client.path("clientId").asString().equals(clientId)) {
                return client;
            }
        }
        throw new AssertionError("no client named " + clientId + " in the realm file");
    }

    private static JsonNode user(JsonNode realm, java.util.function.Predicate<JsonNode> match) {
        for (JsonNode user : realm.path("users")) {
            if (match.test(user)) {
                return user;
            }
        }
        throw new AssertionError("no matching user in the realm file");
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).collect(Collectors.toList());
    }

    @Test
    void Given_the_realm_file_When_read_Then_registration_and_password_reset_and_remember_me_are_off() {
        JsonNode realm = realm();

        assertThat(realm.path("registrationAllowed").asBoolean()).isFalse();
        assertThat(realm.path("resetPasswordAllowed").asBoolean()).isFalse();
        assertThat(realm.path("rememberMe").asBoolean()).isFalse();
    }

    @Test
    void Given_the_realm_file_When_read_Then_brute_force_protection_is_on_with_the_agreed_thresholds() {
        JsonNode realm = realm();

        assertThat(realm.path("bruteForceProtected").asBoolean()).isTrue();
        assertThat(realm.path("failureFactor").asInt()).isEqualTo(5);
        assertThat(realm.path("waitIncrementSeconds").asInt()).isEqualTo(60);
        assertThat(realm.path("maxFailureWaitSeconds").asInt()).isEqualTo(900);
    }

    @Test
    void Given_the_realm_file_When_read_Then_the_password_policy_matches_the_agreed_rule() {
        assertThat(realm().path("passwordPolicy").asString())
                .isEqualTo("length(12) and notUsername and notEmail and passwordHistory(3)");
    }

    @Test
    void Given_the_realm_file_When_read_Then_ssl_is_required_externally_only() {
        assertThat(realm().path("sslRequired").asString()).isEqualTo("external");
    }

    @Test
    void Given_the_realm_file_When_read_Then_the_token_lifetimes_and_rotation_match_the_agreed_values() {
        JsonNode realm = realm();

        assertThat(realm.path("accessTokenLifespan").asInt()).isEqualTo(300);
        assertThat(realm.path("ssoSessionIdleTimeout").asInt()).isEqualTo(1800);
        assertThat(realm.path("ssoSessionMaxLifespan").asInt()).isEqualTo(36000);
        assertThat(realm.path("revokeRefreshToken").asBoolean()).isFalse();
    }

    @Test
    void Given_the_realm_file_When_read_Then_exactly_the_three_agreed_roles_exist() {
        List<String> roleNames = realm().path("roles")
                .path("realm")
                .valueStream()
                .map(n -> n.path("name").asString())
                .toList();

        assertThat(roleNames).containsExactlyInAnyOrder("owner", "viewer", "assistant");
    }

    @Test
    void Given_the_gateway_client_When_read_Then_it_uses_only_the_standard_flow_with_pkce_s256() {
        JsonNode gateway = client(realm(), "finassist-gateway");

        assertThat(gateway.path("publicClient").asBoolean()).isFalse();
        assertThat(gateway.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(gateway.path("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(gateway.path("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(gateway.path("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(gateway.path("attributes").path("pkce.code.challenge.method").asString())
                .isEqualTo("S256");
    }

    @Test
    void Given_the_gateway_client_When_read_Then_its_redirect_and_post_logout_uris_are_exact() {
        JsonNode gateway = client(realm(), "finassist-gateway");

        assertThat(texts(gateway.path("redirectUris")))
                .containsExactly("${GATEWAY_PUBLIC_URL}/bff/login/oauth2/code/keycloak");
        assertThat(gateway.path("attributes").path("post.logout.redirect.uris").asString())
                .isEqualTo("${GATEWAY_PUBLIC_URL}/");
        assertThat(texts(gateway.path("webOrigins"))).isEmpty();
    }

    @Test
    void Given_the_assistant_client_When_read_Then_it_is_a_confidential_service_account_only_client() {
        JsonNode assistant = client(realm(), "finassist-assistant");

        assertThat(assistant.path("publicClient").asBoolean()).isFalse();
        assertThat(assistant.path("serviceAccountsEnabled").asBoolean()).isTrue();
        assertThat(assistant.path("standardFlowEnabled").asBoolean()).isFalse();
        assertThat(assistant.path("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(assistant.path("directAccessGrantsEnabled").asBoolean()).isFalse();
    }

    @Test
    void Given_the_realm_file_When_read_Then_the_assistants_service_account_user_holds_the_assistant_role() {
        JsonNode serviceAccountUser = user(
                realm(),
                u -> "finassist-assistant"
                        .equals(u.path("serviceAccountClientId").asString(null)));

        assertThat(texts(serviceAccountUser.path("realmRoles"))).containsExactly("assistant");
    }

    /**
     * {@code clientScopes} is deliberately absent from the realm (see the Dockerfile and the
     * task report): declaring one, even to add these three, silently drops Keycloak's own
     * default scopes - {@code basic} included, which is what carries {@code sub} - so the
     * mappers below live on each client directly instead. Every scope name the plan settled on
     * survives as a mapper name, so the vocabulary is unchanged even though the mechanism is not.
     */
    @Test
    void Given_the_realm_file_When_read_Then_no_top_level_client_scope_is_declared() {
        assertThat(realm().has("clientScopes")).isFalse();
    }

    @Test
    void Given_both_clients_When_read_Then_the_roles_claim_is_flat_multivalued_and_in_both_tokens() {
        for (String clientId : List.of("finassist-gateway", "finassist-assistant")) {
            JsonNode mapper = mapperNamed(client(realm(), clientId), "finassist-roles");

            assertThat(mapper.path("protocolMapper").asString()).isEqualTo("oidc-usermodel-realm-role-mapper");
            assertThat(mapper.path("config").path("claim.name").asString()).isEqualTo("roles");
            assertThat(mapper.path("config").path("multivalued").asString()).isEqualTo("true");
            assertThat(mapper.path("config").path("id.token.claim").asString()).isEqualTo("true");
            assertThat(mapper.path("config").path("access.token.claim").asString())
                    .isEqualTo("true");
        }
    }

    @Test
    void Given_the_gateway_client_When_read_Then_it_carries_both_the_ledger_and_the_assistant_audience() {
        JsonNode gateway = client(realm(), "finassist-gateway");

        assertThat(mapperNamed(gateway, "finassist-ledger-audience")
                        .path("config")
                        .path("included.custom.audience")
                        .asString())
                .isEqualTo("finassist-ledger");
        assertThat(mapperNamed(gateway, "finassist-assistant-audience")
                        .path("config")
                        .path("included.custom.audience")
                        .asString())
                .isEqualTo("finassist-assistant");
    }

    @Test
    void Given_the_assistant_client_When_read_Then_it_carries_only_the_ledger_audience() {
        JsonNode assistant = client(realm(), "finassist-assistant");

        List<String> audienceMapperNames = assistant
                .path("protocolMappers")
                .valueStream()
                .filter(m ->
                        "oidc-audience-mapper".equals(m.path("protocolMapper").asString()))
                .map(m -> m.path("config").path("included.custom.audience").asString())
                .toList();

        assertThat(audienceMapperNames).containsExactly("finassist-ledger");
    }

    private static JsonNode mapperNamed(JsonNode client, String name) {
        for (JsonNode mapper : client.path("protocolMappers")) {
            if (mapper.path("name").asString().equals(name)) {
                return mapper;
            }
        }
        throw new AssertionError("no protocol mapper named " + name + " on client " + client.path("clientId"));
    }

    @Test
    void Given_the_owner_user_When_read_Then_the_password_is_temporary_with_update_password_and_totp_required() {
        JsonNode owner = user(
                realm(),
                u -> "${FINASSIST_OWNER_USERNAME}".equals(u.path("username").asString(null)));

        assertThat(texts(owner.path("requiredActions"))).containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP");
        JsonNode credential = owner.path("credentials").get(0);
        assertThat(credential.path("type").asString()).isEqualTo("password");
        assertThat(credential.path("temporary").asBoolean()).isTrue();
        assertThat(texts(owner.path("realmRoles"))).containsExactly("owner");
    }

    /**
     * The owner's {@code id} is the {@code sub} every future journal row will name. Pinned so
     * re-importing into a fresh {@code keycloak-db-data} volume gives the owner back the same
     * subject rather than a random new one (see the Dockerfile).
     */
    @Test
    void Given_the_owner_user_When_read_Then_its_id_is_pinned_rather_than_left_for_keycloak_to_assign() {
        JsonNode owner = user(
                realm(),
                u -> "${FINASSIST_OWNER_USERNAME}".equals(u.path("username").asString(null)));

        assertThat(owner.path("id").asString()).isEqualTo("6c4a4be0-7c38-4145-845f-f4311ef64202");
    }

    @Test
    void Given_no_viewer_When_the_realm_file_is_read_Then_none_is_seeded() {
        List<String> roles = realm().path("users")
                .valueStream()
                .flatMap(u -> texts(u.path("realmRoles")).stream())
                .toList();

        assertThat(roles).doesNotContain("viewer");
    }

    /**
     * The one guarantee that matters most in a file this build never encrypts. Every client
     * secret and every credential value must read as an unresolved placeholder, never a literal.
     */
    @Test
    void Given_the_realm_file_When_read_Then_every_client_secret_is_a_bare_placeholder() {
        for (String clientId : List.of("finassist-gateway", "finassist-assistant")) {
            String secret = client(realm(), clientId).path("secret").asString();
            assertThat(secret).matches(BARE_PLACEHOLDER);
        }
    }

    @Test
    void Given_the_owner_user_When_read_Then_its_credential_value_is_a_bare_placeholder() {
        JsonNode owner = user(
                realm(),
                u -> "${FINASSIST_OWNER_USERNAME}".equals(u.path("username").asString(null)));
        String credentialValue = owner.path("credentials").get(0).path("value").asString();

        assertThat(credentialValue).matches(BARE_PLACEHOLDER);
    }

    /**
     * Guards against a default creeping in, such as {@code ${VAR:some-default}} - the realm has
     * no defaults, on purpose, so a missing variable is caught by {@code :?} in
     * docker-compose.yml rather than silently answered by one buried in this file.
     */
    @Test
    void Given_the_realm_file_When_read_Then_no_placeholder_declares_a_default_value() {
        String text = rawText();

        assertThat(text).contains("${"); // the file does use placeholders - this pins that fact
        assertThat(text).doesNotMatch("(?s).*\\$\\{[A-Za-z_][A-Za-z0-9_]*:.*");
    }
}
