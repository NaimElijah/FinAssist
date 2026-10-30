package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.WaitingConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

/**
 * Builds {@code ops/keycloak/Dockerfile} and runs the real Keycloak image against the real
 * {@code finassist-realm.json}, on a plain PostgreSQL, entirely through Testcontainers - a
 * random, ephemeral network and containers this JVM starts and stops, never the Compose project
 * a developer or the owner runs by hand (ADR-025's model, applied to an identity provider rather
 * than a database).
 *
 * <p>This is the one test in this build that proves the realm file the way it must be
 * proved: by having Keycloak actually import it, rather than by asserting shapes
 * {@link KeycloakRealmTest} reads statically. What a static read cannot see is checked here:
 *
 * <ul>
 *   <li>{@code KC_HOSTNAME_BACKCHANNEL_DYNAMIC} does not leak the backchannel address into
 *       {@code iss} - the discovery document's issuer is asserted to be the <em>public</em> URL,
 *       even though this test necessarily reaches Keycloak on a random Testcontainers-mapped
 *       port rather than the fixed one production uses.
 *   <li>the assistant's service-account token actually carries the claims ADR-032 requires:
 *       {@code iss}, {@code aud=[finassist-ledger]}, {@code roles} holding {@code assistant} and
 *       nothing else, a {@code sub}, and an RS256 {@code kid} that is genuinely present in the
 *       realm's JWKS.
 *   <li>each client's scope, as Keycloak computes it: full scope off, and only the roles that
 *       client's own tokens need. The file says what was asked for; this says what was built.
 *   <li>the realm's own {@code admin-cli} entry takes the place of the one Keycloak creates in
 *       every realm, so a password grant through it is refused, and an offline session is capped at
 *       the browser session's maximum.
 *   <li>Keycloak honours the owner's pinned {@code id}, which is what makes the {@code sub} in
 *       every journal row survive a re-import (ADR-031, ADR-033).
 *   <li>the realm's sign-in protection as Keycloak holds it after the import: no self-registration,
 *       no password reset, no remember-me, no event listener, the password policy and the
 *       brute-force thresholds; the owner's forced password change and TOTP enrolment; and the
 *       gateway client's two audiences, both as its mappers and as the access token Keycloak would
 *       issue the owner through it. A key Keycloak ignored or renamed on import would leave
 *       {@link KeycloakRealmTest} green and the realm at Keycloak's own default. For {@code
 *       registrationAllowed} that default is off, so a lost key would be harmless; for {@code
 *       bruteForceProtected} it is off too, where the realm asks for on, so a lost key would
 *       silently remove the protection.
 * </ul>
 *
 * <p>Every realm placeholder is supplied here as a container environment variable, because the
 * realm has no defaults and Keycloak does not refuse an import over a missing one: it imports
 * the literal, unresolved placeholder text, neither blank nor a failure (observed against the real
 * image when the realm was first drafted). The variable that matters for a secret is instead
 * enforced one layer up, by {@code :?} in docker-compose.yml, which this test cannot exercise
 * because it never goes through Compose.
 *
 * <p><b>What this deliberately does not prove: a real browser user token for the gateway.</b>
 * The assistant's service-account token is easy to mint here because its client permits
 * {@code client_credentials} - the gateway client correctly permits none of that, only the
 * standard authorization-code flow, which needs a login form this test does not drive. So
 * {@code basic}'s presence on the gateway's own tokens is checked only statically, in
 * {@link KeycloakRealmTest}'s {@code defaultClientScopes} assertion, never against a real image
 * the way the assistant's is. Its role scope is checked here through Keycloak's own admin API
 * instead of through an issued token, and so are its audiences and roles, in the example access
 * token that API builds for the owner, which is the closest this test can get. That a real browser
 * sign-in against the gateway produces a usable {@code sub} is checked by hand, not by this test
 * or any other: the owner's first sign-in enrols TOTP (the README's "First sign-in").
 */
class KeycloakRealmIT {

