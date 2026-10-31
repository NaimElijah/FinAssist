# ADR-054: Each service ships as a two-stage image, built from source, run as a non-root user

- **Status:** Accepted, extended by
  [ADR-063](ADR-063-every-container-holds-only-the-privilege-it-uses.md) and
  [ADR-064](ADR-064-what-the-build-fetches-is-pinned-by-content.md)
- **Date:** 2026-10-29

Records a decision three Dockerfiles already embody. It extends
[ADR-005](ADR-005-docker-compose-as-the-run-mechanism.md), which chose Compose as the run
mechanism, and sits beside [ADR-045](ADR-045-dependabot-triage-and-toolchain-holds.md), which holds
the base-image lines.

## Context

**No record says how a service becomes an image**
([#339](https://github.com/NaimElijah/FinAssist/issues/339)). ADR-005 chose Compose and the Makefile. [ADR-040](ADR-040-the-smoke-check-trigger-fired.md) tests
the packaged jar, not the image. ADR-045 notes that a Dockerfile-only bump is built by no CI job
([#65](https://github.com/NaimElijah/FinAssist/issues/65)). The only image ADR-031 describes is
Keycloak's. The shape of the ledger's, the gateway's and the assistant's images is in the
Dockerfiles and their comments alone.

**What the three Dockerfiles do** (`backend/ledger/Dockerfile`, `backend/gateway/Dockerfile`,
`backend/assistant/Dockerfile`; `docker-compose.yml` builds each with `build: ./backend/<name>`).

- **Java.** A JDK build stage, `eclipse-temurin:21.0.9_10-jdk-noble`, copies the Maven wrapper and
  `pom.xml` first so `dependency:go-offline` is cached across source changes, then runs
  `./mvnw -B package -DskipTests`. A JRE runtime stage, `eclipse-temurin:21.0.9_10-jre-noble`,
  copies the one repackaged jar and runs `java -jar`.
- **Python.** A build stage, `python:3.13.8-slim-trixie`, takes `uv` 0.12.7 from
  `ghcr.io/astral-sh/uv`, and runs `uv sync --locked --no-dev` twice: once for dependencies, once
  for the project with `--no-editable`. `UV_COMPILE_BYTECODE=1` compiles once at build. A runtime
  stage of the same base copies only `.venv`.
- **User.** Each runtime stage creates a system user and group with no home directory, and runs as
  it. The two Java images hand the jar to that user. The assistant leaves `.venv` owned by root, so
  that code execution in the process cannot rewrite an installed package.
- **Pins.** Both stages of an image pin the same base generation, Ubuntu Noble or Debian trixie,
  checked with `docker manifest inspect` before the file was committed.
- **Tests.** The Java builds skip them. The ledger's Dockerfile says why: its `*IT` tests need a
  Docker daemon that an image build does not have, and CI runs the suite anyway. The assistant
  installs no test dependency, with `--no-dev`.

**The build is shaped by the machine that runs Compose.** ADR-005 made Compose the mechanism, so
`docker compose up --build` should work on a clone that has Docker and nothing else. A host JDK or
`uv` is needed for the native inner loop, not to run the stack.

## Decision

**Each of the ledger, the gateway and the assistant ships as a two-stage image, built from source
in the image, and run as a non-root user.**

- **Two stages.** A build stage compiles or installs. A runtime stage copies one artifact out of it
  and carries no compiler, wrapper, build cache or `uv`.
- **Built from source, inside the image.** The image build needs Docker and the repository, and no
  JDK or `uv` on the host. The dependency layer comes before the source copy, so only a change to
  `pom.xml`, or to `pyproject.toml` and `uv.lock`, re-resolves dependencies.
- **Tests do not run in the image build.** CI runs them (`make test-ledger`, `make test-gateway`,
  `make test-assistant`). The image carries an artifact built from the same source the suites verified.
- **A non-root user.** A system user with no home directory runs every service. The assistant's
  packages stay root-owned.
- **Both stages pin one base generation**, and the base moves only as ADR-045 allows. Temurin
  stays on Java 21 and Python on 3.13 until an ADR moves them.
- **No image is pulled from, or pushed to, a registry that FinAssist owns.** Each is built on the
  machine that runs it.

Keycloak's `ops/keycloak/Dockerfile` ([ADR-031](ADR-031-keycloak-as-the-identity-provider.md)) is
two-stage too, but it configures an upstream image rather than building this repository's source.
This record does not govern it, and does not say which user it runs as.

## Consequences

**Makes easy**

- **A clone with Docker runs the stack.** No JDK, Maven, `uv` or Node is needed to start it.
- **The runtime images are small and plain.** A JRE or a slim Python, one artifact, no build tools.
- **A compromised service does not run as root**, and the assistant cannot rewrite its own code.
- **Rebuilds are cheap.** A source edit re-runs only the final build and copy.

**Makes hard**

- **Nothing in CI builds an image.** A Dockerfile breakage, such as a bad base bump, surfaces on a
  developer's `up --build` ([#65](https://github.com/NaimElijah/FinAssist/issues/65)).
- **The image is not what CI tests.** `GatewayPackagedJarIT` starts the jar `package` wrote, which
  the image also builds. The ledger's and the assistant's shipped artifacts have no start-up check
  yet ([#234](https://github.com/NaimElijah/FinAssist/issues/234)).
- **Every machine builds its own images.** There is no signing, no SBOM and no scan, since no
  image leaves the machine.
- **The Java images and the assistant differ on ownership.** The Java images hand the jar to the
  service user, which the assistant's image deliberately does not do for its packages. The Java
  Dockerfiles give no reason. This record does not decide whether to align them.

**Accepted cost**

- **Skipping tests in the build trusts CI.** An image built from a commit CI never ran carries an
  untested artifact. Nothing here prevents that.

## Alternatives considered

- **A single stage on the JDK or the full Python image.** Rejected: the runtime would carry the
  compiler, the Maven cache and `uv` for no use.
- **Build the jar or the virtualenv on the host and copy it in.** Rejected: `docker compose up
  --build` would need a host JDK and `uv` first, and ADR-005 makes Compose the one entry point.
- **Run the test suite inside the image build.** Rejected: the Java `*IT` tests need a Docker
  daemon that an image build lacks (the ledger's Dockerfile says so), and CI already runs them.
- **Run as root.** Rejected: the owner's rule is least privilege for every credential, role and
  container, and a service has no need of root.
- **Distroless or minimal bases.** Not weighed against evidence here. They matter once images are
  scanned or signed, which nothing does yet. Deferred, not rejected.

## Revisit when

- **Images are built in CI, or pushed to a registry** ([#65](https://github.com/NaimElijah/FinAssist/issues/65),
  [#188](https://github.com/NaimElijah/FinAssist/issues/188)). Signing, an SBOM, image scanning and
  a distroless or buildpack base all become questions, and "built on the machine that runs it"
  stops being true.
- **A service needs a start-up check in its image** ([#234](https://github.com/NaimElijah/FinAssist/issues/234)).
  The image, not the jar, is then what is tested.
- **The Java and Python lines move** by an ADR (ADR-045). The Dockerfile, CI setup and Compose pins
  change together.
- **A container runs with a user id the host must know**, such as a bind-mounted volume that must
  be writable. A named user with a fixed id then replaces the system user.

## What lands with this record

No code. Documentation only: ADR-005 reads `Accepted, extended by ADR-049 and ADR-054, corrected by
ADR-047`, with a row and a note in `docs/ADRs/README.md`.
