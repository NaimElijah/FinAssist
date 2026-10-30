# ADR-003: Python 3.13 and FastAPI for the assistant and analysis service

- **Status:** Accepted, extended by
  [ADR-046](ADR-046-architecture-rules-read-the-code-never-load-it.md) and
  [ADR-062](ADR-062-every-kept-dependency-is-justified-and-has-a-revisit-trigger.md), its Python 3.13 line held by
  [ADR-045](ADR-045-dependabot-triage-and-toolchain-holds.md)
- **Date:** 2026-08-29

## Context

The assistant answers natural-language questions over the owner's financial data, and the
analysis capability computes portfolio and market figures. Both live in ecosystems that are
Python-first: the Anthropic SDK and its surrounding evaluation and tracing tooling, and the
pandas/numpy/scipy numerical stack. This was the decisive argument in
[ADR-001](ADR-001-polyglot-java-python-split.md).

Python 3.11.0 is what is installed on the development machine. That is an old patch of an
ageing minor, and `pandas` 3.x requires 3.11 or newer with no headroom.

## Decision

The assistant is Python 3.13 on FastAPI, managed with `uv` and a committed `uv.lock`.
Dependencies are pinned exactly in `pyproject.toml`; ranges are not used, because a range
makes a lockfile a suggestion.

Ruff is both linter and formatter; mypy runs in `--strict`. Both gate CI. The package is
laid out to mirror the ledger — `domain`, `application` (with its ports), `adapters` — and
the domain's freedom from frameworks is enforced by a test rather than by convention:
`assistant/tests/test_architecture.py` imports the domain in a subprocess and asserts that
no framework module was loaded.

Test functions are named `Given<scenario>_When<action>_Then<result>`. Ruff's `N802` is
disabled for `tests/**` in `pyproject.toml`, with the reason recorded there, because the
naming convention and the rule cannot both hold.

## Consequences

**Makes easy**

- Native access to the LLM and numerical libraries the capability actually needs.
- `uv sync --locked` reproduces an exact environment locally and in CI from one file.
- Ruff replaces the usual flake8 + isort + black stack with a single pinned tool.

**Makes hard**

- Python 3.13 is not what is installed locally (3.11.0); the toolchain must be upgraded, or
  the service run through `uv`, which fetches its own interpreter.
- `mypy --strict` cannot express what Java's sealed types can. Invariants owned by this
  service are enforced by tests and `Decimal` discipline, not by the compiler — which is
  precisely why the ledger is not written here.
- Two lint configurations to keep aligned with `.editorconfig`.

## Alternatives considered

- **Poetry or PDM instead of uv** — both work; uv is materially faster, resolves and locks
  with one tool, and manages the interpreter itself, which removes the local 3.11/3.13 gap
  as a setup obstacle.
- **Django or Flask instead of FastAPI** — Django brings an ORM and admin this service does
  not want, since it is not the writer of record. Flask needs extra libraries to reach
  FastAPI's typed request and response validation, which is the feature being bought.
- **Black + flake8 + isort instead of Ruff** — three tools, three configurations and three
  pins to keep aligned, for behaviour Ruff already provides.
