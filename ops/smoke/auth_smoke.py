"""Opt-in Compose smoke check for the authentication and RBAC wiring (ADR-030 through ADR-032).

Runs as the `auth-smoke` Compose service (profile `smoke`), inside the Compose network, so it
reaches every other service by its Compose DNS name rather than a published host port. That
placement is deliberate: it is the only way to catch a class of fault the unit and integration
suites cannot see from outside the network — Compose's own DNS and routing, the issuer Keycloak
actually places in a token when reached over the backchannel (R2 in
docs/ADRs/ADR-031-keycloak-as-the-identity-provider.md), and the gateway's real redirect
response. It duplicates none of those suites' coverage of the individual services; it proves the
wiring between them.

Standard library only, so it runs unmodified from the pinned `python:3.13.8-slim-trixie` image
with no `pip install` step (the same tag `backend/assistant/Dockerfile` already builds from).

Exits non-zero on the first failed check, naming which one failed and why. Never prints a
token, a full response body, or anything else that could carry one: every message below names a
claim, a status code or a header, never a value that might quote a credential back.

Run it with `make smoke-auth` (see README.md, "Smoke-testing authentication"). Refs #38.
"""

from __future__ import annotations

import base64
import http.client
import json
import os
import sys
import urllib.parse
from collections.abc import Callable
from email.message import Message
from typing import Final

# --- The Compose network topology (docker-compose.yml). Fixed, not environment-configurable:
# this script exists to prove that one topology, not to be portable to another.
KEYCLOAK_HOST: Final = "keycloak"
KEYCLOAK_PORT: Final = 8080
LEDGER_HOST: Final = "ledger"
LEDGER_PORT: Final = 8080
ASSISTANT_HOST: Final = "assistant"
ASSISTANT_PORT: Final = 8000
GATEWAY_HOST: Final = "gateway"
GATEWAY_PORT: Final = 8090

KEYCLOAK_TOKEN_PATH: Final = "/realms/finassist/protocol/openid-connect/token"
LEDGER_ACCOUNTS_PATH: Final = "/api/v1/accounts"
LEDGER_HEALTH_PATH: Final = "/actuator/health"
ASSISTANT_POSITION_PATH: Final = "/api/v1/portfolio/holdings/VOO/position"
GATEWAY_LEDGER_ACCOUNTS_PATH: Final = "/ledger/api/v1/accounts"
GATEWAY_LOGIN_ENTRY_PATH: Final = "/bff/oauth2/authorization/keycloak"

# --- Fixed by ops/keycloak/finassist-realm.json and the ADRs it implements.
ASSISTANT_CLIENT_ID: Final = "finassist-assistant"
ASSISTANT_CLIENT_SECRET_ENV: Final = "ASSISTANT_CLIENT_SECRET"
LEDGER_AUDIENCE: Final = "finassist-ledger"
ASSISTANT_AUDIENCE: Final = "finassist-assistant"
ASSISTANT_ROLE: Final = "assistant"
EXPECTED_ALG: Final = "RS256"

# The public URLs a browser would see — never the backchannel ones this script reaches services
# by. Keycloak leaking the backchannel URL into `iss` (R2) is exactly what check 1 catches, and
# it can only catch it by comparing against this exact string rather than deriving it the same
# way the token was fetched.
PUBLIC_ISSUER: Final = "http://localhost:8081/realms/finassist"
PUBLIC_AUTHORIZATION_ENDPOINT: Final = (
    "http://localhost:8081/realms/finassist/protocol/openid-connect/auth"
)
PUBLIC_REDIRECT_URI: Final = "http://localhost:5173/bff/login/oauth2/code/keycloak"

# Every service names its machine-readable failure the same way: Problems.CODE_MEMBER (ledger),
# GatewayProblems.CODE_MEMBER (gateway) and problem_response's "code" (assistant) all agree.
PROBLEM_CODE_MEMBER: Final = "code"
CODE_FORBIDDEN: Final = "FORBIDDEN"
CODE_UNAUTHENTICATED: Final = "UNAUTHENTICATED"

HTTP_OK: Final = 200
HTTP_FOUND: Final = 302
HTTP_UNAUTHORIZED: Final = 401
HTTP_FORBIDDEN: Final = 403

WWW_AUTHENTICATE_HEADER: Final = "WWW-Authenticate"
LOCATION_HEADER: Final = "Location"

# An empty JSON object is enough: the authorization filter chain in all three services decides
# on the token before any request body is parsed, so a write refused by role never needs a
# well-formed payload (mirrors LedgerAuthorizationIT's own JSON_BODY constant).
EMPTY_JSON_BODY: Final = b"{}"

