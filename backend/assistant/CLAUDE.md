# CLAUDE.md — assistant

Python 3.13, FastAPI. The root `.claude/CLAUDE.md` binds here too; this file adds what binds the
assistant alone.

## Layout

Contexts `portfolio/`, `assistance/` and `marketdata/` under `src/finassist_assistant/`, each with
`domain/`, `application/{port,usecase}/` and `adapters/{inbound,outbound}/` (`marketdata` has
only `outbound/`), plus `shared/`. The composition root is `src/finassist_assistant/adapters/`:
`__main__.py`, `app.py`, `wiring.py`, `settings.py`, `observability.py`, `auth.py`
(`BearerAuthentication`, `RequirePermission`), `hosts.py` (`HostAllowList`), `body_limit.py`
(`RequestBodyLimit`), `problems.py`, `openapi.py` and `seed.py`.

Only `portfolio` is served, under `/api/v1`, behind `HostAllowList` and the problem+json handling
in `adapters/problems.py`. Its OpenAPI document is generated from the code and committed at
`contracts/assistant-v1.openapi.json`, pinned by `tests/test_contract.py`. The assistant serves
`/health` and `/ready` and assembles its use cases at start-up. It has no store, no ledger client
and no price feed yet.

## Commands

From `backend/assistant`:

- **Run** — `uv run python -m finassist_assistant.adapters`.
- **Test** — `make test-assistant`: `uv run pytest --cov --cov-report=term-missing
  --cov-report=xml`. One test: `uv run pytest tests/test_architecture.py -k <name>`.
- **Lint** — `uv run ruff check .`, `uv run ruff format --check .`, `uv run mypy src tests`.
  **Audit** — `uv run pip-audit`.
- **Format** — `make format` formats `ops/smoke`, `ops/backup`, `ops/secret_check` and
  `ops/compose_check` with the assistant: `uv run ruff format . ../../ops/smoke ../../ops/backup
  ../../ops/secret_check ../../ops/compose_check`, `uv run ruff check --fix . ../../ops/smoke
  ../../ops/backup ../../ops/secret_check ../../ops/compose_check`.
- **In a fresh worktree**, run `uv sync --locked --extra dev` before pytest (`--offline` uses the
  uv cache). The suite needs the `dev` extra (pytest, hypothesis), which `uv run`'s own sync
  leaves out, so `uv run pytest` alone fails — sometimes with an import error naming `jwt`, a
  runtime dependency, when a pytest from outside the environment runs instead. Every test runs
  with the `ASSISTANT_*` and `OTEL_*` variables and `ANTHROPIC_API_KEY` cleared, whatever their
  case (`tests/conftest.py`), so an exported `.env` cannot change a result.
- **Re-lock** — `make lock`: `uv lock`, required after any `pyproject.toml` dependency edit, or
  CI's `uv sync --locked` fails.
- **Regenerate the contract after every endpoint change** — `uv run pytest tests/test_contract.py`
  (no container needed). On a mismatch, copy `.contract-drift/assistant-v1.openapi.json` over
  `contracts/assistant-v1.openapi.json`, then run `make generate-web-api`.

## Rules

- **No aggregates or repositories here** (ADR-007). The cores are pure functions over frozen
  dataclasses.
- **The ports are synchronous** (ADR-021). Do not add `async` without the trigger: a use case that
  must fan out concurrent I/O. `pytest-asyncio` is not a dependency.
- **The composition root is `src/finassist_assistant/adapters/`.** It is the only package that may
  import FastAPI, structlog or OpenTelemetry outside a context's own `adapters`. No context may
  import it.
- **Foreign shapes stop at the anti-corruption layer.** Ledger DTOs, Anthropic responses and
  price-feed payloads become local model types in `adapters/outbound/*` and nowhere else.
- **A translation calls the upstream context's use case, never its out-ports or adapters.** A
  translation is a class in the composition root that implements a context's port `Protocol`;
  only a port it names as a base is exempt, so defining an out-port's methods exempts nothing.
  `Given_a_translation_When_parsed_Then_it_names_no_out_port_or_adapter` fails the build
  otherwise; the ledger's mirror is `translations_reach_the_upstream_context_only_through_its_in_ports`.
