# CLAUDE.md — gateway

Java 21, Spring Boot, Spring Cloud Gateway Server Web MVC. The root `.claude/CLAUDE.md` binds here
too; this file adds what binds the gateway alone.

## What it is

The backend-for-frontend (ADR-030). It signs the browser in with OIDC (code flow, PKCE) against
Keycloak, keeps the access and refresh tokens in its in-memory session, and relays `GET`/`POST`
`/ledger/api/v1/**` and `/assistant/api/v1/**` with `StripPrefix`, `RemoveRequestHeader` and
`TokenRelay` — routes declared in `application.yml`. The browser holds only an `HttpOnly` session
cookie and a CSRF token. The gateway answers Actuator probes.

It holds no bounded context: everything is in its `composition/` root. The parts to know:

- `GatewayConfiguration` registers the Host allow-list first. `HostAllowListFilter` is a
  deliberate copy of the ledger's, configured by `GATEWAY_ALLOWED_HOSTS`.
- `GatewaySecurityConfiguration` is the one security chain: deny by default, 401 never 302, and a
  renamed CSRF cookie and header.
- `BffRoutes` holds every `/bff/` path, in one place. `SessionController` serves `GET /bff/me`,
  `SpaLogoutSuccessHandler` answers `POST /bff/logout`, and `SignInFailureHandler` sends a failed
  sign-in back to `?signin=failed`. `LoginEntryFailureHandler` answers a sign-in that cannot start
  (an unknown registration id) with the entry point's plain 401, and logs nothing for it.
- `KeycloakRegistration` builds the `ClientRegistration` by hand, with the public and backchannel
  URLs split, and the client manager `TokenRelay` uses.
- `ProblemAuthenticationEntryPoint` (401), `ProblemAccessDeniedHandler` (403), `GatewayProblems`
  (the problem codes and writer) and `GatewayRelayProblems` (a relay's failures) answer
  problem+json. `GatewayOpenApiConfiguration` shapes the committed contract.

The realm file, `ops/keycloak/finassist-realm.json`, is pinned by `KeycloakRealmTest` and
`KeycloakRealmIT` in this module; its rules are in `ops/CLAUDE.md`.

## Commands

From `backend/gateway`:

- **Run** — `./mvnw spring-boot:run`, after Keycloak is up: `make run-identity`, which is
  `docker compose up -d keycloak-db keycloak`.
- **Test** — `make test-gateway`: `./mvnw -B verify`. **Docker must be running**:
  `KeycloakRealmIT` builds `ops/keycloak/Dockerfile` and runs it against a real PostgreSQL, and
  fails rather than skips without Docker (ADR-025).
- **Lint / format** — `./mvnw -B spotless:check` / `./mvnw -B spotless:apply`.
- **Regenerate the contract after every endpoint change** — `./mvnw -B verify -Dtest=NONE
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=GatewayContractIT` (no container needed). On a
  mismatch, copy `target/contracts/gateway-v1.openapi.json` over
  `contracts/gateway-v1.openapi.json`, then run `make generate-web-api`.

## Rules

- **The Spring Cloud starter takes its version from the `spring-cloud-dependencies` BOM**
  (`spring-cloud.version` in `pom.xml`), never a version of its own.
- **A relayed method is named twice, and both must admit every operation in `contracts/`**: the
  routes' `Method=` in `application.yml` and the chain's relay matchers in
  `GatewaySecurityConfiguration`. `GatewayRelayRoutesTest` holds the routes, and
  `GatewaySecurityTest` the chain, to `ledger-v1` and `assistant-v1` (#339); a new method needs both.
- **The catch-all answers a framework refusal at its own status.**
  `GatewayRelayProblems.relayFailed` peels an `ErrorResponse` off first — 405
  `METHOD_NOT_ALLOWED` and 406 `NOT_ACCEPTABLE`, the only two `GET /bff/me` can provoke — and logs
  it at WARN, so ERROR still means a true 5xx. A status with no code in that map stays a 500 rather
  than being passed through under an invented one. `GatewaySecurityTest` and
  `GatewayRelayProblemsTest` fail the build if either half is lost.
- **Every call to Keycloak is built from Boot's own HTTP builders**, in `KeycloakRegistration`: the
  authorization-code client and the `refresh_token` client from `RestClient.Builder`, and the ID
  token's `JwtDecoderFactory` from `RestTemplateBuilder`. Spring Security's defaults for all three
  build their client from a static factory that never sees `spring.http.clients.*`, so dropping any
  of these beans silently makes that call unbounded — only the relay's own client gets Boot's
  budget for free. `GatewayKeycloakTimeoutIT` stalls each endpoint in turn and fails the build if
  one stops giving up; `GatewayRelayTimeoutIT` does the same for the relay's own read budget.
- **Every outbound call speaks HTTP/1.1.** `GatewayConfiguration`'s `relayHttpVersion` customizer
  pins the JDK client's version, because its HTTP/2 default offers an h2c upgrade on a cleartext
  hop, and uvicorn answers 400 to an upgrade request that carries a body — every POST relayed to
  the assistant. Boot exposes no property for this, so a
  `ClientHttpRequestFactoryBuilderCustomizer` bean is the lever. `GatewayLoginFlowIT` fails the
  build if an upgrade offer ever reaches an upstream again. Lift it only once a relayed hop runs
  over TLS, where HTTP/2 arrives by ALPN with no upgrade header at all.
- **The shipped jar must start, and CI starts it.** `GatewayPackagedJarIT` runs the repackaged jar
  `package` wrote with `java -jar` and requires readiness, because every other suite runs on the
  test classpath, which can hold a library the jar lacks. A test-scoped declaration of something
  Boot needs at runtime (`snakeyaml`, #233) fails the build there.
