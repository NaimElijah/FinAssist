"""Verifies that a bearer token is genuine (ADR-030, ADR-032).

A token is genuine when it is signed by a key the configured issuer actually controls, names
that issuer and this service's own audience, is alive right now, and identifies somebody. Every
one of those is required, never merely checked if present - a token that omits a claim is
refused exactly as one that gets it wrong.

Lives in the composition root, alongside :mod:`~finassist_assistant.adapters.settings` and
:mod:`~finassist_assistant.adapters.observability`, because PyJWT and ``cryptography`` are
frameworks: ``tests/test_architecture.py`` forbids importing either from an inward layer, and
this is the one package allowed to import them outside a context's own ``adapters``.

:class:`BearerAuthentication` wires the verifier into every request, denying by default (ADR-030);
:class:`RequirePermission` and :data:`ROLE_PERMISSIONS` decide what a verified caller may then do
(ADR-032). Nothing in :class:`TokenVerifier` itself decides that - it carries a token's own
``roles`` claim on :class:`Principal` and nothing more.

Two traps a first pass at this kind of check tends to fall into, both guarded here and pinned by
``tests/test_auth.py``:

- **``iat`` in the future.** A validator that checks only ``exp`` and ``nbf`` admits a token that
  claims to have been issued in the future. :meth:`TokenVerifier._check_lifetime` checks ``iat``
  against the clock exactly as it checks ``nbf`` - refused once it is more than the leeway ahead.
- **``require`` with verification switched off.** ``jwt.decode`` is called with
  ``verify_exp``/``verify_nbf``/``verify_iat`` all ``False``, so this module can check them
  against an *injected* clock instead of the wall clock PyJWT would otherwise read. Turning those
  three off does not turn ``require`` off too: read from the installed ``jwt.api_jwt`` source,
  ``PyJWT._validate_claims`` calls ``_validate_required_claims`` unconditionally, before any
  ``verify_*`` flag is even inspected, so a claim named in ``require`` still has to be present
  whatever the ``verify_*`` options say. A missing ``exp`` is refused for exactly that reason.

The issuer check is a deliberate trade of the same kind: ``jwt.decode`` is never given
``issuer=``, so PyJWT's own (redundant) issuer comparison never runs, and
:meth:`TokenVerifier._check_issuer` is the *only* one left. That is safe - it is an exact string
comparison, ``iss`` is required by ``require`` regardless of whether anything then compares it,
and four tests in ``tests/test_auth.py`` guard the exact-match boundary - so do not pass
``issuer=`` back in "to be safe twice"; it would only duplicate a check this module already owns.
"""

from __future__ import annotations

import math
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from http import HTTPStatus
from typing import Any, Final

import jwt
import structlog
from fastapi import HTTPException, Request
from jwt import PyJWK, PyJWKClient
from jwt.exceptions import InvalidSubjectError
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import Headers
from starlette.types import ASGIApp, Receive, Scope, Send

from finassist_assistant.adapters.problems import (
    AUTHENTICATION_UNAVAILABLE,
    UNAUTHENTICATED,
    problem_response,
)
from finassist_assistant.adapters.wiring import Clock

__all__ = [
    "ALGORITHM",
    "ASSISTANT_AUDIENCE",
    "DEFAULT_LEEWAY",
    "PRINCIPAL_SCOPE_KEY",
    "PUBLIC_PATHS",
    "REASON_AUDIENCE",
    "REASON_ISSUER",
    "REASON_KEY_SOURCE",
    "REASON_LIFETIME",
    "REASON_MALFORMED",
    "REASON_MISSING",
    "REASON_REJECTED",
    "REASON_SUBJECT",
    "REASON_UNKNOWN_KEY",
    "REQUIRED_CLAIMS",
    "ROLE_PERMISSIONS",
    "BearerAuthentication",
    "KeySource",
    "KeySourceUnavailable",
    "Principal",
    "RequirePermission",
    "TokenRefused",
    "TokenVerifier",
    "jwks_key_source",
]

