# Both stages pin the same Debian trixie generation of python:3.13, verified with
# `docker manifest inspect` before this file was committed (CI's setup-uv pins no Python of its
# own; the development machine's `.python-version` names "3.13"). uv is taken from its own
# official image rather than installed with pip, pinned to 0.12.7 to match `uv --version` on the
# development machine; CI's astral-sh/setup-uv@v10.0.1 step states no uv version of its own.

# --- Build -----------------------------------------------------------------------------------
FROM python:3.13.8-slim-trixie AS build
WORKDIR /build

COPY --from=ghcr.io/astral-sh/uv:0.12.7 /uv /uvx /usr/local/bin/

# The lockfile is synced before the source is copied, so only editing pyproject.toml or uv.lock
# busts this layer. --no-install-project defers installing the local package itself to the second
# sync, once its source exists; syncing it here would fail with nothing under src/ yet.
#
# `dev` is an optional extra in pyproject.toml, not a uv dependency group — uv sync installs no
# extra unless --extra names it, so it is already excluded here. --no-dev is kept alongside it in
# case a dependency-groups "dev" is ever added, since that flag governs groups, not extras.
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project

COPY src/ src/
# --no-editable installs the project as an ordinary package rather than a link back to this
# stage's /build/src, which would dangle once only .venv is copied into the runtime stage below.
RUN uv sync --locked --no-dev --no-editable

# --- Runtime -----------------------------------------------------------------------------------
FROM python:3.13.8-slim-trixie
RUN groupadd --system assistant && useradd --system --gid assistant --no-create-home assistant
WORKDIR /app
COPY --from=build /build/.venv .venv
RUN chown -R assistant:assistant /app
USER assistant

ENV PATH="/app/.venv/bin:${PATH}"

EXPOSE 8000

ENTRYPOINT ["python", "-m", "finassist_assistant.adapters"]
