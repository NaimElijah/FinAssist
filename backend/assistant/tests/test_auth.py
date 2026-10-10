"""Is a bearer token genuine? ``TokenVerifier`` decides, and this is its specification.

Keys come from the ``rsa_key_pair`` fixture (``tests/conftest.py``), generated once with
``cryptography``; the clock is ``fixed_clock``, fixed at a known instant. Every boundary test
calls ``fixed_clock()`` for that instant and moves a token's own claim relative to it, never the
clock itself, so "just inside the leeway" and "just outside" are proven rather than assumed - the
way the ledger's own ``LedgerTokenValidationTest`` proves the same boundaries in Java.

Most scenarios use ``static_key_source``, a stand-in that returns one fixed key regardless of the
token. The scenarios near the bottom of this file use the real ``PyJWKClient`` (via
:func:`~finassist_assistant.adapters.auth.jwks_key_source`) against a real ``http.server`` running
in a background thread, because a stand-in cannot prove the wrapper around a real dependency is
wired correctly.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import socket
import struct
import threading
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, NamedTuple

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
from jwt import PyJWK
from jwt.algorithms import RSAAlgorithm
from pydantic import ValidationError

from finassist_assistant.adapters.app import create_app
from finassist_assistant.adapters.auth import (
    ASSISTANT_AUDIENCE,
    REASON_AUDIENCE,
    REASON_ISSUER,
    REASON_KEY_SOURCE,
    REASON_LIFETIME,
    REASON_MALFORMED,
    REASON_MISSING,
    REASON_REJECTED,
    REASON_SUBJECT,
    REASON_UNKNOWN_KEY,
    KeySource,
    KeySourceUnavailable,
    Principal,
    TokenRefused,
    TokenVerifier,
    jwks_key_source,
)
from finassist_assistant.adapters.observability import REDACTED, redact_secrets
from finassist_assistant.adapters.settings import Settings
from finassist_assistant.adapters.wiring import Clock

ISSUER = "http://localhost:8081/realms/finassist"
SUBJECT = "owner-user"
KEY_ID = "test-key"

_POSITION_URL = "/api/v1/portfolio/holdings/VOO/position"
"""Any route behind the permission check; the assembly holds nothing, so it answers 404."""


def _live_settings(jwks_uri: str) -> Settings:
    """Settings a production-shaped ``create_app`` reads, pointed at a real local JWKS endpoint."""
    return Settings(
        OTEL_EXPORTER_OTLP_ENDPOINT=None,
        OTEL_EXPORT_ENABLED=False,
        ASSISTANT_LOG_LEVEL="INFO",
        # TestClient sends `Host: testserver`.
        ASSISTANT_ALLOWED_HOSTS=["testserver"],
        ASSISTANT_OIDC_ISSUER=ISSUER,
        ASSISTANT_OIDC_JWKS_URI=jwks_uri,
    )


_BEYOND_LEEWAY = timedelta(seconds=31)
"""One second past the 30 s leeway :data:`~finassist_assistant.adapters.auth.DEFAULT_LEEWAY`
configures."""

_WITHIN_LEEWAY = timedelta(seconds=29)
"""One second inside it."""


def _encode(
    private_key: rsa.RSAPrivateKey,
    claims: Mapping[str, Any],
    *,
    algorithm: str = "RS256",
    key_id: str | None = KEY_ID,
) -> str:
    """Signs *claims* as a compact JWT."""
    headers = {"kid": key_id} if key_id is not None else {}
    return jwt.encode(dict(claims), private_key, algorithm=algorithm, headers=headers)


def _valid_claims(now: datetime, **overrides: Any) -> dict[str, Any]:
    """A token valid on every check as of *now*, so a test overrides only the claim it means to
    exercise."""
    claims: dict[str, Any] = {
        "sub": SUBJECT,
        "iss": ISSUER,
        "aud": ASSISTANT_AUDIENCE,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(hours=1)).timestamp()),
    }
    claims.update(overrides)
    return claims


def _without(claims: dict[str, Any], *names: str) -> dict[str, Any]:
    """*claims*, minus each name in *names* - how a required claim goes missing in a test."""
    return {key: value for key, value in claims.items() if key not in names}


def _verifier(key_source: KeySource, clock: Clock) -> TokenVerifier:
    return TokenVerifier(key_source, ISSUER, ASSISTANT_AUDIENCE, clock)


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


# --- the happy path --------------------------------------------------------------------------


def Given_a_valid_token_When_verified_Then_the_principal_carries_its_subject(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal == Principal(subject=SUBJECT)


# --- roles: read defensively from the already-verified claims (ADR-032's role table) ----------


def Given_a_token_naming_one_role_When_verified_Then_the_principal_carries_it(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), roles=["owner"]))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset({"owner"})


def Given_a_token_naming_several_roles_When_verified_Then_the_principal_carries_all_of_them(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), roles=["owner", "viewer"]))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset({"owner", "viewer"})


def Given_a_token_with_no_roles_claim_When_verified_Then_the_principal_has_no_roles(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset()


def Given_a_roles_claim_that_is_not_a_list_When_verified_Then_the_principal_has_no_roles(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """A caller controls this claim, and one that exists but is shaped wrong - a string here,
    rather than a list of them - must grant no role, never raise."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), roles="owner"))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset()