_log = structlog.get_logger(__name__)

ALGORITHM: Final = "RS256"
"""The only signature algorithm ever accepted.

Naming exactly one fixes the algorithm allow-list ``jwt.decode`` checks before it ever looks at a
key. That check alone is what defeats an attacker who signs a token with ``alg: HS256`` and
tries to use this service's own RSA *public* key as the HMAC secret (RFC 8725 S2.1): the header's
algorithm is compared against this allow-list first, so the forged signature is never even
computed against.
"""

ASSISTANT_AUDIENCE: Final = "finassist-assistant"
"""This service's own audience (ADR-032).

Fixed here, not read from the environment: a caller cannot redirect the assistant into accepting
a token meant for another audience - such as the assistant's own service-account token, whose
audience names only the ledger - by configuration.
"""

REQUIRED_CLAIMS: Final[tuple[str, ...]] = ("sub", "iss", "aud", "exp", "iat")
"""ADR-030's token validation: every one of these must be present, not merely valid when present."""

DEFAULT_LEEWAY: Final = timedelta(seconds=30)
"""Clock-skew allowance either side of ``exp``, ``nbf`` and ``iat`` (ADR-030)."""

_JWKS_CACHE_LIFESPAN_SECONDS: Final = 300
_JWKS_TIMEOUT_SECONDS: Final = 5

# The fixed reason vocabulary every authentication failure is logged under (ADR-030). A caller
# logs one of these nine words, never the token and never a claim value.
REASON_MISSING: Final = "missing"
REASON_MALFORMED: Final = "malformed"
REASON_REJECTED: Final = "rejected"
REASON_LIFETIME: Final = "lifetime"
REASON_ISSUER: Final = "issuer"
REASON_AUDIENCE: Final = "audience"
REASON_SUBJECT: Final = "subject"
REASON_UNKNOWN_KEY: Final = "unknown_key"
REASON_KEY_SOURCE: Final = "key_source"

_MISSING_CLAIM_REASON: Final[Mapping[str, str]] = {
    "sub": REASON_SUBJECT,
    "iss": REASON_ISSUER,
    "aud": REASON_AUDIENCE,
    "exp": REASON_LIFETIME,
    "iat": REASON_LIFETIME,
}
"""Which reason category a claim reports when it is missing entirely.

Matches the category its own validator would report had the claim been present but wrong, so a
caller sees the same reason either way - a token with no ``iss`` at all is as much an ``issuer``
failure as one naming the wrong issuer.
"""


KeySource = Callable[[str], PyJWK]
"""Resolves the key that must have signed a token.

:func:`jwks_key_source` satisfies this against a real JWKS endpoint. Tests substitute a static
stand-in that returns one fixed key regardless of the token it is given.

Whatever the implementation, it reports a failure in one of three ways, and
:class:`TokenVerifier` relies on the split: :class:`KeySourceUnavailable` when the key set could
not be fetched or holds nothing a token can be verified against; ``jwt.PyJWKClientError`` when the
set holds keys, only not the one the token names; and any other ``jwt.PyJWTError`` when the token
itself is malformed.
"""


@dataclass(frozen=True, slots=True)
class Principal:
    """Who a verified bearer token identifies, and which roles it named.

    :param subject: the token's ``sub`` claim, already checked non-blank.
    :param roles: the token's own ``roles`` claim, read defensively by
        :class:`TokenVerifier` and never re-parsed from the raw token afterwards - acting on a
        claim nobody has verified would defeat the point of verifying it once. A missing claim,
        one that is not a JSON array, and any element that is not a string are each dropped
        rather than raised, mirroring the ledger's ``LedgerPermissions.convert``: a caller
        controls this claim, and a token that shapes it oddly must be handled, not trusted.

    Nothing about a caller's authority is decided here. Which permission a role grants is
    :data:`ROLE_PERMISSIONS`'s job, applied later by :class:`RequirePermission` - this dataclass
    carries only the claims a valid token proved.
    """

    subject: str
    roles: frozenset[str] = field(default_factory=frozenset)


