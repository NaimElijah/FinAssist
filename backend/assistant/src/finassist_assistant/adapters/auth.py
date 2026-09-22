"""Verifies that a bearer token is genuine (ADR-030, ADR-032).

A token is genuine when it is signed by a key the configured issuer actually controls, names
that issuer and this service's own audience, is alive right now, and identifies somebody. Every
one of those is required, never merely checked if present - a token that omits a claim is
refused exactly as one that gets it wrong.

Lives in the composition root, alongside :mod:`~finassist_assistant.adapters.settings` and
:mod:`~finassist_assistant.adapters.observability`, because PyJWT and ``cryptography`` are
frameworks: ``tests/test_architecture.py`` forbids importing either from an inward layer, and
this is the one package allowed to import them outside a context's own ``adapters``. Task 15
wires this into a middleware and a permission table; nothing here decides what a caller may do,
only who it is.

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
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Final

import jwt
from jwt import PyJWK, PyJWKClient
from jwt.exceptions import InvalidSubjectError

from finassist_assistant.adapters.wiring import Clock

__all__ = [
    "ALGORITHM",
    "ASSISTANT_AUDIENCE",
    "DEFAULT_LEEWAY",
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
    "KeySource",
    "KeySourceUnavailable",
    "Principal",
    "TokenRefused",
    "TokenVerifier",
    "jwks_key_source",
]

ALGORITHM: Final = "RS256"
"""The only signature algorithm ever accepted.

Naming exactly one fixes the algorithm allow-list ``jwt.decode`` checks before it ever looks at a
key. That check alone is what defeats an attacker who signs a token with ``alg: HS256`` and
tries to use this service's own RSA *public* key as the HMAC secret (RFC 8725 S2.1): the header's
algorithm is compared against this allow-list first, so the forged signature is never even
computed against.
"""

ASSISTANT_AUDIENCE: Final = "finassist-assistant"
"""This service's own audience (ADR-032's Vocabulary).

Fixed here, not read from the environment: a caller cannot redirect the assistant into accepting
a token meant for another audience - such as the assistant's own service-account token, whose
audience names only the ledger - by configuration.
"""

REQUIRED_CLAIMS: Final[tuple[str, ...]] = ("sub", "iss", "aud", "exp", "iat")
"""ADR-030's Vocabulary: every one of these must be present, not merely valid when present."""

DEFAULT_LEEWAY: Final = timedelta(seconds=30)
"""Clock-skew allowance either side of ``exp``, ``nbf`` and ``iat`` (ADR-030's Vocabulary)."""

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

``PyJWKClient.get_signing_key_from_jwt``, via :func:`jwks_key_source`, satisfies this against a
real JWKS endpoint. Tests substitute a static stand-in that returns one fixed key regardless of
the token it is given.
"""


@dataclass(frozen=True, slots=True)
class Principal:
    """Who a verified bearer token identifies.

    :param subject: the token's ``sub`` claim, already checked non-blank.

    Nothing about a caller's authority is decided here: role and permission enforcement is a
    later use case's job (ADR-032), so this dataclass carries only the fact a valid token proved.
    """

    subject: str


class TokenRefused(Exception):  # noqa: N818 - ADR-030's Vocabulary fixes this exact name
    """A bearer token was read but is not valid.

    :param reason: one of this module's fixed reason categories - never the token itself and
        never a claim value, so a caller may log it without ever risking either.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class KeySourceUnavailable(Exception):  # noqa: N818 - ADR-030's Vocabulary fixes this name
    """The JWKS source could not be reached; this token's validity could not be decided.

    Deliberately a different exception type from :class:`TokenRefused`, mirroring the ledger's
    own split between a rejected token and an unreachable key source: nothing here says whether
    the token would have been valid, so a caller must answer 503, never 401.
    """

    reason: Final[str] = REASON_KEY_SOURCE


def jwks_key_source(uri: str) -> KeySource:
    """Builds a :data:`KeySource` backed by a real JWKS endpoint.

    :param uri: the *internal* JWKS URI, fetched service-to-service. Always distinct from a
        token's own ``iss``, which is always the public issuer a browser signed in against
        (ADR-030) - the two are never the same value, so they are never the same setting.

    The returned key source caches the fetched key set for five minutes and, once fetched, the
    individual signing key each ``kid`` resolves to, so an ordinary verification fetches nothing
    over the network. Five seconds bounds the wait on a fetch, so an unreachable JWKS endpoint
    fails quickly rather than hanging the request that discovered it.
    """
    client = PyJWKClient(
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
        :raises KeySourceUnavailable: the JWKS source could not be reached, so this token's
            validity could not be decided at all.
        """
        if not token.strip():
            raise TokenRefused(REASON_MISSING)

        try:
            signing_key = self._key_source(token)
        except jwt.PyJWKClientConnectionError as unreachable:
            raise KeySourceUnavailable from unreachable
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
        return Principal(subject=subject)

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
        ``require`` already refused a token missing either; ``nbf`` is optional, exactly as
        ADR-030's Vocabulary leaves it, so it is checked only when present.
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
