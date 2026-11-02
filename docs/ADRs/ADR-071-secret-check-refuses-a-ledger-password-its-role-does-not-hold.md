# ADR-071: secret-check refuses a ledger password that differs from its database role's

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-044](ADR-044-compose-refuses-a-published-secret.md), whose `secret-check` refuses a
secret that a file in the repository publishes, and
[ADR-061](ADR-061-the-stack-refuses-a-configuration-that-weakens-a-credential.md), which adds a
minimum length. Both judge each secret alone. This record adds a rule about two of them together.

## Context

**The ledger signs in as the role `db` creates, with a password `db` never sees.** PostgreSQL
creates the role from `POSTGRES_PASSWORD` when it initialises an empty volume. The ledger signs in
with `LEDGER_DB_PASSWORD`. Its username, `LEDGER_DB_USERNAME`, and `POSTGRES_USER` both default to
`finassist`, so the ledger signs in as the role `db` creates until
[#34](https://github.com/NaimElijah/FinAssist/issues/34) gives it a least-privilege role of its
own.

**Nothing refused two different values.** `db` starts on any `POSTGRES_PASSWORD`, and the database
then refuses every sign-in the ledger makes. Under `restart: unless-stopped`
([ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md)) the ledger restarts in a
loop while `db` reports healthy. The README and `.env.example` said the two must match, and nothing
held them to it.

**Where a check runs decides whether it can work (ADR-044).** The role's password is fixed when the
volume is first initialised, so a mismatch is cheapest to refuse before `db` first starts.
`secret-check` already runs there, and already holds the values of both variables.

## Decision

**`secret-check` refuses a `LEDGER_DB_PASSWORD` that differs from `POSTGRES_PASSWORD` while
`LEDGER_DB_USERNAME` and `POSTGRES_USER` name one role,** until #34 gives the ledger a role of its
own.

- **It is a consistency rule, not a publication rule.** A mismatch refuses even when neither value
  appears in any file. The report names the two variables and never a value, its length, or how the
  two differ (ADR-044).
- **It is handed the two usernames, which are not secrets.** Compose passes `POSTGRES_USER` and
  `LEDGER_DB_USERNAME` with `db`'s and the ledger's own defaults. When either is unset, or the two
  name different roles, no comparison is made.
- **It blocks every service that waits on the gate, by design.** `db`, `keycloak-db` and `grafana`
  wait on `secret-check` (ADR-044), and every other service waits on one of those. So a mismatch
  also stops `keycloak-db` and Grafana, which the ledger's password does not touch, and
  `make run-identity` is refused too. One gate that holds all or nothing is simpler than a gate per
  service, and that is an accepted cost.
- **The refusal says which value to keep.** It tells the owner to set both passwords to the one the
  role holds now, which is the one the ledger signs in with, or to rotate in place first
  ([Rotating a secret](../operations/operations.md#rotating-a-secret)).
- **CI exercises the refusal.** The compose job gives the fresh pair one value, as before, and runs
  `secret-check` through Compose on a mismatched pair, requiring the refusal.

## Consequences

**Makes easy**

- **A mismatch is named before the first start,** by variable, instead of surfacing as a ledger that
  restarts while `db` is healthy.
- **The rule has tests that fail when it is lost** (below), and CI proves the real wiring.

**Makes hard**

- **A mismatch blocks services it does not concern.** An owner who wants only Keycloak, through
  `make run-identity`, must fix the ledger's password first.
- **The check compares two values; it cannot read the role's actual password.** The volume is not
  its input. A pair that agrees but differs from the password an initialised volume's role holds
  passes, and the ledger then fails to sign in. Changing the role's password stays the work of
  [Rotating a secret](../operations/operations.md#rotating-a-secret).
- **It rests on the ledger sharing `db`'s role.** The day that stops being true, the rule is wrong
  and has to be removed with it (#34).

## Alternatives considered

- **No check; the documentation says the two must match.** Rejected: the failure appears after `db`
  has initialised its volume, as a restart loop with nothing naming the cause, and a sentence in
  the README is read once. **Revisit when** a start-up path exists that cannot run the gate.
- **Compare inside the ledger at start-up.** Rejected: the ledger holds only `LEDGER_DB_PASSWORD`,
  not `POSTGRES_PASSWORD`, so it cannot compare the two, and it runs after `db` has initialised its
  volume (ADR-044). **Revisit when** the ledger can read the role's password from the database.
- **A gate per service.** Rejected for now: a second one-shot, or a gate that refuses only the
  ledger, would let `keycloak-db` and Grafana start through a mismatch, at the cost of more
  services, more `depends_on` wiring and more for `make check-compose` to hold. **Revisit when** a
  mismatch blocking an unrelated service costs the owner in practice.

## Revisit when

- **#34 gives the ledger a role of its own.** Its password is then that role's, nothing ties it to
  `POSTGRES_PASSWORD`, and this rule is removed. The test that the rule passes for a ledger on a
  role of its own already holds that case.
- **A deployment target other than Compose is chosen**
  ([#61](https://github.com/NaimElijah/FinAssist/issues/61)). Where the secrets come from, and so
  what `secret-check` reads, is decided with it (ADR-044).

## What lands with this record

- **The rule.** `ops/secret_check/secret_check.py`: `_ledger_password_differs`, and the two
  usernames in the `secret-check` service's environment in `docker-compose.yml`.
- **The tests.** `ops/secret_check/tests/test_secret_check.py`:
  `Given_the_ledger_on_the_database_role_with_another_password_When_checked_Then_both_are_named`,
  `Given_the_ledger_on_the_database_role_with_its_password_When_checked_Then_it_passes`,
  `Given_the_ledger_on_a_role_of_its_own_When_its_password_differs_Then_it_passes`,
  `Given_a_username_unset_When_the_passwords_differ_Then_no_role_is_compared_and_it_passes` and
  `Given_the_compose_file_When_the_check_service_is_read_Then_each_username_resolves_alike`.
- **CI.** The compose job in `.github/workflows/ci.yml`: the fresh pair takes one value, and a
  mismatched pair is refused through Compose.
- **Documentation.** ADR-044 reads `Accepted, extended by ADR-061 and ADR-071`, with its row in
  `docs/ADRs/README.md`, a row for 071 and a note; the third refusal in `README.md`,
  `docs/operations/operations.md`, `docs/testing/testing.md`, `docs/architecture/architecture.md`
  and `ops/CLAUDE.md`.