REQUEST_TIMEOUT_SECONDS: Final = 10

CHECK_NAMES: Final[dict[int, str]] = {
    1: "assistant client-credentials token claims",
    2: "GET /api/v1/accounts with the assistant token",
    3: "POST /api/v1/accounts with the assistant token",
    4: "GET /api/v1/accounts with no token",
    5: "the ledger-audience token presented to the assistant",
    6: "the gateway's /ledger/... with no session",
    7: "the gateway's login entry",
    8: "GET /actuator/health with no token",
}


class SmokeCheckError(Exception):
    """Raised by a check that did not get what ADR-030 through ADR-032 promise.

    Carries only a human-readable reason. `main` attributes it to a check number itself, from
    the position in its own run order, so a check never has to duplicate its own number.
    """


def _request(
    host: str,
    port: int,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: bytes | None = None,
) -> tuple[int, Message, bytes]:
    """Sends one HTTP request and returns (status, headers, body) — including for a refusal.

    Uses `http.client` rather than `urllib.request` because a redirect must be observed, never
    followed (check 7): `http.client` never follows one on its own, so there is no redirect
    handler to disable.
    """
    headers: dict[str, str] = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
    connection = http.client.HTTPConnection(host, port, timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        payload = response.read()
        return response.status, response.headers, payload
    finally:
        connection.close()


def _post_form(
    host: str, port: int, path: str, fields: dict[str, str]
) -> tuple[int, Message, bytes]:
    """Sends one `application/x-www-form-urlencoded` POST — the OAuth2 token endpoint's shape."""
    body = urllib.parse.urlencode(fields).encode()
    connection = http.client.HTTPConnection(host, port, timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        connection.request(
            "POST", path, body=body, headers={"Content-Type": "application/x-www-form-urlencoded"}
        )
        response = connection.getresponse()
        payload = response.read()
        return response.status, response.headers, payload
    finally:
        connection.close()


def _decode_jwt(token: str) -> tuple[dict[str, object], dict[str, object]]:
    """Decodes a JWT's header and payload without verifying its signature.

    Signature verification is each resource server's own job (ADR-030) — proven by
    `KeycloakRealmIT` and the two services' own test suites. This check only inspects claims the
    services have already promised to enforce.
    """
    parts = token.split(".")
    if len(parts) != 3:
        raise SmokeCheckError("the token was not a three-part JWT")

    def decode_segment(segment: str) -> dict[str, object]:
        padding = "=" * (-len(segment) % 4)
        decoded = base64.urlsafe_b64decode(segment + padding)
        parsed = json.loads(decoded)
        if not isinstance(parsed, dict):
            raise SmokeCheckError("a JWT segment did not decode to a JSON object")
        return parsed

    return decode_segment(parts[0]), decode_segment(parts[1])


def _status_mismatch(description: str, status: int, expected: int) -> str:
    """Formats one "answered X, expected Y" message — the shape every status check below needs."""
    return f"{description} answered {status}, expected {expected}"


def _problem_code(body: bytes) -> str | None:
    """Reads the problem+json `code` member, or None if the body was not that shape."""
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict):
        return None
    code = parsed.get(PROBLEM_CODE_MEMBER)
    return code if isinstance(code, str) else None


def check_assistant_token_claims() -> str:
    """Check 1: fetches an assistant client-credentials token and inspects its claims.

    The token is fetched over the backchannel (`keycloak:8080`), exactly as the ledger and the
    assistant themselves fetch signing keys. If Keycloak ever put that backchannel address in
    `iss` instead of the public one, every token every service issues would carry an issuer no
    resource server's `LEDGER_OIDC_ISSUER`/`ASSISTANT_OIDC_ISSUER` could ever match — this is
    the one check positioned to catch that before it reaches either resource server.
    """
    secret = os.environ.get(ASSISTANT_CLIENT_SECRET_ENV)
    if not secret:
        raise SmokeCheckError(f"{ASSISTANT_CLIENT_SECRET_ENV} is not set in this container")

    status, _headers, body = _post_form(
        KEYCLOAK_HOST,
        KEYCLOAK_PORT,
        KEYCLOAK_TOKEN_PATH,
        {
            "grant_type": "client_credentials",
            "client_id": ASSISTANT_CLIENT_ID,
            "client_secret": secret,
        },
    )
    if status != HTTP_OK:
        raise SmokeCheckError(f"the token endpoint answered {status}, expected {HTTP_OK}")

    token_response = json.loads(body)
    access_token = token_response.get("access_token")
    if not isinstance(access_token, str) or not access_token:
        raise SmokeCheckError("the token response carried no access_token")

    header, payload = _decode_jwt(access_token)

    alg = header.get("alg")
    if alg != EXPECTED_ALG:
        raise SmokeCheckError(f"header alg was {alg!r}, expected {EXPECTED_ALG!r}")

    issuer = payload.get("iss")
    if issuer != PUBLIC_ISSUER:
        raise SmokeCheckError(
            "iss was not the public issuer — Keycloak may be leaking the backchannel URL (R2)"
        )

    audience = payload.get("aud")
    audiences = audience if isinstance(audience, list) else [audience]
    if LEDGER_AUDIENCE not in audiences:
        raise SmokeCheckError(f"aud did not contain {LEDGER_AUDIENCE!r}: {audiences!r}")
    if ASSISTANT_AUDIENCE in audiences:
        raise SmokeCheckError(f"aud unexpectedly also contained {ASSISTANT_AUDIENCE!r}")

    roles = payload.get("roles")
    if not isinstance(roles, list) or ASSISTANT_ROLE not in roles:
        raise SmokeCheckError(f"roles did not contain {ASSISTANT_ROLE!r}: {roles!r}")

    if not payload.get("sub"):
        raise SmokeCheckError("the token carried no sub claim")

    return access_token


def check_ledger_read_admitted(token: str) -> None:
    """Check 2: the assistant role has ledger:read (ADR-032's role→permission table)."""
    status, _headers, _body = _request(
        LEDGER_HOST, LEDGER_PORT, "GET", LEDGER_ACCOUNTS_PATH, token=token
    )
    if status != HTTP_OK:
        raise SmokeCheckError(f"GET {LEDGER_ACCOUNTS_PATH} answered {status}, expected {HTTP_OK}")


def check_ledger_write_forbidden(token: str) -> None:
    """Check 3: the assistant role has no ledger:write — it is read-only on the ledger (ADR-004)."""
    status, _headers, body = _request(
        LEDGER_HOST, LEDGER_PORT, "POST", LEDGER_ACCOUNTS_PATH, token=token, body=EMPTY_JSON_BODY
    )
    if status != HTTP_FORBIDDEN:
        raise SmokeCheckError(
            _status_mismatch(f"POST {LEDGER_ACCOUNTS_PATH}", status, HTTP_FORBIDDEN)
        )
    code = _problem_code(body)
    if code != CODE_FORBIDDEN:
        raise SmokeCheckError(f"problem code was {code!r}, expected {CODE_FORBIDDEN!r}")


def check_ledger_requires_a_token() -> None:
    """Check 4: the ledger denies by default — no token, no access, ever (ADR-030)."""
    status, headers, _body = _request(LEDGER_HOST, LEDGER_PORT, "GET", LEDGER_ACCOUNTS_PATH)
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(f"GET {LEDGER_ACCOUNTS_PATH} with no token", status, HTTP_UNAUTHORIZED)
        )
    if headers.get(WWW_AUTHENTICATE_HEADER) is None:
        raise SmokeCheckError(f"the 401 carried no {WWW_AUTHENTICATE_HEADER} header")


