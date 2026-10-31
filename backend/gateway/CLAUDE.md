# CLAUDE.md — gateway

Java 21, Spring Boot, Spring Cloud Gateway Server Web MVC. The root `.claude/CLAUDE.md` binds here
too; this file adds what binds the gateway alone.

## What it is

The backend-for-frontend (ADR-030). It signs the browser in with OIDC (code flow, PKCE) against
Keycloak, keeps the access and refresh tokens in its in-memory session, and relays `GET`/`POST`
`/ledger/api/v1/**` and `/assistant/api/v1/**` with `StripPrefix`, `RemoveRequestHeader`,
`RemoveResponseHeader` (a service's `Set-Cookie`, `Server`, `Clear-Site-Data`,
`Strict-Transport-Security` and CORS headers) and `TokenRelay` — routes declared in
`application.yml`. The browser holds only an `HttpOnly` session cookie and a CSRF token. The gateway
answers Actuator probes.

It holds no bounded context: everything is in its `composition/` root. The parts to know:

- `GatewayConfiguration` registers the Host allow-list first. `HostAllowListFilter` is a
  deliberate copy of the ledger's, configured by `GATEWAY_ALLOWED_HOSTS`. `RequestBodyLimitFilter`,
  registered after the Host allow-list and the request observation, is a copy of the ledger's too:
  a body over 64 KiB, declared or chunked, is refused 413 `CONTENT_TOO_LARGE` before the security
  chain or the relay reads it.
- `GatewaySecurityConfiguration` is the one security chain: deny by default, 401 never 302, and a
  renamed CSRF cookie and header. `HeaderOnlyCsrfTokenRequestHandler` reads the token from that
  header alone, never from a `_csrf` request parameter, which `csrf().spa()`'s own handler accepts.
- The public login entry creates a session, so `LoginEntryNavigationFilter` refuses, 403
  `FORBIDDEN`, a browser request that is not a top-level navigation (`Sec-Fetch-Mode` other than
  `navigate`, or `Sec-Fetch-Dest` other than `document`, whichever is sent) before one exists, and
  `PreSignInAuthorizationRequestRepository` gives the session it does create a fifteen-minute
  idle limit, which the sign-in success handler lifts back to `server.servlet.session.timeout`.
- `BffRoutes` holds every `/bff/` path, in one place. `SessionController` serves `GET /bff/me`,
  `SpaLogoutSuccessHandler` answers `POST /bff/logout`, and `SignInFailureHandler` sends a failed
  sign-in back to `?signin=failed`. `LoginEntryFailureHandler` answers a sign-in that cannot start
  (an unknown registration id) with the entry point's plain 401, and logs nothing for it.
- `KeycloakRegistration` builds the `ClientRegistration` by hand, with the public and backchannel
  URLs split, and the client manager `TokenRelay` uses.
