"""Run the assistant: ``uv run python -m finassist_assistant.adapters``.

A module rather than a console script, so the run command is visible in the Makefile and the
README instead of hiding behind an entry point that has to be installed first.
"""

from __future__ import annotations

from typing import Final

import uvicorn

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.settings import Settings

HTTP_IMPLEMENTATION: Final = "h11"
"""uvicorn's pure-Python HTTP/1.1 implementation, chosen for its header bound.

uvicorn otherwise picks ``httptools`` whenever it is installed, as ``uvicorn[standard]`` installs
it, and ``httptools`` buffers a header block of any size. The throughput it would add does not
matter to a single owner.
"""

MAX_HEADER_BLOCK_BYTES: Final = 16 * 1024
"""The most of an unfinished request line and header block the server buffers, in bytes.

Past it the request is refused 400 before the application, and so authentication, sees it.
``h11`` checks the bound while the block is incomplete, so a whole block that arrives in a single
read can exceed it by up to that read's size: a bound on the buffer, not an exact header limit.
"""

WEBSOCKET_IMPLEMENTATION: Final = "none"
"""No WebSocket implementation, so uvicorn performs no upgrade at all.

No WebSocket route is served until how one authenticates is designed. An ``Upgrade: websocket``
request is answered as the plain HTTP request it also is, and no handshake is ever logged with
its path and query string.
"""


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
        http=HTTP_IMPLEMENTATION,
        h11_max_incomplete_event_size=MAX_HEADER_BLOCK_BYTES,
        ws=WEBSOCKET_IMPLEMENTATION,
    )


if __name__ == "__main__":
    main()