    private static final String OPS_DIR_PROPERTY = "finassist.ops.dir";

    private static final String PUBLIC_URL = "http://localhost:8081";
    private static final String PUBLIC_ISSUER = PUBLIC_URL + "/realms/finassist";

    private static final String GATEWAY_CLIENT_SECRET = "it-gateway-secret";
    private static final String ASSISTANT_CLIENT_SECRET = "it-assistant-secret";
    private static final String OWNER_USERNAME = "it-owner";

    /** The realm file pins this id so the owner's {@code sub} survives a re-import (ADR-031). */
    private static final String OWNER_USER_ID = "6c4a4be0-7c38-4145-845f-f4311ef64202";

    private static final String OWNER_INITIAL_PASSWORD = "it-owner-initial-pw-12chars";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    /** The redirect the gateway client registers, from the {@code GATEWAY_PUBLIC_URL} given below. */
    private static final String GATEWAY_REDIRECT_URI = "http://localhost:5173/bff/login/oauth2/code/keycloak";

    /**
     * Any well-formed S256 challenge: the sign-in below fails at the password, so no code is ever
     * exchanged against it. This one is RFC 7636's own example.
     */
    private static final String PKCE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    /** The login page's form, whose action carries the authentication session's parameters. */
    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");

    /** Keycloak's own message for a wrong username or password, in its default English theme. */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password.";

    /** How long the control sign-in's log line may take to reach the container's log. */
    private static final int CONTROL_LINE_WAIT_SECONDS = 30;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Network network;
    private static GenericContainer<?> db;
    private static GenericContainer<?> keycloak;