def Given_a_roles_claim_with_non_string_elements_When_verified_Then_they_are_dropped(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), roles=["owner", 42, None, True]))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset({"owner"})


def Given_a_roles_claim_that_is_an_empty_list_When_verified_Then_the_principal_has_no_roles(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), roles=[]))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.roles == frozenset()


# --- exp --------------------------------------------------------------------------------------


def Given_a_token_expired_beyond_the_leeway_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, exp=int((now - _BEYOND_LEEWAY).timestamp())))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_LIFETIME


def Given_a_token_expired_within_the_leeway_When_verified_Then_it_is_accepted(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, exp=int((now - _WITHIN_LEEWAY).timestamp())))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.subject == SUBJECT


def Given_a_token_whose_exp_claim_is_not_numeric_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), exp="soon"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


def _instant_from_platform(exp: float) -> bool:
    """True when this platform's own ``datetime.fromtimestamp`` turns *exp* into an instant.

    Whether an extreme or pre-epoch timestamp converts is a C-runtime quirk that genuinely
    differs between platforms (see ``_as_instant``'s docstring in ``auth.py``): Windows refuses
    a large pre-epoch value with ``OSError`` where another platform's runtime accepts it. This
    probes the real conversion so the test's expectation is read from what this machine actually
    does, rather than guessed from ``sys.platform`` - a name check would guess at the cause, not
    observe it.
    """
    try:
        datetime.fromtimestamp(exp, tz=UTC)
    except (TypeError, ValueError, OverflowError, OSError):
        return False
    return True


@pytest.mark.parametrize(
    "exp",
    [1e300, float("inf"), -1e10, float("nan")],
    ids=["huge", "infinity", "pre_epoch", "nan"],
)
def Given_a_token_whose_exp_is_an_extreme_or_non_finite_float_When_verified_Then_it_is_refused(
    exp: float,
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """A validly-signed token whose ``exp`` is a value no real clock could ever be set to must be
    refused, never left to crash the caller with an exception the verifier's contract does not
    advertise. That refusal is unconditional and platform-independent; only its *reason* is not,
    for one of the four values below, and is derived from ``_instant_from_platform`` rather than
    hard-coded - see that helper and ``_as_instant``'s own docstring for why.

    ``jwt.encode`` serialises the payload with plain ``json.dumps`` (no ``allow_nan=False``), so
    ``float("inf")`` and ``float("nan")`` reach the wire as the genuine JSON literals ``Infinity``
    and ``NaN`` - exactly what a real, misbehaving issuer could sign - not merely as Python
    values a test happens to construct. ``1e300`` overflows every platform's representable range
    outright, so it is always refused ``malformed``; ``-1e10`` (a pre-epoch timestamp) is the one
    value here whose fate genuinely differs by platform - refused ``malformed`` where the
    runtime's own conversion raises, and refused ``lifetime`` (simply very expired) where it
    instead succeeds and reaches the ordinary expiry check.
    """
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), exp=exp))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)

    # Only the refusal *reason* depends on the platform's own conversion (see docstring); the
    # refusal itself never does, which is what pytest.raises above already pins unconditionally.
    expected_reason = REASON_LIFETIME if _instant_from_platform(exp) else REASON_MALFORMED
    assert refused.value.reason == expected_reason


def Given_a_token_with_no_exp_claim_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """Turning PyJWT's own ``verify_exp`` off must not turn ``require`` off with it.

    Read from the installed ``jwt.api_jwt`` source: ``_validate_required_claims`` runs
    unconditionally, before any ``verify_*`` flag is inspected, so ``exp`` still has to be
    present. Without ``require`` naming it, this test would pass with a principal returned
    instead of ``TokenRefused`` - PyJWT's own timestamp check never runs, and nothing else in
    this module would have refused a token with no ``exp`` at all.
    """
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _without(_valid_claims(fixed_clock()), "exp"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_LIFETIME


# --- nbf ----------------------------------------------------------------------------------------


def Given_a_token_not_yet_valid_beyond_the_leeway_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, nbf=int((now + _BEYOND_LEEWAY).timestamp())))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_LIFETIME


def Given_a_token_not_yet_valid_within_the_leeway_When_verified_Then_it_is_accepted(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, nbf=int((now + _WITHIN_LEEWAY).timestamp())))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.subject == SUBJECT


# --- iat: required, and refused from the future - the check a stock validator skips ------------


def Given_a_token_issued_beyond_the_leeway_in_the_future_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, iat=int((now + _BEYOND_LEEWAY).timestamp())))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_LIFETIME


