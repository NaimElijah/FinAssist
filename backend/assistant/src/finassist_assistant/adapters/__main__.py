"""Run the assistant: ``uv run python -m finassist_assistant.adapters``.

A module rather than a console script, so the run command is visible in the Makefile and the
README instead of hiding behind an entry point that has to be installed first.
"""

from __future__ import annotations

import os

import uvicorn

from finassist_assistant.adapters.app import create_app

DEFAULT_PORT = 8000


def main() -> None:
    """Serve the application on ``ASSISTANT_PORT``."""
    uvicorn.run(
        create_app(),
        # Every interface, because the process is reached from outside its container (ADR-005).
        host="0.0.0.0",
        port=int(os.environ.get("ASSISTANT_PORT", DEFAULT_PORT)),
        # Logging is configured by create_app; uvicorn's own dictConfig would replace the
        # structured handler with its plain-text one and quietly bypass the redaction boundary.
        log_config=None,
    )


if __name__ == "__main__":
    main()