class TokenRefused(Exception):  # noqa: N818 - ADR-030's own term: a refused token, not an error
    """A bearer token was read but is not valid.

    :param reason: one of this module's fixed reason categories - never the token itself and
        never a claim value, so a caller may log it without ever risking either.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class KeySourceUnavailable(Exception):  # noqa: N818 - ADR-030's term: an unreachable key source
    """The JWKS source could not be reached, or answered nothing a token can be verified against;
    this token's validity could not be decided (ADR-030, ADR-037).

    Deliberately a different exception type from :class:`TokenRefused`, mirroring the ledger's
    own split between a rejected token and an unusable key source, whether unreachable, unreadable
    or empty: nothing here says whether the token would have been valid, so a caller must answer
    503, never 401.
    """

    reason: Final[str] = REASON_KEY_SOURCE


class _KeySetClient(PyJWKClient):
    """A ``PyJWKClient`` that reports a key set it cannot use as an outage (ADR-037).

    PyJWT raises the same types for a key set it cannot use as for a token naming a key the set
    lacks: ``[]`` raises ``PyJWKClientError`` exactly as an unknown ``kid`` does. Telling the two
    apart by message would be fragile, so they are told apart by *where* they are raised instead.
    """

    def fetch_data(self) -> Any:
        """Fetches, decodes and parses the key set, exactly as ``PyJWKClient`` does.

        :raises KeySourceUnavailable: the set could not be fetched, or holds nothing a token can be
            verified against. This method never sees a token, so no failure inside it can be the
            caller's - which is what makes catching ``ValueError`` here safe, where catching it
            around the whole key lookup would also take an error raised from the token itself.
        """
        try:
            return super().fetch_data()
        except (
            jwt.PyJWKClientError,
            jwt.PyJWKSetError,
            ValueError,
            TypeError,
            RecursionError,
            OSError,
        ) as unusable:
            # PyJWKClientError: unreachable (its subclass PyJWKClientConnectionError), or a body
            # that is not a JSON object. PyJWKSetError: no keys member, no keys, or no key of a
            # type PyJWT supports - raised from the key-set cache's own parse, which runs in here
            # only because jwks_key_source leaves that cache on. ValueError: not JSON, not UTF-8,
            # or an integer past CPython's conversion limit (#121, #126). TypeError: a key member
            # of the wrong JSON type, which PyJWK raises bare from that same parse and the key set
            # does not skip. RecursionError: nested too deep to parse, and not a ValueError at all.
            # OSError: the connection reset while the response was being read. urllib passes that
            # through raw, and it is none of the three types PyJWT wraps.
            raise KeySourceUnavailable from unusable


def jwks_key_source(uri: str) -> KeySource:
    """Builds a :data:`KeySource` backed by a real JWKS endpoint.

    :param uri: the *internal* JWKS URI, fetched service-to-service. Always distinct from a
        token's own ``iss``, which is always the public issuer a browser signed in against
        (ADR-030) - the two are never the same value, so they are never the same setting.

    The returned key source caches the fetched key set for five minutes and, once fetched, the
    individual signing key each ``kid`` resolves to, so an ordinary verification fetches nothing
    over the network. Five seconds bounds the wait on a fetch, so an unreachable JWKS endpoint
    fails quickly rather than hanging the request that discovered it. A key set that cannot be
    fetched or used raises :class:`KeySourceUnavailable` through :class:`_KeySetClient`; a failed
    refresh leaves the last good set cached, because PyJWT caches only a successful fetch.
    """
    client = _KeySetClient(
        uri,
        cache_keys=True,
        lifespan=_JWKS_CACHE_LIFESPAN_SECONDS,
        timeout=_JWKS_TIMEOUT_SECONDS,
    )
    return client.get_signing_key_from_jwt


class TokenVerifier:
    """Decides whether a bearer token is genuine, and who it names.

    A plain synchronous callable (ADR-021): build one with the service's own configuration, then
    call it with a token. Every check is required, not merely available - a token silent about
    one of them is refused exactly as one that gets it wrong.

    :param key_source: resolves the key that must have signed the token.
    :param issuer: the exact ``iss`` a token must name. Compared as an exact string, never a
        prefix: ``http://localhost:8081/realms/finassist-evil`` extends the real issuer
        byte-for-byte and must still be refused.
    :param audience: the exact ``aud`` a token must name, among any others it also carries.
    :param clock: reads the current instant. Injected, never read from the wall clock directly,
        so ``exp``, ``nbf`` and ``iat`` are checked against a time a test can fix - the same
        "pass the clock in" rule the domain follows, applied at this boundary.
    :param leeway: how far either side of ``exp``, ``nbf`` and ``iat`` a token may sit and still
        be accepted, absorbing ordinary clock drift between this service and the issuer.
    """

    def __init__(
        self,
        key_source: KeySource,
        issuer: str,
        audience: str,
        clock: Clock,
        leeway: timedelta = DEFAULT_LEEWAY,
    ) -> None:
        self._key_source = key_source
        self._issuer = issuer
        self._audience = audience
        self._clock = clock
        self._leeway = leeway

    def __call__(self, token: str) -> Principal:
        """Verifies *token* and returns who it names.

        :raises TokenRefused: the token is blank, malformed, unsigned by a key this service
            trusts, or fails a required claim.
        :raises KeySourceUnavailable: the JWKS source could not be reached, or answered nothing a
            token can be verified against, so this token's validity could not be decided at all.
        """
        if not token.strip():
            raise TokenRefused(REASON_MISSING)

        _require_algorithm(token)

        # A KeySourceUnavailable from the key source is already the answer, and passes through.
        try:
            signing_key = self._key_source(token)
        except jwt.PyJWKClientError as unmatched:
            raise TokenRefused(REASON_UNKNOWN_KEY) from unmatched
        except jwt.PyJWTError as malformed:
            # get_signing_key_from_jwt peeks at the header before any key is known, so a
            # structurally broken token fails here, never inside jwt.decode below.
            raise TokenRefused(_reason_for(malformed)) from malformed

        try:
            claims = jwt.decode(
                token,
                key=signing_key,
                algorithms=[ALGORITHM],
                audience=self._audience,
                # `issuer` is deliberately not passed here: PyJWT would then own the comparison,
                # and this module could no longer be the place a loosened issuer check is caught.
                # `_check_issuer` below does it instead, over the `iss` `require` already forces
                # to be present.
                options={
                    "require": list(REQUIRED_CLAIMS),
                    "verify_exp": False,
                    "verify_nbf": False,
                    "verify_iat": False,
                },
            )
        except jwt.PyJWTError as refused:
            raise TokenRefused(_reason_for(refused)) from refused

        self._check_issuer(claims)
        self._check_lifetime(claims)

        subject = claims["sub"]
        if not subject.strip():
            raise TokenRefused(REASON_SUBJECT)
        return Principal(subject=subject, roles=_roles_from(claims))

    def _check_issuer(self, claims: Mapping[str, Any]) -> None:
        """Compares the raw ``iss`` claim to the configured issuer, as an exact string.

        Never a prefix or a suffix match: ``http://localhost:8081/realms/finassist-evil``
        extends the real issuer byte-for-byte and must still be refused. This is deliberately an
        explicit comparison this module owns, rather than delegated to ``jwt.decode``'s own
        ``issuer=`` handling - the point being not the rule's own strength (PyJWT compares
        exactly too) but that a change to it lives in this module, where a test can, and does,
        watch for it.
        """
        if claims["iss"] != self._issuer:
            raise TokenRefused(REASON_ISSUER)

    def _check_lifetime(self, claims: Mapping[str, Any]) -> None:
        """Checks ``exp``, ``nbf`` and ``iat`` against the injected clock, with the leeway.

        ``__call__`` already switched PyJWT's own timestamp checks off, so this is the only place
        any of the three is enforced, and it reads the clock this verifier was built with rather
        than the wall clock. ``exp`` and ``iat`` are guaranteed present by this point, because
        ``require`` already refused a token missing either; ``nbf`` is optional, since ADR-030
        does not list it among the claims every token must carry, so it is checked only when
        present.
        """
        now = self._clock()
        if _as_instant(claims["exp"]) < now - self._leeway:
            raise TokenRefused(REASON_LIFETIME)

        # A token claiming to be issued in the future is refused, exactly as one not yet valid
        # is - the check an earlier pass at this kind of validator can omit entirely.
        if _as_instant(claims["iat"]) > now + self._leeway:
            raise TokenRefused(REASON_LIFETIME)

        not_before = claims.get("nbf")
        if not_before is not None and _as_instant(not_before) > now + self._leeway:
            raise TokenRefused(REASON_LIFETIME)


def _require_algorithm(token: str) -> None:
    """Refuses a token whose header names any algorithm but :data:`ALGORITHM`, before a key is
    looked up.

    ``jwt.decode``'s own allow-list in :meth:`TokenVerifier.__call__` is still the defence. This
    check exists so the reason is the same whichever key source is asked. The real
    ``PyJWKClient`` looks a key up by the header's ``kid`` first, and an ``alg: none`` token names
    none, so it would be refused ``unknown_key`` there and ``malformed`` against any key source
    that returned a key (#124). Here it is ``malformed`` on both, as on the ledger, whose Nimbus
    refuses an "Unsupported algorithm of none" before it looks for a key. Refusing first also
    spares the key source a fetch for a token no key could ever verify.

    :raises TokenRefused: the header cannot be read, or names another algorithm.
    """
    try:
        header = jwt.get_unverified_header(token)
    except jwt.PyJWTError as unreadable:
        raise TokenRefused(_reason_for(unreadable)) from unreadable
    if header.get("alg") != ALGORITHM:
        raise TokenRefused(REASON_MALFORMED)


def _roles_from(claims: Mapping[str, Any]) -> frozenset[str]:
    """The already-verified token's ``roles`` claim, read defensively (ADR-032's role table).

    A missing claim, one that is not a JSON array, and a non-string element each contribute no
    role, exactly as the ledger's ``LedgerPermissions.convert`` reads ``Jwt#getClaim`` rather
    than ``Jwt#getClaimAsStringList`` for the same reason: the claim is caller-controlled, and a
    claim that exists but is shaped wrong must grant nothing rather than raise.
    """
    claim = claims.get("roles")
    if not isinstance(claim, list):
        return frozenset()
    return frozenset(role for role in claim if isinstance(role, str))


def _as_instant(value: Any) -> datetime:
    """A NumericDate claim as a UTC instant.

    A claim that is not really a number is refused, and so is a number no clock can ever be set
    to. ``json.loads`` accepts the literals ``Infinity``, ``-Infinity`` and ``NaN`` by default, so
    a real (if misbehaving) issuer can sign ``{"exp": Infinity}`` and have it arrive here as a
    genuine ``float`` rather than a parse error. ``math.isfinite`` refuses that value, and
    ``nan``, for a stated reason - rather than by relying on whichever exception a given platform
    happens to raise for them, which is not the same exception everywhere (see below).

    The ``try`` stays narrow, around the one remaining conversion - a numeric timestamp to a
    ``datetime`` - so catching ``OSError`` here is a targeted translation of a platform quirk,
    never a broad silence. ``OSError`` is a Windows-specific behaviour: its C runtime refuses some
    finite, non-``NaN``, non-infinite values that ``fromtimestamp`` cannot turn into a ``time_t``
    (observed for a large pre-epoch instant such as ``exp=-1e10``). ``OverflowError`` is caught
    alongside it because an extreme-but-finite value such as ``1e300`` overflows the same way on
    every platform. The security property this function guarantees - a non-representable
    timestamp is always refused, never left to crash the caller - holds everywhere; only the
    *reason category* a value falls under can differ by platform: this same ``exp=-1e10`` is
    refused ``malformed`` here, on Windows, but would convert successfully and then be refused as
    ``lifetime`` (simply very expired) on a platform whose C runtime accepts it.
    """
    try:
        numeric = float(value)
        if not math.isfinite(numeric):
            raise TokenRefused(REASON_MALFORMED)
        return datetime.fromtimestamp(numeric, tz=UTC)
    except (TypeError, ValueError, OverflowError, OSError) as unparseable:
        raise TokenRefused(REASON_MALFORMED) from unparseable


def _reason_for(exception: jwt.PyJWTError) -> str:
    """Maps a PyJWT failure onto this module's fixed reason vocabulary.

    PyJWT's own exception hierarchy already distinguishes each kind of failure - unlike Nimbus's,
    which the ledger has to categorise from an English message - so this reads types, checked
    from the most specific down. ``InvalidSignatureError`` is a ``DecodeError`` subclass and is
    matched first, or a bad signature would be reported as the more general ``malformed``.

    ``InvalidIssuerError`` has no branch here: ``__call__`` never passes ``issuer=`` to
    ``jwt.decode``, so PyJWT can never raise it from this module's own call -
    :meth:`TokenVerifier._check_issuer` reports that failure instead.
    """
    if isinstance(exception, jwt.MissingRequiredClaimError):
        return _MISSING_CLAIM_REASON.get(exception.claim, REASON_MALFORMED)
    if isinstance(exception, jwt.InvalidAudienceError):
        return REASON_AUDIENCE
    if isinstance(exception, InvalidSubjectError):
        return REASON_SUBJECT
    if isinstance(exception, jwt.InvalidAlgorithmError):
        return REASON_MALFORMED
    if isinstance(exception, jwt.InvalidSignatureError):
        return REASON_REJECTED
    if isinstance(exception, jwt.DecodeError):
        return REASON_MALFORMED
    return REASON_REJECTED


# --- authentication: wiring the verifier into every request (ADR-030) -------------------------

PRINCIPAL_SCOPE_KEY: Final = "finassist.principal"
"""Where :class:`BearerAuthentication` stores a verified :class:`Principal`.

