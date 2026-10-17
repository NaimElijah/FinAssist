# ADR-043: GitHub's own secret scanning and Dependabot alerts watch the repository

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

**The repository became public on 2026-10-17, with every GitHub security feature switched off**
([#139](https://github.com/NaimElijah/FinAssist/issues/139)). Asked that day, the API answered:

- vulnerability alerts: 404, disabled;
- Dependabot alerts: 403, disabled;
- secret scanning: disabled, as was every other key of `security_and_analysis`.

Dependabot opened version-update PRs and nothing else.

**Nothing alerted on a published advisory.** CI's `pip-audit` and `npm audit` catch one in the
assistant's and the web client's trees, but only when a push or a pull request happens to run
them. The two Java services have no audit at all
([#66](https://github.com/NaimElijah/FinAssist/issues/66)).

**Nothing looked for a committed secret.** The requirements' Secrets row held by review alone.
On a public repository anything ever committed is readable by anyone: every branch, and every
pull request's head under `refs/pull/*`, including the commits of the branches deleted on
2026-10-17. A scan of the whole history was owed before anything else.

**What a public repository on the Free plan can turn on was asked of the API, not assumed.**
GitHub's own pages disagree on whether per-repository push protection needs a paid Secret
Protection licence. On 2026-10-17 the API answered:

| Feature | Requested | API answered |
|---|---|---|
| Dependabot alerts (`PUT …/vulnerability-alerts`) | on | 204; on |
| Secret scanning | on | enabled |
| Push protection | on | enabled |
| Scanning for non-provider patterns | on | accepted the request, and stayed disabled |
| Dependabot security updates | not requested | disabled |

**Dependabot security updates would bypass `dev`.** GitHub's options reference says of
`target-branch`: "Options defined for this `package-ecosystem` no longer apply to security updates
because security updates always use the default branch for the repository." Every security fix
would then arrive as a pull request into `main`, which is exactly what `dependabot.yml` sets
`target-branch: dev` to prevent.

## Decision

**Turn on Dependabot alerts.** GitHub raises an alert when an advisory names a dependency in the
graph, whether or not anything has been pushed.

**Turn on GitHub's own secret scanning and push protection for the repository.**

- Secret scanning covers the whole history on every branch, and every push after it.
- Push protection blocks a push that contains a secret matching a provider's pattern, before it
  reaches the repository, whichever machine it comes from. Anyone with write access can still
  push past the block by giving a reason, such as "used in tests" or "false positive", and GitHub
  records an alert when they do. It stops an accident, not a decision.

**Leave Dependabot security updates off.** A fix for an alerted dependency arrives through the
weekly version updates into `dev`, or is raised by hand from the alert.

**Add no secret scanner to CI, and no pre-commit hook.**

**Scan the history once, with gitleaks, before turning anything on.** This was done on
2026-10-17. gitleaks 8.30.1 (MIT), from image
`ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f`,
ran with `--log-opts=--all --redact` over a mirror clone of 147 refs. 119 of them sit under
`refs/pull/*`: the head of every pull request, 93, and the test-merge refs of the open ones, 26.
Of the 647 commits on those refs it reported 576 scanned. The other 71
are the 69 merge commits, for which `git log -p` shows no patch, and two more it did not count.
Which two was not established: four commits in the history add no line at all.

It reported four matches, all of them `generic-api-key`, and none of them a secret:

| File | What matched | Why it is not a secret |
|---|---|---|
| `docker-compose.yml` | the host, port and database name in the Keycloak database's JDBC URL, which holds no credential | not a secret at all |
| `KeycloakRealmIT.java` | the same URL | the same |
| `KeycloakRealmIT.java` | `OWNER_INITIAL_PASSWORD` | the initial password of a user in a throwaway Keycloak container the test starts and discards |
| `TestTokens.java` (ledger) | `HMAC_SECRET` | a 32-byte HS256 key that signs a token the ledger must refuse, because it accepts RS256 only; nothing verifies against it |

**GitHub's own results, read about half an hour after both were turned on:**

- secret scanning: no alert, open or resolved;
- Dependabot: no alert, against a dependency graph of 443 packages.

## Consequences

**Makes easy**

- **An advisory against the npm or Python trees reaches the owner without waiting for a push.**
- **A provider's credential is blocked at push time**, on the server, on every machine, unless
  the pusher bypasses it with a reason, which GitHub records as an alert.
- **No new dependency, job or allow-list to keep.** The scanning is the platform's.

**Makes hard**

- **A generic secret is not caught by anything continuous.** GitHub matches providers' token
  formats. A password, a client secret or an HMAC key, the kind this repository actually holds,
  matches none of them, and the non-provider scan stayed disabled. The `.gitignore` entry for
  `.env`, the `${VAR}` placeholders `KeycloakRealmTest` pins, and review still carry that
  class.
- **The Java services are barely in the graph.**
  - Of its 443 packages, 311 are npm and 95 are Python, but only 31 are Maven.
  - 24 of those 31 carry no version, because they inherit it from the Spring Boot parent and its
    BOMs.
  - No transitive Maven dependency appears at all: not Spring, Tomcat, Jackson or Nimbus.

  "No alert" therefore says nothing about the ledger's or the gateway's runtime tree, and
  [#66](https://github.com/NaimElijah/FinAssist/issues/66) stays open.
- **Alerts read the default branch, `main`, not `dev`.** A vulnerable bump merged into `dev`
  raises nothing until `dev` is promoted, and a fix on `dev` leaves its alert open until then.
  The 443-package graph above is `main`'s.
- **A security fix is an alert, not a pull request.** Someone has to act on it.

**Accepted cost**

- **Alerts gate nothing.** They land in the Security tab and the owner's notifications. Whether a
  red check should block a merge at all is [#134](https://github.com/NaimElijah/FinAssist/issues/134).

## Alternatives considered

- **gitleaks as a CI job on every push.** It would catch generic secrets, which GitHub does not.
  Rejected for now:
  - Its first run found only false positives, so a job would start with an allow-list to keep it
    green.
  - It would be one more pinned image to maintain.
  - It would gate nothing while #134 is open.
- **Dependabot security updates.** Rejected: they open their pull requests against `main` whatever
  `target-branch` says, so every one would bypass `dev`.
- **An opt-in pre-commit hook running gitleaks.** Rejected: nothing else here uses pre-commit, and
  a hook protects only the machine that installed it. Push protection covers every push, from
  anywhere, though like a hook it can be bypassed on purpose.
- **Leave everything off until #134 is decided.** Rejected: the repository is already public, and
  an alert is worth having even when it blocks nothing.

## Revisit when

- **The repository goes private again.** GitHub's documentation makes secret scanning free for a
  public repository, and ties it and per-repository push protection to a Secret Protection
  licence otherwise. That was not tested here: the API was asked only once the repository was
  public. Read `security_and_analysis` back then, and add gitleaks to CI if scanning is off.
- **A generic secret is found committed**, the class GitHub's patterns miss. Add gitleaks to CI,
  with an allow-list for the four matches above.
- **Non-provider patterns become available here.** Turn them on.
- **#134 makes CI a merge gate.** A scanner job would then block something, which changes its
  worth.
- **#66 gives the Java services an audit, or their resolved trees are submitted to the dependency
  graph.** Read how far Dependabot alerts then reach into them, and say so here.
- **Dependabot security updates can target a branch other than the default.** Turn them on, aimed
  at `dev`.
- **pre-commit is adopted here for another reason, or a second contributor joins.** A local
  gitleaks hook then costs little to add, and catches a generic secret before it is ever pushed,
  which push protection cannot do.