def check_wrong_audience_at_the_assistant(ledger_token: str) -> None:
    """Check 5: a ledger-audience token is not a valid credential at the assistant either."""
    status, _headers, _body = _request(
        ASSISTANT_HOST, ASSISTANT_PORT, "GET", ASSISTANT_POSITION_PATH, token=ledger_token
    )
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(
                f"GET {ASSISTANT_POSITION_PATH} with the ledger's token", status, HTTP_UNAUTHORIZED
            )
        )


def check_gateway_denies_without_a_session() -> None:
    """Check 6: an unauthenticated gateway request answers a 401 problem, never a 302 (ADR-030).

    The whole point of routing sign-in through an explicit `GET /bff/oauth2/authorization/...`
    is that nothing else may start it implicitly. A relayed path that redirected instead would
    silently defeat that: a browser script reading `/ledger/...` would be bounced into an OIDC
    flow it never asked for.
    """
    status, headers, body = _request(
        GATEWAY_HOST, GATEWAY_PORT, "GET", GATEWAY_LEDGER_ACCOUNTS_PATH
    )
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(
                f"GET {GATEWAY_LEDGER_ACCOUNTS_PATH} with no session", status, HTTP_UNAUTHORIZED
            )
        )
    if headers.get(LOCATION_HEADER) is not None:
        raise SmokeCheckError(f"the 401 carried a {LOCATION_HEADER} header — this was a redirect")
    code = _problem_code(body)
    if code != CODE_UNAUTHENTICATED:
        raise SmokeCheckError(f"problem code was {code!r}, expected {CODE_UNAUTHENTICATED!r}")


