# ADR-045: Dependabot's updates are triaged weekly, and the toolchain lines move only by ADR

- **Status:** Accepted, extended by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md) and
  [ADR-062](ADR-062-every-kept-dependency-is-justified-and-has-a-revisit-trigger.md)
- **Date:** 2026-10-20

## Context

**By 2026-10-20, 26 Dependabot pull requests were open against `dev`, the oldest from
2026-09-01, and none had ever been merged** ([#138](https://github.com/NaimElijah/FinAssist/issues/138)).
Another 24 had been closed unmerged, most of them replaced by a newer release. Left to pile up,
they did five kinds of harm:

- **Their checks went stale.** Each branch was 82 to 515 commits behind `dev`, so a green check
  described a base `dev` had long left. #118 still showed red audit jobs from such a base.
- **The ledger and the gateway drifted apart.** Each service had its own `maven` and `docker`
  entries. The ledger's `maven` entry sat at its five-PR limit, so the ledger was offered Spotless
  3.10.2 and Maven 3.9.16 while the gateway got 3.10.4 and 3.10.0. JaCoCo arrived as two separate
  PRs.
- **A diff did more than its title said.** Both Maven-wrapper PRs re-committed `mvnw.cmd` with
  CRLF line endings in the index, against `.gitattributes`, and rewrote all 189 of its lines.
- **Toolchain jumps arrived as routine bumps.** One offered Temurin 21 → 24, which is not an LTS
  release, though [ADR-002](ADR-002-java-spring-boot-for-the-ledger.md) holds Java 21 and CI
  builds on 21. Another offered Python 3.13 → 3.14, though
  [ADR-003](ADR-003-python-fastapi-for-assistant-and-analysis.md) and `.python-version` hold
  3.13. Both changed only a Dockerfile, which CI does not build
  ([#65](https://github.com/NaimElijah/FinAssist/issues/65)), so their green checks proved nothing.
- **Documentation fell behind.** The README's tech-stack table, `docs/architecture/architecture.md`
  and several comments name versions. A Dependabot PR changes only the manifest.

Nothing stops a red or stale PR from merging
([#134](https://github.com/NaimElijah/FinAssist/issues/134)), so whatever keeps these honest is
practice, not enforcement.

## Decision

**A toolchain line moves only by ADR.** `.github/dependabot.yml` ignores `eclipse-temurin`
`>= 22` and `python` `>= 3.14`. Patch releases within the current lines still arrive. The change
that records a new line in an ADR deletes its ignore entry in the same commit.

**The ledger and the gateway move together.** One `maven` entry and one `docker` entry name both
directories, and group by dependency name (`group-by: dependency-name`, from GitHub's options
reference), so a single PR bumps a dependency in both services.

**A runtime security dependency goes to the owner before it merges.** Today that is Keycloak,
PyJWT, and anything that moves Spring Security. The owner approved Keycloak 26.8.1 this way.

**Dependabot's PRs are triaged in the week they open:**

- A PR behind `dev` is rebased with `@dependabot rebase` before its checks count. A check that
  ran against an older base counts as missing.
- Each PR is merged, or closed with a one-line reason.
- When a bump leaves a document stale, or Dependabot's diff touches more than the version, it is
  applied by hand on a branch instead, with the documents in the same commit. The PR is then
  closed with a pointer to that branch's PR. That is how #138's 26 were cleared.

## Consequences

**Makes easy**

- **A Java or Python line cannot change unasked.** Moving one takes an ADR, and its PR touches the
  Dockerfile, the CI setup and the Compose pins together.
- **The two Java services cannot drift apart through Dependabot.** Each dependency arrives as one
  PR covering both.
- **A green check means something again.** It describes today's `dev`.

**Makes hard**

- **The configuration takes effect only on `main`.** Dependabot reads `dependabot.yml` from the
  default branch, so the ignores and the grouping apply once `dev` is promoted. Until then it can
  still offer a newer Temurin 24.x or Python 3.14.x, and that PR is closed the same way.
- **The grouping has not yet been seen working here.** `check-jsonschema`'s Dependabot schema
  validates each entry's keys, including `directories`, but not the keys inside a group, so only
  GitHub's documentation vouches for `group-by`. Where the two directories' constraints on a
  dependency are incompatible, the documentation says Dependabot opens separate PRs.
- **Triage is manual.** Nothing enforces the weekly cadence, and the owner's time pays for it.
- **The ignore hides Java 25, the next LTS release.** Nothing will propose it, so someone has to
  look.

**Accepted cost**

- **Many of Dependabot's PRs end closed rather than merged**, because the bump landed by hand. Its
  history then reads as unmerged, though the versions it proposed are on `dev`.

## Alternatives considered

- **Renovate.** It groups, schedules and auto-merges natively. Rejected: it is a new GitHub App
  with write access to a public repository, and one more configuration to learn. Dependabot alerts
  are already GitHub's ([ADR-043](ADR-043-github-secret-scanning-and-dependabot-alerts.md)), and
  the pile-up came from no one triaging, not from the tool.
- **Auto-merging patch updates.** Rejected for now: no status check is required (#134), so
  auto-merge would merge on a red or missing check.
- **`@dependabot ignore this major version` comments instead of `ignore` entries.** Rejected:
  Dependabot stores those outside the repository, where review and `git log` cannot see them.
- **Version updates off, with every bump made by hand.** Rejected: nothing else would notice a new
  release, and the Java services have no audit at all
  ([#66](https://github.com/NaimElijah/FinAssist/issues/66)).
- **Rebasing and merging #138's 26 PRs as they stood.** Rejected for that backlog: their npm and
  uv lockfiles conflict with each other, so it meant about ten serial rounds of rebase and CI. Two
  of the PRs damaged `mvnw.cmd`, and the Java pair would still have drifted. A single current PR
  with a clean diff is still merged as it stands.

## Revisit when

- **#134 makes CI a merge gate.** Auto-merging patch updates then stops being a merge on an
  unchecked PR, and is worth reconsidering.
- **An ADR adopts a new Java or Python line.** Delete that ignore entry in the same change.
- **Dependabot opens separate ledger and gateway PRs for one dependency.** The grouping did not
  work. Merge such a pair together, and record that here.
- **More than one week's PRs are open at once.** The cadence is not holding, and Renovate or
  auto-merge deserves another look.
- **A second contributor joins.** Triage then needs a named owner.
