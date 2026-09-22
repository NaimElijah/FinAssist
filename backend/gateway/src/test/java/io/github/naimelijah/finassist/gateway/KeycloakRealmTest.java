package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code ops/keycloak/finassist-realm.json} as data, without starting Keycloak, and pins
 * the security posture ADR-031 records: sign-in protection, each client's flow and audience, the
 * owner's forced first sign-in, and - the one guarantee that matters most in a file the build
 * never encrypts - that every secret and credential in it is a placeholder, never a real value.
 *
 * <p>It also compares the realm's placeholders with what {@code docker-compose.yml} gives the
 * {@code keycloak} service, which is a comparison of two text files and needs no container -
 * and which nothing else makes: {@link KeycloakRealmIT} supplies every placeholder itself as
 * container environment, and the assertions below check only that each is bare.
 *
 * <p>{@link KeycloakRealmIT} is this file's other half: it proves Keycloak actually reads the
 * realm the way these assertions assume. This test cannot catch a wrong assumption about
 * Keycloak's own behaviour, only a wrong or drifted realm file.
 */
class KeycloakRealmTest {

    /** Set by surefire and failsafe in {@code pom.xml} to the repository's {@code ops/} directory. */
    private static final String OPS_DIR_PROPERTY = "finassist.ops.dir";

    /** Set the same way, to the repository root, because {@code docker-compose.yml} lives there. */
    private static final String REPO_DIR_PROPERTY = "finassist.repo.dir";

    private static final String REALM_FILE = "finassist-realm.json";

    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The service whose environment resolves the realm's placeholders at import. */
    private static final String KEYCLOAK_SERVICE = "keycloak";

    /** One {@code ${VAR}} reference in the realm file, capturing the variable's name. */
    private static final Pattern PLACEHOLDER_REFERENCE = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");

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
     * {@code clientScopes} is deliberately absent from the realm: declaring one, even to add
     * these three, silently drops Keycloak's own default scopes - {@code basic} included, which
     * is what carries {@code sub} - so the mappers below live on each client directly instead.
     * Each client scope ADR-031 lists survives as a mapper of the same purpose on the clients
     * whose tokens need it - {@code finassist-roles} on both, and an audience mapper for each
     * audience a client's tokens carry - so what a token carries is unchanged even though the
     * mechanism is not.
     */
    @Test
    void Given_the_realm_file_When_read_Then_no_top_level_client_scope_is_declared() {
        assertThat(realm().has("clientScopes")).isFalse();
    }

    /**
     * The failure this guards is not the one {@code no_top_level_client_scope_is_declared}
     * catches. That test catches a reintroduced {@code clientScopes} array; this one catches
     * the other way to reach the same broken outcome - simply deleting {@code "basic"} from a
     * client's own {@code defaultClientScopes} - which {@code KeycloakRealmIT} can only prove
     * for the assistant (a real client-credentials token is easy to mint there). Nothing in
     * this build mints a real browser user token for the gateway, because its client correctly
     * permits only the standard flow - so this static check is the one thing standing between a
     * dropped {@code "basic"} on the gateway and every browser sign-in silently losing
     * {@code sub}.
     */
    @Test
    void Given_both_clients_When_read_Then_basic_is_a_default_scope() {
        for (String clientId : List.of("finassist-gateway", "finassist-assistant")) {
            assertThat(texts(client(realm(), clientId).path("defaultClientScopes")))
                    .contains("basic");
        }
    }

    /**
     * Least privilege in the {@code roles} claim. Left at Keycloak's default of {@code true},
     * {@code fullScopeAllowed} puts <em>every</em> role in the realm into a client's scope — which
     * a real import confirms: with it unset, the gateway client's scope held
     * {@code default-roles-finassist}, {@code offline_access}, {@code uma_authorization} and even
     * {@code assistant}, the service principal's own role. Off, the scope is exactly what
     * {@link #Given_the_realm_file_When_read_Then_each_client_is_scoped_to_only_the_roles_it_needs()}
     * declares. {@code KeycloakRealmIT} proves both against the running image.
     */
    @Test
    void Given_both_clients_When_read_Then_neither_is_allowed_the_realms_full_scope() {
        for (String clientId : List.of("finassist-gateway", "finassist-assistant")) {
            assertThat(client(realm(), clientId).path("fullScopeAllowed").asBoolean(true))
                    .as("fullScopeAllowed on %s", clientId)
                    .isFalse();
        }
    }

    /**
     * The other half: with full scope off, a role reaches a token only if it is named here. The
     * gateway issues the browser's tokens, so it needs the two roles a person can hold; the
     * assistant issues only its own service-account token, so it needs one. The sets are closed,
     * because a widened one would silently restore what the switch above turns off.
     */
    @Test
    void Given_the_realm_file_When_read_Then_each_client_is_scoped_to_only_the_roles_it_needs() {
        assertThat(scopedRealmRoles(realm(), "finassist-gateway")).containsExactlyInAnyOrder("owner", "viewer");
        assertThat(scopedRealmRoles(realm(), "finassist-assistant")).containsExactly("assistant");
    }

