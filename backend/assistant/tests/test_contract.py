"""The assistant's OpenAPI document, pinned to the contract committed in ``contracts/``.

FastAPI generates the document from the code, so nothing else stops the two drifting apart
silently (ADR-028). Parsed trees are compared, not bytes: key order and whitespace are not the
contract, and a byte comparison would fail on formatting rather than on a change anyone made.

On a mismatch the document the code produces is written to ``.contract-drift/``, which git
ignores, formatted exactly as the committed file is. Accepting an intended change is then a copy,
and its diff is the change to the contract. ``contracts/README.md`` gives the command.

The remaining tests say why the snapshot looks as it does, which the snapshot alone would pin
without explaining: the ledger's problem shape, response members a generated client can rely on,
operation names it can call, and amounts that never travel in a URL.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.auth import TokenVerifier
from finassist_assistant.adapters.settings import Settings

CONTRACT_FILE = "assistant-v1.openapi.json"

# Anchored on this file, never on the working directory or pytest's rootdir, both of which vary
# with how the suite is invoked.
CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
COMMITTED = CONTRACTS / CONTRACT_FILE
LEDGER_CONTRACT = CONTRACTS / "ledger-v1.openapi.json"
DRIFT = Path(__file__).resolve().parents[1] / ".contract-drift" / CONTRACT_FILE

DISPOSAL_PATH = "/api/v1/portfolio/holdings/{symbol}/disposal"

PROBLEM_RESPONSE = {
    "description": "A problem (RFC 9457)",
    "content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}},
}
HTTP_METHODS = frozenset({"get", "put", "post", "delete", "options", "head", "patch", "trace"})

# The component schemas the member rule does not apply to, each with its reason. Every other
# schema is held to it, so a model added later is checked without anyone remembering to list it.
# That includes request bodies: every member of the disposal request is required of the client.
# Query parameters are declared inline and appear as no schema. A schema the rule truly cannot
# apply to must be named here, with its reason.
NOT_ALWAYS_PRESENT_MEMBERS = {
    "Problem": "detail, instance and errors are absent from some problems, as on the ledger",
}


@pytest.fixture
def document(token_verifier: TokenVerifier) -> dict[str, Any]:
    settings = Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
    )
    generated: dict[str, Any] = create_app(settings, token_verifier=token_verifier).openapi()
    return generated


def _formatted(document: dict[str, Any]) -> str:
    """Sorted keys, two-space indentation and a trailing line feed: the committed file's form."""
    return json.dumps(document, indent=2, sort_keys=True) + "\n"


