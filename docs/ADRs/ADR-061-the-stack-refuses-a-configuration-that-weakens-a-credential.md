# ADR-061: The stack refuses a configuration that weakens a credential

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-044](ADR-044-compose-refuses-a-published-secret.md), whose `secret-check` refuses a
secret that a file in the repository publishes, and checks no property of a secret that is not
published. It also extends [ADR-049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md),
which makes the Compose conventions check `make check-compose` and names the keys it refuses, and
which has no rule for a log level.

## Context

**ADR-044 refuses a secret the repository publishes, and accepts any other.** A `.env` whose
`POSTGRES_PASSWORD` is `a` passes it. `.env.example` lists the eight secrets blank, and
`docs/operations/operations.md` documents `openssl rand -hex 16` for generating each, but nothing
makes the owner use it ([#340](https://github.com/NaimElijah/FinAssist/issues/340)).

**Length is the only bound on guessing where nothing locks the account out.** Two of the stack's
credentials stand behind no lockout:

- **Keycloak's `master`-realm administrator.** The `master` realm is not under the repository's
  configuration ([#368](https://github.com/NaimElijah/FinAssist/issues/368)).
- **PostgreSQL's password.** A database has no account lockout, and its port is published on
  loopback.

A rule for those two would stop short of the other six. The owner's decision, on 2026-10-30, was to
set one minimum and apply it to all eight.

**The gateway's log levels are set by environment variables nothing checks.** Spring Boot reads
`LOGGING_LEVEL_*`, `LOGGING_GROUP_*` and `LOGGING_CONFIG` from the container's environment, and
also its `DEBUG` and `TRACE` switches: set to anything but `false`, even the empty string, `DEBUG`
lowers the `org.springframework.web` group to DEBUG and `TRACE` lowers all of
`org.springframework` to TRACE. `application.yml` sets no level, so Boot's INFO applies. At DEBUG,
Spring Web and Spring Security write the authorization code, its PKCE verifier and a refresh
grant's form body. `make check-compose` refuses the keys that reopen the Actuator exposure
(ADR-049) and no key that lowers a level, so an owner who adds
`LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB: DEBUG`, or just `DEBUG: "true"`, to debug a failed sign-in
passes it. The gateway's own rule is that it logs no token and no secret (ADR-030), and that rule
held only at INFO.

## Decision

**`secret-check` refuses any of its eight secrets shorter than `MIN_SECRET_LENGTH`, 32
characters,** the length `openssl rand -hex 16` writes. The owner chose 32, and all eight, on
2026-10-30.

- **The eight.** `POSTGRES_PASSWORD`, `LEDGER_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD`,
  `KEYCLOAK_ADMIN_PASSWORD`, `FINASSIST_OWNER_INITIAL_PASSWORD`, `GATEWAY_CLIENT_SECRET`,
  `ASSISTANT_CLIENT_SECRET` and `GRAFANA_ADMIN_PASSWORD`, the list ADR-044 hands it.
- **It fails closed.** A short secret ends the check with `EXIT_REFUSED`, and
  `db`, `keycloak-db` and `grafana` wait on `service_completed_successfully`, so nothing that waits
  on it starts (ADR-044).
- **It names the variable and the minimum, never the value or its length.** The refusal reads
  `<NAME> is shorter than the 32 characters every secret needs`, and points to
  `docs/operations/operations.md#rotating-a-secret`.
- **It reports both refusals.** A value that is short and also published is named once as published
  and once as short.
- **An unset or empty secret is not its business.** Compose already refuses one through `${VAR:?}`.

**`make check-compose` refuses a log level, a log group, a logging configuration or Spring
Boot's `DEBUG` or `TRACE` switch on the gateway.** `ops/compose_check/compose_check.py` refuses
any key in the `gateway` service's environment that begins `LOGGING_LEVEL_` or `LOGGING_GROUP_`
or is `LOGGING_CONFIG`, `DEBUG` or `TRACE`, in any spelling and with any value, the empty string
included, naming the service and the key and never the value. It runs where ADR-049 puts every
Compose convention: `make check-compose`, which CI's `compose` job calls.

## Consequences

**Makes easy**

- **A short secret cannot start the stack.** The owner learns it from the variable's name, before
  a database has a password to protect.
- **One rule, one number.** The same minimum covers all eight, the generator's output meets it, and
  `.env.example` says so at its top.
- **The gateway's logs cannot be lowered to DEBUG by an environment variable** in the Compose
  file.
- **Each rule has a test that fails when it is lost** (below).

**Makes hard**

- **An owner whose `.env` holds a shorter value must rotate it before the stack starts.**
  PostgreSQL applies its password only to an empty volume, and Keycloak copies its secrets into its
  database only at its first start (ADR-044), so an existing volume does not take a new value from
  `.env` alone. `operations.md`'s "Rotating a secret" changes each one in place, `.env` first.
- **A secret the owner types is now a long one.** `FINASSIST_OWNER_INITIAL_PASSWORD` is entered
  once, at the first sign-in, and has to meet 32 characters too.
- **Length is all the check knows.** A 32-character value of one repeated character passes.
- **The log rule covers the gateway alone.** The ledger and the assistant are not checked. This
  record does not claim they are safe at DEBUG, only that the gateway's sign-in and refresh requests
  are what carry the authorization code and the refresh token.
- **The gateway cannot be run at DEBUG through Compose.** `make check-compose` fails on it. The
  check reads the resolved Compose file, so a gateway run natively is not covered.

## Alternatives considered

- **Apply the minimum to the Keycloak admin password only.** Rejected: PostgreSQL's password has
  no lockout either, and a rule that names two secrets leaves six on the old footing. **Revisit
  when** the `master` realm is under the repository's configuration with a lockout (#368) and the
  database port is no longer published (#61): the two credentials that motivate the rule are then
  protected another way, and the rule for the other six can be argued on its own.
- **An entropy check.** Rejected: length is what the generator guarantees. **Revisit when** the
  owner stops generating the values with `openssl rand -hex 16`.
- **A different minimum, or a subset of the secrets.** The owner chose 32 and all eight on
  2026-10-30.
- **Refuse log levels on every service.** Not now: this record has evidence for the gateway alone.
  **Revisit when** a scan shows the ledger or the assistant writes a credential below INFO.

## Revisit when

- **A secret is added to the stack.** It joins `SECRET_VARIABLES`, the Compose lists and
  `.env.example`, and `tests/test_secret_check.py` keeps the three lists equal.
- **A scan shows the ledger or the assistant writes a credential below INFO.**
  `LEVEL_PINNED_SERVICES` gains it.
- **A deployment target is chosen (#61).** Where the secrets come from, and so what `secret-check`
  reads, is decided with it.

## What lands with this record

No code beyond #340's own fixes. Every rule above exists and has a test:

- **The minimum.** `ops/secret_check/tests/test_secret_check.py`:
  `Given_a_secret_one_character_short_of_the_minimum_When_checked_Then_it_is_refused_by_name_alone`
  and `Given_a_secret_of_exactly_the_minimum_length_When_checked_Then_it_passes`, each for all
  eight secrets; `Given_the_minimum_length_When_read_Then_it_is_what_openssl_rand_hex_16_writes`;
  and `Given_a_short_secret_a_committed_file_also_holds_When_checked_Then_both_refusals_are_named`.
- **The log levels.** `ops/compose_check/tests/test_compose_check.py`:
  `Given_the_gateway_service_sets_a_logging_level_When_checked_Then_compose_check_refuses_it`,
  its sibling cases for a logging group and a logging configuration,
  `Given_the_gateway_service_sets_springs_debug_or_trace_switch_When_checked_Then_it_is_refused` and
  `Given_a_logging_level_on_a_service_without_spring_When_checked_Then_it_passes`.

Documentation:

- **Status lines and README.** ADR-044 reads `Accepted, extended by ADR-061`, and ADR-049
  `Accepted, extended by ADR-061`, each with its row in `docs/ADRs/README.md`, a row for 061 and a
  note.
- **`ops/CLAUDE.md`.** The minimum and the log rule.
- **`.env.example` and `docs/operations/operations.md`.** The minimum.
- **`backend/gateway/CLAUDE.md`.** The log rule, with the reason.
