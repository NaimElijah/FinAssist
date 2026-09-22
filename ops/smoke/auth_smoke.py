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
token, a client secret, a cookie, an `Authorization` header or a response body. A failure
message may quote a non-secret claim value (the token's `alg`, `iss` or `aud`, a role name)
where the failure cannot be diagnosed without it, and a response's status, problem code,
`WWW-Authenticate` challenge, or redirect target stripped of its query. An error none of the
checks anticipated is reported by its type alone: a standard-library exception's message can
quote its input, and `http.client`'s "Invalid header value" quotes the whole rejected header,
token included.

Run it with `make smoke-auth` (see README.md, "Smoke-testing authentication"). Refs #38.
"""

from __future__ import annotations

import base64
import http.client
import json
import os
import re
import sys
import urllib.parse
from collections.abc import Callable
from email.message import Message
from typing import Final

# --- Where each service answers inside the Compose network. docker-compose.yml fixes every one
# of these, and no `.env` setting moves them, so they are constants. `_INTERNAL_PORT`, never
# `_PORT`: `.env`'s KEYCLOAK_PORT, LEDGER_PORT, ASSISTANT_PORT and GATEWAY_PORT name the ports
# published on the host, which this script never uses.
KEYCLOAK_HOST: Final = "keycloak"
KEYCLOAK_INTERNAL_PORT: Final = 8080
LEDGER_HOST: Final = "ledger"
LEDGER_INTERNAL_PORT: Final = 8080
ASSISTANT_HOST: Final = "assistant"
ASSISTANT_INTERNAL_PORT: Final = 8000
GATEWAY_HOST: Final = "gateway"
GATEWAY_INTERNAL_PORT: Final = 8090

REALM_PATH: Final = "/realms/finassist"
KEYCLOAK_TOKEN_PATH: Final = REALM_PATH + "/protocol/openid-connect/token"
KEYCLOAK_AUTHORIZATION_PATH: Final = REALM_PATH + "/protocol/openid-connect/auth"
LEDGER_ACCOUNTS_PATH: Final = "/api/v1/accounts"
LEDGER_HEALTH_PATH: Final = "/actuator/health"
ASSISTANT_POSITION_PATH: Final = "/api/v1/portfolio/holdings/VOO/position"
GATEWAY_LEDGER_ACCOUNTS_PATH: Final = "/ledger/api/v1/accounts"
GATEWAY_LOGIN_ENTRY_PATH: Final = "/bff/oauth2/authorization/keycloak"
GATEWAY_CALLBACK_PATH: Final = "/bff/login/oauth2/code/keycloak"

# --- Fixed by ops/keycloak/finassist-realm.json and the ADRs it implements.
ASSISTANT_CLIENT_ID: Final = "finassist-assistant"
ASSISTANT_CLIENT_SECRET_ENV: Final = "ASSISTANT_CLIENT_SECRET"
LEDGER_AUDIENCE: Final = "finassist-ledger"
ASSISTANT_AUDIENCE: Final = "finassist-assistant"
ASSISTANT_ROLE: Final = "assistant"
EXPECTED_ALG: Final = "RS256"

# --- The public bases a browser uses, which `.env` can move (KEYCLOAK_PORT, GATEWAY_PUBLIC_URL).
# docker-compose.yml passes this container both, built from the same expressions it builds
# KC_HOSTNAME, the resource servers' issuer and the gateway's GATEWAY_PUBLIC_URL from, so a
# reconfigured stack is checked against its own settings. Never derived from the address this
# script reaches a service by: check 1 exists to notice an `iss` naming that backchannel address.
KEYCLOAK_PUBLIC_URL_ENV: Final = "KEYCLOAK_PUBLIC_URL"
GATEWAY_PUBLIC_URL_ENV: Final = "GATEWAY_PUBLIC_URL"
# ADR-030 signs the browser in with PKCE S256; `plain` would put the verifier itself in the URL.
PKCE_METHOD: Final = "S256"

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
AUTHORIZATION_HEADER: Final = "Authorization"
CONTENT_TYPE_HEADER: Final = "Content-Type"
BEARER_SCHEME: Final = "Bearer"
# What the ledger and the assistant send for a token they read and refused (ADR-030); a request
# with no token at all gets the bare `BEARER_SCHEME` instead.
INVALID_TOKEN_CHALLENGE: Final = 'Bearer error="invalid_token"'
JSON_CONTENT_TYPE: Final = "application/json"
# The OAuth2 token endpoint's request shape (RFC 6749, section 4.4.2).
FORM_CONTENT_TYPE: Final = "application/x-www-form-urlencoded"

# An empty JSON object is enough: the authorization filter chain in all three services decides
# on the token before any request body is parsed, so a write refused by role never needs a
# well-formed payload (mirrors LedgerAuthorizationIT's own JSON_BODY constant).
EMPTY_JSON_BODY: Final = b"{}"

REQUEST_TIMEOUT_SECONDS: Final = 10

# A JWS compact serialisation (RFC 7515, section 7.1): three base64url segments joined by two
# dots, and nothing else. The token goes into an `Authorization` header, so a character outside
# this alphabet — a CR or an LF above all — must stop at check 1, not reach `http.client`.
JWS_COMPACT: Final = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")


class SmokeCheckError(Exception):
    """Raised by a check that did not get what ADR-030 through ADR-032 promise.

    Carries only a human-readable reason. `main` reports it against the check `_CheckRun` was
    running, so a check never has to know its own number or name.
    """


def _request(
    host: str,
    port: int,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: bytes | None = None,
    content_type: str = JSON_CONTENT_TYPE,
) -> tuple[int, Message, bytes]:
    """Sends one HTTP request and returns (status, headers, body) — including for a refusal.

    Uses `http.client` rather than `urllib.request` because a redirect must be observed, never
    followed (check 7): `http.client` never follows one on its own, so there is no redirect
    handler to disable.

    :param token: sent as a bearer token when given.
    :param body: sent, labelled `content_type`, when given; a request without one sends neither.
    """
    headers: dict[str, str] = {}
    if token is not None:
        headers[AUTHORIZATION_HEADER] = f"{BEARER_SCHEME} {token}"
    if body is not None:
        headers[CONTENT_TYPE_HEADER] = content_type
    connection = http.client.HTTPConnection(host, port, timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        return response.status, response.headers, response.read()
    finally:
        connection.close()


def _decode_jwt(token: str) -> tuple[dict[str, object], dict[str, object]]:
    """Decodes a JWT's header and payload without verifying its signature.

    Signature verification is each resource server's own job (ADR-030) — proven by
    `KeycloakRealmIT` and the two services' own test suites. This check only inspects claims the
    services have already promised to enforce.

    :param token: already matched against `JWS_COMPACT`, so it has exactly three segments.
    :raises SmokeCheckError: the header or the payload is not a base64url-encoded JSON object.
    """
    header, payload, _signature = token.split(".")

    def decode_segment(segment: str) -> dict[str, object]:
        padding = "=" * (-len(segment) % 4)
        try:
            parsed = json.loads(base64.urlsafe_b64decode(segment + padding))
        except ValueError as malformed:
            raise SmokeCheckError("a JWT segment was not base64url-encoded JSON") from malformed
        if not isinstance(parsed, dict):
            raise SmokeCheckError("a JWT segment did not decode to a JSON object")
        return parsed

    return decode_segment(header), decode_segment(payload)


def _required_environment(name: str) -> str:
    """Reads one variable docker-compose.yml sets for this container.

    :raises SmokeCheckError: the variable is unset or blank. The message names the variable,
        never a value.
    """
    value = os.environ.get(name, "")
    if not value.strip():
        raise SmokeCheckError(f"{name} is not set in this container")
    return value


def _public_url(name: str) -> str:
    """Reads a public base URL, normalised the way the gateway's `Origin` normalises it.

    `Origin` strips surrounding whitespace and every trailing slash from GATEWAY_PUBLIC_URL
    before building a URL from it, so an expectation built here must too, or the two disagree
    over nothing but a slash.
    """
    return _required_environment(name).strip().rstrip("/")


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
    secret = _required_environment(ASSISTANT_CLIENT_SECRET_ENV)
    public_issuer = _public_url(KEYCLOAK_PUBLIC_URL_ENV) + REALM_PATH

    form = urllib.parse.urlencode(
        {
            "grant_type": "client_credentials",
            "client_id": ASSISTANT_CLIENT_ID,
            "client_secret": secret,
        }
    ).encode()
    status, _headers, body = _request(
        KEYCLOAK_HOST,
        KEYCLOAK_INTERNAL_PORT,
        "POST",
        KEYCLOAK_TOKEN_PATH,
        body=form,
        content_type=FORM_CONTENT_TYPE,
    )
    if status != HTTP_OK:
        raise SmokeCheckError(f"the token endpoint answered {status}, expected {HTTP_OK}")

    try:
        token_response = json.loads(body)
    except ValueError as not_json:
        raise SmokeCheckError("the token endpoint's 200 carried no JSON body") from not_json
    access_token = token_response.get("access_token") if isinstance(token_response, dict) else None
    if not isinstance(access_token, str) or not access_token:
        raise SmokeCheckError("the token response carried no access_token")
    # fullmatch, never match with `$`: `$` also matches just before a trailing newline, the very
    # character this refuses.
    if JWS_COMPACT.fullmatch(access_token) is None:
        raise SmokeCheckError("the access_token was not a JWS compact serialisation")

    header, payload = _decode_jwt(access_token)

    alg = header.get("alg")
    if alg != EXPECTED_ALG:
        raise SmokeCheckError(f"header alg was {alg!r}, expected {EXPECTED_ALG!r}")

    issuer = payload.get("iss")
    if issuer != public_issuer:
        raise SmokeCheckError(f"iss was {issuer!r}, expected the public issuer {public_issuer!r}")

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
        LEDGER_HOST, LEDGER_INTERNAL_PORT, "GET", LEDGER_ACCOUNTS_PATH, token=token
    )
    if status != HTTP_OK:
        raise SmokeCheckError(f"GET {LEDGER_ACCOUNTS_PATH} answered {status}, expected {HTTP_OK}")


def check_ledger_write_forbidden(token: str) -> None:
    """Check 3: the assistant role has no ledger:write — it is read-only on the ledger (ADR-004)."""
    status, _headers, body = _request(
        LEDGER_HOST,
        LEDGER_INTERNAL_PORT,
        "POST",
        LEDGER_ACCOUNTS_PATH,
        token=token,
        body=EMPTY_JSON_BODY,
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
    status, headers, _body = _request(
        LEDGER_HOST, LEDGER_INTERNAL_PORT, "GET", LEDGER_ACCOUNTS_PATH
    )
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(f"GET {LEDGER_ACCOUNTS_PATH} with no token", status, HTTP_UNAUTHORIZED)
        )
    # The exact scheme, as LedgerAuthorizationIT pins it: mere presence would also pass a
    # re-enabled HTTP Basic answering `Basic realm=...`.
    challenge = headers.get(WWW_AUTHENTICATE_HEADER)
    if challenge != BEARER_SCHEME:
        raise SmokeCheckError(
            f"the 401's {WWW_AUTHENTICATE_HEADER} was {challenge!r}, expected {BEARER_SCHEME!r}"
        )


def check_wrong_audience_at_the_assistant(ledger_token: str) -> None:
    """Check 5: the assistant refuses a token whose audience is the ledger's (ADR-030, ADR-032).

    Asserts exactly what the assistant's `BearerAuthentication` sends for a token it read and
    refused: 401, `WWW-Authenticate: Bearer error="invalid_token"` and problem code
    `UNAUTHENTICATED`. That proves the token reached the assistant's verifier and was refused as
    invalid, rather than treated as missing (a bare `Bearer`) or left undecided because the key
    source was unreachable (a 503).

    It cannot prove why. This token fails the audience check whatever else is wrong, so an
    assistant with a mis-set ASSISTANT_OIDC_ISSUER, or a JWKS URI serving some other realm's
    keys, answers exactly the same. The ledger has check 2 as its positive control; the assistant
    has none here, because only a token whose audience is the assistant could be one, and only an
    owner's or viewer's token is: that needs an interactive sign-in through the gateway, which
    this script cannot perform.
    """
    status, headers, body = _request(
        ASSISTANT_HOST, ASSISTANT_INTERNAL_PORT, "GET", ASSISTANT_POSITION_PATH, token=ledger_token
    )
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(
                f"GET {ASSISTANT_POSITION_PATH} with the ledger's token", status, HTTP_UNAUTHORIZED
            )
        )
    challenge = headers.get(WWW_AUTHENTICATE_HEADER)
    if challenge != INVALID_TOKEN_CHALLENGE:
        raise SmokeCheckError(
            f"the 401's {WWW_AUTHENTICATE_HEADER} was {challenge!r},"
            f" expected {INVALID_TOKEN_CHALLENGE!r}"
        )
    code = _problem_code(body)
    if code != CODE_UNAUTHENTICATED:
        raise SmokeCheckError(f"problem code was {code!r}, expected {CODE_UNAUTHENTICATED!r}")


def check_gateway_denies_without_a_session() -> None:
    """Check 6: an unauthenticated gateway request answers a 401 problem, never a 302 (ADR-030).

    The whole point of routing sign-in through an explicit `GET /bff/oauth2/authorization/...`
    is that nothing else may start it implicitly. A relayed path that redirected instead would
    silently defeat that: a browser script reading `/ledger/...` would be bounced into an OIDC
    flow it never asked for.

    The 401 must also carry no `WWW-Authenticate`. The gateway's own refusal never does, since it
    takes a session cookie rather than a credential (its `ProblemAuthenticationEntryPoint`); the
    ledger's always does. Without this, a route that stopped requiring a session would relay the
    ledger's look-alike 401 problem and pass.
    """
    status, headers, body = _request(
        GATEWAY_HOST, GATEWAY_INTERNAL_PORT, "GET", GATEWAY_LEDGER_ACCOUNTS_PATH
    )
    if status != HTTP_UNAUTHORIZED:
        raise SmokeCheckError(
            _status_mismatch(
                f"GET {GATEWAY_LEDGER_ACCOUNTS_PATH} with no session", status, HTTP_UNAUTHORIZED
            )
        )
    if headers.get(LOCATION_HEADER) is not None:
        raise SmokeCheckError(f"the 401 carried a {LOCATION_HEADER} header — this was a redirect")
    if headers.get(WWW_AUTHENTICATE_HEADER) is not None:
        raise SmokeCheckError(
            f"the 401 carried a {WWW_AUTHENTICATE_HEADER} header — a relayed 401, not the"
            " gateway's own"
        )
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
    public_authorization_endpoint = (
        _public_url(KEYCLOAK_PUBLIC_URL_ENV) + KEYCLOAK_AUTHORIZATION_PATH
    )
    public_redirect_uri = _public_url(GATEWAY_PUBLIC_URL_ENV) + GATEWAY_CALLBACK_PATH

    status, headers, _body = _request(
        GATEWAY_HOST, GATEWAY_INTERNAL_PORT, "GET", GATEWAY_LOGIN_ENTRY_PATH
    )
    if status != HTTP_FOUND:
        raise SmokeCheckError(f"the login entry answered {status}, expected {HTTP_FOUND}")

    location = headers.get(LOCATION_HEADER)
    if not location:
        raise SmokeCheckError(f"the {HTTP_FOUND} carried no {LOCATION_HEADER} header")

    parsed = urllib.parse.urlsplit(location)
    authorization_endpoint = urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, parsed.path, "", "")
    )
    if authorization_endpoint != public_authorization_endpoint:
        raise SmokeCheckError(
            f"redirected to {authorization_endpoint!r},"
            f" expected the public {public_authorization_endpoint!r}"
        )

    query = urllib.parse.parse_qs(parsed.query)
    if query.get("code_challenge_method") != [PKCE_METHOD]:
        raise SmokeCheckError(f"the redirect did not carry code_challenge_method={PKCE_METHOD}")
    if query.get("redirect_uri") != [public_redirect_uri]:
        raise SmokeCheckError(f"redirect_uri was not the public {public_redirect_uri!r}")


def check_ledger_health_is_public() -> None:
    """Check 8: Actuator health stays public on the ledger's main port (a settled design choice,
    recorded in the plan's "Actuator stays on the ledger's main port" decision)."""
    status, _headers, _body = _request(LEDGER_HOST, LEDGER_INTERNAL_PORT, "GET", LEDGER_HEALTH_PATH)
    if status != HTTP_OK:
        raise SmokeCheckError(f"GET {LEDGER_HEALTH_PATH} answered {status}, expected {HTTP_OK}")


def _report(line: str) -> None:
    """Writes one line of this CLI's report to stdout, flushed at once.

    PASS and FAILED lines share one stream on purpose: their order is the report (which checks
    passed before the one that failed), and two streams would leave that order to buffering and
    to however a caller joins them. The exit status, not the stream, says the run failed.
    """
    # T201 exists to keep output behind the services' logging boundary (ADR-019). This is an
    # operator CLI, not a service: the report on stdout is its whole product.
    print(line, flush=True)  # noqa: T201


class _CheckRun:
    """Numbers and reports the checks `main` runs, in the order it runs them.

    Each check's name is paired with its function at the one call in `main` that runs it, so the
    two cannot drift apart, and the token check 1 returns reaches the checks that reuse it as an
    ordinary value.
    """

    def __init__(self) -> None:
        self.passed = 0
        self.current = ""

    def check[**P, R](
        self, name: str, step: Callable[P, R], /, *args: P.args, **kwargs: P.kwargs
    ) -> R:
        """Runs one check and reports it passed.

        :param name: the check's name in the report.
        :param step: the check itself, called with `args` and `kwargs`.
        :return: whatever `step` returned.
        :raises Exception: whatever `step` raised, unreported: `main` reports it against
            `current`, which already names this check.
        """
        number = self.passed + 1
        self.current = f"{number} ({name})"
        result = step(*args, **kwargs)
        _report(f"{number}. {name}: PASS")
        self.passed = number
        return result


def main() -> int:
    """Runs every check in order, stopping at the first failure (see the module docstring)."""
    run = _CheckRun()
    try:
        token = run.check("assistant client-credentials token claims", check_assistant_token_claims)
        run.check(
            "GET /api/v1/accounts with the assistant token", check_ledger_read_admitted, token
        )
        run.check(
            "POST /api/v1/accounts with the assistant token", check_ledger_write_forbidden, token
        )
        run.check("GET /api/v1/accounts with no token", check_ledger_requires_a_token)
        run.check(
            "the ledger-audience token presented to the assistant",
            check_wrong_audience_at_the_assistant,
            token,
        )
        run.check(
            "the gateway's /ledger/... with no session", check_gateway_denies_without_a_session
        )
        run.check("the gateway's login entry", check_gateway_login_entry_redirects)
        run.check("GET /actuator/health with no token", check_ledger_health_is_public)
    except SmokeCheckError as failure:
        _report(f"FAILED check {run.current}: {failure}")
        return 1
    except OSError as error:
        # A service unreachable over the Compose network is exactly the class of wiring
        # fault this script exists to catch, not an internal error to mask.
        _report(f"FAILED check {run.current}: could not reach a service — {error}")
        return 1
    except Exception as error:
        # Last resort, so that every failure still names its check. The type alone, never the
        # message, a repr or a traceback: see the module docstring for the one that quotes a token.
        _report(f"FAILED check {run.current}: unexpected {type(error).__name__}")
        return 1

    _report(f"{run.passed}/{run.passed} checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
