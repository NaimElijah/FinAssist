package io.github.naimelijah.finassist.gateway.composition;

import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * The OAuth2 error codes this gateway may write to a log as themselves (ADR-030): every log line
 * about a failed sign-in or a failed relay authorization names its error code through {@link
 * #loggableCode}, and nothing else of the error.
 *
 * <p>The vocabulary is fixed here rather than trusted. A provider's error response reaches the
 * sign-in callback as its {@code error} query parameter, and the token endpoint's error body
 * reaches a refresh, and Spring carries either into the error code as it arrived, so whoever sent
 * it chose it. A code outside the vocabulary is logged as {@value #UNRECOGNISED}, never as sent.
 * Only the code is ever logged: an error's description is the provider's prose, which can quote
 * the request it refused, and in the worst case an authorization code.
 */
final class OAuth2ErrorVocabulary {

    /**
     * Spring Security's code for a token endpoint it could not use: unreachable, answering an error
     * status other than 400, or answering with no token response it can read. A refresh that fails
     * with it says the identity provider is unusable, not that one session's grant was refused.
     */
    static final String INVALID_TOKEN_RESPONSE = "invalid_token_response";

    /** Stands in for an error code outside the vocabulary, whatever it said. */
    static final String UNRECOGNISED = "unrecognised";

    /**
     * Every error code a failed sign-in or relay authorization can legitimately carry: the
     * authorization endpoint's (RFC 6749 §4.1.2.1), the token endpoint's (§5.2), OpenID Connect's
     * authentication errors (Core §3.1.2.6), and the codes Spring Security 7.1.2 raises itself
     * while completing a sign-in or authorizing a relay, read from its {@code oauth2-client}
     * sources.
     */
    private static final Set<String> REGISTERED = Set.of(
            // RFC 6749 §4.1.2.1 and §5.2
            "invalid_request",
            "unauthorized_client",
            "access_denied",
            "unsupported_response_type",
            "invalid_scope",
            "server_error",
            "temporarily_unavailable",
            "invalid_client",
            "invalid_grant",
            "unsupported_grant_type",
            // OpenID Connect Core 1.0 §3.1.2.6
            "interaction_required",
            "login_required",
            "account_selection_required",
            "consent_required",
            "invalid_request_uri",
            "invalid_request_object",
            "request_not_supported",
            "request_uri_not_supported",
            "registration_not_supported",
            // Spring Security's own, raised at the callback
            "authorization_request_not_found",
            "client_registration_not_found",
            "invalid_state_parameter",
            INVALID_TOKEN_RESPONSE,
            "invalid_id_token",
            "invalid_nonce",
            "missing_signature_verifier",
            "invalid_user_info_response",
            "missing_user_info_uri",
            "missing_user_name_attribute",
            // Spring Security's own, raised when a session holds no authorized client to relay with
            "client_authorization_required");

    private OAuth2ErrorVocabulary() {}

    /**
     * The code to log for an OAuth2 error.
     *
     * @param error the error a failure carries
     * @return its code when the vocabulary holds it, compared exactly, else {@value #UNRECOGNISED}
     */
    static String loggableCode(OAuth2Error error) {
        String code = error.getErrorCode();
        return REGISTERED.contains(code) ? code : UNRECOGNISED;
    }
}
