# ADR-005: Docker Compose is the run mechanism; the Makefile is the interface

- **Status:** Accepted, corrected by
  [ADR-047](ADR-047-accepted-records-reconciled-with-the-system-as-built.md)
- **Date:** 2026-08-29

## Context

The repository baseline requires one command to set up, one to run and one to test,
identical locally and in CI. [ADR-001](ADR-001-polyglot-java-python-split.md) makes that
harder: there are two toolchains to front.

The development machine is Windows 10 with PowerShell as the shell. A probe of it found
Java 21.0.9, Maven 3.9.6, Python 3.11.0, Docker 29.7.2 and Compose v5.4.0 — and **no
`make`, no `just`, and no `bash` on `PATH`**. A Makefile alone would therefore document
commands that do not run on the machine they were written on.

## Decision

Docker Compose is the mechanism. `docker-compose.yml` defines the runnable stack and works
unchanged on Windows, on Linux and in CI, using tools that are already installed.

The `Makefile` is the interface, not the mechanism: each target is a thin wrapper over
`docker compose`, `./mvnw` or `uv`. CI invokes the same targets a developer does, so the
pipeline and the local commands cannot drift.

`make` is therefore a convenience, not a prerequisite. The README documents the raw command
behind each target so that nobody is blocked, and gives the one-line install for Windows.

## Consequences

**Makes easy**

- The pipeline is defined once, in the Makefile, and read by both CI and a developer.
- Postgres needs no local install, and its version is identical everywhere.
- Adding a service is a Compose entry, not a new page of setup instructions.

**Makes hard**

- `make` must be installed to use the short commands on Windows, or the raw command copied
  out of the Makefile.
- Compose is not a task runner; anything conditional stays in the Makefile, so the logic is
  split across two files.
- Running the services in containers is slower to iterate on than running them natively,
  which is why the Makefile keeps native `./mvnw` and `uv` targets for the inner loop.

## Alternatives considered

- **Makefile alone** — the convention, and the first option the baseline lists. Rejected as
  the primary mechanism because `make` is absent from the development machine, so its
  commands could not be verified there.
- **`just`** — a single cross-platform binary with better Windows support than `make`.
  Rejected for now: it is a new dependency, also not installed, and it buys little over a
  Makefile once Compose does the real work. Reconsider if the Makefile grows logic that
  POSIX `sh` makes awkward.
- **PowerShell scripts** — native to the development machine and needing no install, but
  not what Linux CI runs, so the local and CI commands would diverge — exactly what the
  baseline forbids.
