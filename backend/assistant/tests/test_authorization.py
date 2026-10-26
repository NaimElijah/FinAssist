"""Every request needs a bearer token, and every route needs a permission (ADR-030, ADR-032).

``tests/test_auth.py`` holds ``TokenVerifier``'s own boundaries - what makes a token genuine.
This file holds what wires that verifier into the running application: ``BearerAuthentication``'s
deny-by-default and its 401/503/403 shapes, ``RequirePermission``'s role table, and the coverage
checks that fail the build if a route loses its permission dependency or a new public path
sneaks in unlisted.

The middleware order (``HostAllowList`` -> correlate -> auth -> routes) is proved indirectly:
a refused token still carries a correlation id only because authentication runs *inside* the
correlation middleware, and a foreign Host is refused with no token present only because the
Host check runs *before* authentication is even attempted. Both properties would go red if the
order in ``app.py`` were reversed - which was confirmed by hand and reverted before this file was
committed, because a rule that has never failed is not evidence.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Iterator
from typing import Any

import jwt
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.routing import APIRoute, RouteContext, iter_route_contexts
from fastapi.testclient import TestClient
from starlette.requests import Request

from finassist_assistant.adapters.app import CORRELATION_ID_HEADER, create_app
from finassist_assistant.adapters.auth import (
    PRINCIPAL_SCOPE_KEY,
    PUBLIC_PATHS,
    ROLE_PERMISSIONS,
    KeySourceUnavailable,
    Principal,
    RequirePermission,
    TokenVerifier,
    _granted_permissions,
)
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Clock
from finassist_assistant.portfolio.adapters.inbound.api.router import READ_PORTFOLIO

HOLDINGS = "/api/v1/portfolio/holdings"
POSITION_URL = f"{HOLDINGS}/VOO/position"
VALUATION_URL = f"{HOLDINGS}/VOO/valuation"
DISPOSAL_URL = f"{HOLDINGS}/VOO/disposal"
DISPOSAL_BODY = {"quantity": "1", "proceeds": {"amount": "1.00", "currency": "USD"}}

_ROUTES = [
    ("GET", POSITION_URL, None),
    ("GET", VALUATION_URL, None),
    ("POST", DISPOSAL_URL, DISPOSAL_BODY),
]
_ROUTE_IDS = ["position", "valuation", "disposal"]

_INVALID_TOKEN_CHALLENGE = 'Bearer error="invalid_token"'
_INSUFFICIENT_SCOPE_CHALLENGE = 'Bearer error="insufficient_scope"'


@pytest.fixture
def settings() -> Settings:
    return Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`.
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
    )


@pytest.fixture
def app(settings: Settings, token_verifier: TokenVerifier) -> FastAPI:
    return create_app(settings, token_verifier=token_verifier)


@pytest.fixture
def client(app: FastAPI, owner_headers: dict[str, str]) -> Iterator[TestClient]:
    with TestClient(app, headers=owner_headers) as running:
        yield running


@pytest.fixture
def anonymous(app: FastAPI) -> Iterator[TestClient]:
    """A client with no default headers at all.

    Confirmed from the installed ``httpx`` source: ``Client._merge_headers`` merges a per-request
    ``headers=`` argument onto the client's own defaults and only overrides a key both name - it
    never removes a default header the call does not mention. A test proving "no token was sent"
    must therefore use a client that was never given the owner's header in the first place, not
    the shared ``client`` fixture with an override.
    """
    with TestClient(app) as running:
        yield running


def _for_each_route(
    marks: list[tuple[str, str, dict[str, Any] | None]] = _ROUTES,
) -> pytest.MarkDecorator:
    return pytest.mark.parametrize(("method", "path", "body"), marks, ids=_ROUTE_IDS)


# --- rejections --------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": "Basic dXNlcjpwYXNz"},
        {"Authorization": "Bearer"},
        {"Authorization": "Bearer    "},
    ],
    ids=["no-header", "basic-scheme", "bearer-scheme-no-token", "bearer-scheme-blank-token"],
)
def Given_no_bearer_credential_When_a_protected_route_is_requested_Then_401_with_a_bare_challenge(
    anonymous: TestClient, headers: dict[str, str]
) -> None:
    response = anonymous.get(POSITION_URL, headers=headers)

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["code"] == "UNAUTHENTICATED"


def Given_a_lowercase_bearer_scheme_When_a_protected_route_is_requested_Then_it_is_admitted(
    anonymous: TestClient, owner_headers: dict[str, str]
) -> None:
    """The scheme is matched case-insensitively, as RFC 9110 says an auth scheme is. Nothing is
    held for this symbol, so 404 is the answer only an admitted caller ever sees."""
    token = owner_headers["Authorization"].removeprefix("Bearer ")

    response = anonymous.get(POSITION_URL, headers={"Authorization": f"bearer {token}"})

    assert response.status_code == 404
    assert response.json()["code"] == "HOLDING_NOT_FOUND"


def Given_a_refused_token_When_a_protected_route_is_requested_Then_it_is_401_invalid_token(
    anonymous: TestClient,
) -> None:
    response = anonymous.get(POSITION_URL, headers={"Authorization": "Bearer not-a-jwt"})

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == _INVALID_TOKEN_CHALLENGE
    assert response.json()["code"] == "UNAUTHENTICATED"


def Given_a_refused_token_When_answered_Then_a_correlation_id_is_carried_and_only_the_reason_logged(
    anonymous: TestClient, capsys: pytest.CaptureFixture[str]
) -> None:
    """Proves authentication runs *inside* the correlation middleware (ADR-019, ADR-030): the
    401 still gets a correlation id only because ``correlate``'s own ``call_next`` is what
    invokes ``BearerAuthentication`` and sees the response it produces."""
    token = "not-a-jwt-and-definitely-not-a-secret"
    capsys.readouterr()

    response = anonymous.get(POSITION_URL, headers={"Authorization": f"Bearer {token}"})

    assert CORRELATION_ID_HEADER in response.headers
    captured = capsys.readouterr().out
    records = [json.loads(line) for line in captured.strip().splitlines() if line.startswith("{")]
    warnings = [record for record in records if record.get("level") == "warning"]
    assert len(warnings) == 1
    assert warnings[0]["reason"] == "malformed"
    assert warnings[0]["correlation_id"] == response.headers[CORRELATION_ID_HEADER]
    assert token not in captured


def Given_an_unknown_route_When_requested_with_no_token_Then_it_is_401_not_404(
    anonymous: TestClient,
) -> None:
    response = anonymous.get(f"{HOLDINGS}/nope/whatever")

    assert response.status_code == 401


def Given_the_key_source_is_unreachable_When_a_protected_route_is_requested_Then_it_is_503(
    settings: Settings, owner_headers: dict[str, str], fixed_clock: Clock
) -> None:
    def unreachable(_token: str) -> jwt.PyJWK:
        raise KeySourceUnavailable

    verifier = TokenVerifier(unreachable, "https://issuer.invalid", "aud", fixed_clock)
    app = create_app(settings, token_verifier=verifier)

    with TestClient(app, headers=owner_headers) as client:
        response = client.get(POSITION_URL)

    assert response.status_code == 503
    assert response.json()["code"] == "AUTHENTICATION_UNAVAILABLE"
    assert "www-authenticate" not in {key.lower() for key in response.headers}


def Given_the_key_source_is_unreachable_When_a_request_is_refused_Then_it_is_logged_at_error(
    settings: Settings,
    owner_headers: dict[str, str],
    fixed_clock: Clock,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """An outage is ERROR, under an event of its own; a refused token stays WARN.

    Nobody can authenticate at all while the key source is unreachable, which is "actionable
    now" - so it must not read as one more refused token in a log a person is scanning. Both
    lines are driven through the same application, because the distinction only means anything
    if the two are told apart where they are written. The ledger's
    ``ProblemAuthenticationEntryPoint`` draws the same line in the same place, and
    ``ProblemAuthenticationEntryPointTest`` pins it there.
    """

    def unreachable(_token: str) -> jwt.PyJWK:
        raise KeySourceUnavailable

    verifier = TokenVerifier(unreachable, "https://issuer.invalid", "aud", fixed_clock)
    app = create_app(settings, token_verifier=verifier)
    token = owner_headers["Authorization"].removeprefix("Bearer ")
    capsys.readouterr()

    with TestClient(app) as client:
        # A token to look up a key for, so the unreachable key source is actually consulted.
        client.get(POSITION_URL, headers=owner_headers)
        # No credential at all, which is refused before any key is looked up.
        client.get(POSITION_URL)

    captured = capsys.readouterr().out
    records = [json.loads(line) for line in captured.strip().splitlines() if line.startswith("{")]
    outages = [record for record in records if record.get("level") == "error"]
    refusals = [record for record in records if record.get("level") == "warning"]

    assert len(outages) == 1
    assert outages[0]["reason"] == "key_source"
    assert len(refusals) == 1
    assert refusals[0]["event"] != outages[0]["event"]
    assert token not in captured


def Given_a_foreign_host_When_requested_with_no_token_Then_it_is_refused_before_authentication(
    anonymous: TestClient,
) -> None:
    """A foreign Host is refused 403 even with no token at all - proving ``HostAllowList`` runs
    before ``BearerAuthentication`` ever decides anything, not merely that both eventually agree
    to refuse. Were the order reversed, a request with no token would be 401 here, not 403."""
    response = anonymous.get(POSITION_URL, headers={"host": "attacker.example"})

    assert response.status_code == 403
    assert response.json()["code"] == "HOST_NOT_ALLOWED"


# --- admissions ----------------------------------------------------------------------------


@pytest.mark.parametrize("path", sorted(PUBLIC_PATHS))
def Given_a_public_path_When_requested_with_no_token_Then_it_is_admitted(
    anonymous: TestClient, path: str
) -> None:
    assert anonymous.get(path).status_code == 200


@pytest.mark.parametrize("role", ["owner", "viewer"])
@_for_each_route()
def Given_an_owner_or_viewer_token_When_any_route_is_requested_Then_it_is_admitted(
    app: FastAPI,
    headers_for: Callable[[list[str] | None], dict[str, str]],
    role: str,
    method: str,
    path: str,
    body: dict[str, Any] | None,
) -> None:
    with TestClient(app, headers=headers_for([role])) as client:
        response = client.request(method, path, json=body)

    assert response.status_code not in (401, 403)


@pytest.mark.parametrize(
    "roles",
    [["assistant"], ["unknown-role"], [], None],
    ids=["assistant", "unknown-role", "empty-list", "no-claim"],
)
@_for_each_route()
def Given_a_role_that_grants_no_portfolio_permission_When_any_route_is_requested_Then_it_is_403(
    app: FastAPI,
    headers_for: Callable[[list[str] | None], dict[str, str]],
    roles: list[str] | None,
    method: str,
    path: str,
    body: dict[str, Any] | None,
) -> None:
    with TestClient(app, headers=headers_for(roles)) as client:
        response = client.request(method, path, json=body)

    assert response.status_code == 403
    assert response.headers["www-authenticate"] == _INSUFFICIENT_SCOPE_CHALLENGE
    assert response.json()["code"] == "FORBIDDEN"


def Given_no_permission_and_a_form_body_When_a_disposal_is_priced_Then_403_comes_before_415(
    app: FastAPI, headers_for: Callable[[list[str] | None], dict[str, str]]
) -> None:
    """The permission dependency is declared before ``_require_json`` on the disposal route
    (``router.py``), and FastAPI evaluates a route's declared dependencies in that order: a
    caller with no permission sending a non-JSON body is refused for who they are before the
    request is refused for how it was shaped."""
    with TestClient(app, headers=headers_for(["assistant"])) as client:
        response = client.post(DISPOSAL_URL, data={"quantity": "1"})

    assert response.status_code == 403
    assert response.json()["code"] == "FORBIDDEN"


# --- coverage: a route or a role table that drifts fails the build ------------------------


def _api_routes(app: FastAPI) -> list[RouteContext]:
    """Every route *app* actually answers to, minus the public ones ``BearerAuthentication``
    never asks a permission of at all (ADR-030).

    ``app.routes`` alone is not enough: FastAPI 0.141.1 mounts a router ``include_router`` brings
    in behind an internal wrapper (``_IncludedRouter``) rather than flattening its routes onto
    ``app.routes`` directly, so a plain ``isinstance(route, APIRoute)`` scan of ``app.routes``
    finds none of ``portfolio_router``'s own routes at all (confirmed against the installed
    version). ``iter_route_contexts`` is the same walk FastAPI's own OpenAPI generator uses
    (``fastapi.openapi.utils`` calls it too) to resolve the *effective* route behind any depth of
    ``include_router`` nesting, dependencies included - so a second router registered in
    ``create_app`` is covered automatically, exactly like ``portfolio_router`` is today.
    """
    return [
        context
        for context in iter_route_contexts(app.routes)
        if isinstance(context.original_route, APIRoute) and context.path not in PUBLIC_PATHS
    ]


def Given_the_assembled_app_When_routes_are_inspected_Then_each_declares_one_permission(
    app: FastAPI,
) -> None:
    """Proven to fail: dropping the permission dependency from one route in ``router.py`` turns
    this red, confirmed by hand and reverted before this file was committed."""
    routes = _api_routes(app)

    assert routes, "no route to check, so this test would pass vacuously"
    for route in routes:
        permission_dependencies = [
            dependency.dependency
            for dependency in route.dependencies
            if isinstance(dependency.dependency, RequirePermission)
        ]
        assert len(permission_dependencies) == 1, route.path


def Given_the_public_paths_When_compared_Then_they_are_exactly_health_ready_and_openapi() -> None:
    assert frozenset({"/health", "/ready", "/openapi.json"}) == PUBLIC_PATHS


def Given_the_role_table_When_read_Then_owner_and_viewer_grant_portfolio_read() -> None:
    assert ROLE_PERMISSIONS["owner"] == frozenset({READ_PORTFOLIO})
    assert ROLE_PERMISSIONS["viewer"] == frozenset({READ_PORTFOLIO})


def Given_the_role_table_When_read_Then_the_assistant_role_is_absent() -> None:
    """ "assistant" grants nothing (ADR-004, ADR-032) by being absent from the
    table, exactly like an unknown role - there is no third branch to keep in step."""
    assert "assistant" not in ROLE_PERMISSIONS


def Given_the_router_and_the_composition_root_When_compared_Then_permission_strings_match() -> None:
    """``router.py`` and ``adapters.auth`` each keep their own copy of ``"portfolio:read"`` -
    a context may not import the composition root back - and this pins them equal."""
    assert READ_PORTFOLIO in ROLE_PERMISSIONS["owner"]


# --- RequirePermission and _granted_permissions, exercised directly -----------------------


def _with(principal: Principal) -> Request:
    request = Request({"type": "http", "method": "GET", "path": "/x", "headers": []})
    request.scope[PRINCIPAL_SCOPE_KEY] = principal
    return request


def Given_a_principal_whose_role_grants_the_permission_When_required_Then_nothing_is_raised() -> (
    None
):
    require = RequirePermission(READ_PORTFOLIO)

    require(_with(Principal(subject="owner-user", roles=frozenset({"owner"}))))


def Given_a_principal_whose_role_lacks_the_permission_When_required_Then_403_is_raised() -> None:
    require = RequirePermission(READ_PORTFOLIO)

    with pytest.raises(HTTPException) as failure:
        require(_with(Principal(subject="assistant-user", roles=frozenset({"assistant"}))))

    assert failure.value.status_code == 403
    assert failure.value.headers is not None
    assert failure.value.headers["WWW-Authenticate"] == _INSUFFICIENT_SCOPE_CHALLENGE


def Given_an_unknown_role_When_permissions_are_granted_Then_nothing_is_granted() -> None:
    assert (
        _granted_permissions(Principal(subject="x", roles=frozenset({"unknown-role"})))
        == frozenset()
    )


def Given_no_roles_When_permissions_are_granted_Then_nothing_is_granted() -> None:
    assert _granted_permissions(Principal(subject="x")) == frozenset()
