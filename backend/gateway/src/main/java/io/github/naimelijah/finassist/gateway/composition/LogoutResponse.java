package io.github.naimelijah.finassist.gateway.composition;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code POST /bff/logout}: where the browser must go next to end Keycloak's session
 * as well as the gateway's.
 *
 * <p>JSON rather than a {@code 302}, for the same reason an unauthenticated request gets a 401
 * problem: the web client signs out with {@code fetch}, and a redirect would send that {@code
 * fetch} to Keycloak instead of the browser (ADR-030). By the time this is written the gateway's
 * own session is already gone; navigating to the URL is what ends the one at the identity
 * provider, and a client that ignores it is signed out of FinAssist either way.
 *
 * <p>Its one member is always present, and marked required (ADR-029) for the same reason as
 * {@link SessionResponse}'s: {@link GatewayOpenApiConfiguration} resolves this record's schema by
 * hand, because springdoc never sees this endpoint, and the resolved schema carries this
 * annotation into the contract's {@code required} list exactly as it would from a real controller.
 *
 * @param logoutUrl Keycloak's absolute end-session URL, carrying {@code id_token_hint},
 *     {@code client_id} and the public post-logout redirect URI
 */
record LogoutResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String logoutUrl) {}
