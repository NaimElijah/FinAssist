# ADR-030: OIDC authentication through a backend-for-frontend gateway

- **Status:** Accepted, extended by [ADR-037](ADR-037-an-unusable-key-set-is-an-outage.md),
  [ADR-053](ADR-053-the-gateways-outbound-calls-speak-http-1-1-under-one-time-budget.md),
  [ADR-057](ADR-057-a-page-on-another-localhost-port-is-treated-as-hostile.md) and
  [ADR-058](ADR-058-each-service-bounds-what-one-request-can-cost.md), corrected by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md). Two
  details were corrected by what was built, both described as built in
  `docs/architecture/architecture.md`: the `key_source` log level, and the gateway's unreachable
  `NOT_FOUND`. ADR-047 adds that `/error` is no public path and that the realm declares no client
  scope.
- **Date:** 2026-09-16

Supersedes [ADR-027](ADR-027-defer-authentication-and-bind-to-loopback.md). This record replaces
ADR-027's decision to have no authentication, and keeps its loopback binding, Host allow-lists and
JSON-only writes as defence in depth. It extends three records:

- [ADR-026](ADR-026-web-client-calls-both-services-through-one-origin.md): the dev proxy now sends
  every call to the gateway.
- [ADR-028](ADR-028-http-wire-conventions.md): this record adds new problem codes.
- [ADR-029](ADR-029-web-client-stack.md): the web client also generates types from the gateway's
  contract.

The identity provider is [ADR-031](ADR-031-keycloak-as-the-identity-provider.md). What each
principal may do is [ADR-032](ADR-032-role-based-authorization-per-service.md).

## Context

ADR-027 was accepted on 2026-09-14. It deferred authentication and relied on three controls
instead:

- every service binds loopback;
- a Host allow-list defeats DNS rebinding;
- writes accept only `application/json`.

It named four revisit triggers: a port reachable from anywhere other than the owner's machine, a
chosen deployment target, a journal that needs an actor, and a second person using FinAssist.

