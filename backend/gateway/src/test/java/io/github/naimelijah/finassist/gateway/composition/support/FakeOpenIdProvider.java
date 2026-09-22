package io.github.naimelijah.finassist.gateway.composition.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal, real OpenID Connect provider for {@code GatewayLoginFlowIT}: a JDK {@link
 * HttpServer} answering the authorization, token and JWK-set endpoints Keycloak would, with a
 * genuine RSA-signed ID token so the gateway's own signature and claim validation runs for real
 * rather than through a mock.
 *
 * <p>Endpoints are served at the exact paths {@code KeycloakRegistration} builds against a
 * configured issuer base — {@code /realms/finassist/protocol/openid-connect/{auth,token,certs}}
 * — spelled out here rather than imported, matching this project's own convention of not
 * borrowing production constants for a wire-level test double (see {@code GatewaySecurityTest}):
 * a change to the real path would then have to break this fake too, rather than silently agree
 * with a mistake.
 *
 * <p><b>What a test can steer, and what it always gets:</b>
 *
 * <ul>
 *   <li>{@link #setIssuerOverride(String)} — embeds a different {@code iss} claim, for the
 *       "foreign issuer" sign-in failure.
 *   <li>{@link #setAccessTokenExpiresInSeconds(long)} — a negative value mints an
 *       already-expired access token, so the very next relay must refresh it first.
 *   <li>{@link #refuseNextRefresh()} — the next {@code refresh_token} grant is answered
 *       {@code invalid_grant}, once, then reverts to succeeding.
 *   <li>{@link #lastAuthorizationRequestHadPkce()} and {@link
 *       #lastTokenRequestHadCodeVerifier()} — whether the two halves of PKCE actually arrived.
 *   <li>{@link #lastAccessToken()}, {@link #lastRefreshToken()} and {@link #lastIdToken()} — the
 *       exact values last issued, for the log sweep to search for.
 *   <li>{@link #stall(Endpoint)} and {@link #releaseStalledRequests()} — the endpoint accepts the
 *       connection and then never answers, which is what an unbounded HTTP client hangs on
 *       forever (see {@code GatewayKeycloakTimeoutIT}).
 * </ul>
 *
 * <p>Each exchange is handled on its own thread, so one stalled request does not also stop the
 * endpoints a test still needs while it waits.
 */
public final class FakeOpenIdProvider implements AutoCloseable {

    /** Matches {@code KeycloakRegistration}'s {@code CLIENT_ID}, spelled out rather than shared (see class doc). */
    private static final String CLIENT_ID = "finassist-gateway";

    private static final String REALM_PATH = "/realms/finassist";
    private static final String OIDC_PATH = REALM_PATH + "/protocol/openid-connect";
    private static final String SUBJECT = "2b9d1f4e-0000-4000-8000-0123456789ab";
    private static final String KEY_ID = "fake-idp-key";
    private static final int RSA_KEY_SIZE_BITS = 2048;

    /**
     * How long a stalled handler waits before giving up on its own. Far beyond any budget a test
     * asserts, so the stall is what the client gives up on — but finite, so a test that forgets
     * {@link #releaseStalledRequests()} leaks a thread for this long rather than for ever.
     */
    private static final Duration STALL_LIMIT = Duration.ofMinutes(2);

    private static final JsonMapper JSON = JsonMapper.shared();

    private final HttpServer server;
    private final ExecutorService exchanges = Executors.newCachedThreadPool();
    private final RSAKey rsaKey;
    private final Map<String, String> nonceByCode = new ConcurrentHashMap<>();
    private final Set<Endpoint> stalled = ConcurrentHashMap.newKeySet();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>(new CountDownLatch(1));

    private final AtomicReference<String> lastAccessToken = new AtomicReference<>();
    private final AtomicReference<String> lastRefreshToken = new AtomicReference<>();
    private final AtomicReference<String> lastIdToken = new AtomicReference<>();
    private volatile boolean lastAuthorizationRequestHadPkce;
    private volatile boolean lastTokenRequestHadCodeVerifier;

    private volatile String issuerOverride;
    private volatile long accessTokenExpiresInSeconds = 300;
    private volatile boolean refuseNextRefreshOnce;

    public FakeOpenIdProvider() throws JOSEException, IOException {
        this.rsaKey = new RSAKeyGenerator(RSA_KEY_SIZE_BITS)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(exchanges);
        server.createContext(OIDC_PATH + "/auth", this::handleAuthorize);
        server.createContext(OIDC_PATH + "/token", this::handleToken);
        server.createContext(OIDC_PATH + "/certs", this::handleCerts);
        server.start();
    }

    /** The endpoints a test can make stall: the ones the gateway itself calls over the backchannel. */
    public enum Endpoint {
        /** Both the authorization-code exchange and the {@code refresh_token} grant. */
        TOKEN,
        /** The JWK set the ID token's signature is verified against. */
        CERTS
    }

    /** The base URL both {@code GATEWAY_OIDC_PUBLIC_URL} and {@code GATEWAY_OIDC_INTERNAL_URL} are pointed at in the IT. */
    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** The default issuer this fake signs tokens for: its own base plus the realm path. */
    public String defaultIssuer() {
        return baseUrl() + REALM_PATH;
    }

    /** Every subsequent ID token carries this {@code iss} instead of {@link #defaultIssuer()}, until cleared with {@code null}. */
    public void setIssuerOverride(String issuer) {
        this.issuerOverride = issuer;
    }

    /** A negative value mints an access token already expired by the time it is issued. */
    public void setAccessTokenExpiresInSeconds(long seconds) {
        this.accessTokenExpiresInSeconds = seconds;
    }

    /** The next {@code refresh_token} grant fails with {@code invalid_grant}; the one after succeeds. */
    public void refuseNextRefresh() {
        this.refuseNextRefreshOnce = true;
    }

    /**
     * Clears a pending {@link #refuseNextRefresh()} that no refresh ever consumed. The flag
     * otherwise self-clears only when a refresh actually arrives, so a test that sets it and
     * then fails — or simply never triggers a refresh — would leak it into whichever test runs
     * next against this shared instance.
     */
    public void stopRefusingRefresh() {
        this.refuseNextRefreshOnce = false;
    }

    /**
     * Every subsequent request to {@code endpoint} accepts the connection and then waits, writing
     * no status line at all, until {@link #releaseStalledRequests()} or {@link #STALL_LIMIT}.
     *
     * <p>This is the failure a connect timeout cannot catch and a read timeout must: the TCP
     * handshake completes, so the client is not refused — it simply waits for bytes that never
     * come, which is what a paused or wedged Keycloak looks like from the gateway.
     */
    public void stall(Endpoint endpoint) {
        stalled.add(endpoint);
    }

    /**
     * Lets every stalled request finish and stops stalling new ones, so the next test starts on a
     * fake that answers. Idempotent, and safe to call when nothing is stalled.
     */
    public void releaseStalledRequests() {
        stalled.clear();
        release.getAndSet(new CountDownLatch(1)).countDown();
    }

    /** Blocks this exchange until the test releases it, or {@link #STALL_LIMIT} passes. */
    private void awaitRelease() throws IOException {
        try {
            release.get().await(STALL_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("the stalled exchange was interrupted", interrupted);
        }
    }

    public boolean lastAuthorizationRequestHadPkce() {
        return lastAuthorizationRequestHadPkce;
    }

    public boolean lastTokenRequestHadCodeVerifier() {
        return lastTokenRequestHadCodeVerifier;
    }

    public String lastAccessToken() {
        return lastAccessToken.get();
    }

    public String lastRefreshToken() {
        return lastRefreshToken.get();
    }

    public String lastIdToken() {
        return lastIdToken.get();
    }

    @Override
    public void close() {
        releaseStalledRequests();
        server.stop(0);
        exchanges.shutdownNow();
    }

    private void handleAuthorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseFormEncoded(exchange.getRequestURI().getRawQuery());
        lastAuthorizationRequestHadPkce =
                query.get("code_challenge") != null && "S256".equals(query.get("code_challenge_method"));
        String code = "fake-auth-code-" + UUID.randomUUID();
        nonceByCode.put(code, query.get("nonce"));
        String location =
                query.get("redirect_uri") + "?code=" + urlEncode(code) + "&state=" + urlEncode(query.get("state"));
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        if (stalled.contains(Endpoint.TOKEN)) {
            awaitRelease();
            return;
        }
        Map<String, String> form =
                parseFormEncoded(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String grantType = form.get("grant_type");
        if ("authorization_code".equals(grantType)) {
            handleAuthorizationCodeGrant(exchange, form);
        } else if ("refresh_token".equals(grantType)) {
            handleRefreshTokenGrant(exchange);
        } else {
            respondJson(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private void handleAuthorizationCodeGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        String verifier = form.get("code_verifier");
        lastTokenRequestHadCodeVerifier = verifier != null && !verifier.isBlank();
        String nonce = nonceByCode.remove(form.get("code"));
        issueTokens(exchange, nonce);
    }

    private void handleRefreshTokenGrant(HttpExchange exchange) throws IOException {
        if (refuseNextRefreshOnce) {
            refuseNextRefreshOnce = false;
            respondJson(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token revoked\"}");
            return;
        }
        // No id_token on a refresh response: RefreshTokenOAuth2AuthorizedClientProvider never
        // asks for one, so there is nothing here to validate a nonce or an issuer against.
        issueTokens(exchange, null);
    }

    private void issueTokens(HttpExchange exchange, String nonce) throws IOException {
        String accessToken = "fake-access-token-" + UUID.randomUUID();
        String refreshToken = "fake-refresh-token-" + UUID.randomUUID();
        lastAccessToken.set(accessToken);
        lastRefreshToken.set(refreshToken);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenExpiresInSeconds);
        response.put("refresh_token", refreshToken);
        response.put("scope", "openid profile");
        if (nonce != null) {
            String idToken = signIdToken(nonce);
            lastIdToken.set(idToken);
            response.put("id_token", idToken);
        }
        respondJson(exchange, 200, JSON.writeValueAsString(response));
    }

    private String signIdToken(String nonce) throws IOException {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuerOverride != null ? issuerOverride : defaultIssuer())
                    .subject(SUBJECT)
                    .audience(CLIENT_ID)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", nonce)
                    .claim("preferred_username", "owner")
                    .claim("roles", List.of("owner"))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (JOSEException signingFailed) {
            throw new IOException("the fake IdP could not sign its ID token", signingFailed);
        }
    }

    private void handleCerts(HttpExchange exchange) throws IOException {
        if (stalled.contains(Endpoint.CERTS)) {
            awaitRelease();
            return;
        }
        Map<String, Object> jwkSet = new com.nimbusds.jose.jwk.JWKSet(rsaKey.toPublicJWK()).toJSONObject();
        respondJson(exchange, 200, JSON.writeValueAsString(jwkSet));
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parseFormEncoded(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