def check_gateway_login_entry_redirects() -> None:
    """Check 7: the login entry sends the browser to Keycloak's public authorize endpoint,
    with PKCE and a redirect_uri the browser itself can reach — never the backchannel one.

    Redirect-following must stay off here (see `_request`'s docstring): following it would
    reach `keycloak:8080` from inside this container, which is not what a real browser could
    ever do, and would hide a wrong `Location` behind a response that still "worked".
    """
    status, headers, _body = _request(GATEWAY_HOST, GATEWAY_PORT, "GET", GATEWAY_LOGIN_ENTRY_PATH)
    if status != HTTP_FOUND:
        raise SmokeCheckError(f"the login entry answered {status}, expected {HTTP_FOUND}")

    location = headers.get(LOCATION_HEADER)
    if not location:
        raise SmokeCheckError(f"the {HTTP_FOUND} carried no {LOCATION_HEADER} header")

    parsed = urllib.parse.urlsplit(location)
    authorization_endpoint = urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, parsed.path, "", "")
    )
    if authorization_endpoint != PUBLIC_AUTHORIZATION_ENDPOINT:
        raise SmokeCheckError(
            f"redirected to {authorization_endpoint!r}, expected the public authorization endpoint"
        )

    query = urllib.parse.parse_qs(parsed.query)
    if query.get("code_challenge_method") != ["S256"]:
        raise SmokeCheckError("the redirect did not carry code_challenge_method=S256")
    if query.get("redirect_uri") != [PUBLIC_REDIRECT_URI]:
        raise SmokeCheckError(f"redirect_uri was not the public {PUBLIC_REDIRECT_URI!r}")


def check_ledger_health_is_public() -> None:
    """Check 8: Actuator health stays public on the ledger's main port (a settled design choice,
    recorded in the plan's "Actuator stays on the ledger's main port" decision)."""
    status, _headers, _body = _request(LEDGER_HOST, LEDGER_PORT, "GET", LEDGER_HEALTH_PATH)
    if status != HTTP_OK:
        raise SmokeCheckError(f"GET {LEDGER_HEALTH_PATH} answered {status}, expected {HTTP_OK}")


def _emit(message: str, *, to_stderr: bool = False) -> None:
    """Writes one line of this CLI's own report — never application logging.

    `ops/smoke` is a standalone operator diagnostic, not one of the three services
    ".claude/CLAUDE.md"'s "nothing writes output except the logging boundary" rule protects;
    stdout and stderr are its actual interface for a human running `make smoke-auth`.
    """
    print(message, file=sys.stderr if to_stderr else sys.stdout)


def main() -> int:
    """Runs every check in order, stopping at the first failure (see the module docstring)."""
    # Holds the token check 1 fetches, for the later checks that reuse it rather than fetching
    # their own — closed over by the steps below instead of threaded through `steps`'s uniform
    # no-argument shape.
    token_holder: dict[str, str] = {}

    def step_1() -> None:
        token_holder["assistant"] = check_assistant_token_claims()

    def step_2() -> None:
        check_ledger_read_admitted(token_holder["assistant"])

    def step_3() -> None:
        check_ledger_write_forbidden(token_holder["assistant"])

    def step_5() -> None:
        check_wrong_audience_at_the_assistant(token_holder["assistant"])

    steps: list[Callable[[], None]] = [
        step_1,
        step_2,
        step_3,
        check_ledger_requires_a_token,
        step_5,
        check_gateway_denies_without_a_session,
        check_gateway_login_entry_redirects,
        check_ledger_health_is_public,
    ]

    for number, step in enumerate(steps, start=1):
        name = CHECK_NAMES[number]
        try:
            step()
        except SmokeCheckError as failure:
            _emit(f"FAILED check {number} ({name}): {failure}", to_stderr=True)
            return 1
        except OSError as error:
            # A service unreachable over the Compose network is exactly the class of wiring
            # fault this script exists to catch, not an internal error to mask.
            _emit(
                f"FAILED check {number} ({name}): could not reach a service — {error}",
                to_stderr=True,
            )
            return 1
        _emit(f"{number}. {name}: PASS")

    _emit(f"{len(steps)}/{len(steps)} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