**None of those triggers has fired.** Every port is still published on loopback only. No
deployment target has been chosen (#61). FinAssist still has one owner and one dataset. On
2026-09-16 the owner decided to build authentication and authorisation to a professional standard
anyway. This record does not claim that the threat model ADR-027 wrote down now demands it; it
does not. The work is built ahead of its trigger, deliberately, and the cost of that is stated
below rather than hidden.

**What "a professional standard" means here:**

- a real sign-in, through OIDC;
- access and refresh tokens that never reach the browser;
- two services that validate a token on every request and deny by default;
- role-based permissions;
- a journal that records who wrote each row.

**The journal follows from this decision; it did not drive it.**
[ADR-020](ADR-020-audit-trail-follows-persistence.md) and
[ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) both name "authentication is
decided" as a trigger. This record fires that trigger. The actor column is recorded as an
extension of those two records, together with the migration that adds it.

**A browser is the hardest client to give a token to.** Any script running on the page can read a
token held by a single-page application, whether it sits in memory or in storage. That was one of
ADR-027's reasons for rejecting a static token in `.env`. The IETF OAuth working group's guidance
for browser-based applications describes a backend-for-frontend, which keeps every token on a
server and gives the browser only a session cookie, as the most secure of its architectures.

**ADR-026 anticipated this.** It listed "two auth stories" as a cost, and its first revisit trigger
is *"Authentication arrives, and terminating it once becomes cheaper than terminating it at both
services."* ADR-026 also rejected two things that this record now adds, so its reasons need an
answer:

- **The assistant as the backend-for-frontend.** ADR-026's reasons still hold, and this record
  does not make the assistant the gateway.
- **A reverse-proxy container, "production topology chosen with no deployment target".** The
  gateway is not a guess at production topology. It exists because a server has to hold the
  tokens, and in development no other process can hold them.

**Keycloak runs under Compose, so it has two addresses** (ADR-031):

- the browser reaches it at `http://localhost:8081`;
- containers reach it at `http://keycloak:8080`.

A token's `iss` claim can name only one of them, and every service must agree on which.

**Until now, the ledger has never needed a clock.** A lifecycle date arrives in the request body
(ADR-028), and no ledger class reads the time. Token validation needs "now".

**[ADR-019](ADR-019-opentelemetry-and-structured-logging.md) exempted the Java services from a
redaction boundary**, because at the time the only Java service held no secret and called no
third-party API. A gateway that holds a client secret and every user's tokens does not fit that
premise.

**[ADR-008](ADR-008-backend-frontend-root-layout.md) has its own trigger.** It says to flatten the
root layout once a third deployable appears that is neither backend nor frontend. The gateway is a
server process that sits in front of the two backend services, so it is a backend deployable and
belongs in `backend/gateway`. Keycloak is not this repository's code: its image definition and its
realm sit under `ops/keycloak/`, beside the other infrastructure configuration (ADR-031). The
trigger does not fire.

## Decision

### The shape

```
Browser ─► Vite :5173 ─(/ledger, /assistant, /bff)─► gateway :8090
            gateway: OIDC code+PKCE login, FINASSIST_SESSION cookie, CSRF (spa), TokenRelay,
                     strips Cookie + X-FINASSIST-XSRF-TOKEN before relaying
              ├─► ledger :8080     resource server — ledger:read / ledger:write
              └─► assistant :8000  resource server — portfolio:read
Keycloak: browser-facing http://localhost:8081, backchannel http://keycloak:8080
          own Postgres service keycloak-db (no published port)
assistant ─(future #62: client_credentials, role assistant, aud finassist-ledger)─► ledger
```

**Authentication is always on, and no switch turns it off.** No profile, environment variable or
property disables it, in any service or environment. A switch that exists gets left on somewhere.
Tests mint real tokens against a local key set instead.

**Authentication and authorisation happen in different places.**

- The gateway authenticates the browser, and it grants nothing.
- Each service authenticates the bearer token it receives, and decides what that token may do from
  its own role table (ADR-032).

### The gateway

**A new deployable, `backend/gateway`, is the backend-for-frontend.**

- **Stack.** It is built on Spring Cloud Gateway Server Web MVC, from the Spring Cloud 2025.1
  release train, on the same Spring Boot 4.1 as the ledger
  ([ADR-002](ADR-002-java-spring-boot-for-the-ledger.md)).
- **Why this dependency.** It replaces a hand-written reverse proxy, which would have to handle
  headers, timeouts and token relay itself. It is Apache-2.0 licensed and maintained by the Spring
  team. Spring Security's OAuth2 client can sign a user in and keep the tokens, but it does not
  proxy a request.
- **Servlet, not reactive.** The Server Web MVC variant was chosen over the WebFlux one so that
  both Java deployables share one programming model.

**It signs the browser in with the OIDC authorization code flow, with PKCE (S256).** It is the
confidential client `finassist-gateway`, and it authenticates to Keycloak with
`client_secret_basic`. After sign-in, the browser holds exactly two things from the gateway:

- **`FINASSIST_SESSION`**, the session cookie: `HttpOnly`, `SameSite=Lax`, with `Secure` taken
  from `GATEWAY_COOKIE_SECURE`, and a 30-minute idle timeout;
- **a CSRF token**, described below.

**It relays GET and POST requests under `/ledger/api/v1/**` and `/assistant/api/v1/**`.** For each
relayed request it:

- strips the first path segment;
- removes the `Cookie` and `X-FINASSIST-XSRF-TOKEN` headers, so neither the session cookie nor
  the CSRF token reaches a service or its logs;
- adds `Authorization: Bearer <access token>`, refreshing an expired access token first.

**A failed relay is still a problem response.** If the refresh fails, the gateway ends the session
and answers 401. If a service is unreachable, it answers 502 `UPSTREAM_UNAVAILABLE`. A problem
returned by the service passes through unchanged.

**The gateway authorises nothing beyond "has a session".** Relaying never restates a DTO, so each
screen still talks to the service that owns its data, as ADR-026 decided. Everything that is
neither relayed nor listed below is denied.

**Everything the gateway serves itself lives under `/bff/`.** The dev proxy forwards three
anchored prefixes, and one prefix for the gateway's own endpoints means they can never collide
with a relayed path.

| Endpoint | Purpose |
|---|---|
| `/bff/oauth2/authorization/keycloak` | Login entry. The browser navigates here. |
| `/bff/login/oauth2/code/keycloak` | Callback from Keycloak. |
| `GET /bff/me` | `{subject, name, roles}` for the signed-in user. |
| `POST /bff/logout` | `200 {logoutUrl}`. |

**A request that needs a session and has none gets a 401 problem, never a 302.** If a `fetch`
followed a redirect to a login page, it would receive HTML, or a CORS failure, instead of a status
it can act on. Login starts only when the browser navigates to the login entry. No login page is
generated, and the gateway does not remember which request needed a login.

**Redirects:**

- after sign-in, the browser always lands on `GATEWAY_PUBLIC_URL/`;
- a failed sign-in lands on `GATEWAY_PUBLIC_URL/?signin=failed`, and only the OAuth2 error code is
  logged.

**Every redirect the gateway issues is absolute**, built from `GATEWAY_PUBLIC_URL`, which defaults
to `http://localhost:5173`. Nothing depends on forwarded headers, and `trusted-proxies` stays
unset, because a forwarded header says only what its sender claims.

**Logout returns JSON, not a redirect, for the same reason as the 401.** The gateway:

1. ends its session and deletes `FINASSIST_SESSION`;
2. answers with Keycloak's end-session URL, carrying `id_token_hint`, the public post-logout URI
   and `client_id`.

The browser then navigates to that URL, which ends the Keycloak session too.

**CSRF uses Spring Security's single-page-application mode, with renamed names.**

- The token travels in a script-readable cookie, `FINASSIST-XSRF-TOKEN`.
- It is sent back in the header `X-FINASSIST-XSRF-TOKEN` on every write.
- A missing or wrong token is answered 403 `CSRF_TOKEN_INVALID`.
- Both names are renamed from Spring's defaults because cookies ignore ports. Every application on
  `localhost` shares one cookie jar, so the defaults (`XSRF-TOKEN`, `JSESSIONID`) would collide
  with those of other local applications.

**The gateway holds secrets, so ADR-019's premise for the Java services does not cover it.** It
holds:

- `GATEWAY_CLIENT_SECRET`, which has no default, so the gateway refuses to start without it;
- every signed-in user's access, refresh and ID tokens, in its session store.

The gateway therefore logs no request header, cookie, token, session id or secret. It logs only
OAuth2 error codes and reason categories. A test drives a full sign-in and relay, captures the
logs, and asserts that none of those values appears in them. The gateway exposes only Actuator's
`health` endpoint.

**Sessions live in memory, inside the gateway process.** One instance serves one owner, so a
shared session store would have nothing to share.

**Refresh-token rotation is off.** The accounts screen sends one balance request per account, in
parallel (ADR-028), and Spring does not serialise refreshes for the same session. With rotation
on, two concurrent refreshes would present the same refresh token, the second would be refused,
and the owner would be signed out at random.

### The issuer, and where each URL points

**The issuer is always the public URL**, `http://localhost:8081/realms/finassist`. Keycloak is
configured with `KC_HOSTNAME` set to the public URL and with `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`, so
containers can reach it by its service name while `iss` stays public (ADR-031).

**No service runs OIDC discovery at start-up.** A service that runs discovery cannot start while
Keycloak is down, and the URLs it learns depend on the host name it asked by. Every URL is
configured explicitly instead:

- **Ledger and assistant:** the expected issuer, plus the *internal* JWKS URI they fetch signing
  keys from.
- **Gateway:** its `ClientRegistration` is built in Java.
  - The authorization and end-session URIs are public, because the browser navigates to them.
  - The token and JWK set URIs use the backchannel.
  - It has no user-info URI; the claims it needs come from the ID token.
  - Its `issuerUri` is the public issuer.

### Token validation

**Both services validate a token the same way:**

- the signature is RS256, and no other algorithm is accepted;
- `iss` is the public issuer;
- `aud` contains the service's own audience, `finassist-ledger` or `finassist-assistant`;
- `exp`, `nbf` and `iat` are checked against an injected clock, with 30 seconds of leeway;
- `sub`, `iss`, `aud`, `exp` and `iat` are all required.

Audiences, the realm name, RS256 and the leeway are constants, not settings.

**Each service reads bearer tokens only, never a cookie.** The ledger's security chain is
stateless, with CSRF, HTTP Basic and form login all disabled. Neither service knows the browser
or the session exists.

**A refused token is a 401, and only its reason is logged.**

- The response is `UNAUTHENTICATED`, with `WWW-Authenticate: Bearer error="invalid_token"`.
- A request that sent no token at all gets a bare `Bearer` challenge.
- The service logs a WARN line naming one reason category: `missing`, `malformed`, `rejected`,
  `lifetime`, `issuer`, `audience`, `subject`, `unknown_key` or `key_source`.
- The token and its claims are never logged.

**If the key source is unreachable, the answer is 503 `AUTHENTICATION_UNAVAILABLE`, not 401.** A
service that cannot fetch signing keys cannot tell a good token from a bad one, and the failure is
not the caller's.

**The clock is passed in, at the token as everywhere else.**

- **Ledger.** The ledger gets its first `Clock` bean, in `composition`, and it feeds
  `JwtTimestampValidator#setClock`. It is used only to validate tokens. Lifecycle dates still come
  from request bodies (ADR-028), and no use case or domain function receives the clock.
- **Assistant.** The assistant injects `utc_now` into its token verifier. PyJWT's own time checks
  are switched off, and the verifier repeats them against the injected clock.
- **Why.** This keeps the "pass the clock in" rule, and it lets a test pin a token exactly at the
  edge of the leeway.

**One user access token carries both audiences.** The gateway's client scope adds
`finassist-ledger` and `finassist-assistant`, so one token serves both relays. The assistant's own
service-account token carries `finassist-ledger` only (ADR-032).

### What stays public

| Service | Public without a token or session |
|---|---|
| Ledger | `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, `/actuator/metrics/**`, `/v3/api-docs` and `/v3/api-docs.yaml`, `/error` |
| Assistant | `/health`, `/ready`, `/openapi.json` |
| Gateway | `/bff/oauth2/**`, `/bff/login/**`, `/actuator/health/**` |

Everything else is denied unless a rule admits it. On the ledger, that includes HEAD and OPTIONS.

**Actuator stays on the ledger's main port, and those four endpoints stay public.**

- They carry no business data.
- The Compose Prometheus scrapes them, and it holds no credential.
- A separate management port was rejected. Boot 4.1 serves that port from a child management
  context, which `HostAllowListFilter` does not cover, so Actuator would lose its DNS-rebinding
  control.
- The exposure list is unchanged, and `/actuator/env` stays unexposed (ADR-019).

**The API documents stay public too.** Both contracts are already committed in this repository,
so hiding the copy a service serves would protect nothing.

### Defence in depth, carried forward from ADR-027

Each of these controls is cheap and independent of the token, so a defect in one control does not
open the whole system.

- **Loopback by default.** The ledger, the assistant, the gateway (`GATEWAY_ADDRESS`) and Vite all
  bind `127.0.0.1`.
  - Inside a container, a process binds `0.0.0.0`.
  - Compose publishes every port on `127.0.0.1` only.
  - `keycloak-db` publishes no port at all.
- **Host allow-lists on all three services.**
  - The gateway's list is `GATEWAY_ALLOWED_HOSTS`, enforced by a deliberate copy of the ledger's
    `HostAllowListFilter`, because two deployables share no code.
  - The filter is registered first, and Spring Security runs after it.
  - A DNS-rebound page carries none of the owner's cookies, because the browser keys cookies by
    host name, so it cannot use the owner's session. The allow-list still keeps it away from the
    public endpoints and the login entry.
- **Writes accept only `application/json`.** The gateway's CSRF token is the primary control
  against cross-site writes. The services never read a cookie, so a cross-site form cannot carry a
  credential to them anyway. The media-type check still costs nothing and stays.

**There is no TLS while everything is on loopback.**

- `GATEWAY_COOKIE_SECURE` defaults to `false`, because Safari refuses a `Secure` cookie over plain
  HTTP. It must be set to `true` behind TLS.
- Keycloak's realm requires TLS only for external addresses (`sslRequired: external`, ADR-031).
- Loopback is what makes plain HTTP acceptable. A port reachable from a network would carry tokens
  and cookies in clear text.

### Failure codes

This extends ADR-028's error table. Every new code uses the same problem+json shape and the same
stable `code` member, and no response ever echoes a token.

| Code | Status | Returned by | Headers |
|---|---|---|---|
| `UNAUTHENTICATED` | 401 | all three services | ledger and assistant: `WWW-Authenticate: Bearer`, or `Bearer error="invalid_token"` when a token was sent |
| `FORBIDDEN` | 403 | all three services | ledger and assistant: `WWW-Authenticate: Bearer error="insufficient_scope"` |
| `CSRF_TOKEN_INVALID` | 403 | gateway | — |
| `AUTHENTICATION_UNAVAILABLE` | 503 | ledger, assistant | — (the JWKS source is unreachable) |
| `UPSTREAM_UNAVAILABLE` | 502 | gateway | — |

When the gateway answers a refusal of its own, it uses the codes the ledger already uses for the
same situation: `HOST_NOT_ALLOWED` for an unlisted host, `NOT_FOUND` for an unknown route, and
`INTERNAL_ERROR` for an unexpected failure.

### Contracts, and the dev proxy

**Extends ADR-029: the gateway's contract is generated like the other two.**

- springdoc generates `contracts/gateway-v1.openapi.json`, and a drift test built like
  `LedgerContractIT` pins it.
- The document describes the session cookie and the CSRF header as security schemes, and
  describes `/bff/me` and `/bff/logout`.
- The web client generates its session types from it, alongside the other two contracts.
- The ledger's and the assistant's contracts each gain a `bearerAuth` scheme and a global security
  requirement.

**Extends ADR-026: the dev proxy now sends everything to the gateway.**

- Vite forwards `/ledger`, `/assistant` and `/bff` to `http://127.0.0.1:8090`, without rewriting
  the path. The gateway strips `/ledger` and `/assistant` when it relays.
- The browser still makes no cross-origin call, and no service has any CORS configuration.
- ADR-026's first revisit trigger is answered this way: the browser's session ends at one place,
  the gateway, while each service still validates the token it receives and keeps its own
  contract.
- The built bundle still has no server (#61).

### Configuration

Each new variable is added to `.env.example` and the README's Configuration table in the same
commit that first reads it.

| Service | Variables |
|---|---|
| Gateway | `GATEWAY_PORT` (8090), `GATEWAY_ADDRESS` (127.0.0.1), `GATEWAY_ALLOWED_HOSTS`, `GATEWAY_PUBLIC_URL`, `GATEWAY_OIDC_PUBLIC_URL`, `GATEWAY_OIDC_INTERNAL_URL`, `GATEWAY_LEDGER_URI`, `GATEWAY_ASSISTANT_URI`, `GATEWAY_COOKIE_SECURE` (false), `GATEWAY_CLIENT_SECRET` |
| Ledger | `LEDGER_OIDC_ISSUER`, `LEDGER_OIDC_JWKS_URI` |
| Assistant | `ASSISTANT_OIDC_ISSUER`, `ASSISTANT_OIDC_JWKS_URI` |

Keycloak's own variables are listed in ADR-031.

### What the assistant presents to the ledger

When the assistant calls the ledger, it authenticates with the client credentials grant, as the
`finassist-assistant` client, and holds only read access (ADR-032). This answers the question
ADR-027 deferred. The client that makes the call is #62, and it is not built by this decision.

## Consequences

**Makes easy**

- No access or refresh token ever reaches the browser. A script injected into the page can make
  requests while the session lasts, but it cannot carry a credential away.
- One sign-in serves both services, and each service still decides for itself what the caller may
  do.
- Each service validates a standard JWT and knows nothing about sessions or browsers. The future
  assistant-to-ledger call therefore uses the same validation path as the browser's calls.
- The journal can now record who. ADR-020's trigger has fired, and the actor is a `sub` that a
  real sign-in produced.
- Still one origin in development, and still no CORS configuration anywhere.

**Makes hard**

- **More to run.** There is a third deployable, and two more containers (Keycloak and its
  database) in the inner loop. A business endpoint cannot be reached without Keycloak running and
  a signed-in session, and a hand-written `curl` now needs a token.
- **The app must be opened at the origin `GATEWAY_PUBLIC_URL` names**, which is
  `http://localhost:5173` by default. Browsers key cookies by host name, and `127.0.0.1` and
  `localhost` are different hosts to a browser. A sign-in started on one therefore returns to the
  other with no session, and fails.
- **A wrong `GATEWAY_PUBLIC_URL` breaks sign-in** instead of quietly working, because every
  redirect is built from it.
- **Existing `.env` files lack the new secrets.** Compose refuses to start and names each missing
  variable.
- **Clock drift beyond 30 seconds**, which WSL2 is known for, makes a valid token look expired or
  not yet valid.
- **Two copies to keep in step.** The gateway's `HostAllowListFilter` and its problem writer are
  copies of the ledger's, and each copy is pinned by its own tests.
- **Every endpoint test must now present a token**, and every `@WebMvcTest` slice must import the
  security configuration.

**Accepted cost**

- **The threat model does not demand this yet.** It is built ahead of every trigger ADR-027 named,
  and it adds a deployable, an identity provider and a login to a product with one user.
- **One user access token carries both audiences.** A token stolen from one service's traffic is
  accepted by the other.
- **Sessions live in memory.** Restarting the gateway signs everyone out.
- **Refresh tokens are not rotated.** A stolen refresh token stays usable until it expires or the
  Keycloak session ends.
- **The ID token appears as `id_token_hint` in the logout URL**, so it reaches the browser's
  history and Keycloak's access log. No service accepts it as a credential: its audience is the
  gateway client, and each service requires its own audience.
- **There is no TLS.** Anything able to read loopback traffic on the owner's machine can read the
  tokens. That is the same one-machine threat model ADR-027 stated.
- **Actuator's public metrics are readable by any local process.** They show request counts and
  timings, and no business data.

## Alternatives considered

**Keep ADR-027, with no authentication.** Its reasoning still matches the threat model. Rejected
because the owner decided on 2026-09-16 to build authentication now.

**Tokens in the single-page application** (a public client with PKCE, holding its tokens in memory
or storage). No gateway would be needed. Rejected: any script on the page could read the tokens,
refresh tokens included, and the browser is the one place this record exists to keep them out of.

**A static bearer token in `.env`.** Rejected, for ADR-027's reasons: it names one identity for
every request, and the browser would have to hold it.

**The assistant as the backend-for-frontend.** Rejected, for ADR-026's reasons, which still hold:
the assistant would become a gateway for traffic that has nothing to do with assistance.

**A session in each service.** Rejected: two session stores, two login flows and two CSRF defences.
The services would stop being stateless, and the future service-to-service call would need an
authentication path of its own.

**A configuration-only proxy, such as oauth2-proxy.** Rejected: its failure responses and its
session endpoint have their own shapes rather than ADR-028's problem+json. Its behaviour is
configuration that can be tested only by running the container. A Spring gateway, by contrast, is
code tested with the same suites and toolchain as the ledger.

**Spring Cloud Gateway on WebFlux.** Rejected: a second programming model beside the servlet-based
ledger, for no need that the servlet variant fails to meet.

**A separate management port for Actuator.** Rejected: Boot 4.1's child management context would
bypass `HostAllowListFilter`.

**A shared session store now, such as Spring Session over Redis.** Rejected: there is one gateway
instance, so the store would have nothing to share.

**Refresh-token rotation.** Rejected: parallel requests would race to refresh, and the owner would
be signed out at random.

**TLS on loopback, with a local certificate authority.** Rejected: every machine would need
certificates generated and trusted, for traffic that never leaves the machine.

**An "auth off" switch for development.** Rejected permanently: a switch that exists gets left on
somewhere. Tests mint real tokens instead.

**Token exchange for each hop** (RFC 8693), so that each service receives a token for itself
alone. Rejected for now: the cost of one token carrying both audiences is accepted above, and
exchange adds a round trip to Keycloak, plus a second token per service to cache and refresh.

## Revisit when

- **A deployment target is chosen, TLS is added, or anything other than the owner's machine can
  reach a port** (#61). With a network in the path, several choices change together:
  - `GATEWAY_COOKIE_SECURE` becomes `true`;
  - the public Actuator and API-document paths are reconsidered, and so is a management port;
  - whatever serves the built bundle has to sit in front of, or beside, the gateway.
- **A second gateway instance is needed.** The answer then is Spring Session, with a shared store.
- **Token exchange is needed**: a service calls another on a user's behalf, or one service must
  stop accepting a token meant for the other.
- **Refreshes can be serialised within one session.** Refresh-token rotation can then be turned
  on.
