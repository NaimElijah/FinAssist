# ADR-053: The gateway's outbound calls speak HTTP/1.1, under one time budget

- **Status:** Accepted
- **Date:** 2026-10-29

Extends [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md). ADR-030 names "headers,
timeouts and token relay" as what the gateway dependency replaces, and lists the two headers the
relay removes. It does not record how the gateway's own outbound calls behave, or that it removes
two more headers. Four fixes between 2026-09-22 and 2026-10-09 settled both, and only code
comments and `backend/gateway/CLAUDE.md` hold the reasons.

## Context

**The gateway makes four kinds of outbound call, and each failed in a way nothing in the design
predicted** ([#339](https://github.com/NaimElijah/FinAssist/issues/339)). They are the relay to the
ledger or the assistant, and three calls to Keycloak: the authorization-code exchange, the
`refresh_token` grant, and the JWK-set fetch that verifies an ID token.

**The JDK client offered an h2c upgrade on every request, and uvicorn refused it.**
(`5c731ea`, 2026-09-22.)

- The `HttpClient` under Boot defaults to HTTP/2. Over cleartext that means an upgrade offer on
  every request: `Upgrade: h2c`, an `HTTP2-Settings` header, and a `Connection` header naming both.
- Tomcat ignores them, so the relay to the ledger worked.
- The assistant runs uvicorn on h11, which answers 400 to an upgrade request that carries a body.
  So every POST relayed to the assistant failed, and pricing a disposal was unusable from the web
  client.
- Nothing in front of any of these services speaks TLS, so HTTP/2 was never reachable. The offer
  was cost with no benefit.

**Boot has no property for the HTTP version.** `spring.http.clients.*` carries `connect-timeout`,
`read-timeout`, `redirects`, `cookie-handling`, `ssl.bundle`, `imperative.factory` and
`reactive.connector`, and none of them names one. The lever Boot publishes is a
`ClientHttpRequestFactoryBuilderCustomizer` bean.

**Three of the four calls were unbounded** (`6217273`, 2026-09-22).

- Spring Cloud Gateway Server Web MVC has no timeout property of its own. Its `RestClient` is
  Boot's, so `spring.http.clients.*` is the only lever, and the relay gets it for free.
- Spring Security builds its clients for the other three from a static factory that never sees
  `spring.http.clients.*`. A Keycloak that accepted a connection and then stopped answering would
  have parked a Tomcat thread for ever, and a refresh happens on a request a browser is waiting on.

**The relay passed on two browser headers it should have removed.**

- **`Authorization`** (`3c9f8e3`, 2026-09-23). `TokenRelay` sets the bearer header, so a
  browser-supplied one is overwritten on the ordinary path. But the same filter falls through to
  the unmodified request when no authorized client exists, and on that path a header the browser
  chose would reach the service untouched.
- **`HTTP2-Settings`** (`9e91d4c`, 2026-10-09, #98). Spring Cloud Gateway's hop-by-hop filter
  strips `Upgrade` and `Connection` but not this one. Nothing upstream acts on it alone.

ADR-030 (lines 145-147) records only `Cookie` and `X-FINASSIST-XSRF-TOKEN`. No other ADR records
any of this.

## Decision

**Every outbound call the gateway makes speaks HTTP/1.1.** `GatewayConfiguration`'s
`relayHttpVersion` customizer pins the JDK client's version. The relay's `RestClient` and the
`RestTemplateBuilder` behind the ID-token JWK fetch both build from the factory builder it
customizes.

**Every outbound call runs under one time budget**: `spring.http.clients.connect-timeout: 5s` and
`read-timeout: 10s` in `application.yml`.

- A relayed request that exceeds it is a 502 `UPSTREAM_UNAVAILABLE`.
- A Keycloak call that exceeds it fails the sign-in or the refresh.
- **The three Keycloak calls are built from Boot's builders, in `KeycloakRegistration`.** The
  authorization-code client and the `refresh_token` client come from `RestClient.Builder`, and the
  ID token's `JwtDecoderFactory` from `RestTemplateBuilder`. Dropping any of those beans silently
  unbounds that call.

**The relay removes four browser headers on both routes**: `Cookie`, `X-FINASSIST-XSRF-TOKEN`,
`Authorization` and `HTTP2-Settings`. This extends ADR-030's two.

**The HTTP/1.1 pin lifts when a relayed hop runs over TLS.** There HTTP/2 arrives by ALPN with no
upgrade header at all.

## Consequences

**Makes easy**

- **No upgrade offer reaches any upstream.** A POST to the assistant succeeds.
- **No call parks a thread.** One budget, in one file, bounds all four.
- **The guarantee is the gateway's own.** The header removal does not depend on a library's choice
  of `set` over `add`.
- **Each rule has a test that fails when it is lost** (below).

**Makes hard**

- **A relayed call that takes over 10 seconds is a 502**, though the service would have answered.
  The budget is global, so a slow endpoint added later must fit in it or the budget must become
  per-route.
- **The pin depends on which client Boot detects.** It is typed to the JDK builder. If an
  HttpComponents, Jetty or Reactor client ever reaches the classpath, Boot detects that one and the
  customizer stops applying. The two upgrade-offer tests turn red when it does.
- **The three Keycloak clients are rebuilt by hand.** `KeycloakRegistration` reproduces Spring
  Security's defaults, such as RS256 only and `OidcIdTokenValidator`. A Spring Security upgrade can
  change those defaults without this file noticing.

**Accepted cost**

- **One budget for every call.** The JWK fetch and a relayed request share 5 s and 10 s. No call
  has needed another yet.

## Alternatives considered

- **Leave HTTP/2 on, and fix the upstream.** Rejected: the offer cannot be accepted by anything
  here, since no hop is TLS. The pin removes a cost, and uvicorn's refusal is not the gateway's to
  change.
- **Set the version by property.** Not possible: Boot publishes none.
- **Leave the three Keycloak calls on Spring Security's defaults.** Rejected: they are unbounded,
  and a stall parks a request thread.
- **A separate budget for each call.** Rejected for now: Boot's property is global, and a per-call
  budget would need a bean for each. No call has needed one.

## Revisit when

- **A relayed hop runs over TLS.** Lift the pin. HTTP/2 then arrives by ALPN.
- **An upstream endpoint legitimately needs more than 10 seconds.** The budget becomes per-route,
  or the call becomes asynchronous.
- **Boot publishes a property for the HTTP version.** Replace the customizer with it.
- **A second HTTP client reaches the classpath.** Check that the pin still applies.

## What lands with this record

No code. Every rule above exists and has a test:

- **HTTP/1.1.** `GatewayLoginFlowIT`:
  `Given_a_signed_in_session_When_it_posts_a_body_to_the_assistant_route_Then_no_http2_upgrade_is_offered`
  and `Given_a_signed_in_session_When_it_gets_the_ledger_route_Then_no_http2_upgrade_is_offered`.
- **The relay's budget.** `GatewayRelayTimeoutIT`:
  `Given_the_ledger_accepts_a_relayed_request_and_never_answers_When_relayed_Then_the_relay_gives_up_with_502`.
- **The Keycloak budget.** `GatewayKeycloakTimeoutIT` stalls the token endpoint, the JWK set and a
  refresh in turn.
- **Header removal.** `GatewayLoginFlowIT`:
  `Given_the_shipped_relay_routes_When_their_filters_are_read_Then_each_strips_the_browsers_own_headers`
  and
  `Given_a_signed_in_session_When_a_relayed_request_carries_HTTP2_Settings_Then_neither_upstream_receives_it`.

Documentation:

- **Status line and README.** ADR-030 reads `Accepted, extended by ADR-037 and ADR-053, corrected by
  ADR-047`, with a row and a note in `docs/ADRs/README.md`.
- **`backend/gateway/CLAUDE.md`.** The two rules, the HTTP version and the time budget, cite
  ADR-053.