def Given_a_token_issued_within_the_leeway_in_the_future_When_verified_Then_it_is_accepted(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    now = fixed_clock()
    token = _encode(private_key, _valid_claims(now, iat=int((now + _WITHIN_LEEWAY).timestamp())))

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.subject == SUBJECT


def Given_a_token_with_no_issued_at_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """``iat`` is required, not merely checked when present - the other half of the iat check."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _without(_valid_claims(fixed_clock()), "iat"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_LIFETIME


# --- audience -----------------------------------------------------------------------------------


def Given_a_token_naming_only_the_ledger_audience_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """The assistant's own service-account token names only the ledger (ADR-032); the assistant
    must refuse it exactly as it refuses any other wrong audience."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), aud="finassist-ledger"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_AUDIENCE


def Given_a_token_naming_both_audiences_When_verified_Then_it_is_accepted(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """A browser user's token names both services (ADR-030's accepted cost); naming this
    audience among others is enough."""
    private_key, _ = rsa_key_pair
    token = _encode(
        private_key,
        _valid_claims(fixed_clock(), aud=["finassist-ledger", ASSISTANT_AUDIENCE]),
    )

    principal = _verifier(static_key_source, fixed_clock)(token)

    assert principal.subject == SUBJECT


def Given_a_token_with_no_aud_claim_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _without(_valid_claims(fixed_clock()), "aud"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_AUDIENCE


# --- issuer -------------------------------------------------------------------------------------


def Given_a_token_from_a_foreign_issuer_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(
        private_key,
        _valid_claims(fixed_clock(), iss="http://attacker.example/realms/finassist"),
    )

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_ISSUER


def Given_an_issuer_prefix_extension_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """Pins the issuer check to an exact match, never a prefix one.

    ``.../realms/finassist-evil`` extends the real issuer byte-for-byte and must still be
    refused. Loosening ``TokenVerifier._check_issuer`` to a prefix comparison is exactly the
    kind of change this test exists to catch.
    """
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), iss=f"{ISSUER}-evil"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_ISSUER


def Given_a_token_with_no_issuer_claim_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _without(_valid_claims(fixed_clock()), "iss"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_ISSUER


# --- subject --------------------------------------------------------------------------------


def Given_a_token_with_no_subject_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _without(_valid_claims(fixed_clock()), "sub"))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_SUBJECT


def Given_a_token_with_a_blank_subject_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """``sub`` must be non-blank, not merely present - PyJWT's own check only asks for a string."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), sub="   "))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_SUBJECT


# --- algorithm: alg=none and the algorithm-confusion attack -----------------------------------


def _unsigned(claims: Mapping[str, Any]) -> str:
    """*claims* as an ``alg: none`` token, the way an attacker sends one: no ``kid`` in its
    header, and an empty signature."""
    header = _b64url(json.dumps({"alg": "none", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64url(json.dumps(dict(claims), separators=(",", ":")).encode())
    return f"{header}.{payload}."


def Given_a_token_signed_with_the_none_algorithm_When_verified_Then_it_is_refused(
    static_key_source: KeySource, fixed_clock: Clock
) -> None:
    """Refused before any key is looked up, so the reason does not depend on the key source; the
    real client's case follows."""
    token = _unsigned(_valid_claims(fixed_clock()))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


def Given_a_token_signed_with_the_none_algorithm_When_the_real_client_reads_it_Then_it_is_malformed(
    jwks_server: str, fixed_clock: Clock
) -> None:
    """The real ``PyJWKClient`` looks a key up by the header's ``kid`` before anything else, and an
    ``alg: none`` token names none. Before #124 it was therefore refused ``unknown_key`` here, but
    ``malformed`` against the stand-in above, so the logged reason depended on which key source
    was asked. The ledger logs it ``malformed`` too: Nimbus refuses an "Unsupported algorithm of
    none" before it looks for a key."""
    token = _unsigned(_valid_claims(fixed_clock()))

    with pytest.raises(TokenRefused) as refused:
        _verifier(jwks_key_source(jwks_server), fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


def _hmac_confused(public_key: rsa.RSAPublicKey, claims: Mapping[str, Any]) -> str:
    """*claims* signed HS256 with *public_key*'s PEM bytes as the secret, naming the real key's
    ``kid`` - the algorithm-confusion attack, built by hand for the reason the test below gives."""
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    header = _b64url(
        json.dumps({"alg": "HS256", "typ": "JWT", "kid": KEY_ID}, separators=(",", ":")).encode()
    )
    payload = _b64url(json.dumps(dict(claims), separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode()
    signature = hmac.new(public_pem, signing_input, hashlib.sha256).digest()
    return f"{header}.{payload}.{_b64url(signature)}"


def Given_a_token_signed_HS256_with_the_public_key_bytes_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
) -> None:
    """The classic algorithm-confusion attack (RFC 8725 S2.1): an attacker who knows this
    service's own RSA *public* key tries to sign a token with it as an HS256 secret, hoping a
    careless verifier reuses the same bytes to check the signature.

    Built by hand rather than through ``jwt.encode``: PyJWT's own ``HMACAlgorithm.prepare_key``
    refuses a PEM-formatted key as an HMAC secret precisely to stop this attack from being built
    that way (confirmed against the installed ``jwt.algorithms`` source -
    ``jwt.encode({...}, public_pem, algorithm="HS256")`` raises ``InvalidKeyError`` rather than
    producing a token). The algorithm allow-list is the defence that matters: the header names an
    algorithm the verifier never allowed, so this is refused ``malformed`` before any key is even
    looked up, and ``jwt.decode``'s own ``algorithms=["RS256"]`` would refuse it again - not
    because *this* particular attack happens to be hard to build.
    """
    _, public_key = rsa_key_pair
    token = _hmac_confused(public_key, _valid_claims(fixed_clock()))

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


# --- signature: a genuine claim set, signed by the wrong key or edited after signing ----------


def Given_a_token_signed_by_a_key_the_verifier_does_not_hold_When_verified_Then_it_is_refused(
    static_key_source: KeySource,
    fixed_clock: Clock,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Mirrors the ledger's ``TestTokens.foreignKey()``: a well-formed, correctly-signed RS256
    token, but signed by a key ``static_key_source`` never returns. ``static_key_source``
    (``conftest.py``) always resolves to the same fixed key regardless of the token it is asked
    about, so a token signed by any other private key fails signature verification against it -
    exactly as a real JWKS lookup would find no matching key among the ones it actually
    publishes.
    """
    foreign_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    token = _encode(foreign_key, _valid_claims(fixed_clock()))
    capsys.readouterr()

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)

    assert refused.value.reason == REASON_REJECTED
    assert token not in capsys.readouterr().out


def Given_a_tokens_payload_is_swapped_but_its_signature_kept_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    static_key_source: KeySource,
    fixed_clock: Clock,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Mirrors the ledger's ``TestTokens.tamperedSignature()`` - the textbook JWT integrity
    attack: take a genuine token, then swap in another token's payload while keeping the
    original header and signature untouched. Both are signed by the same real key with an
    identical header (both go through ``_encode`` with the same ``key_id`` and algorithm), so the
    payload segment is the only thing that actually differs between the two combined here -
    which is exactly why the kept signature no longer verifies against it.
    """
    private_key, _ = rsa_key_pair
    genuine_header, _genuine_payload, genuine_signature = _encode(
        private_key, _valid_claims(fixed_clock())
    ).split(".")
    _tampered_header, tampered_payload, _tampered_signature = _encode(
        private_key, _valid_claims(fixed_clock(), sub="attacker-user")
    ).split(".")
    token = f"{genuine_header}.{tampered_payload}.{genuine_signature}"
    capsys.readouterr()

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)

    assert refused.value.reason == REASON_REJECTED
    assert token not in capsys.readouterr().out


# --- the key source itself --------------------------------------------------------------------


def Given_an_unknown_key_id_When_verified_Then_it_is_refused(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], fixed_clock: Clock
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    def unknown_key(_token: str) -> PyJWK:
        raise jwt.PyJWKClientError('Unable to find a signing key that matches: "unknown"')

    with pytest.raises(TokenRefused) as refused:
        _verifier(unknown_key, fixed_clock)(token)
    assert refused.value.reason == REASON_UNKNOWN_KEY


def Given_the_key_source_is_unavailable_When_verified_Then_the_outage_passes_through_unchanged(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], fixed_clock: Clock
) -> None:
    """A key source reports an outage itself, as :class:`KeySourceUnavailable` (ADR-037), and the
    verifier must pass that on, never read it as a refused token. How the real key source turns
    each of its own failures into one is proved through ``jwks_key_source`` below: a dead port, a
    hang-up, and every body no token can be verified against."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))
    outage = KeySourceUnavailable()

    def unavailable(_token: str) -> PyJWK:
        raise outage

    with pytest.raises(KeySourceUnavailable) as raised:
        _verifier(unavailable, fixed_clock)(token)
    assert raised.value is outage
    assert raised.value.reason == REASON_KEY_SOURCE


# --- garbage and missing input -----------------------------------------------------------------


def Given_garbage_input_When_verified_Then_it_is_refused(
    static_key_source: KeySource, fixed_clock: Clock
) -> None:
    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)("not-a-jwt")
    assert refused.value.reason == REASON_MALFORMED


@pytest.mark.parametrize("blank", ["", "   "], ids=["empty", "whitespace"])
def Given_a_blank_token_When_verified_Then_it_is_refused(
    blank: str, static_key_source: KeySource, fixed_clock: Clock
) -> None:
    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(blank)
    assert refused.value.reason == REASON_MISSING


# --- the real PyJWKClient, against a real JWKS endpoint ----------------------------------------


@pytest.fixture
def jwks_server(rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey]) -> Iterator[str]:
    """A real JWKS endpoint, serving *rsa_key_pair*'s public key, on an ephemeral loopback port.

    Proves :func:`~finassist_assistant.adapters.auth.jwks_key_source` wraps the real
    ``PyJWKClient`` correctly - the constructor arguments it passes, and the shape of the key
    that comes back - rather than only the hand-substituted stand-in every other test in this
    file uses.
    """
    _, public_key = rsa_key_pair
    jwk = RSAAlgorithm.to_jwk(public_key, as_dict=True)
    jwk["kid"] = KEY_ID
    body = json.dumps({"keys": [jwk]}).encode()

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: Any) -> None:
            pass  # silences http.server's default stderr access log during the test run

    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/certs"
    finally:
        server.shutdown()
        thread.join()


def Given_the_real_PyJWKClient_serving_a_matching_key_When_verified_Then_the_principal_is_returned(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], jwks_server: str, fixed_clock: Clock
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    principal = _verifier(jwks_key_source(jwks_server), fixed_clock)(token)

    assert principal == Principal(subject=SUBJECT)


def Given_a_real_PyJWKClient_with_a_dead_port_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], fixed_clock: Clock
) -> None:
    """A closed loopback port, not a mock: proves the connection failure genuinely propagates
    out of ``jwks_key_source``'s wrapper as :class:`KeySourceUnavailable`."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        dead_port = probe.getsockname()[1]

    key_source = jwks_key_source(f"http://127.0.0.1:{dead_port}/certs")

    with pytest.raises(KeySourceUnavailable):
        _verifier(key_source, fixed_clock)(token)


@pytest.fixture
def jwks_server_that_hangs_up() -> Iterator[str]:
    """A JWKS endpoint that accepts the connection, reads the request, and closes it unanswered.

    Unlike a closed port, the connection succeeds, so the failure arrives as
    ``http.client.RemoteDisconnected`` from reading the response - an ``HTTPException`` that
    PyJWT 2.14.0 let escape ``PyJWKClient.fetch_data`` and 2.15.0 wraps as
    ``PyJWKClientConnectionError``.
    """

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)

        def log_message(self, _format: str, *_args: Any) -> None:
            pass  # silences http.server's default stderr access log during the test run

    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/certs"
    finally:
        server.shutdown()
        thread.join()


def Given_a_JWKS_endpoint_that_hangs_up_unanswered_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_that_hangs_up: str,
    fixed_clock: Clock,
) -> None:
    """An outage, answered 503 with the outage's ERROR line, never a 500: on PyJWT 2.14.0 the
    disconnect escaped the key source unwrapped, and ``BearerAuthentication`` catches only
    :class:`TokenRefused` and :class:`KeySourceUnavailable`."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    with pytest.raises(KeySourceUnavailable):
        _verifier(jwks_key_source(jwks_server_that_hangs_up), fixed_clock)(token)


@pytest.fixture
def jwks_server_that_resets_mid_body() -> Iterator[str]:
    """A JWKS endpoint that sends ``200`` and part of its body, then resets the connection.

    ``SO_LINGER`` with a zero timeout makes ``close`` send a reset rather than a clean end of
    stream, so reading the body raises ``ConnectionResetError`` - an ``OSError`` that
    ``PyJWKClient.fetch_data`` does not wrap, unlike the ``IncompleteRead`` a clean close would
    give. Written over a raw socket, because ``http.server`` cannot stop halfway through a body.
    """
    listener = socket.create_server(("127.0.0.1", 0))

    def answer_then_reset() -> None:
        connection, _ = listener.accept()
        with connection:
            connection.recv(65_536)
            connection.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{"keys": [')
            connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))

    thread = threading.Thread(target=answer_then_reset, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{listener.getsockname()[1]}/certs"
    finally:
        thread.join()
        listener.close()


def Given_a_JWKS_endpoint_that_resets_mid_body_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_that_resets_mid_body: str,
    fixed_clock: Clock,
) -> None:
    """An outage like any other, never a 500: the reset escapes PyJWT unwrapped, so the key source
    has to map it itself. Found in the review of #132."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    with pytest.raises(KeySourceUnavailable) as unavailable:
        _verifier(jwks_key_source(jwks_server_that_resets_mid_body), fixed_clock)(token)
    assert isinstance(unavailable.value.__cause__, ConnectionResetError)


class _UnreadableJwks(NamedTuple):
    """A running JWKS endpoint, and the error its body raises inside ``PyJWKClient.fetch_data``."""

    uri: str
    provokes: type[Exception]


@contextmanager
def _serving(body: bytes, content_type: str) -> Iterator[str]:
    """Serves *body* with ``200`` on an ephemeral loopback port, and yields the JWKS URI."""

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: Any) -> None:
            pass  # silences http.server's default stderr access log during the test run

    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/certs"
    finally:
        server.shutdown()
        thread.join()