Namespaced, as ASGI asks of anything an application adds to a scope - the same convention
:mod:`~finassist_assistant.adapters.hosts` follows for its own scope key. A route's permission
dependency reads it back through :class:`RequirePermission`; nothing else may write it.
"""

PUBLIC_PATHS: Final[frozenset[str]] = frozenset({"/health", "/ready", "/openapi.json"})
"""Every path served with no bearer token at all (ADR-030).

Deliberately short: the two probes, for whatever runs the process, and the committed contract's
own document, which is already public (both contracts are committed in the repository). Every
other path denies by default, so a route added without a permission dependency is refused here
rather than served unprotected.
"""

_BEARER_PREFIX: Final = "bearer "
_BARE_CHALLENGE: Final = "Bearer"
_INVALID_TOKEN_CHALLENGE: Final = 'Bearer error="invalid_token"'
_INSUFFICIENT_SCOPE_CHALLENGE: Final = 'Bearer error="insufficient_scope"'
_WWW_AUTHENTICATE_HEADER: Final = "WWW-Authenticate"

_UNAUTHENTICATED_DETAIL: Final = "This request needs a valid bearer token."
_AUTHENTICATION_UNAVAILABLE_DETAIL: Final = (
    "The authentication service could not be reached; this token's validity could not be decided."
)
_INSUFFICIENT_SCOPE_DETAIL: Final = "This token does not grant the permission this request needs."

_HTTP_SCOPE: Final = "http"


def _bearer_token(headers: Headers) -> str:
    """The bearer token a request actually presented, or ``""`` when it presented none at all.

    Deliberately not "whatever the Authorization header holds": a Basic-scheme header, or one
    absent altogether, is not a bearer-token attempt, and treating either as a malformed one
    would answer ``invalid_token`` where the caller never claimed to send a bearer token in the
    first place. Both collapse to ``""`` instead, which :class:`TokenVerifier` already refuses as
    :data:`REASON_MISSING` - the same reason category a genuinely absent credential belongs to,
    so :class:`BearerAuthentication` only has to branch on the reason once.
    """
    header = headers.get("authorization", "")
    if not header.lower().startswith(_BEARER_PREFIX):
        return ""
    return header[len(_BEARER_PREFIX) :].strip()


class BearerAuthentication:
    """Authenticates every request and denies by default (ADR-030, ADR-032).

    Modelled on :class:`~finassist_assistant.adapters.hosts.HostAllowList`: a pure ASGI
    middleware wrapping the application, deciding before the request reaches routing at all.
    Unlike the Host check, this one denies by default rather than admits by default - every path
    outside :data:`PUBLIC_PATHS` needs a valid bearer token, so a new route that forgets a
    permission dependency is still refused here, never served unprotected.

    Added closer to the application than
    :func:`~finassist_assistant.adapters.app.create_app`'s correlation middleware, so it runs
    *inside* that one rather than outside it as ``HostAllowList`` does: a refused request still
    gets a correlation id and the one request-answered line every admitted request gets, because
    the correlation middleware's own ``call_next`` is what invokes this middleware and sees the
    response it produces. ``HostAllowList`` stays outside both, so a foreign ``Host`` is refused
    before authentication is even attempted.

    Only the ``http`` scope is checked. The assistant serves no WebSocket route, and
    ``HostAllowList`` already refuses a foreign host on that scope type too.

    :param app: the application behind the check.
    :param verifier: decides whether a bearer token is genuine.

    The token is verified through :func:`starlette.concurrency.run_in_threadpool`: the verifier
    is a synchronous callable (ADR-021), and calling it directly from this coroutine would block
    the event loop for every other request while a JWKS fetch or a signature check ran.
    """

    def __init__(self, app: ASGIApp, verifier: TokenVerifier) -> None:
        self._app = app
        self._verifier = verifier

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != _HTTP_SCOPE or scope["path"] in PUBLIC_PATHS:
            await self._app(scope, receive, send)
            return

        token = _bearer_token(Headers(scope=scope))
        try:
            principal = await run_in_threadpool(self._verifier, token)
        except TokenRefused as refused:
            await self._refuse(refused.reason, scope, receive, send)
            return
        except KeySourceUnavailable as unavailable:
            await self._report_unavailable(unavailable.reason, scope, receive, send)
            return

        scope[PRINCIPAL_SCOPE_KEY] = principal
        await self._app(scope, receive, send)

    async def _refuse(self, reason: str, scope: Scope, receive: Receive, send: Send) -> None:
        """401: no valid identity. Logged at WARN, naming only the reason (never the token)."""
        _log.warning("refused a request with no valid bearer token", reason=reason)
        challenge = _BARE_CHALLENGE if reason == REASON_MISSING else _INVALID_TOKEN_CHALLENGE
        refusal = problem_response(
            HTTPStatus.UNAUTHORIZED,
            UNAUTHENTICATED,
            _UNAUTHENTICATED_DETAIL,
            headers={_WWW_AUTHENTICATE_HEADER: challenge},
        )
        await refusal(scope, receive, send)

    async def _report_unavailable(
        self, reason: str, scope: Scope, receive: Receive, send: Send
    ) -> None:
        """503: the JWKS source could not be reached, or answered nothing a key can be read from,
        so nothing here says the token was bad.

        Logged at ERROR, under an event of its own rather than :meth:`_refuse`'s, because this is
        the one failure here that is nobody's caller's fault: until the key source serves a key
        set again, *no* request can be authenticated, which is "actionable now". Still the reason
        category alone, never the token. The ledger's ``ProblemAuthenticationEntryPoint`` draws the
        same line in the same place, so a rule written against one service reads the other.

        The message says "could not reach" even for an unreadable body, on purpose. The ledger
        logs its equivalent line for the same bodies. And a JWKS URI that answers a page that is
        not a key set almost always means something answered in the key source's place, such as
        a proxy with Keycloak down behind it, so the operator's next step is the same.
        """
        _log.error(
            "could not reach the authentication key source; "
            "no request can be authenticated until it answers",
            reason=reason,
        )
        refusal = problem_response(
            HTTPStatus.SERVICE_UNAVAILABLE,
            AUTHENTICATION_UNAVAILABLE,
            _AUTHENTICATION_UNAVAILABLE_DETAIL,
        )
        await refusal(scope, receive, send)


# --- authorization: what a verified caller may then do (ADR-032) ------------------------------

ROLE_PERMISSIONS: Final[Mapping[str, frozenset[str]]] = {
    "owner": frozenset({"portfolio:read"}),
    "viewer": frozenset({"portfolio:read"}),
    # "assistant" is deliberately absent: a service principal has no portfolio permission at all
    # (ADR-004, ADR-032), and it falls out of this table exactly as an unknown role
    # does - there is no third branch to keep in step with the other two.
}
"""Every permission a role grants, owned by this service alone (ADR-032).

