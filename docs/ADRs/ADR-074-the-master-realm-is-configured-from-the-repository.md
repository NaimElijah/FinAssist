# ADR-074: The master realm is configured from the repository

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-031](ADR-031-keycloak-as-the-identity-provider.md), whose realm is code but whose
`master` realm was not; [ADR-060](ADR-060-the-finassist-realm-and-the-keycloak-image-are-configured-not-defaulted.md),
whose "configured, not defaulted" now covers `master` too; and
[ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md), which this record gives a
third one-shot service.

## Context

**Keycloak creates `master` itself, on its own defaults.** The repository configures `finassist`
in full and pins it with `KeycloakRealmTest` and `KeycloakRealmIT`. `master` is the realm
Keycloak's administrator signs in to, and that administrator can act on `finassist`: read and
change its users, roles and clients. Scan 3 ([#340](https://github.com/NaimElijah/FinAssist/issues/340))
found `master` exactly as Keycloak creates it, and asked for its settings to come from the
repository as `finassist`'s do ([#368](https://github.com/NaimElijah/FinAssist/issues/368)).

**`master` cannot be imported.** `--import-realm` skips a realm that already exists, and
Keycloak creates `master` before it imports anything, so a `master` file in `data/import` would
never be read. `kc.sh import --override true` deletes a realm before importing it, which for
`master` would delete its administrator.

**An existing volume must be configured in place, keeping every user.** The owner's
`keycloak-db-data` may already hold `master`; the settings must reach it as well as a fresh one.

**Every runbook signs in as the administrator with a password.** `docs/operations/operations.md`'s
`kcadm.sh` recipes sign in to `master` through its `admin-cli` with the password grant, and
`kcadm.sh` has no way to send a one-time code.

## Decision

**`ops/keycloak/master-realm.json` holds the settings `master` takes, and a one-shot Compose
service merges it into the running realm on every `up`.**

- **What it sets.** The brute-force policy of
  [ADR-073](ADR-073-every-realm-locks-out-a-guessed-sign-in-for-a-while-never-for-good.md),
  identical to `finassist`'s; the password policy `length(32) and notUsername`, so a password set
  in the console or with `kcadm.sh set-password` is held to the same 32-character minimum that
  `secret-check` holds `KEYCLOAK_ADMIN_PASSWORD` to
  ([ADR-061](ADR-061-the-stack-refuses-a-configuration-that-weakens-a-credential.md)); and no event
  listener, so a username, or a password typed into the username field, never reaches the log, as
  in `finassist` (ADR-060). No password history: the recovery runbook sets the administrator's
  password back to the value in `.env`, which a history rule would refuse.
- **How.** `ops/keycloak/apply-master-realm.sh` runs `kcadm.sh update realms/master --file
  /opt/finassist/master-realm.json --merge`, signed in for that one call with `--no-config`, the
  password from `KC_CLI_PASSWORD`, never an argument. The merge changes only what the file names,
  so `master`'s users and other settings are kept. Both files are baked into the image under
  `/opt/finassist/`, owned by root, outside `data/import`.
- **When.** The `keycloak-master` service runs the script from the Keycloak image once `keycloak`
  is healthy, on every `up`. It has no profile, takes no restart, log bound or stop grace, as
  ADR-039's one-shots take none, and runs as `nobody` on a read-only root with `/tmp` on a tmpfs,
  on the default network only. The same `up` configures a fresh volume and an existing one.
- **The gateway waits on it.** `gateway` depends on `keycloak-master` completing successfully,
  so the stack does not open sign-in while `master` is unconfigured. `make check-compose` holds
  that gate as it holds `secret-check`'s.
- **The administrator stays one account, signing in with a password.** No second factor and no
  replacement account. **The owner's decision, made on 2026-11-02.** `master`'s `admin-cli` keeps
  the password grant, because every `kcadm.sh` runbook signs in through it.
- **`KEYCLOAK_ADMIN_PASSWORD` must now stay equal to the administrator's live password.** Keycloak
  reads it once, when it creates `master`; the one-shot signs in with it on every `up`. A password
  changed in Keycloak and not in `.env` stops the gateway from starting until `.env` matches.

## Consequences

**Makes easy**

- **`master` is configured, not defaulted,** on every volume, by the same `up` that starts the
  stack, and `KeycloakRealmIT` reads every setting back from the running image.
- **A setting changed in `master-realm.json` reaches an existing volume on the next `up`,** with
  no re-import and no user lost.

**Makes hard**

- **The administrator's password is single-factor.** Brute-force protection, the 32-character
  minimum and loopback-only ports protect it; a second factor does not. A process that can read
  `.env` holds it either way.
- **A drifted `.env` stops sign-in.** If the administrator's password changes anywhere but `.env`
  first, the one-shot fails and the gateway does not start. The runbooks write `.env` first.
- **A lockout of the administrator stops the stack's start for its length.** Five wrong passwords
  lock the administrator out for up to fifteen minutes (ADR-073), and the one-shot cannot sign in
  until it ends.
- **A setting removed from the file stays in `master`.** A merge changes what the file names; a
  setting taken out of it keeps its last value until it is set again, by the file or by hand.
- **Three one-shots instead of two,** each one more exemption for `make check-compose` to name.

## Alternatives considered

- **A `master` file in `data/import`.** Rejected: `--import-realm` skips a realm that exists, and
  Keycloak creates `master` first.
- **A permanent administrator with TOTP, and a service-account client for automation.** Rejected by
  the owner: every `kcadm.sh` runbook would change, and the automation's client secret, held in
  `.env` beside everything else, would still be a full-admin credential with no second factor.
  **Revisit when** Keycloak is reachable beyond loopback (M5).
- **A TOTP administrator and a temporary administrator per task, made with `kc.sh
  bootstrap-admin`.** Rejected by the owner: the strongest, but every runbook and the settings step
  itself become manual. **Revisit when** M5, with the option above.
- **A runbook alone, run by hand.** Rejected: a fresh or recreated volume would run on defaults
  until someone remembered it.
- **keycloak-config-cli or another configuration tool.** Rejected: a new dependency and image to
  vet for one file `kcadm.sh` already applies.

## Revisit when

- **Keycloak is reachable beyond loopback** (M5, [#61](https://github.com/NaimElijah/FinAssist/issues/61)):
  the administrator needs a second factor, and the automation a credential of its own.
- **Keycloak can import or configure `master` from a file at start-up.** The one-shot then gives
  way to that.

## What lands with this record

- **The settings and the script.** `ops/keycloak/master-realm.json`,
  `ops/keycloak/apply-master-realm.sh`, and their `COPY` into `/opt/finassist/` in
  `ops/keycloak/Dockerfile`.
- **The service.** `keycloak-master` in `docker-compose.yml`, and the gateway's dependency on it;
  `ops/compose_check/compose_check.py` and its tests hold both.
- **The tests.** `KeycloakRealmTest` pins the file, its equality with `finassist`'s brute-force
  policy, where the image puts it and the service's wiring. `KeycloakRealmIT` runs the script in a
  second container from the built image, as `nobody` on a read-only root, reads every setting back
  from `master`, runs it again, and runs it with a password that is wrong.
- **Documentation.** ADR-031, ADR-039 and ADR-060 Status lines and their rows in
  `docs/ADRs/README.md`, a row for 074 and a note; `docs/operations/operations.md`;
  `docs/architecture/architecture.md`; `README.md`; `.env.example`; the root `CLAUDE.md`,
  `ops/CLAUDE.md` and `backend/gateway/CLAUDE.md`.
