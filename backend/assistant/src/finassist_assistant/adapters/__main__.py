"""Run the assistant: ``uv run python -m finassist_assistant.adapters``.

A module rather than a console script, so the run command is visible in the Makefile and the
README instead of hiding behind an entry point that has to be installed first.
"""

from __future__ import annotations

import uvicorn

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.settings import Settings


def main() -> None:
    """Serve the application on the configured host and port.

    Host and port come from ``Settings`` rather than from ``os.environ`` here, so a malformed
    ``ASSISTANT_PORT`` is refused by pydantic with the field named, instead of a bare
    ``ValueError`` from ``int("")`` that says nothing about which variable is wrong.
    """
    settings = Settings()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        # Logging is configured by create_app; uvicorn's own dictConfig would replace the
        # structured handler with its plain-text one and quietly bypass the redaction boundary.
        log_config=None,
        # The app writes each request's line while its correlation id is bound. Uvicorn's access
        # line comes afterwards, from the protocol layer, carrying no id and the query string too
        # (ADR-019, ADR-028).
        access_log=False,
    )


if __name__ == "__main__":
    main()