@pytest.fixture(
    params=[
        pytest.param(
            (b"<html><body>Down for maintenance</body></html>", json.JSONDecodeError),
            id="not-json",
        ),
        pytest.param((b"\x80\x81", UnicodeDecodeError), id="not-utf-8"),
        pytest.param((b"[" * 100_000, RecursionError), id="nested-too-deep"),
    ],
)
def jwks_server_answering_garbage(request: pytest.FixtureRequest) -> Iterator[_UnreadableJwks]:
    """A JWKS endpoint that answers ``200`` with a body that is not JSON (#121).

    Each body gets through ``PyJWKClient.fetch_data``'s ``try`` unwrapped, because it wraps
    transport failures only:
    - a proxy's maintenance page is not JSON at all, and raises ``json.JSONDecodeError``;
    - bytes that are not even UTF-8 raise ``UnicodeDecodeError`` before parsing starts;
    - brackets nested past the parser's recursion limit raise ``RecursionError``, which is not a
      ``ValueError`` at all.
    All three hold on PyJWT 2.15.0.
    """
    body: bytes
    provokes: type[Exception]
    body, provokes = request.param
    with _serving(body, "text/html") as uri:
        yield _UnreadableJwks(uri, provokes)


def Given_a_JWKS_endpoint_answering_a_body_that_is_not_JSON_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_answering_garbage: _UnreadableJwks,
    fixed_clock: Clock,
) -> None:
    """The key source answered, but with nothing a key can be read from. Nothing here says
    whether the token was good, so this is the same outage as an unreachable source, never a
    refusal. Before #121 the decoding error escaped unwrapped and the request answered 500.

    The cause is asserted, not just the type: a fixture that stopped answering ``200`` would
    raise ``KeySourceUnavailable`` from a connection failure instead, and would pass without the
    handling this test exists for."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))
    key_source = jwks_key_source(jwks_server_answering_garbage.uri)

    with pytest.raises(KeySourceUnavailable) as unavailable:
        _verifier(key_source, fixed_clock)(token)
    assert isinstance(unavailable.value.__cause__, jwks_server_answering_garbage.provokes)


_LONG_INTEGER = b"1" * 5_000
"""Valid JSON, and past the 4,300 digits CPython converts by default, so ``json.load`` refuses it
with a bare ``ValueError`` - not a ``json.JSONDecodeError`` - wherever in the body it sits
(#126)."""


@pytest.fixture(
    params=[
        pytest.param((b"[]", jwt.PyJWKClientError), id="a-json-array"),
        pytest.param((b"{}", jwt.PyJWKSetError), id="no-keys-member"),
        pytest.param((b'{"keys":[]}', jwt.PyJWKSetError), id="no-keys"),
        pytest.param(
            (b'{"keys":[{"kty":"unsupported","kid":"test-key"}]}', jwt.PyJWKSetError),
            id="only-an-unsupported-key",
        ),
        pytest.param((_LONG_INTEGER, ValueError), id="a-long-integer"),
        pytest.param(
            (
                b'{"keys":[{"kty":"RSA","kid":"test-key","e":"AQAB","n":' + _LONG_INTEGER + b"}]}",
                ValueError,
            ),
            id="a-long-integer-modulus",
        ),
        pytest.param(
            (b'{"keys":[{"kty":"RSA","kid":"test-key","alg":["RS256"]}]}', TypeError),
            id="a-key-member-of-the-wrong-type",
        ),
    ],
)
def jwks_server_answering_no_key_set(request: pytest.FixtureRequest) -> Iterator[_UnreadableJwks]:
    """A JWKS endpoint that answers ``200`` with JSON no token can be verified against (#126).

    Each body fails inside ``PyJWKClient.fetch_data``, which never sees a token:
    - ``[]`` is not a JSON object, and raises ``PyJWKClientError`` - the very type a token naming a
      key the set lacks raises too, which is why the type alone could never tell them apart;
    - ``{}``, ``{"keys": []}`` and a set whose only key is of an unsupported type each leave no key
      at all, and raise ``PyJWKSetError`` from the key-set cache's own parse;
    - an integer past CPython's conversion limit raises a bare ``ValueError`` from ``json.load``,
      whether it is the whole body or one key's member;
    - a key member of the wrong JSON type, such as an ``alg`` that is an array, raises a bare
      ``TypeError`` from ``PyJWK``. The key set skips only PyJWT's own errors, so one such key
      breaks a set that also holds a good one.
    ``LedgerJwksBodyTest`` serves the same bodies to the ledger, which answers each one 503 too.
    """
    body: bytes
    provokes: type[Exception]
    body, provokes = request.param
    with _serving(body, "application/json") as uri:
        yield _UnreadableJwks(uri, provokes)


def Given_a_JWKS_answering_JSON_that_is_no_usable_key_set_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_answering_no_key_set: _UnreadableJwks,
    fixed_clock: Clock,
) -> None:
    """No token at all could be verified against this set, so it is the same outage as an
    unreachable source (ADR-037). Before #126 the first four bodies were refused 401, as if the
    token were at fault, and the long integer escaped as a 500.

    The cause's exact type is asserted: ``PyJWKClientConnectionError`` is a ``PyJWKClientError``
    too, so a fixture that stopped answering would otherwise pass the ``[]`` case."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))
    key_source = jwks_key_source(jwks_server_answering_no_key_set.uri)

    with pytest.raises(KeySourceUnavailable) as unavailable:
        _verifier(key_source, fixed_clock)(token)
    assert type(unavailable.value.__cause__) is jwks_server_answering_no_key_set.provokes


@pytest.mark.parametrize(
    "header",
    [b"<html><body>Down for maintenance</body></html>", b"\x80\x81", b"[" * 100_000],
    ids=["not-json", "not-utf-8", "nested-too-deep"],
)
def Given_a_token_whose_header_is_not_JSON_When_the_real_client_reads_it_Then_it_is_malformed(
    header: bytes, jwks_server: str, fixed_clock: Clock
) -> None:
    """A token whose header provokes the very errors an unreadable JWKS body does is still a 401
    refusal, never a false outage page raised by a caller holding no key at all.

    The key source reports ``json.JSONDecodeError``, ``UnicodeDecodeError`` and ``RecursionError``
    as an outage only from inside ``PyJWKClient.fetch_data``, which never sees a token. PyJWT
    parses the token's own header elsewhere and turns each of these failures into ``DecodeError``,
    reported ``malformed``."""
    payload = json.dumps(_valid_claims(fixed_clock())).encode()
    token = ".".join([_b64url(header), _b64url(payload), _b64url(b"not-a-signature")])

    with pytest.raises(TokenRefused) as refused:
        _verifier(jwks_key_source(jwks_server), fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


def Given_a_payload_nested_too_deeply_When_the_real_client_reads_it_Then_it_is_malformed(
    jwks_server: str, fixed_clock: Clock
) -> None:
    """``PyJWKClient.get_signing_key_from_jwt`` decodes the whole token without checking its
    signature, payload included, to find its ``kid`` - so this is reachable by a caller holding no
    key at all. PyJWT 2.14.0 let the parser's ``RecursionError`` escape, which answered 500;
    2.15.0 raises ``DecodeError``, refused like any other malformed token. A nested *header* was
    already a ``DecodeError`` on both versions, so the payload is the segment that proves it."""
    depth = 100_000
    header = json.dumps({"alg": "RS256", "kid": KEY_ID}).encode()
    payload = ('{"x":' + "[" * depth + "]" * depth + "}").encode()
    token = ".".join([_b64url(header), _b64url(payload), _b64url(b"not-a-signature")])

    with pytest.raises(TokenRefused) as refused:
        _verifier(jwks_key_source(jwks_server), fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


_TokenFor = Callable[[rsa.RSAPrivateKey, rsa.RSAPublicKey, datetime], str]
"""Builds one malformed token from the test key pair and the fixed clock's instant."""