The ledger keeps its own copy in ``LedgerPermissions``; neither service reads the other's table.
The permission string ``"portfolio:read"`` is duplicated from the router's own
``READ_PORTFOLIO`` on purpose, not imported: a context may not import the composition root, and
this table has to live here, where :class:`RequirePermission` is built.
``tests/test_authorization.py`` pins both spellings equal.
"""


def _granted_permissions(principal: Principal) -> frozenset[str]:
    """Every permission *principal*'s roles grant, from :data:`ROLE_PERMISSIONS`.

    A role absent from the table - an unknown one, or every one when ``principal.roles`` is
    empty - contributes nothing, exactly as a missing, non-list or non-string-elements ``roles``
    claim already left ``principal.roles`` empty in :func:`_roles_from`.
    """
    return frozenset().union(*(ROLE_PERMISSIONS.get(role, frozenset()) for role in principal.roles))


class RequirePermission:
    """A FastAPI dependency admitting only a caller whose role grants *permission* (ADR-032).

    :param permission: the permission this route requires, such as ``portfolio:read``.

    Reads the :class:`Principal` that :class:`BearerAuthentication` already verified and stored
    in ``request.scope[PRINCIPAL_SCOPE_KEY]``. Deny-by-default means one is always there by the
    time a route's dependencies run: every path outside :data:`PUBLIC_PATHS` already passed
    authentication before routing began, and every route this factory guards is outside that set.

    A plain, synchronous callable (ADR-021): FastAPI runs a ``def`` dependency in its own thread
    pool, so this never has to be async merely to sit behind one - the same reason
    :func:`~finassist_assistant.portfolio.adapters.inbound.api.router._require_json` is not
    async either.
    """

    def __init__(self, permission: str) -> None:
        self._permission = permission

    def __call__(self, request: Request) -> None:
        """:raises HTTPException: 403, with ``WWW-Authenticate: Bearer error="insufficient_scope"``,
        when *permission* is not among what the caller's roles grant.
        """
        principal: Principal = request.scope[PRINCIPAL_SCOPE_KEY]
        if self._permission not in _granted_permissions(principal):
            raise HTTPException(
                status_code=HTTPStatus.FORBIDDEN,
                detail=_INSUFFICIENT_SCOPE_DETAIL,
                headers={_WWW_AUTHENTICATE_HEADER: _INSUFFICIENT_SCOPE_CHALLENGE},
            )