    /** The realm roles {@code scopeMappings} puts in one client's scope. */
    private static List<String> scopedRealmRoles(JsonNode realm, String clientId) {
        for (JsonNode mapping : realm.path("scopeMappings")) {
            if (clientId.equals(mapping.path("client").asString(null))) {
                return texts(mapping.path("roles"));
            }
        }
        throw new AssertionError("no scope mapping for client " + clientId + " in the realm file");
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

    /**
     * Exhaustive on purpose, like the assistant's equivalent test below: an audience mapper the
     * gateway acquires beyond these two would silently widen which service accepts a browser
     * user's token, so {@code containsExactlyInAnyOrder} closes the set rather than merely
     * checking that two named entries exist among however many there are.
     */
    @Test
    void Given_the_gateway_client_When_read_Then_it_carries_exactly_the_ledger_and_assistant_audience() {
        JsonNode gateway = client(realm(), "finassist-gateway");

        List<String> audiences = audienceMapperValues(gateway);

        assertThat(audiences).containsExactlyInAnyOrder("finassist-ledger", "finassist-assistant");
    }

    @Test
    void Given_the_assistant_client_When_read_Then_it_carries_only_the_ledger_audience() {
        JsonNode assistant = client(realm(), "finassist-assistant");

        assertThat(audienceMapperValues(assistant)).containsExactly("finassist-ledger");
    }

    private static List<String> audienceMapperValues(JsonNode client) {
        return client.path("protocolMappers")
                .valueStream()
                .filter(m ->
                        "oidc-audience-mapper".equals(m.path("protocolMapper").asString()))
                .map(m -> m.path("config").path("included.custom.audience").asString())
                .toList();
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

    /**
     * The realm's placeholders against the environment Compose actually gives Keycloak.
     *
     * <p>Keycloak does not refuse an import over a missing variable: it imports the literal,
     * unresolved {@code ${GATEWAY_CLIENT_SECRET}} text, neither blank nor a failure (observed
     * against the real image, and recorded in {@link KeycloakRealmIT}'s javadoc). So a placeholder
     * renamed or added here without a matching entry on the {@code keycloak} service becomes a
     * client secret, or the owner's initial password, with a value anyone reading this repository
     * can type.
     *
     * <p>Nothing else could catch that. {@link KeycloakRealmIT} supplies all of them itself as
     * container environment, and the assertions above check only that each is bare. This is a
     * comparison between two files in the repository and needs no container at all.
     *
     * <p>Compose's own {@code :?} on the three secrets is the control that stops a variable being
     * <em>unset</em> at run time; this is the control that stops one being <em>unmentioned</em>,
     * which {@code :?} cannot see.
     */
    @Test
    void Given_the_realm_file_When_compared_with_compose_Then_every_placeholder_it_names_is_given_to_keycloak() {
        Set<String> named = placeholdersNamedByTheRealm();

        assertThat(named)
                .as("the realm does use placeholders, so this comparison is not vacuous")
                .isNotEmpty();
        assertThat(keycloakServiceEnvironmentKeys())
                .as("environment the keycloak service is given in %s", COMPOSE_FILE)
                .containsAll(named);
    }

    /** Every distinct {@code ${VAR}} the realm file refers to, in a stable order for a failure message. */
    private static Set<String> placeholdersNamedByTheRealm() {
        Set<String> names = new TreeSet<>();
        Matcher references = PLACEHOLDER_REFERENCE.matcher(rawText());
        while (references.find()) {
            names.add(references.group(1));
        }
        return names;
    }

    /**
     * The environment variable names the {@code keycloak} service declares. Parsed as YAML rather
     * than matched by text, because the block also carries folded scalars and quoted values that a
     * line-wise reading would misread.
     */
    private static Set<String> keycloakServiceEnvironmentKeys() {
        String directory = System.getProperty(REPO_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(REPO_DIR_PROPERTY
                    + " is not set. Failsafe and surefire set it in pom.xml; run this test through Maven, or"
                    + " pass -D" + REPO_DIR_PROPERTY + "=<repository>.");
        }
        Path file = Path.of(directory, COMPOSE_FILE).toAbsolutePath().normalize();
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Object> compose = new Yaml().load(reader);
            Map<String, Object> services = asMap(compose.get("services"), "services");
            Map<String, Object> keycloak = asMap(services.get(KEYCLOAK_SERVICE), KEYCLOAK_SERVICE);
            return asMap(keycloak.get("environment"), KEYCLOAK_SERVICE + ".environment")
                    .keySet();
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node, String what) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new AssertionError(COMPOSE_FILE + " has no mapping at " + what);
        }
        return (Map<String, Object>) map;
    }
}
