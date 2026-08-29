# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Entries are generated from the [Conventional Commit](https://www.conventionalcommits.org/)
history; write the commit message well and this file writes itself.

## [Unreleased]

### Added

- Repository baseline: requirements, architecture and ADR documents, licence, editor and
  formatter configuration, pinned toolchains for both services, Docker Compose entry point,
  and a CI pipeline that gates build, lint, format and dependency audit.
- Web client (`frontend/web`): React 19 on Vite 8 with TypeScript, Vitest, ESLint and
  Prettier, one feature directory per user-facing bounded context.

- Domain layer for all five bounded contexts. Accounting: double-entry `Transaction` with a
  balancing invariant, five account types, a sealed lifecycle, and a derived `Balance`.
  Budgeting: periodic `Budget` with per-category limits and derived remaining. Portfolio:
  lot-based FIFO cost basis. Assistance: an answer stating a figure must cite its evidence.
  Market data: quotes and staleness against a supplied clock. `Money` and `Currency` in both
  languages.

### Changed

- Services moved under `backend/`, and both are now packaged by bounded context rather than
  by layer: `accounting` and `budgeting` in the ledger, `portfolio`, `assistance` and
  `marketdata` in the assistant. A context is one directory with its own hexagon.
- Architecture tests extended to enforce context isolation in all three codebases; each rule
  was verified by making it fail.
- The ledger's ArchUnit rules are no longer dormant: `archRule.failOnEmptyShould=false` was
  deleted with the first domain class, and all five now run against real code.

[Unreleased]: https://github.com/NaimElijah/FinAssist/commits/main
