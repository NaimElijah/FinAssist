# ADR-073: Every realm locks out a guessed sign-in for a while, never for good

- **Status:** Accepted
- **Date:** 2026-11-02

Extends [ADR-031](ADR-031-keycloak-as-the-identity-provider.md), which says only "brute-force
protection on". It records the thresholds behind that phrase, and why a lockout is temporary, for
every realm the repository configures: `finassist`, and `master` since
[ADR-074](ADR-074-the-master-realm-is-configured-from-the-repository.md).

## Context

**The thresholds were pinned, not decided.** `ops/keycloak/finassist-realm.json` has set a
specific policy since the realm was first written, and since scan 1
([#338](https://github.com/NaimElijah/FinAssist/issues/338)) `KeycloakRealmTest` and
`KeycloakRealmIT` have pinned every value of it. The one recorded reason for a temporary lockout
was a test comment. Tests should cite a decision, not stand in for one
([#351](https://github.com/NaimElijah/FinAssist/issues/351)).

**The trade-off is real.** A temporary lockout slows online guessing without stopping it. A
permanent one turns five wrong passwords, the owner's own included, into a denial of service that
lasts until an administrator acts. In FinAssist the one administrator is the owner too.

**`master` needs a policy as well.** Its administrator can act on `finassist`, and ADR-074 now
configures it from the repository, so it needs the same decision.

## Decision

**Both realms take one brute-force policy: five failures, then a wait that starts at a minute and
grows to fifteen, and never a permanent lockout.** **The owner chose to keep the values the realm
already held, on 2026-11-02.**

| Setting | Value | Meaning |
|---|---|---|
| `bruteForceProtected` | `true` | Failures are counted per user |
| `bruteForceStrategy` | `MULTIPLE` | The wait grows with each further run of failures, Keycloak's own default, stated so it cannot move with an upgrade |
| `failureFactor` | `5` | The fifth failure starts a wait |
| `waitIncrementSeconds` | `60` | Each wait adds a minute |
| `maxFailureWaitSeconds` | `900` | No wait is longer than fifteen minutes |
| `quickLoginCheckMilliSeconds` | `1000` | Two failures less than a second apart count as a quick login |
| `minimumQuickLoginWaitSeconds` | `60` | A quick login waits at least a minute |
| `maxDeltaTimeSeconds` | `43200` | The failure count resets after twelve hours without one |
| `permanentLockout` | `false` | A lockout always ends on its own |
| `maxTemporaryLockouts` | `0` | No number of temporary lockouts turns permanent |

- **Temporary, because each realm has one person who would have to undo a permanent lockout.** The
  owner signs in to `finassist` and is also `master`'s administrator. A permanent lockout of either
  would need the recovery runbooks every time, `kc.sh bootstrap-admin` for the administrator.
- **It is enough here because of what sits around each realm.** Every published port binds
  loopback ([ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md)).
  - In `finassist`, a password has at least 12 characters, by the realm's policy. The owner also
    needs TOTP, but a viewer signs in with a password alone
    ([ADR-060](ADR-060-the-finassist-realm-and-the-keycloak-image-are-configured-not-defaulted.md)),
    so for a viewer the lockout is the only defence against guessing.
  - In `master`, the administrator's password has at least 32 characters, by the policy ADR-074
    sets, as `secret-check` holds the bootstrap value
    ([ADR-061](ADR-061-the-stack-refuses-a-configuration-that-weakens-a-credential.md)). At five
    guesses per wait, a password of that length is out of reach.
- **One policy, written twice.** `finassist-realm.json` and `master-realm.json` each carry the full
  block. `KeycloakRealmTest` requires the two to be equal, and `KeycloakRealmIT` reads both back
  from a running Keycloak.

## Consequences

**Makes easy**

- **The tests cite a decision.** A change of threshold is a change to this record first.
- **A forgotten password costs at most fifteen minutes,** the administrator's included, with no
  runbook.

**Makes hard**

- **Online guessing is slowed, not stopped.** Anyone who can reach a sign-in can keep a user
  waiting, the owner or the administrator included, for as long as they keep guessing. On loopback
  only, that is a local process.
- **Two files must stay equal.** A test holds them so, but a change still has to be made twice.

## Alternatives considered

- **A permanent lockout after some temporary ones** (`maxTemporaryLockouts > 0` with
  `permanentLockout` on). Rejected by the owner: stronger against slow guessing, but every lockout
  past the limit needs a recovery runbook, and the administrator's needs `kc.sh bootstrap-admin`.
  **Revisit when** sign-in is reachable beyond loopback.
- **Leave the values undecided, pinned by tests alone.** Rejected: a test that pins a value nobody
  chose cannot say when the value should change.

## Revisit when

- **Sign-in is reachable beyond loopback** (M5, [#61](https://github.com/NaimElijah/FinAssist/issues/61)).
- **More than one household signs in** (M6): a lockout then affects people who are not the owner.
- **A viewer is added who does not share the owner's devices** (ADR-060's trigger): a
  single-factor viewer is the account this policy protects least.
- **The owner, or the administrator, is locked out by accident** in practice.

## What lands with this record

- **The values.** `ops/keycloak/finassist-realm.json` and `ops/keycloak/master-realm.json`.
- **The tests.** `KeycloakRealmTest`:
  `Given_the_realm_file_When_read_Then_brute_force_protection_is_on_with_the_agreed_thresholds`
  and the master file's equality with it; `KeycloakRealmIT`:
  `Given_the_realm_as_imported_When_its_brute_force_settings_are_read_Then_they_hold_the_agreed_thresholds`
  and master's read-back. Each cites this record.
- **Documentation.** ADR-031's Status line and its row in `docs/ADRs/README.md`, a row for 073 and
  a note; `docs/operations/operations.md`, where a lockout's length is described.
