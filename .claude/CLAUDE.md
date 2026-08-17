# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## State of the repository

Empty scaffold — no source code, no build file, no test runner. Nothing below can be
inferred from code yet. Do not document commands, layering rules, or conventions here
until they exist in the repository and can be pointed at.

## Commands

- Build: TODO — none defined.
- Run: TODO — none defined.
- Test (all): TODO — none defined.
- Test (single): TODO — none defined.
- Lint / format: TODO — none defined.

When a build file is added, replace each `TODO` with the exact command, and keep this
list in sync with `README.md`.

## Layout

- `docs/ADRs/` — one file per architectural decision, `ADR-NNN-short-kebab-title.md`,
  zero-padded and monotonically increasing. Append-only: supersede, never rewrite.
- `docs/requirements/` — specifications and requirement documents.
- `docs/architecture/` — architecture notes; diagrams live as code, not images.
- `.claude/` — project agent configuration; tracked in git. Only
  `.claude/settings.local.json` is ignored.

## Conventions

None observable yet. Record a convention here only once the code demonstrates it.
