# ADR-060: The `finassist` realm and the Keycloak image are configured, not defaulted

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-031](ADR-031-keycloak-as-the-identity-provider.md), which makes the realm code and
pins the settings it names, and leaves every setting it does not name to Keycloak's default. It
also extends [ADR-019](ADR-019-opentelemetry-and-structured-logging.md), whose structured logs and
redaction boundary stop at the three services Keycloak sits beside.

## Context

**A realm imported from a file keeps Keycloak's default for anything the file omits**
([#340](https://github.com/NaimElijah/FinAssist/issues/340)). ADR-031 pins what FinAssist chose:
no self-registration, the password policy, a forced password change, TOTP at the owner's first
sign-in, and each client's scope and audience. Four defaults it did not choose were still in
force, and one choice was still to be made.

**The default event listener writes a sign-in failure to the log, username included.** The
`jboss-logging` listener logs each `LOGIN_ERROR` at WARN with the username as it was typed. A
password typed into the username box by mistake is therefore written to `docker logs`, and to
whatever collector a deployment later attaches, in plain text. ADR-019's redaction boundary is a
property of the three services; it does not reach Keycloak's own output. The realm file does
not set `eventsEnabled`, so Keycloak's default applies and no event is stored, and the log is also
the only place a failure would show.

**Keycloak creates an `admin-cli` client in every realm, and the file did not declare it.** The
default is a public client with the password grant on. FinAssist uses it in the `master` realm
only: the `kcadm.sh` procedures sign in there, never to `finassist`. In `finassist` it was an unused
password-grant client, and its tokens carry no FinAssist audience or role, so neither service
accepts one.

**Offline sessions had no absolute lifetime.** The browser session ends after ten hours
(`ssoSessionMaxLifespan`, 36,000 seconds); `offlineSessionMaxLifespanEnabled` was off, so an offline
session renewed for as long as it was used.

**Keycloak 26.8 enables two deprecated features by default**, `twitter-broker` and
`identity-brokering-api`, and FinAssist brokers no identity provider. Keycloak also logs plain
text by default, where every other service logs one JSON object per line (ADR-019).

**A viewer signs in with a password alone.** The browser flow keeps Keycloak's conditional OTP:
the form appears for a user who has enrolled a credential, and the seeded owner is forced to enrol
at the first sign-in. Nothing requires a viewer the owner adds to enrol one.

## Decision

**The realm file and the image state each of these, so no default decides them.**

- **`"eventsListeners": []`.** No event listener writes to the log, so a typed username, and a
  password typed in its place, stay out of it.
- **`admin-cli` is declared.** `finassist-realm.json` holds an explicit entry: a public client
  with `standardFlowEnabled`, `implicitFlowEnabled`, `directAccessGrantsEnabled` and
  `serviceAccountsEnabled` all `false`, `fullScopeAllowed` `false`, and no redirect URI or web
  origin.
- **Offline sessions end with the browser session's maximum.** `offlineSessionMaxLifespanEnabled`
  is `true` and `offlineSessionMaxLifespan` is 36,000 seconds, equal to `ssoSessionMaxLifespan`.
- **Keycloak logs JSON.** `docker-compose.yml` sets `KC_LOG_CONSOLE_OUTPUT=json` on the `keycloak`
  service. It is a runtime option, so it is set there and not baked in by `kc.sh build`.
- **The deprecated features are off in the image.** `ops/keycloak/Dockerfile` sets
  `KC_FEATURES_DISABLED=twitter-broker,identity-brokering-api` before `kc.sh build`, because a
  feature is a build-time option.
- **An existing realm is changed in place.** The realm file is imported once, so a realm created
  before these settings keeps the defaults. `docs/operations/operations.md`, "Applying a realm
  setting in place", sets the event listeners, the offline cap and the five `admin-cli` settings the import
  sets, `directAccessGrantsEnabled`, `standardFlowEnabled`, `implicitFlowEnabled`,
  `serviceAccountsEnabled` and `fullScopeAllowed`, with `kcadm.sh` and reads them back, keeping
  every user, and rebuilds Keycloak for the log format and the features. It changes the
  `finassist` realm only.

**A viewer is not required to enrol a second factor. This is a deliberate rejection, made by the
owner on 2026-10-30,** and `docs/requirements/requirements.md` lists it under Non-goals. TOTP stays
required of the owner at the first sign-in and of any user who has enrolled one.

**Two neighbouring gaps are out of scope here and tracked on their own:** the `master` realm, which
holds Keycloak's own administrator ([#368](https://github.com/NaimElijah/FinAssist/issues/368)),
and a Host allow-list in front of Keycloak, as the three services have
([#369](https://github.com/NaimElijah/FinAssist/issues/369)).

## Consequences

**Makes easy**

- **No credential, or username, reaches Keycloak's log through a failed sign-in.**
- **The realm file states every grant its clients have,** `admin-cli` included, and a test reads
  the imported realm back from a real Keycloak.
- **An offline session cannot outlive a browser session.**
- **Keycloak's output parses like the other services'.** One JSON object per line.
- **No deprecated feature is enabled.** A test reads every deprecated feature back from the
  built image.

**Makes hard**

- **No log line or stored event records a failed sign-in or a lockout.** With no listener and no
  stored events, a run of wrong passwords leaves nothing to read. The brute-force lockout still
  works, and `kcadm.sh get attack-detection/brute-force/users/<id> -r finassist` reads a user's
  lockout state. Stored sign-in events are
  [#159](https://github.com/NaimElijah/FinAssist/issues/159)'s.
- **A realm that already exists needs the in-place procedure.** Re-importing would delete every
  user, so the file alone changes a fresh volume and nothing else.
- **A Keycloak upgrade can move a default again.** The realm pins what these tests read; a new
  default outside them is invisible until a scan finds it.

**Accepted cost**

- **A viewer's password is the whole credential.** A viewer reads everything the ledger and the
  portfolio hold (ADR-032), so a guessed or captured password reads it all. This is the cost of
  the owner's rejection above.

## Alternatives considered

- **Require a second factor of every user, through a required action or an unconditional OTP step
  in a copied browser flow.** Rejected by the owner on 2026-10-30. **Revisit when** FinAssist is
  reachable from beyond the owner's machine
  ([#61](https://github.com/NaimElijah/FinAssist/issues/61)), or when a viewer is added who does
  not share the owner's devices.
- **Keep the default listener and accept the log line.** Rejected: it writes what a user typed,
  password included when it is typed in the wrong box, and ADR-019 has the repository write no
  credential to a log. **Revisit when** stored sign-in events exist (#159); a stored event is the
  place for the history this record gives up.
- **Leave `admin-cli` to Keycloak's default.** Rejected: an unused client with the password grant
  on is a grant FinAssist does not use. Nothing signs in to it.
- **Rebuild the realm to apply the new settings.** Rejected for an existing realm: it deletes
  every user, the owner's TOTP enrolment included. The in-place `kcadm.sh` procedure keeps them.

## Revisit when

- **FinAssist is reachable from beyond the owner's machine (#61), or a viewer is added who does
  not share the owner's devices.** The second-factor decision above is reopened.
- **Stored sign-in events are added (#159).** The listener setting is revisited with them.
- **A Keycloak release changes the grant, session or feature defaults these tests read.**

## What lands with this record

No code beyond #340's own fixes. Every rule above exists and has a test:

- **The realm file.** `KeycloakRealmTest`:
  `Given_the_realm_file_When_read_Then_no_event_listener_writes_sign_ins_to_the_log`,
  `Given_the_realm_file_When_read_Then_an_offline_session_lives_no_longer_than_a_browser_session`
  and `Given_the_admin_cli_client_When_read_Then_it_permits_no_grant`.
- **The imported realm, read back from a real Keycloak.** `KeycloakRealmIT`:
  `Given_the_realm_as_imported_When_its_event_listeners_are_read_Then_there_are_none`,
  `Given_a_failed_sign_in_through_the_login_form_When_the_log_is_read_Then_the_username_is_absent`,
  `Given_the_realm_as_imported_When_admin_cli_is_read_Then_it_is_the_realms_own_entry_with_every_flow_off`,
  `Given_admin_cli_in_the_finassist_realm_When_it_asks_for_a_password_grant_Then_keycloak_refuses_the_client`
  and
  `Given_the_realm_as_imported_When_its_offline_session_settings_are_read_Then_they_have_the_browser_sessions_cap`.
- **The image.** `KeycloakRealmIT`:
  `Given_the_built_image_When_its_features_are_read_Then_no_deprecated_feature_is_enabled`.
- **The log format.** `KeycloakRealmTest`:
  `Given_the_keycloak_service_When_its_environment_is_read_Then_its_console_log_is_json`.

Documentation:

- **Status lines and README.** ADR-031 reads `Accepted, extended by ADR-036 and ADR-060, corrected
  by ADR-047`, and ADR-019 `Accepted, extended by ADR-030, ADR-039, ADR-050 and ADR-060, corrected
  by ADR-047`, each with its row in `docs/ADRs/README.md`, a row for 060 and a note.
- **`docs/operations/operations.md`.** The in-place procedure.
- **`ops/CLAUDE.md`.** The realm rules.
- **`docs/requirements/requirements.md`.** The Non-goal.