    @BeforeAll
    static void startKeycloakAgainstItsOwnRealm() {
        network = Network.newNetwork();

        db = new GenericContainer<>(DockerImageName.parse("postgres:17.11-trixie"))
                .withNetwork(network)
                .withNetworkAliases("keycloak-db")
                .withEnv(Map.of(
                        "POSTGRES_DB", "keycloak",
                        "POSTGRES_USER", "keycloak",
                        "POSTGRES_PASSWORD", "it-db-password"))
                // Twice: the image's entrypoint first starts a temporary server to run its init
                // scripts, which logs this line, then stops it and starts the real one. Waiting for
                // the first lets Keycloak connect into that restart. Testcontainers' own
                // PostgreSQLContainer waits for two for the same reason.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
        db.start();

        Path opsKeycloakDir = Path.of(opsDir(), "keycloak").toAbsolutePath().normalize();
        ImageFromDockerfile image = new ImageFromDockerfile().withFileFromPath(".", opsKeycloakDir);

        keycloak = new GenericContainer<>(image)
                .withNetwork(network)
                .withExposedPorts(8080, 9000)
                .withEnv("KC_HOSTNAME", PUBLIC_URL)
                .withEnv("KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true")
                .withEnv("KC_HTTP_ENABLED", "true")
                .withEnv("KC_DB", "postgres")
                .withEnv("KC_DB_URL", "jdbc:postgresql://keycloak-db:5432/keycloak")
                .withEnv("KC_DB_USERNAME", "keycloak")
                .withEnv("KC_DB_PASSWORD", "it-db-password")
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "it-admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "it-admin-password")
                .withEnv("GATEWAY_CLIENT_SECRET", GATEWAY_CLIENT_SECRET)
                .withEnv("ASSISTANT_CLIENT_SECRET", ASSISTANT_CLIENT_SECRET)
                .withEnv("GATEWAY_PUBLIC_URL", "http://localhost:5173")
                .withEnv("FINASSIST_OWNER_USERNAME", OWNER_USERNAME)
                .withEnv("FINASSIST_OWNER_INITIAL_PASSWORD", OWNER_INITIAL_PASSWORD)
                .waitingFor(Wait.forHttp("/health/ready")
                        .forPort(9000)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();
    }

    @AfterAll
    static void stopEverything() {
        if (keycloak != null) {
            keycloak.stop();
        }
        if (db != null) {
            db.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static String opsDir() {
        String directory = System.getProperty(OPS_DIR_PROPERTY);
        if (directory == null) {
            throw new IllegalStateException(OPS_DIR_PROPERTY
                    + " is not set. Failsafe sets it in pom.xml; run this test through Maven, or pass -D"
                    + OPS_DIR_PROPERTY + "=<repository>/ops.");
        }
        return directory;
    }

    private static String baseUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    private static JsonNode getJson(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode discoveryDocument() throws Exception {
        return getJson("/realms/finassist/.well-known/openid-configuration");
    }

    private static JsonNode jwks() throws Exception {
        return getJson("/realms/finassist/protocol/openid-connect/certs");
    }

    /** The client as Keycloak holds it after the import, not as the realm file spells it. */
    private static JsonNode importedClient(String clientId) throws Exception {
        return adminGet("/admin/realms/finassist/clients?clientId=" + clientId).get(0);
    }

    /**
     * Every realm role inside {@code clientId}'s scope, composites expanded — Keycloak's own
     * answer to "which of this realm's roles may reach a token this client is issued".
     *
     * <p>With {@code fullScopeAllowed} off, this set is exactly what the {@code roles} claim can
     * carry, whatever roles the user happens to hold. It is read from the running server rather
     * than from the file because the file states an intent and this states the result.
     */
    private static List<String> realmRolesInScopeOf(String clientId) throws Exception {
        String clientUuid = importedClient(clientId).path("id").asString();
        return adminGet("/admin/realms/finassist/clients/" + clientUuid + "/scope-mappings/realm/composite")
                .valueStream()
                .map(role -> role.path("name").asString())
                .toList();
    }

    /** A bootstrap-admin token from the master realm, for the admin API calls above. */
    private static String adminToken() throws Exception {
        String body = "grant_type=password&client_id=admin-cli&username=it-admin&password=it-admin-password";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/realms/master/protocol/openid-connect/token"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("admin token response").isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    private static JsonNode adminGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + adminToken())
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("GET %s -> %s", path, response.body())
                .isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** The id Keycloak actually gave the owner on import, which {@link #OWNER_USER_ID} pins. */
    private static String importedOwnerId() throws Exception {
        return adminGet("/admin/realms/finassist/users?exact=true&username=" + OWNER_USERNAME)
                .get(0)
                .path("id")
                .asString();
    }

    /** The assistant's own credential to Keycloak, never the owner's - ADR-032's service principal. */
    private static String assistantClientCredentialsToken() throws Exception {
        String body =
                "grant_type=client_credentials&client_id=finassist-assistant&client_secret=" + ASSISTANT_CLIENT_SECRET;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/realms/finassist/protocol/openid-connect/token"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("client_credentials token response")
                .isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    private static JsonNode jwtSegment(String token, int index) {
        String[] parts = token.split("\\.");
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64Url(parts[index]));
        return JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private static String padBase64Url(String segment) {
        int remainder = segment.length() % 4;
        return remainder == 0 ? segment : segment + "====".substring(remainder);
    }

    @Test
    void Given_the_running_realm_When_the_discovery_document_is_read_Then_the_issuer_is_the_public_url()
            throws Exception {
        JsonNode discovery = discoveryDocument();

        assertThat(discovery.path("issuer").asString()).isEqualTo(PUBLIC_ISSUER);
    }

    @Test
    void
            Given_the_assistants_service_account_When_it_requests_a_client_credentials_token_Then_it_carries_the_agreed_claims()
                    throws Exception {
        String token = assistantClientCredentialsToken();
        JsonNode header = jwtSegment(token, 0);
        JsonNode payload = jwtSegment(token, 1);

        assertThat(header.path("alg").asString()).isEqualTo("RS256");
        assertThat(payload.path("iss").asString()).isEqualTo(PUBLIC_ISSUER);
        assertThat(audience(payload)).containsExactly("finassist-ledger");
        assertThat(roles(payload)).contains("assistant");
        assertThat(payload.path("sub").asString()).isNotBlank();
    }

    /**
     * Least privilege in the claim itself. Keycloak gives every user, service accounts included,
     * the composite {@code default-roles-finassist}, which carries {@code offline_access} and
     * {@code uma_authorization} — and a client whose {@code fullScopeAllowed} is left at its
     * default puts all of them in the {@code roles} claim of every token it issues.
     *
     * <p>Nothing in FinAssist grants anything for those roles, so no permission is at stake. What
     * is at stake is the claim's meaning: a token should say what its bearer may do here and
     * nothing else, so that a future consumer cannot be tempted to read a role this product never
     * defined. The set is closed with {@code containsExactly} rather than checked for the three
     * names, so a fourth role reaching the claim fails this too.
     */
    @Test
    void Given_the_assistants_token_When_its_roles_are_read_Then_they_are_only_the_role_the_realm_granted_it()
            throws Exception {
        JsonNode payload = jwtSegment(assistantClientCredentialsToken(), 1);

        assertThat(roles(payload)).containsExactly("assistant");
    }

    /**
     * Least privilege as the running server computes it, and the half that could break a sign-in.
     *
     * <p>With {@code fullScopeAllowed} off, a role reaches a token only if the realm's {@code
     * scopeMappings} put it in that client's scope. Get the gateway's wrong and every browser
     * sign-in still succeeds while carrying no role at all — the owner signed in and refused by
     * both services, which is the worst failure this realm can produce. No token for the owner can
     * be minted here (its client permits only the authorization-code flow, which needs a login
     * form), so Keycloak's own view of the scope is the evidence instead of an issued token.
     */
    @Test
    void Given_the_realm_as_imported_When_each_clients_scope_is_read_Then_it_holds_only_that_clients_own_roles()
            throws Exception {
        assertThat(realmRolesInScopeOf("finassist-gateway")).containsExactlyInAnyOrder("owner", "viewer");
        assertThat(realmRolesInScopeOf("finassist-assistant")).containsExactly("assistant");
    }

    /**
     * The switch that makes the scope above mean anything. Left at its default of {@code true},
     * every role in the realm reaches every token the client issues and the mappings are ignored.
     */
    @Test
    void Given_the_realm_as_imported_When_each_client_is_read_Then_neither_has_full_scope() throws Exception {
        assertMember(importedClient("finassist-gateway"), "fullScopeAllowed", BooleanNode.FALSE);
        assertMember(importedClient("finassist-assistant"), "fullScopeAllowed", BooleanNode.FALSE);
    }

    /**
     * The claim the Dockerfile makes for pinning the owner's {@code id}: that a re-import gives
     * the owner back the same {@code sub}, so journal rows written under it still name someone
     * Keycloak knows (ADR-031, ADR-033). Only a real import can say whether Keycloak honours an
     * {@code id} in the file at all.
     */
    @Test
    void Given_the_realm_as_imported_When_the_owner_is_looked_up_Then_keycloak_kept_the_pinned_id() throws Exception {
        assertThat(importedOwnerId()).isEqualTo(OWNER_USER_ID);
    }

    @Test
    void Given_the_assistants_token_When_its_key_id_is_read_Then_it_is_present_in_the_realms_jwks() throws Exception {
        String token = assistantClientCredentialsToken();
        String kid = jwtSegment(token, 0).path("kid").asString();

        List<String> signingKeyIds = jwks().path("keys")
                .valueStream()
                .filter(key -> "RS256".equals(key.path("alg").asString(null)))
                .map(key -> key.path("kid").asString())
                .toList();

        assertThat(kid).isNotBlank();
        assertThat(signingKeyIds).contains(kid);
    }

    /** The realm as Keycloak holds it after the import, read through the admin API. */
    private static JsonNode importedRealm() throws Exception {
        return adminGet("/admin/realms/finassist");
    }

    /**
     * The member, read as exactly the given node: a boolean must be a JSON boolean and a number a
     * JSON number, so a member Keycloak dropped on import, which reads as missing, cannot pass for
     * {@code false} or {@code 0}.
     */
    private static void assertMember(JsonNode holder, String member, JsonNode expected) {
        assertThat(holder.path(member)).as("%s as Keycloak holds it", member).isEqualTo(expected);
    }

    /**
     * Nobody but the owner, and the people the owner adds by hand, may ever hold an account here
     * (ADR-031). Each switch is read as Keycloak stored it, because a misspelt or renamed key in
     * the file is not an import error: Keycloak drops it and keeps its own default.
     */
    @Test
    void
            Given_the_realm_as_imported_When_its_sign_in_switches_are_read_Then_registration_reset_and_remember_me_are_off()
                    throws Exception {
        JsonNode realm = importedRealm();

        assertMember(realm, "registrationAllowed", BooleanNode.FALSE);
        assertMember(realm, "resetPasswordAllowed", BooleanNode.FALSE);
        assertMember(realm, "rememberMe", BooleanNode.FALSE);
    }

    /**
     * The property the empty listener list stands for, read where it would show: a failed sign-in
     * through the login form leaves the username it was given out of Keycloak's log. The page's own
     * error message proves the attempt reached the credential check, so the absence is not for
     * want of a sign-in.
     *
     * <p>The log is read only once a later control line has reached it: a failed sign-in to {@code
     * master}, which keeps Keycloak's default listener. Keycloak writes the two in order, so a line
     * from the first attempt cannot still be on its way when the second has arrived. Should {@code
     * master} ever log no sign-in event either, the wait fails rather than the check passing.
     */
    @Test
    void Given_a_failed_sign_in_through_the_login_form_When_the_log_is_read_Then_the_username_is_absent()
            throws Exception {
        String marker = "it-marker-username-7qz41";
        String control = "it-control-username-3vk82";
        WaitingConsumer log = new WaitingConsumer();
        keycloak.followOutput(log);

        HttpResponse<String> answer = failedFormSignIn(marker);
        failedMasterPasswordGrant(control);
        log.waitUntil(frame -> frame.getUtf8String().contains(control), CONTROL_LINE_WAIT_SECONDS, TimeUnit.SECONDS);

        assertThat(answer.statusCode()).as("the login form's answer").isEqualTo(200);
        assertThat(answer.body()).contains(INVALID_CREDENTIALS_MESSAGE);
        assertThat(keycloak.getLogs()).doesNotContain(marker);
    }

    /** A password grant to {@code master} for a user that does not exist, refused as such. */
    private static void failedMasterPasswordGrant(String username) throws Exception {
        String body = "grant_type=password&client_id=admin-cli&username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=it-wrong-password-for-the-control";
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/realms/master/protocol/openid-connect/token"))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(JSON.readTree(response.body()).path("error").asString())
                .as("the control sign-in's refusal")
                .isEqualTo("invalid_grant");
    }

    /**
     * Opens the gateway client's login page and submits its form with {@code username} and a wrong
     * password. The JDK's {@code CookieManager} sends no {@code Secure} cookie over plain HTTP,
     * which Keycloak's are, so the page's cookies are carried by hand; and the form's action names
     * the public URL, so it is replayed against the mapped port instead.
     */
    private static HttpResponse<String> failedFormSignIn(String username) throws Exception {
        String query = "client_id=finassist-gateway&response_type=code&scope=openid&state=it"
                + "&code_challenge_method=S256&code_challenge=" + PKCE_CHALLENGE
                + "&redirect_uri=" + URLEncoder.encode(GATEWAY_REDIRECT_URI, StandardCharsets.UTF_8);
        HttpResponse<String> page = CLIENT.send(
                HttpRequest.newBuilder(
                                URI.create(baseUrl() + "/realms/finassist/protocol/openid-connect/auth?" + query))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).as("the login page").isEqualTo(200);
        Matcher form = LOGIN_FORM_ACTION.matcher(page.body());
        assertThat(form.find()).as("the login page holds its form").isTrue();
        URI action = URI.create(form.group(1).replace("&amp;", "&"));
        String cookies = String.join(
                "; ",
                page.headers().allValues("set-cookie").stream()
                        .map(cookie -> cookie.split(";", 2)[0])
                        .toList());
        String credentials = "credentialId=&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode("it-wrong-password-for-the-marker", StandardCharsets.UTF_8);
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + action.getRawPath() + "?" + action.getRawQuery()))
                        .timeout(HTTP_TIMEOUT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Cookie", cookies)
                        .POST(HttpRequest.BodyPublishers.ofString(credentials))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Brute-force protection as the running server applies it. {@code permanentLockout} is pinned
     * off with the rest because the realm has a single owner: a permanent lockout after five wrong
     * guesses would lock that owner out until someone with the admin password intervened.
     */
    @Test
    void Given_the_realm_as_imported_When_its_brute_force_settings_are_read_Then_they_hold_the_agreed_thresholds()
            throws Exception {
        JsonNode realm = importedRealm();

        assertMember(realm, "bruteForceProtected", BooleanNode.TRUE);
        assertMember(realm, "permanentLockout", BooleanNode.FALSE);
        assertMember(realm, "maxTemporaryLockouts", IntNode.valueOf(0));
        assertMember(realm, "failureFactor", IntNode.valueOf(5));
        assertMember(realm, "waitIncrementSeconds", IntNode.valueOf(60));
        assertMember(realm, "quickLoginCheckMilliSeconds", IntNode.valueOf(1000));
        assertMember(realm, "minimumQuickLoginWaitSeconds", IntNode.valueOf(60));
        assertMember(realm, "maxFailureWaitSeconds", IntNode.valueOf(900));
        assertMember(realm, "maxDeltaTimeSeconds", IntNode.valueOf(43200));
    }

    @Test
    void Given_the_realm_as_imported_When_its_password_policy_is_read_Then_it_is_the_agreed_rule() throws Exception {
        assertMember(
                importedRealm(),
                "passwordPolicy",
                StringNode.valueOf("length(12) and notUsername and notEmail and passwordHistory(3)"));
    }

    /**
     * Keycloak's default listener, {@code jboss-logging}, writes sign-in events to the container
     * log. An empty list in the file must reach the running realm as empty, not be dropped on
     * import in favour of that default.
     */
    @Test
    void Given_the_realm_as_imported_When_its_event_listeners_are_read_Then_there_are_none() throws Exception {
        assertMember(importedRealm(), "eventsListeners", JSON.createArrayNode());
    }

    /**
     * Keycloak creates its own {@code admin-cli} in every new realm, password grant on. Only a real
     * import can say whether the realm file's entry replaces that default or is set aside in its
     * favour, and there must be exactly one client by that name either way.
     */
    @Test
    void Given_the_realm_as_imported_When_admin_cli_is_read_Then_it_is_the_realms_own_entry_with_every_flow_off()
            throws Exception {
        JsonNode adminClis = adminGet("/admin/realms/finassist/clients?clientId=admin-cli");

        assertThat(adminClis.size()).as("clients named admin-cli").isEqualTo(1);
        JsonNode adminCli = adminClis.get(0);
        assertMember(adminCli, "directAccessGrantsEnabled", BooleanNode.FALSE);
        assertMember(adminCli, "standardFlowEnabled", BooleanNode.FALSE);
        assertMember(adminCli, "implicitFlowEnabled", BooleanNode.FALSE);
        assertMember(adminCli, "serviceAccountsEnabled", BooleanNode.FALSE);
        assertMember(adminCli, "fullScopeAllowed", BooleanNode.FALSE);
    }

    /**
     * The behaviour the switch above stands for. The owner's correct initial password is sent, so
     * the only ground left for a refusal is the client itself.
     */
    @Test
    void Given_admin_cli_in_the_finassist_realm_When_it_asks_for_a_password_grant_Then_keycloak_refuses_the_client()
            throws Exception {
        String body = "grant_type=password&client_id=admin-cli&username=" + OWNER_USERNAME + "&password="
                + OWNER_INITIAL_PASSWORD;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/realms/finassist/protocol/openid-connect/token"))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as("password grant through admin-cli").isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asString()).isEqualTo("unauthorized_client");
    }

    /**
     * Read back because Keycloak's default is no absolute expiry at all: a key dropped on import
     * would leave an offline token renewable for as long as it is used.
     */
    @Test
    void
            Given_the_realm_as_imported_When_its_offline_session_settings_are_read_Then_they_have_the_browser_sessions_cap()
                    throws Exception {
        JsonNode realm = importedRealm();

        assertMember(realm, "offlineSessionMaxLifespanEnabled", BooleanNode.TRUE);
        assertMember(realm, "offlineSessionMaxLifespan", IntNode.valueOf(36000));
    }

    /**
     * The owner's first sign-in replaces the initial password, which {@code .env} holds in plain
     * text, and enrols TOTP. Keycloak runs exactly the required actions the user carries, so this
     * set, as stored, is what that first sign-in will demand.
     */
    @Test
    void Given_the_owner_as_imported_When_its_required_actions_are_read_Then_a_password_change_and_totp_are_forced()
            throws Exception {
        JsonNode owner = adminGet("/admin/realms/finassist/users?exact=true&username=" + OWNER_USERNAME)
                .get(0);

        assertThat(owner.path("requiredActions").valueStream().map(JsonNode::asString))
                .containsExactlyInAnyOrder("UPDATE_PASSWORD", "CONFIGURE_TOTP");
    }

    /**
     * The audiences the gateway's tokens carry, as mappers Keycloak holds. The set is closed: one
     * more audience would make a browser user's token acceptable to one more service.
     */
    @Test
    void
            Given_the_gateway_client_as_imported_When_its_audience_mappers_are_read_Then_they_name_the_ledger_and_assistant()
                    throws Exception {
        String clientUuid = importedClient("finassist-gateway").path("id").asString();

        List<String> audiences = adminGet("/admin/realms/finassist/clients/" + clientUuid + "/protocol-mappers/models")
                .valueStream()
                .filter(mapper -> "oidc-audience-mapper"
                        .equals(mapper.path("protocolMapper").asString(null)))
                .map(mapper ->
                        mapper.path("config").path("included.custom.audience").asString())
                .toList();

        assertThat(audiences).containsExactlyInAnyOrder("finassist-ledger", "finassist-assistant");
    }

    /**
     * The nearest this test gets to a real browser token for the gateway (see the class
     * javadoc): Keycloak's own example access token for the owner through the gateway client,
     * which its admin API builds by running the same mappers and scope a sign-in would. It carries
     * both audiences, and only the owner's own role.
     */
    @Test
    void Given_the_owner_and_the_gateway_client_When_keycloak_builds_an_example_token_Then_it_names_both_audiences()
            throws Exception {
        String clientUuid = importedClient("finassist-gateway").path("id").asString();

        JsonNode token = adminGet("/admin/realms/finassist/clients/" + clientUuid
                + "/evaluate-scopes/generate-example-access-token?scope=openid&userId=" + OWNER_USER_ID);

        assertThat(audience(token)).containsExactlyInAnyOrder("finassist-ledger", "finassist-assistant");
        assertThat(roles(token)).containsExactly("owner");
    }

    /** The claim is a bare JSON array of role names, not Keycloak's nested {@code realm_access} shape (ADR-032). */
    private static List<String> roles(JsonNode payload) {
        return payload.path("roles").valueStream().map(JsonNode::asString).toList();
    }

    /** A single-audience token still deserialises {@code aud} as a JSON string, not an array. */
    private static List<String> audience(JsonNode payload) {
        JsonNode aud = payload.path("aud");
        return aud.isArray() ? aud.valueStream().map(JsonNode::asString).toList() : List.of(aud.asString());
    }
}
