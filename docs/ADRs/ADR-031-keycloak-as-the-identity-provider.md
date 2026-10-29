# ADR-031: Keycloak as the identity provider

- **Status:** Accepted, extended by [ADR-036](ADR-036-least-privilege-keycloak-client-scoping.md),
  corrected by [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md).
- **Date:** 2026-09-16

## Context

[ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md) signs the browser in through a
gateway and has each service validate a bearer JWT. Something has to issue those tokens and sign
the owner in. The provider has to offer all of the following:

- **Flows:**
  - the authorization code flow with PKCE, for the gateway;
  - the client credentials grant, for the assistant's future ledger client
    ([ADR-032](ADR-032-role-based-authorization-per-service.md));
  - refresh tokens and an end-session endpoint.
- **Tokens:** RS256-signed, carrying a flat `roles` claim and a separate audience for each
  service.
- **Sign-in protection:** a second factor, brute-force protection and a password policy.

**The constraints on the answer:**

- **One owner, one machine.** There is no deployment target (#61). Everything runs under Compose
  ([ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md)) and binds loopback (ADR-030).
- **Privacy is undecided.** Whether the owner's data may leave the machine is still open: both
  the requirements' privacy question about a third-party LLM API and their Compliance row wait on
  it.
- **CI has to run it.** A realm the build cannot start is a realm nobody has tested, and the
  integration tests already run real dependencies in containers
  ([ADR-025](ADR-025-integration-tests-on-testcontainers.md)).
- **The ledger's database is the ledger's.**
  [ADR-024](ADR-024-jdbc-persistence-and-append-only-schema.md) gives it one schema per bounded
  context, owned by the ledger.
- **Secrets never enter the repository.**

## Decision

**Keycloak, self-hosted.**

- It is Apache-2.0 licensed and actively maintained.
- It runs as the Compose service `keycloak`, built from `ops/keycloak/Dockerfile`, which starts
  from the official `quay.io/keycloak/keycloak` image. The image tag is pinned exactly in that
  Dockerfile.
- The Dockerfile runs an optimised build, with PostgreSQL and health checks enabled, and the
  container starts with `start --optimized --import-realm`.

**Keycloak has two addresses and one issuer.**

- The browser reaches it at `http://localhost:8081` (`KEYCLOAK_PORT`), published on `127.0.0.1`
  only.
- Containers reach it at `http://keycloak:8080`.
- `KC_HOSTNAME` names the public URL, and `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` lets backchannel calls
  use the internal one. The issuer therefore stays `http://localhost:8081/realms/finassist` for
  everyone (ADR-030).
- The admin console is at `http://localhost:8081/admin`, so it too is reachable only from the
  owner's machine.

**Keycloak gets its own PostgreSQL**: the Compose service `keycloak-db`.

- It has its own volume, `keycloak-db-data`, and its own password, `KEYCLOAK_DB_PASSWORD`.
- It publishes no port.
- It has no init script, so the owner's existing `db-data` volume is never touched.
- The identity store and the books share no role, no backup and no failure domain, and either one
  can be rebuilt without the other.

**The realm is code**, committed at `ops/keycloak/finassist-realm.json` and imported at start-up.

- **Realm `finassist`:**
  - registration off, password reset off (there is no mail server), remember-me off;
  - brute-force protection on;
  - password policy `length(12) and notUsername and notEmail and passwordHistory(3)`;
  - `sslRequired: external`;
  - access tokens live 5 minutes, an idle SSO session 30 minutes, and any SSO session at most 10
    hours;
  - refresh-token rotation off (ADR-030).
- **Realm roles:** `owner`, `viewer` and `assistant` (ADR-032).
- **Client scopes:**
  - one maps realm roles to a flat, multivalued `roles` claim, in both the access token and the ID
    token;
  - the others add the audiences `finassist-ledger` and `finassist-assistant`.
  - The built-in `basic` scope stays a default on every client, because it carries `sub`.
- **Client `finassist-gateway`:**
  - confidential;
  - standard flow only, with no direct grants, no implicit flow and no service account;
  - PKCE with S256;
  - an exact redirect URI, `${GATEWAY_PUBLIC_URL}/bff/login/oauth2/code/keycloak`, and an exact
    post-logout URI, `${GATEWAY_PUBLIC_URL}/`;
  - no web origins.
- **Client `finassist-assistant`:**
  - confidential, and a service account only;
  - its service-account user holds the `assistant` role;
  - its tokens carry the ledger audience and nothing else.
- **One seeded user, `${FINASSIST_OWNER_USERNAME}`:**
  - holds the `owner` role;
  - has a temporary password, `${FINASSIST_OWNER_INITIAL_PASSWORD}`;
  - must change that password and configure TOTP at first sign-in.
- **No viewer is seeded.** The owner adds one in the admin console.

**Every secret in the realm file is a `${VAR}` placeholder with no default.** Keycloak fills each
placeholder from the environment at import, and Compose refuses to start without the variable,
through `:?`.

| Variable | Secret |
|---|---|
| `KEYCLOAK_PORT` (8081) | no |
| `KEYCLOAK_ADMIN_USERNAME` | no |
| `KEYCLOAK_ADMIN_PASSWORD` | yes |
| `KEYCLOAK_DB_PASSWORD` | yes |
| `FINASSIST_OWNER_USERNAME` | no |
| `FINASSIST_OWNER_INITIAL_PASSWORD` | yes |
| `GATEWAY_CLIENT_SECRET` | yes |
| `ASSISTANT_CLIENT_SECRET` | yes |

Each secret is blank in `.env.example`, and CI's `compose` job fills it with a throwaway value.

**The realm is tested in two ways.**

- **A static test reads the JSON** and asserts the security settings above, including that every
  secret and credential is a placeholder.
- **An integration test boots the real image in Testcontainers.** It checks the issuer in the
  discovery document, and the claims of a client-credentials token. This is the one check in CI
  that Keycloak reads the realm file the way this record assumes.

## Consequences

**Makes easy**

- **Standard OIDC.** Every flow ADR-030 and ADR-032 need is built in, and the tokens are standard
  JWTs that Spring Security and PyJWT already know how to verify.
- **Sign-in protection.** A second factor, brute-force protection and a password policy come as
  configuration rather than code in this repository.
- **The realm is reviewable.** Its security posture is a diff, and a test fails if a secret is
  ever committed into it.
- **Nothing leaves the machine.** No vendor account is needed, and signing in needs no internet
  connection.
- **Adding a viewer** is a few clicks in the admin console.

**Makes hard**

- **The realm file is imported only into an empty database.** `--import-realm` leaves an existing
  realm alone, so editing the file changes nothing until one of two things happens:
  - `keycloak-db-data` is recreated;
  - `kc.sh import --override` is run.
- **The file and the running realm can drift.** Changes made in the admin console, a viewer among
  them, never reach the file.
- **More to run.** Keycloak is a JVM process with a slow start, and its database is a second
  PostgreSQL to keep running and upgraded.
- **Upgrades are ours.** Dependabot can bump the Dockerfile's image. The `keycloak-db` tag in
  Compose is bumped by hand (#80).
- **CI gets slower.** It builds the Keycloak image for the realm's integration test.

**Accepted cost**

- **While Keycloak is down, nobody can sign in or refresh a session.** A service that cannot fetch
  signing keys answers 503 (ADR-030).
- **Applying a realm change by recreating `keycloak-db-data` costs the owner their identity.**
  Keycloak creates the owner afresh:
  - the owner has to repeat the first sign-in, including TOTP;
  - the new user has a new `sub`, so once the journal records who, rows recorded before the
    rebuild name a subject that Keycloak no longer knows.
- **An identity provider is more machinery than one owner needs.** That is ADR-030's accepted
  cost, and this record inherits it.

## Alternatives considered

**A managed identity provider**, such as a hosted OIDC service. There would be no server to run or
upgrade. Rejected, for four reasons:

- the owner's identity and sign-in activity would sit with a third party while the privacy
  question is open;
- signing in to a local application would need the internet;
- it needs a vendor account and its terms;
- CI could not run the realm without network access and credentials.

**Spring Authorization Server.** It is Java, on the ledger's stack. Rejected: this repository would
write and own the login pages, TOTP, brute-force protection, password policy and user management,
which is identity code nobody here needs to own.

**Hand-rolled token issuance.** Rejected: the signing keys, the login, the rotation and every
mistake in them would be this repository's, and identity is the last place to build without a
tested product.

**Keycloak's schema in the ledger's database.** One PostgreSQL fewer. Rejected, for two reasons:

- the books and the identity store would share a role, a backup and a failure domain;
- the owner's existing `db-data` volume would need a database added by hand, because PostgreSQL's
  image runs its init scripts only on an empty volume.

**Keycloak's development mode (`start-dev`) with its embedded database.** Less setup. Rejected:
Keycloak documents development mode as not for production use, and ADR-030 asks for the
production shape.

## Revisit when

- **A managed identity provider becomes acceptable.** The privacy question is answered in its
  favour, or running Keycloak costs more than it returns.
- **A deployment target is chosen** (#61). Where Keycloak runs, its host name, TLS, and
  `sslRequired` all change with it.
- **`keycloak-db-data` has to be recreated while the journal holds attributed rows.** First pin the
  owner's user id in the realm file, so that the `sub` those rows name survives the rebuild.