def _payload_not_json(
    private_key: rsa.RSAPrivateKey, _public: rsa.RSAPublicKey, _now: datetime
) -> str:
    header = _b64url(json.dumps({"alg": "RS256", "typ": "JWT", "kid": KEY_ID}).encode())
    return ".".join([header, _b64url(b"not json"), _b64url(b"not-a-signature")])


@pytest.mark.parametrize(
    "token_for",
    [
        pytest.param(lambda _private, _public, _now: "not-a-jwt", id="garbage"),
        pytest.param(
            lambda _private, public, now: _hmac_confused(public, _valid_claims(now)),
            id="hs256-with-the-public-key",
        ),
        pytest.param(_payload_not_json, id="payload-not-json"),
        pytest.param(
            lambda private, _public, now: _encode(private, _valid_claims(now, exp="soon")),
            id="exp-not-numeric",
        ),
        pytest.param(
            lambda private, _public, now: _encode(private, _valid_claims(now, exp=1e300)),
            id="exp-huge",
        ),
        pytest.param(
            lambda private, _public, now: _encode(private, _valid_claims(now, exp=float("inf"))),
            id="exp-infinite",
        ),
        pytest.param(
            lambda private, _public, now: _encode(private, _valid_claims(now, exp=float("nan"))),
            id="exp-nan",
        ),
    ],
)
def Given_a_malformed_token_When_the_real_client_reads_it_Then_it_is_refused_as_the_stand_in_does(
    token_for: _TokenFor,
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server: str,
    fixed_clock: Clock,
) -> None:
    """Each malformed shape the stand-in tests above refuse, through the key source production
    always uses, with the reason production actually logs (#124). The garbage token and HS256 are
    refused by the header check before any key is looked up; a payload that is not JSON fails
    inside ``get_signing_key_from_jwt``, which decodes the whole token to find its ``kid``; the
    three ``exp`` values get past the key source and are refused by the lifetime check. The
    platform-dependent pre-epoch ``exp`` is left to the stand-in test, which derives its reason."""
    private_key, public_key = rsa_key_pair
    token = token_for(private_key, public_key, fixed_clock())

    with pytest.raises(TokenRefused) as refused:
        _verifier(jwks_key_source(jwks_server), fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


@pytest.mark.parametrize(
    ("claim", "reason"),
    [("sub", REASON_SUBJECT), ("jti", REASON_REJECTED)],
    ids=["subject", "token-id"],
)
def Given_a_claim_PyJWT_requires_to_be_a_string_When_it_is_a_number_Then_it_is_refused(
    claim: str,
    reason: str,
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server: str,
    fixed_clock: Clock,
) -> None:
    """PyJWT refuses a ``sub`` or a ``jti`` that is not a string itself, with
    ``InvalidSubjectError`` and ``InvalidJTIError``. These pin which reason each maps to: the
    subject's own category, and the catch-all ``rejected`` for every PyJWT failure the reason
    vocabulary does not name (#124). Were PyJWT to stop checking ``sub``, ``subject.strip()`` would
    raise on the integer and the request would answer 500, which this would catch."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock(), **{claim: 123}))

    with pytest.raises(TokenRefused) as refused:
        _verifier(jwks_key_source(jwks_server), fixed_clock)(token)
    assert refused.value.reason == reason


# --- the verifier create_app builds when a test does not supply one ---------------------------


def _live_claims(**overrides: Any) -> dict[str, Any]:
    """Claims valid against the wall clock, which is the clock the production wiring reads.

    Every other test in this file mints against ``fixed_clock``; ``create_app``'s own verifier
    is built with ``utc_now``, so a fixed-clock token would be refused for its lifetime before
    the issuer or the key source was ever reached, and the test would pass for the wrong reason.
    """
    now = datetime.now(UTC)
    claims = _valid_claims(now, roles=["owner"])
    claims.update(overrides)
    return claims


def Given_no_token_verifier_When_create_app_builds_its_own_Then_it_accepts_a_correctly_issued_token(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], jwks_server: str
) -> None:
    """The one path the rest of the suite never runs: ``create_app`` with no ``token_verifier``.

    Every other ``create_app`` caller passes one, so nothing would notice if the composition
    root wired the issuer and the JWKS URI to each other's settings - the owner's first sign-in
    would. Here the real ``jwks_key_source`` fetches from a real endpoint, and a token the realm
    would have issued reaches the route.
    """
    private_key, _ = rsa_key_pair
    app = create_app(_live_settings(jwks_server))
    token = _encode(private_key, _live_claims())

    with TestClient(app, headers={"Authorization": f"Bearer {token}"}) as client:
        response = client.get(_POSITION_URL)

    # 404, not merely "not 401": nothing is held for this symbol, which is the answer only a
    # caller who got past both authentication and authorization ever sees.
    assert response.status_code == 404
    assert response.json()["code"] == "HOLDING_NOT_FOUND"


def Given_no_token_verifier_When_create_app_builds_its_own_Then_it_refuses_a_foreign_issuer(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], jwks_server: str
) -> None:
    """Signed by the very key the JWKS serves, so only the issuer check can refuse it - which is
    what pins that ``create_app`` passed the issuer, and not some other URL, to the verifier."""
    private_key, _ = rsa_key_pair
    app = create_app(_live_settings(jwks_server))
    token = _encode(private_key, _live_claims(iss="http://elsewhere.invalid/realms/other"))

    with TestClient(app, headers={"Authorization": f"Bearer {token}"}) as client:
        response = client.get(_POSITION_URL)

    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHENTICATED"


def Given_no_token_verifier_and_a_JWKS_answering_non_JSON_When_a_route_is_requested_Then_it_is_503(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_answering_garbage: _UnreadableJwks,
) -> None:
    """The case #121 reported, seen at the HTTP boundary: 503 ``AUTHENTICATION_UNAVAILABLE``,
    where the unwrapped decoding error used to reach the generic handler as a 500."""
    private_key, _ = rsa_key_pair
    app = create_app(_live_settings(jwks_server_answering_garbage.uri))
    token = _encode(private_key, _live_claims())

    with TestClient(app, headers={"Authorization": f"Bearer {token}"}) as client:
        response = client.get(_POSITION_URL)

    assert response.status_code == 503
    assert response.json()["code"] == "AUTHENTICATION_UNAVAILABLE"


def Given_no_token_verifier_and_a_JWKS_with_no_key_set_When_a_route_is_requested_Then_it_is_503(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_answering_no_key_set: _UnreadableJwks,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """#126 at the HTTP boundary: 503 ``AUTHENTICATION_UNAVAILABLE`` and the outage's one ERROR
    line, where these bodies used to be a 401 logged at WARN, or a 500."""
    private_key, _ = rsa_key_pair
    app = create_app(_live_settings(jwks_server_answering_no_key_set.uri))
    token = _encode(private_key, _live_claims())
    capsys.readouterr()

    with TestClient(app, headers={"Authorization": f"Bearer {token}"}) as client:
        response = client.get(_POSITION_URL)

    records = [
        json.loads(line)
        for line in capsys.readouterr().out.strip().splitlines()
        if line.startswith("{")
    ]
    outages = [record for record in records if record.get("level") == "error"]
    assert response.status_code == 503
    assert response.json()["code"] == "AUTHENTICATION_UNAVAILABLE"
    assert [outage["reason"] for outage in outages] == [REASON_KEY_SOURCE]


# --- settings: the issuer and JWKS URI are validated as http(s) at start-up --------------------


@pytest.mark.parametrize("variable", ["ASSISTANT_OIDC_ISSUER", "ASSISTANT_OIDC_JWKS_URI"])
def Given_a_non_http_oidc_url_When_settings_are_built_Then_it_is_refused(variable: str) -> None:
    with pytest.raises(ValidationError) as failure:
        Settings(**{variable: "file:///etc/passwd"})  # type: ignore[arg-type]
    assert variable in str(failure.value)


def Given_no_override_When_settings_are_built_Then_the_oidc_defaults_target_local_keycloak() -> (
    None
):
    settings = Settings()

    assert settings.oidc_issuer == ISSUER
    assert settings.oidc_jwks_uri == f"{ISSUER}/protocol/openid-connect/certs"


# --- redaction: the fragments that name authentication's credentials ---------------------------


@pytest.mark.parametrize(
    "key",
    ["cookie", "Cookie", "FINASSIST-XSRF-TOKEN", "xsrf", "csrf", "session", "bearer", "jwt"],
)
def Given_the_new_auth_redaction_fragments_When_a_field_matches_Then_its_value_is_redacted(
    key: str,
) -> None:
    assert redact_secrets(None, "info", {key: "should-not-survive"}) == {key: REDACTED}


def Given_the_new_fragments_When_redacted_Then_input_tokens_still_survives() -> None:
    """None of the six new fragments is a substring of ``input_tokens``, so the assistance
    context's usage measurement stays visible, exactly as the bare ``token`` fragment's
    deliberate absence already protects it from the existing ones."""
    assert redact_secrets(None, "info", {"input_tokens": 1200, "output_tokens": 340}) == {
        "input_tokens": 1200,
        "output_tokens": 340,
    }
