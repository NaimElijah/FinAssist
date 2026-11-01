"""The assistant's OpenAPI document, adjusted to describe the failures the assistant really sends.

FastAPI generates the document from the routes, and two things it says are not true of this
service. It documents a ``422`` with an ``HTTPValidationError`` body on every operation that takes
a parameter, and the assistant never sends that body: a malformed request is a 400 problem, and a
refusal is a 422 problem. And it documents no other failure at all.

So every operation gets a ``default`` response carrying a ``Problem``, described member for member
as the ledger's document describes it, and FastAPI's ``422`` goes, together with the two schemas
only it referred to. A client generated from the document then carries the problem shape rather
than a type the wire never holds (ADR-028).

It also adds the ``bearerAuth`` scheme and a global security requirement (ADR-030), identical to
the ledger's own document: every operation here needs a bearer token too, and one client parses
both contracts.

It lives in the composition root because it applies to every context's routes at once.
"""

from __future__ import annotations

import copy
from typing import Any, Final

from fastapi import FastAPI

from finassist_assistant.adapters.problems import PROBLEM_MEDIA_TYPE

__all__ = ["BEARER_SECURITY_SCHEME", "PROBLEM_SCHEMA", "document_problems", "document_security"]

PROBLEM_SCHEMA_NAME: Final = "Problem"
"""The name :data:`PROBLEM_SCHEMA` is registered under in ``components.schemas``."""

_INSTANCE_DESCRIPTION: Final = (
    "The request's path as received by the service that answered."
    " Behind the gateway it omits the gateway's /ledger or /assistant prefix."
)

PROBLEM_SCHEMA: Final[dict[str, Any]] = {
    "type": "object",
    "properties": {
        "type": {"type": "string", "format": "uri-reference"},
        "title": {"type": "string"},
        "status": {"type": "integer"},
        "detail": {"type": "string"},
        "instance": {"type": "string", "description": _INSTANCE_DESCRIPTION},
        "code": {"type": "string"},
        "errors": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"field": {"type": "string"}, "message": {"type": "string"}},
                "required": ["field", "message"],
            },
        },
    },
    # Sorted, as swagger-core writes the ledger's, so that the two schemas compare equal.
    "required": ["code", "status", "title", "type"],
}
"""RFC 9457's members and the ``code`` and ``errors`` extensions, as the ledger describes them.

Written out rather than generated from a model, because it has to equal the ledger's schema and a
generator would add titles the ledger's lacks; ``tests/test_contract.py`` compares the two.
``type``, ``title``, ``status`` and ``code`` are required, as on the ledger, because
:func:`~finassist_assistant.adapters.problems.problem_response` writes all four on every problem,
and ``field`` and ``message`` on every ``errors`` entry; a client may rely on them.
"""

_PROBLEM_RESPONSE: Final[dict[str, Any]] = {
    "description": "A problem (RFC 9457)",
    "content": {
        PROBLEM_MEDIA_TYPE: {"schema": {"$ref": f"#/components/schemas/{PROBLEM_SCHEMA_NAME}"}}
    },
}

_FASTAPI_VALIDATION_STATUS: Final = "422"
_FASTAPI_VALIDATION_SCHEMAS: Final = ("HTTPValidationError", "ValidationError")

BEARER_SCHEME_NAME: Final = "bearerAuth"
"""The name :data:`BEARER_SECURITY_SCHEME` is registered under in ``components.securitySchemes``."""

BEARER_SECURITY_SCHEME: Final[dict[str, Any]] = {
    "type": "http",
    "scheme": "bearer",
    "bearerFormat": "JWT",
}
"""Written out rather than generated, exactly like :data:`PROBLEM_SCHEMA`: it has to equal the
ledger's own scheme byte for byte, and ``tests/test_contract.py`` compares the two."""

_GLOBAL_SECURITY_REQUIREMENT: Final[list[dict[str, list[str]]]] = [{BEARER_SCHEME_NAME: []}]
"""One requirement for the whole document, as the ledger's ``OpenApiConfiguration`` builds -
every operation under ``/api/v1`` needs a bearer token, and a generated client should not have
to be told that once per operation."""


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


def document_security(app: FastAPI) -> None:
    """Make ``app.openapi()`` require the bearer scheme, identically to the ledger's document.

    Wraps ``app.openapi`` the same way :func:`document_problems` does, so the two compose:
    whichever is installed first runs first, and each call site installs both (ADR-030).

    :param app: the application to adjust. Call it once, when the app is built.
    """
    generate = app.openapi

    def openapi() -> dict[str, Any]:
        return _with_security(generate())

    app.openapi = openapi  # type: ignore[method-assign]


def _with_security(document: dict[str, Any]) -> dict[str, Any]:
    schemes = document.setdefault("components", {}).setdefault("securitySchemes", {})
    schemes[BEARER_SCHEME_NAME] = copy.deepcopy(BEARER_SECURITY_SCHEME)
    document["security"] = copy.deepcopy(_GLOBAL_SECURITY_REQUIREMENT)
    return document
