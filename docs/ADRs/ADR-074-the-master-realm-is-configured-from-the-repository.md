# ADR-074: The master realm is configured from the repository

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-031](ADR-031-keycloak-as-the-identity-provider.md), whose realm is code but whose
`master` realm was not; [ADR-060](ADR-060-the-finassist-realm-and-the-keycloak-image-are-configured-not-defaulted.md),
whose "configured, not defaulted" now covers `master` too; and
[ADR-039](ADR-039-compose-restarts-services-and-bounds-their-logs.md), which this record gives a
third one-shot service; and [ADR-049](ADR-049-a-check-that-needs-cis-environment-stays-in-ci.md),
whose `make check-compose` now holds that one-shot and its gate, the single gate
[ADR-044](ADR-044-compose-refuses-a-published-secret.md) gave `secret-check` made a map of gates.

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
  in `finassist` (ADR-060). The cost: a failed administrator sign-in, or a lockout, leaves no
  trace in the log, so `docs/operations/operations.md` reads Keycloak's attack-detection state to
  diagnose one; keeping failures in Keycloak's database instead is
  [#438](https://github.com/NaimElijah/FinAssist/issues/438). No password history: the recovery
  runbook sets the administrator's password back to the value in `.env`, which a history rule
  would refuse.
- **How.** `ops/keycloak/apply-master-realm.sh` runs `kcadm.sh update realms/master --file
  /opt/finassist/master-realm.json --merge`, signed in for that one call with `--no-config`, the
  password from `KC_CLI_PASSWORD`, never an argument. The merge changes only what the file names,
  so `master`'s users and other settings are kept. Both files are baked into the image under
  `/opt/finassist/`, owned by root, outside `data/import`.
- **When.** The `keycloak-master` service runs the script from the Keycloak image once `keycloak`
  is healthy, on every `up`. It has no profile, takes no restart, log bound or stop grace, as
  ADR-039's one-shots take none, and runs as `nobody` on a read-only root with `/tmp` on a tmpfs,
  on the default network only. The same `up` configures a fresh volume and an existing one.
- **The script is Keycloak's image configuration, not an operator tool.** It sits in
  `ops/keycloak/` beside the realm file, ships inside the image, and is tested with the realm in
  the gateway module, by `KeycloakRealmIT`. [ADR-052](ADR-052-ops-holds-operator-code-one-directory-per-tool.md)'s
  directory per tool, and its "a tool needs a second language" trigger, are about host-run operator
  tools with their own lint and test targets; one `kcadm.sh` call is neither. A second script, or
  one that grows past a single call, gets a directory, `shellcheck` and tests of its own.
- **The gateway waits on it.** `gateway` depends on `keycloak-master` completing successfully,
  so `up` fails, and starts no gateway, while `master` is unconfigured or the administrator's
  credential in `.env` has drifted, rather than leaving `master` on defaults unnoticed. It orders a
  start and stops nothing: `master`'s own sign-in, the console and token endpoint on Keycloak's
  port, opens as soon as Keycloak is healthy, before the one-shot runs, and a Docker restart brings
  the gateway back without `depends_on`. The settings persist in `keycloak-db`, so only a volume
  never configured runs on defaults, and only until the one-shot first succeeds. `make
  check-compose` holds that gate as it holds `secret-check`'s.
- **The administrator stays one account, signing in with a password.** No second factor and no
  replacement account. **The owner's decision, made on 2026-11-02.** `master`'s `admin-cli` keeps
  the password grant, because every `kcadm.sh` runbook signs in through it.
- **`KEYCLOAK_ADMIN_PASSWORD` must now stay equal to the administrator's live password, and
  `KEYCLOAK_ADMIN_USERNAME` name the administrator.** Keycloak reads both once, when it creates
  `master`; the one-shot signs in with them on every `up`. A change made in Keycloak and not in
  `.env` keeps `up` from starting the gateway until `.env` matches. Keycloak's console calls the
  bootstrap account temporary and suggests replacing it; under this record it stays.

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
- **A drifted `.env` stops `up`.** If the administrator's username or password changes anywhere
  but `.env` first, the one-shot fails and `up` starts no gateway. The runbooks write `.env` first.
- **A lockout of the administrator stops the stack's start while it lasts.** Five wrong passwords,
  or two less than a second apart, lock the administrator out for a minute growing to fifteen
  (ADR-073), the lock survives a restart of Keycloak, and the one-shot cannot sign in until it ends.
  Something that keeps guessing keeps it locked, so a starting gateway stays down for as long as it
  guesses; a running one is unaffected. `docs/operations/operations.md` tells a lockout from drift
  by reading the administrator's lock.
- **A setting removed from the file stays in `master`.** A merge changes what the file names; a
  setting taken out of it keeps its last value until it is set again, by the file or by hand.
- **Two images from one build context.** `keycloak` and `keycloak-master` are each built from
  `ops/keycloak`, so a rebuild names both, or uses `up --build`, and `KeycloakRealmTest` holds the
  two to one context.
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
  until someone remembered it. **Revisit when** the one-shot's failures cost the owner more starts
  than a step run once per volume would.
- **keycloak-config-cli or another configuration tool.** Rejected: a new dependency and image to
  vet for one file `kcadm.sh` already applies. **Revisit when** a setting has to be removed from
  `master`, which a merge cannot do, or a further realm needs configuring this way.
- **Nothing waits on the one-shot.** Rejected: an unconfigured `master`, or a drifted credential,
  would then show only in `docker compose ps -a` while the stack runs as usual. The gate trades a
  gateway that does not start for a failure nobody misses. **Revisit when** the stack runs under a
  supervisor that reports a failed one-shot on its own, such as a deployment target
  ([#61](https://github.com/NaimElijah/FinAssist/issues/61)).
- **Run the merge inside the `keycloak` container**, from a wrapper that starts `kc.sh`, waits for
  it to be ready and calls `kcadm.sh`. Rejected: Keycloak's own start would then depend on the
  administrator's credential, and its entrypoint would hold a second process and a wait loop; a
  one-shot keeps each container to one job. **Revisit when** a deployment target with a post-start
  hook of its own replaces Compose (#61).
- **Merge `finassist`'s settings by the same one-shot.** Not now: `finassist` is imported whole
  into an empty volume, so a fresh volume already takes its file, and the few settings an existing
  volume lacks are applied once by the in-place runbook (ADR-060). A merge on every `up` would also
  undo settings the owner changed in the console on purpose. **Revisit when** a second `finassist`
  setting has to reach existing volumes, or the in-place runbook is missed once.

## Revisit when

- **Keycloak is reachable beyond loopback** (M5, [#61](https://github.com/NaimElijah/FinAssist/issues/61)):
  the administrator needs a second factor, and the automation a credential of its own.
- **Keycloak can import or configure `master` from a file at start-up.** The one-shot then gives
  way to that.
- **#438 decides `master`'s offline sessions and a record of failed sign-ins.** The file then
  gains those settings.

## What lands with this record

- **The settings and the script.** `ops/keycloak/master-realm.json`,
  `ops/keycloak/apply-master-realm.sh`, and their `COPY` into `/opt/finassist/` in
  `ops/keycloak/Dockerfile`.
- **The service.** `keycloak-master` in `docker-compose.yml`, and the gateway's dependency on it;
  `ops/compose_check/compose_check.py` and its tests hold both.
- **The tests.** `KeycloakRealmTest` pins the file, its equality with `finassist`'s brute-force
  policy, and the service's wiring: its entrypoint, user, build context and the administrator's
  variables. `KeycloakRealmIT` runs the script in a second container from the built image, as
  `nobody` on a read-only root; reads every setting back from `master`; runs it again and finds a
  setting the file does not name kept; runs it for an administrator that does not exist, and
  without a password, each failing without printing a password; finds no failed `master` sign-in
  in the log; and finds the file absent from `data/import`, where `--import-realm` reads.
- **Documentation.** ADR-031, ADR-039, ADR-049 and ADR-060 Status lines and their rows in
  `docs/ADRs/README.md`, a row for 074 and a note; `docs/operations/operations.md`;
  `docs/architecture/architecture.md`; `README.md`; `.env.example`; the root `CLAUDE.md`,
  `ops/CLAUDE.md` and `backend/gateway/CLAUDE.md`.
