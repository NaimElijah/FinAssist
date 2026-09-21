package io.github.naimelijah.finassist.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds {@code ops/keycloak/Dockerfile} and runs the real Keycloak image against the real
 * {@code finassist-realm.json}, on a plain PostgreSQL, entirely through Testcontainers - a
 * random, ephemeral network and containers this JVM starts and stops, never the Compose project
 * a developer or the owner runs by hand (ADR-025's model, applied to an identity provider rather
 * than a database).
 *
 * <p>This is the one test in this build that proves the realm file the way it must be
 * proved: by having Keycloak actually import it, rather than by asserting shapes
 * {@link KeycloakRealmTest} reads statically. Two things a static read cannot see are checked
 * here:
 *
 * <ul>
 *   <li>{@code KC_HOSTNAME_BACKCHANNEL_DYNAMIC} does not leak the backchannel address into
 *       {@code iss} - the discovery document's issuer is asserted to be the <em>public</em> URL,
 *       even though this test necessarily reaches Keycloak on a random Testcontainers-mapped
 *       port rather than the fixed one production uses (R2 in the plan's risk list).
 *   <li>the assistant's service-account token actually carries the claims ADR-032 requires:
 *       {@code iss}, {@code aud=[finassist-ledger]}, {@code roles} containing {@code assistant},
 *       a {@code sub}, and an RS256 {@code kid} that is genuinely present in the realm's JWKS.
 * </ul>
 *
 * <p>Every realm placeholder is supplied here as a container environment variable, because the
 * realm has no defaults and Keycloak does not refuse an import over a missing one - see the task
 * report for what was actually observed (a missing variable is imported as the literal,
 * unresolved placeholder text, not blank and not a failure). The variable that matters for a
 * secret is instead enforced one layer up, by {@code :?} in docker-compose.yml, which this test
 * cannot exercise because it never goes through Compose.
 *
 * <p><b>What this deliberately does not prove: a real browser user token for the gateway.</b>
 * The assistant's service-account token is easy to mint here because its client permits
 * {@code client_credentials} - the gateway client correctly permits none of that, only the
 * standard authorization-code flow, which needs a login form this test does not drive. So
 * {@code basic}'s presence on the gateway's own tokens is checked only statically, in
 * {@link KeycloakRealmTest}'s {@code defaultClientScopes} assertion, never against a real image
 * the way the assistant's is. The end-to-end proof that a real browser sign-in against the
 * gateway actually produces a usable {@code sub} is the plan's final, manual sign-in check
 * (R7), not this test.
 */
class KeycloakRealmIT {

    private static final String OPS_DIR_PROPERTY = "finassist.ops.dir";

    private static final String PUBLIC_URL = "http://localhost:8081";
    private static final String PUBLIC_ISSUER = PUBLIC_URL + "/realms/finassist";

    private static final String GATEWAY_CLIENT_SECRET = "it-gateway-secret";
    private static final String ASSISTANT_CLIENT_SECRET = "it-assistant-secret";
    private static final String OWNER_USERNAME = "it-owner";
    private static final String OWNER_INITIAL_PASSWORD = "it-owner-initial-pw-12chars";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

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
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 1));
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
