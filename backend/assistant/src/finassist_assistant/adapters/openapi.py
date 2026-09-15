"""The assistant's OpenAPI document, adjusted to describe the failures the assistant really sends.

FastAPI generates the document from the routes, and two things it says are not true of this
service. It documents a ``422`` with an ``HTTPValidationError`` body on every operation that takes
a parameter, and the assistant never sends that body: a malformed request is a 400 problem, and a
refusal is a 422 problem. And it documents no other failure at all.

So every operation gets a ``default`` response carrying a ``Problem``, described member for member
as the ledger's document describes it, and FastAPI's ``422`` goes, together with the two schemas
only it referred to. A client generated from the document then carries the problem shape rather
than a type the wire never holds (ADR-028).

It lives in the composition root because it applies to every context's routes at once.
"""

from __future__ import annotations

import copy
from typing import Any, Final

from fastapi import FastAPI

from finassist_assistant.adapters.problems import PROBLEM_MEDIA_TYPE

__all__ = ["PROBLEM_SCHEMA", "document_problems"]

PROBLEM_SCHEMA_NAME: Final = "Problem"

PROBLEM_SCHEMA: Final[dict[str, Any]] = {
    "type": "object",
    "properties": {
        "type": {"type": "string", "format": "uri-reference"},
        "title": {"type": "string"},
        "status": {"type": "integer"},
        "detail": {"type": "string"},
        "instance": {"type": "string"},
        "code": {"type": "string"},
        "errors": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"field": {"type": "string"}, "message": {"type": "string"}},
            },
        },
    },
    "required": ["code"],
}
"""RFC 9457's members and the ``code`` and ``errors`` extensions, as the ledger describes them.

Written out rather than generated from a model, because it has to equal the ledger's schema and a
generator would add titles the ledger's lacks; ``tests/test_contract.py`` compares the two. Only
``code`` is required, as on the ledger: it is what a client branches on.
"""

_PROBLEM_RESPONSE: Final[dict[str, Any]] = {
    "description": "A problem (RFC 9457)",
    "content": {
        PROBLEM_MEDIA_TYPE: {"schema": {"$ref": f"#/components/schemas/{PROBLEM_SCHEMA_NAME}"}}
    },
}

_FASTAPI_VALIDATION_STATUS: Final = "422"
_FASTAPI_VALIDATION_SCHEMAS: Final = ("HTTPValidationError", "ValidationError")


def document_problems(app: FastAPI) -> None:
    """Make ``app.openapi()`` describe every failure as a problem.

    FastAPI still generates and caches the document; this wraps the method that returns it. The
    adjustment is idempotent, because FastAPI hands back the same cached document on every call,
    so it is applied again to its own output.

    :param app: the application whose document to adjust. Call it once, when the app is built.
    """
    generate = app.openapi

    def openapi() -> dict[str, Any]:
        return _with_problems(generate())

    # Replacing the method on the instance is FastAPI's documented extension point
    # (https://fastapi.tiangolo.com/how-to/extending-openapi/), and mypy refuses any assignment to
    # a method.
    app.openapi = openapi  # type: ignore[method-assign]


def _with_problems(document: dict[str, Any]) -> dict[str, Any]:
    # FastAPI writes nothing into a path item but its operations, keyed by method, so every value
    # here is an operation.
    for path_item in document.get("paths", {}).values():
        for operation in path_item.values():
            responses = operation.setdefault("responses", {})
            # Every failure, a 422 refusal included, is the default problem below.
            responses.pop(_FASTAPI_VALIDATION_STATUS, None)
            responses["default"] = copy.deepcopy(_PROBLEM_RESPONSE)

    schemas = document.setdefault("components", {}).setdefault("schemas", {})
    for name in _FASTAPI_VALIDATION_SCHEMAS:
        schemas.pop(name, None)
    schemas[PROBLEM_SCHEMA_NAME] = copy.deepcopy(PROBLEM_SCHEMA)
    return document