def _operations(document: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
    operations = [
        (f"{method.upper()} {path}", operation)
        for path, item in document["paths"].items()
        for method, operation in item.items()
        if method in HTTP_METHODS
    ]
    assert operations, "the document describes no operation, so every rule below checks nothing"
    return operations


# --- the snapshot ---------------------------------------------------------------------------


def Given_the_generated_document_When_compared_Then_it_equals_the_committed_contract(
    document: dict[str, Any],
) -> None:
    committed = json.loads(COMMITTED.read_text(encoding="utf-8")) if COMMITTED.is_file() else None

    if document != committed:
        DRIFT.parent.mkdir(parents=True, exist_ok=True)
        DRIFT.write_text(_formatted(document), encoding="utf-8", newline="\n")

    assert document == committed, (
        "The assistant's OpenAPI document differs from the committed contract.\n"
        f"  committed: {COMMITTED}\n"
        f"  actual:    {DRIFT}\n"
        "Copy the actual document over the committed one if the change is intended."
    )


# --- why it looks as it does ----------------------------------------------------------------


def Given_the_document_When_its_header_is_read_Then_it_names_the_assistant_at_v1_served_from_root(
    document: dict[str, Any],
) -> None:
    """A server URL taken from the request would differ between a test and a real start."""
    assert document["info"] == {"title": "FinAssist assistant", "version": "v1"}
    assert document["servers"] == [{"url": "/"}]


def Given_both_contracts_When_their_problem_schemas_are_compared_Then_they_are_identical(
    document: dict[str, Any],
) -> None:
    """One client parses both services' problems, so both documents must describe one shape."""
    ledger = json.loads(LEDGER_CONTRACT.read_text(encoding="utf-8"))

    assert (
        document["components"]["schemas"]["Problem"] == ledger["components"]["schemas"]["Problem"]
    )


def Given_both_contracts_When_their_security_schemes_are_compared_Then_they_are_identical(
    document: dict[str, Any],
) -> None:
    """One client authenticates against both services the same way (ADR-030)."""
    ledger = json.loads(LEDGER_CONTRACT.read_text(encoding="utf-8"))

    assert document["components"]["securitySchemes"] == ledger["components"]["securitySchemes"]


def Given_the_document_When_security_is_read_Then_every_operation_requires_a_bearer_token(
    document: dict[str, Any],
) -> None:
    """A single global requirement, exactly as the ledger's document carries one (ADR-030): every
    operation inherits it, and none may be documented as needing something else or nothing at all.
    """
    assert document["security"] == [{"bearerAuth": []}]
    for operation_name, operation in _operations(document):
        assert "security" not in operation, operation_name


def Given_every_operation_When_its_responses_are_read_Then_failures_are_problems(
    document: dict[str, Any],
) -> None:
    """FastAPI documents a 422 validation body the assistant never sends; a problem replaces it."""
    for operation_name, operation in _operations(document):
        responses = operation["responses"]
        assert "422" not in responses, operation_name
        assert responses["default"] == PROBLEM_RESPONSE, operation_name


def Given_the_components_When_listed_Then_fastapis_validation_error_schemas_are_gone(
    document: dict[str, Any],
) -> None:
    """Nothing refers to them once the 422 responses go, and a generator would still emit them."""
    assert not {"HTTPValidationError", "ValidationError"} & set(document["components"]["schemas"])


def Given_every_operation_When_its_id_is_read_Then_ids_are_the_handler_names_in_camel_case(
    document: dict[str, Any],
) -> None:
    """Unique and unsuffixed, so a generated client's method names stay stable and readable."""
    operation_ids = sorted(operation["operationId"] for _, operation in _operations(document))

    assert operation_ids == ["priceDisposal", "readPosition", "valueHolding"]


def Given_the_disposal_operation_When_read_Then_its_amounts_travel_in_a_json_body_not_the_url(
    document: dict[str, Any],
) -> None:
    """Money never travels in a URL, which access logs, trace attributes and proxies all record.

    The symbol stays in the path; the quantity and the proceeds are a required JSON body, and the
    answer is 200 because pricing creates nothing.
    """
    item = document["paths"][DISPOSAL_PATH]
    operation = item["post"]

    assert set(item) == {"post"}
    assert [(parameter["in"], parameter["name"]) for parameter in operation["parameters"]] == [
        ("path", "symbol")
    ]
    assert operation["requestBody"] == {
        "content": {
            "application/json": {"schema": {"$ref": "#/components/schemas/DisposalRequestModel"}}
        },
        "required": True,
    }
    assert set(operation["responses"]) == {"200", "default"}


def Given_the_position_schema_When_read_Then_symbol_quantity_and_cost_are_required(
    document: dict[str, Any],
) -> None:
    position = document["components"]["schemas"]["PositionModel"]

    assert set(position["required"]) == {"symbol", "quantity", "cost"}


def Given_every_response_schema_When_read_Then_every_member_is_required_and_camel_case(
    document: dict[str, Any],
) -> None:
    """A generated client reads a member missing from ``required`` as optional, and makes every
    caller check for it. Every member of every response here is always present, and camelCase.

    Every component schema is checked except those named, with a reason, in
    ``NOT_ALWAYS_PRESENT_MEMBERS``, rather than a list of the models that exist today.
    """
    schemas: dict[str, dict[str, Any]] = document["components"]["schemas"]
    checked = {
        name: schema for name, schema in schemas.items() if name not in NOT_ALWAYS_PRESENT_MEMBERS
    }

    not_required = {
        name: sorted(set(schema["properties"]) - set(schema.get("required", [])))
        for name, schema in checked.items()
    }
    snake_case = {
        name: sorted(member for member in schema["properties"] if "_" in member)
        for name, schema in checked.items()
    }

    assert set(NOT_ALWAYS_PRESENT_MEMBERS) <= set(schemas), "an exclusion names no schema"
    assert checked, "no response schema is left to check, so this rule checks nothing"
    assert not {name: members for name, members in not_required.items() if members}
    assert not {name: members for name, members in snake_case.items() if members}


def Given_both_contracts_When_decimal_patterns_are_compared_Then_they_are_the_ledgers_pattern(
    document: dict[str, Any],
) -> None:
    """Anchored, because a JSON Schema pattern is not, and the same on both wires."""
    ledger = json.loads(LEDGER_CONTRACT.read_text(encoding="utf-8"))
    anchored = ledger["components"]["schemas"]["MoneyDto"]["properties"]["amount"]["pattern"]
    schemas = document["components"]["schemas"]
    request = schemas["DisposalRequestModel"]["properties"]

    assert schemas["MoneyModel"]["properties"]["amount"]["pattern"] == anchored
    assert schemas["PositionModel"]["properties"]["quantity"]["pattern"] == anchored
    assert request["quantity"]["pattern"] == anchored
    assert request["proceeds"] == {"$ref": "#/components/schemas/MoneyModel"}