- `ProblemAuthenticationEntryPoint` (401), `ProblemAccessDeniedHandler` (403),
  `ProblemRequestRejectedHandler` (400, a URL the firewall refuses), `GatewayProblems` (the problem
  codes and writer) and `GatewayRelayProblems` (a relay's failures) answer problem+json.
  `GatewayOpenApiConfiguration` shapes the committed contract. `GatewayContractIT` holds every
  operation to a summary and no tag (`springdoc.auto-tag-classes: false`), every schema to
  `"type": "object"`, and every `operationId` clear of the names the web client's generator
  derives from a schema — hence `signOut`, not `logout`, beside `LogoutResponse`.

The realm file, `ops/keycloak/finassist-realm.json`, is pinned by `KeycloakRealmTest` and
`KeycloakRealmIT` in this module; its rules are in `ops/CLAUDE.md`.

## Commands

From `backend/gateway`:

- **Run** — `./mvnw spring-boot:run`, after Keycloak is up: `make run-identity`, which is
  `docker compose up -d keycloak-db keycloak`.
- **Test** — `make test-gateway`: `./mvnw -B verify`. **Docker must be running**:
  `KeycloakRealmIT` builds `ops/keycloak/Dockerfile` and runs it against a real PostgreSQL, and
  fails rather than skips without Docker (ADR-025).
- **Lint / format** — `./mvnw -B spotless:check` / `./mvnw -B spotless:apply`. javac's own lint
  runs on every compile, main and test: `pom.xml` passes `-Xlint:all -Werror`, so any warning
  fails `test`, `verify`, `package` and the image build, while `spotless:check` alone compiles
  nothing. Fix the warning rather than excluding its category; a suppression names its category
  and says why on the line above.
- **Regenerate the contract after every endpoint change** — `./mvnw -B verify -Dtest=NONE
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GatewayContractIT` (no container needed). On a
  mismatch, copy `target/contracts/gateway-v1.openapi.json` over
  `contracts/gateway-v1.openapi.json`, then run `make generate-web-api`.

## Rules

- **The Spring Cloud starter takes its version from the `spring-cloud-dependencies` BOM**
  (`spring-cloud.version` in `pom.xml`), never a version of its own.
- **On every Spring Boot parent bump, read Boot's managed `opentelemetry.version`.** `pom.xml`
  overrides it to 1.67.0 for GHSA-ccrj-gw8r-854m, and an override lower than Boot's own
  downgrades it. Delete the property once Boot manages 1.67.0 or later (ADR-062).
- **The Maven distribution is SHA-256-pinned** by `distributionSha256Sum` in
  `.mvn/wrapper/maven-wrapper.properties`. Dependabot recomputes it when it bumps the wrapper; a
  hand edit of `distributionUrl` must update it too, or `mvnw` refuses to run.
- **A relayed method is named twice, and both must admit every operation in `contracts/`**: the
  routes' `Method=` in `application.yml` and the chain's relay matchers in
  `GatewaySecurityConfiguration`. `GatewayRelayRoutesTest` holds the routes, and
  `GatewaySecurityTest` the chain, to `ledger-v1` and `assistant-v1` (#339); a new method needs both.
- **The catch-all answers a framework refusal at its own status.**
  `GatewayRelayProblems.relayFailed` peels an `ErrorResponse` off first — 405
  `METHOD_NOT_ALLOWED`, with its `Allow` header, and 406 `NOT_ACCEPTABLE`, the only two `GET
  /bff/me` can provoke — and logs it at WARN, so ERROR still means a true 5xx. A status with no
  code in that map stays a 500 rather than being passed through under an invented one.
  `GatewaySecurityTest` and `GatewayRelayProblemsTest` fail the build if either half is lost.
- **A relay's read timeout is 504 `UPSTREAM_TIMEOUT`; a connection never opened is 502
  `UPSTREAM_UNAVAILABLE`** (ADR-067). The request was sent, so its outcome is unknown, and a 502's
  "could not be reached" would invite a resend the append-only journal keeps.
  `GatewayRelayProblems.upstreamFailed` classifies by type on the cause chain, never by message,
  and tests `HttpConnectTimeoutException` first because it extends `HttpTimeoutException`, the type
  spring-web's `JdkClientHttpRequest` raises at the read budget. `GatewayRelayTimeoutIT` and
  `GatewayRelayProblemsTest` fail the build if either is lost.
- **Every call to Keycloak is built from Boot's own HTTP builders**, in `KeycloakRegistration`: the
  authorization-code client and the `refresh_token` client from `RestClient.Builder`, and the ID
  token's `JwtDecoderFactory` from `RestTemplateBuilder`. Spring Security's defaults for all three
  build their client from a static factory that never sees `spring.http.clients.*`, so dropping any
  of these beans silently makes that call unbounded — only the relay's own client gets Boot's
  budget for free (ADR-053). `GatewayKeycloakTimeoutIT` stalls each endpoint in turn and fails the
  build if one stops giving up; `GatewayRelayTimeoutIT` does the same for the relay's own read
  budget.
- **Every outbound call speaks HTTP/1.1** (ADR-053). `GatewayConfiguration`'s `relayHttpVersion`
  customizer pins the JDK client's version, because its HTTP/2 default offers an h2c upgrade on a
  cleartext hop, and uvicorn answers 400 to an upgrade request that carries a body — every POST
  relayed to the assistant. Boot exposes no property for this, so a
  `ClientHttpRequestFactoryBuilderCustomizer` bean is the lever. `GatewayLoginFlowIT` fails the
  build if an upgrade offer ever reaches an upstream again. Lift it only once a relayed hop runs
  over TLS, where HTTP/2 arrives by ALPN with no upgrade header at all.
- **The shipped jar must start, and CI starts it.** `GatewayPackagedJarIT` runs the repackaged jar
  `package` wrote with `java -jar` and requires readiness, because every other suite runs on the
  test classpath, which can hold a library the jar lacks. A test-scoped declaration of something
  Boot needs at runtime (`snakeyaml`, #233) fails the build there.
- **Never lower `org.springframework.web` or `org.springframework.security` to `DEBUG` or `TRACE`**,
  nor a parent such as `org.springframework` or the root logger, and never turn on Spring Boot's
  `debug` or `trace` switch (`DEBUG=true`, `TRACE=true`, `--debug`, `--trace`), in any environment
  that handles a real sign-in: `debug` lowers Boot's `web` logger group, which holds
  `org.springframework.web`, to `DEBUG`, and `trace` lowers all of `org.springframework` to `TRACE`.
  "The gateway logs no token" holds at `INFO` only, which is what `GatewayLoginFlowIT`'s log sweep
  runs at. At `DEBUG`, `DefaultRestClient` writes the token request's form body, so the
  authorization code and the PKCE verifier of every sign-in reach the log, and by the same line,
  read from Spring's sources rather than observed, the refresh token of every refresh;
  `FilterChainProxy` writes the callback's query, `code` and `state` included; and the
  security-context lines carry the ID token's claims. Under Compose, `make check-compose` refuses
  `LOGGING_LEVEL_*`, `LOGGING_GROUP_*`, `LOGGING_CONFIG`, `DEBUG` and `TRACE` on the gateway
  service; a native run has no guard. To diagnose a failed sign-in, read `SignInFailureHandler`'s
  WARN line, which names the OAuth2 error code. Keycloak records no sign-in event
  (`eventsListeners: []`), so for an account that may be locked out ask its brute-force detector:
  `kcadm.sh get attack-detection/brute-force/users/<user-id> -r finassist --config
  /tmp/kcadm.config`, signed in as `docs/operations/operations.md` does: the image's home
  directory is root-owned, so `kcadm.sh` cannot write its default `~/.keycloak` there (ADR-063).