- **Adding a dependency can widen an architecture rule silently.** The forbidden list is derived
  from `pyproject.toml` and mangles `-` to `_`, so a distribution whose import root differs from
  its name (`opentelemetry-sdk` → `opentelemetry`) is missed unless named in the explicit set in
  `tests/test_architecture.py`. `jwt` and `cryptography` are named there for the same reason:
  `PyJWT[crypto]` mangles to the literal `PyJWT`, which matches neither `jwt` (the real import
  root) nor `pyjwt`, and `cryptography` arrives only transitively through the `crypto` extra, so no
  declared dependency name can ever produce it (ADR-030, ADR-032). Standard-library I/O is a set of
  its own, `FORBIDDEN_STANDARD_IO`, since no declared dependency can name it, and a call to the
  builtin `open` is refused by a rule of its own.
- **The router is a factory taking use cases, and never reads `app.state`.** Its handlers are
  `def`, never `async def` — every port behind them is synchronous (ADR-021).
- **Every route declares exactly one permission dependency, built through the `require` factory
  the composition root passes into the router** (ADR-030, ADR-032):
  `Depends(require(READ_PORTFOLIO))` on each of `portfolio_router`'s three routes. A coverage test
  in `tests/test_authorization.py` fails the build if a route is added without one.
  `RequirePermission`, the factory `app.py` passes as `require`, reads the `Principal` that
  `BearerAuthentication` already verified from `request.scope`. The router never imports it: that
  would be the composition-root import the slice rule forbids. `/health`, `/ready` and
  `/openapi.json` stay public; every business route needs a bearer token and `portfolio:read`.
- **`BearerAuthentication` runs inside the correlation middleware, not outside it.** It is added to
  the app before `correlate`, so Starlette's last-added-runs-first rule puts `correlate` outside it
  and a refused request still gets a correlation id and a request-answered line. The order is
  `HostAllowList` → correlate → body limit → auth → routes; `HostAllowList` stays outermost, added
  last, so a foreign `Host` is refused before authentication is even attempted.
- **No body is buffered past `MAX_REQUEST_BODY_BYTES`** (64 KiB, as on the ledger and the
  gateway; ADR-058). `RequestBodyLimit` sits outside authentication, because FastAPI reads and
  parses a body before any dependency runs: a declared `Content-Length` over it is refused 413
  `CONTENT_TOO_LARGE` before authentication and before the body is read. A chunked body is
  counted as the route reads it, after authentication, so an unauthenticated one is answered 401
  unread. Pinned by `tests/test_body_limit.py`.
- **A key-source outage is told apart where it is raised, never by an exception's message** (the
  root's WARN-versus-ERROR rule, ADR-037). `BearerAuthentication._report_unavailable` logs the
  outage, and failures are mapped only inside `_KeySetClient.fetch_data`, which never sees a
  token. Pinned by `tests/test_authorization.py`. `tests/test_auth.py` serves the assistant the
  same key-set bodies that `LedgerJwksBodyTest` serves the ledger: keep the two in step.
- **The problem handlers live in the composition root**, `adapters/problems.py` — the one package
  allowed to see every context's exceptions. The ledger maps them per context, a `*ProblemAdvice`
  in each, and keeps only a fallback, `LedgerProblemHandler`, in `composition`; only `portfolio` is
  served here, so one module maps them all.
- **A logged 500 never carries an amount.** The guarantee is the problem handlers: `_withheld`
  logs a 500 caused by a validation failure through `_rejections` — each error's `loc` and `type`
  alone, never its message, its input or `exc_info`, because FastAPI's `ResponseValidationError`
  quotes the input however a model is configured. The wire models' `hide_input_in_errors=True` is
  defence in depth for anything else that prints a pydantic `ValidationError`, not the guarantee.
  A 503 `PRICE_UNAVAILABLE` logs the event and the symbol only, never a traceback.
- **The development seed is off by default** (`ASSISTANT_DEV_SEED`) and lives only in the
  composition root (`adapters/seed.py`). It loads fictional holdings and quotes so the endpoints
  have something to answer by hand.
- **The shipped image has no start-up check yet** (#234; ADR-040); the gateway's
  `GatewayPackagedJarIT` is the model.
