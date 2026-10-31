# ADR-064: What the build fetches is pinned by content where the tool allows it, and Dependabot moves every pin

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-045](ADR-045-dependabot-triage-and-toolchain-holds.md), which decides how Dependabot's
updates are triaged and which toolchain lines it holds, and whose header comment in
`.github/dependabot.yml` said Compose's image tags could only be bumped by hand. It extends
[ADR-054](ADR-054-each-service-ships-as-a-two-stage-image-run-as-a-non-root-user.md), whose Java
build stages run the Maven wrapper, because their build stage now installs one tool. It corrects
[ADR-062](ADR-062-every-kept-dependency-is-justified-and-has-a-revisit-trigger.md) and
[ADR-031](ADR-031-keycloak-as-the-identity-provider.md), whose bodies say Dependabot does not read
Compose image tags, so the one-shots' Python tag and `keycloak-db`'s are bumped by hand.

## Context

**Scan 5 of 12 read CI, the images and Dependabot on 2026-10-30**
([#342](https://github.com/NaimElijah/FinAssist/issues/342)). It found three pins weaker than the
rest of the build's.

- **Every GitHub Action was pinned by tag.** `actions/checkout@v7.0.1` and the four other actions
  named a tag, which whoever controls the action's repository can move. Nobody can move a commit
  SHA. One of the five, `astral-sh/setup-uv`, is a third party's. This was item 3 of
  [#161](https://github.com/NaimElijah/FinAssist/issues/161).
- **The Maven distribution was trusted on TLS alone.** Both services' `maven-wrapper.properties`
  name `apache-maven-3.10.0-bin.zip` on Maven Central, with no `distributionSha256Sum`. CI, both
  image builds and `make setup` download that zip and run it with the build's privileges.
  `mvnw` verifies a checksum when one is given.
- **Compose's own image tags were bumped by hand.** These are the two PostgreSQL databases, the two
  Python one-shots, the collector, Prometheus and Grafana. `dependabot.yml` said Dependabot has no
  `docker-compose` ecosystem, and so did
  [#80](https://github.com/NaimElijah/FinAssist/issues/80). That stopped being true on 2025-02-25,
  when GitHub made the ecosystem generally available
  ([changelog](https://github.blog/changelog/2025-02-25-dependabot-version-updates-now-support-docker-compose-in-general-availability/)).

**Three facts about the tools shaped the decision.**

- **Dependabot keeps a SHA pin and its version comment in step.** It moves the SHA and the
  trailing `# vX.Y.Z` comment together. dependabot-core's `github_actions` file updater has a
  `VersionCommenter` for this. It has not yet been seen running in this repository.
- **Dependabot recomputes the Maven distribution's checksum.** When it bumps the wrapper,
  dependabot-core's Maven `wrapper_updater.rb` removes `distributionSha256Sum`, runs
  `wrapper:wrapper`, and writes the new SHA-256 back if the file had one. A hand edit of
  `distributionUrl` does not, and the wrapper then refuses the download. The wrapper plugin
  rewrites `maven-wrapper.properties` whole, so a comment there would not survive a bump.
- **`mvnw` checks the `.tar.gz` against the zip's checksum when `unzip` is missing.** It accepts
  only a `*-bin.zip` URL. Without `unzip` it quietly downloads the `.tar.gz` instead and checks
  that against the zip's sum, which fails. `eclipse-temurin:21.0.13_9-jdk-noble` has no `unzip`.
  So with the sum set, `docker compose up --build` failed while CI's runners, which have `unzip`,
  stayed green.

## Decision

**Every action in `.github/workflows/ci.yml` is pinned to a full commit SHA, with its version in a
trailing comment.** Each SHA was resolved from its tag with
`gh api repos/<repo>/commits/<tag>` and cross-checked against `git/ref/tags/<tag>`. Each tag is a
lightweight tag that points at the commit. `astral-sh/setup-uv` moves to v10.3.2 in the same
change, which applies Dependabot's PR #363.

| Action | Version | Commit |
|---|---|---|
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | v6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |
| `actions/setup-node` | v7.1.0 | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| `actions/upload-artifact` | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| `astral-sh/setup-uv` | v10.3.2 | `07a047e608db84b3d8c06cd9a7c7dcc9c355c18e` |

**The Maven distribution is pinned by SHA-256.** Both `maven-wrapper.properties` files carry
`distributionSha256Sum=1f6d9909266510f039f59aa0e13dcd2c66da85f043e56276e41f2918f8bddaff`, the
SHA-256 of `apache-maven-3.10.0-bin.zip`. The value was computed from a download whose SHA-512
matched Apache's published `apache-maven-3.10.0-bin.zip.sha512`, on `downloads.apache.org` and on
Central alike.

**Both Java build stages install `unzip`,** so `mvnw` fetches and verifies the zip it pins.

- **Its justification as a dependency.** It replaces nothing, and it is the archive tool `mvnw`
  needs to verify a pinned distribution. It comes from Ubuntu Noble's archive under the Info-ZIP
  licence, and Ubuntu maintains it.
- **It is a build-stage tool, never shipped.** The runtime stage copies only the jar.
- **It is not version-pinned.** An apt pin breaks the build when the archive drops that version,
  and `unzip` changes rarely.

**Dependabot moves Compose's image tags.** A `docker-compose` entry for `/` targets `dev`, as
every entry does (ADR-045). It has two holds.

- **Python is held below 3.14.** It is the same line ADR-045 holds for the assistant's image,
  because `auth-smoke` and `secret-check` run the assistant's Python. Delete it together with that
  hold.
- **PostgreSQL majors are held,** with `update-types: ["version-update:semver-major"]`. The hold
  is not a version range, because `db` (18.x) and `keycloak-db` (17.x) share one dependency name,
  and minor releases still arrive. A new major cannot start on the old one's data directory. It
  is moved by a backup, a new volume and a restore (ADR-038), not by a tag change.

The collector, Prometheus and Grafana are not held. ADR-045's triage handles them as it handles
any image.

## Consequences

**Makes easy**

- **A moved tag can no longer change what CI runs.** Dependabot proposes each new SHA as a PR,
  which the weekly triage reads (ADR-045).
- **A substituted Maven distribution is refused whenever the wrapper downloads one.** That covers
  both image builds, a machine that has not yet unpacked this version, and CI whenever
  setup-java's wrapper cache misses. The cache is keyed on the properties file, so this change
  forces one fresh, checked download.
- **Compose's PostgreSQL, Python and monitoring tags are proposed like every other dependency**,
  rather than when someone remembers.

**Makes hard**

- **Two pins move in separate PRs and must be applied together by hand.**
  - The Python one-shots' tag and the assistant's Dockerfile tag arrive from two different
    entries, because a Dependabot group cannot span entries.
  - A PostgreSQL minor bump arrives without the Testcontainers pins. The ledger's
    `PostgresFixture` and the gateway's `KeycloakRealmIT` name the same tags as Java string
    literals, which no ecosystem edits, and nothing compares them with `docker-compose.yml`. A
    postgres PR is applied by hand with both. A check that compares them is #80's.
- **A hand edit of the wrapper's `distributionUrl` fails the build** until its sum is updated in
  the same change. Each Java service's `CLAUDE.md` says so.
- **A distribution unpacked before the pin is never checked.** `mvnw` verifies a download, not an
  installation it already has. Deleting `~/.m2/wrapper/dists/apache-maven-3.10.0` once makes the
  next run download and check it.
- **Every host that runs `./mvnw` now needs `unzip`.** Without it, `mvnw` fetches the `.tar.gz`
  and fails the zip's checksum, with a message that names the checksum rather than the missing
  tool. The README lists it among the prerequisites.
- **Nothing refuses a new unpinned action yet.** Review and Dependabot hold the pins until the
  workflow is linted ([#161](https://github.com/NaimElijah/FinAssist/issues/161)).
- **The build stage now reaches Ubuntu's archive** as well as Maven Central, and its `unzip` floats
  within Noble.
- **The configuration takes effect only on `main`** (ADR-045). Until then, Dependabot still uses
  the old `dependabot.yml`.

## Alternatives considered

- **Pin the actions by tag, and trust the publisher.** Rejected: GitHub's own hardening guide
  names a full SHA as the only immutable reference, and a third party publishes one of the five.
- **Pin Compose's images by digest.** Not now: an exact tag is the minimum the owner's rule
  accepts, and digests arrive with images built and pushed in CI
  ([#175](https://github.com/NaimElijah/FinAssist/issues/175),
  [#185](https://github.com/NaimElijah/FinAssist/issues/185)). **Revisit when** CI publishes
  images.
- **Build with the official `maven:` image instead of the wrapper and `unzip`.** Rejected: it
  would be a second base image to pin and vet, and it would leave `mvnw`'s version, which CI and
  `make setup` use, separate from the image's.
- **Skip the checksum, because Central is served over TLS.** Rejected: the pin costs one line per
  service, and Dependabot maintains it.

## Revisit when

- **CI builds and publishes the images** (#65, #175). Pin the base images by digest then.
- **The workflow is linted (#161).** Make an unpinned `uses:` fail CI there.
- **Dependabot leaves a SHA's version comment behind, or drops the wrapper's sum.** The tool
  behaviour this record relies on has changed. Record it here.
- **#80 adds a check that compares the Testcontainers tags with Compose's.** The manual step above
  goes.
- **An ADR adopts a new Python line or a new PostgreSQL major.** Delete the matching hold in the
  same change.

## What lands with this record

- **CI.** `.github/workflows/ci.yml`: the five pinned actions; `setup-uv` at v10.3.2. The PR's own
  CI run proves the workflow, since `actionlint` is not installed.
- **The wrapper and the images.** Both `maven-wrapper.properties` files carry the sum. Both Java
  Dockerfiles install `unzip` in their build stage. Proven three ways:
  - both images built;
  - `./mvnw -v` passed against a fresh Maven home;
  - with one digit of the sum changed, it failed with
    `Failed to validate Maven distribution SHA-256`.
- **Dependabot.** `.github/dependabot.yml` gains its `docker-compose` entry, and its header is
  corrected.
- **Documentation.** The README's known limitations and `docs/architecture/architecture.md`'s
  dependency table no longer say Compose's tags are bumped by hand. `backend/ledger/CLAUDE.md` and
  `backend/gateway/CLAUDE.md` describe the checksum. ADR-045 and ADR-054 read
  `Accepted, extended by … ADR-064`, ADR-062 `Accepted, corrected by ADR-064`, and ADR-031
  `… corrected by ADR-047 and ADR-064`, as do their rows in `docs/ADRs/README.md`, with a row and
  a note for this record.
