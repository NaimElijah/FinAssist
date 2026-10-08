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
import threading
from collections.abc import Iterator, Mapping
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

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


def Given_a_token_signed_with_the_none_algorithm_When_verified_Then_it_is_refused(
    static_key_source: KeySource, fixed_clock: Clock
) -> None:
    header = _b64url(json.dumps({"alg": "none", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = _b64url(json.dumps(_valid_claims(fixed_clock()), separators=(",", ":")).encode())
    token = f"{header}.{payload}."

    with pytest.raises(TokenRefused) as refused:
        _verifier(static_key_source, fixed_clock)(token)
    assert refused.value.reason == REASON_MALFORMED


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
    producing a token). ``algorithms=["RS256"]`` is still the defence that matters: it is checked
    before any key is even inspected, so this is refused ``malformed`` (an algorithm the header
    named but the caller never allowed), not because *this* particular attack happens to be hard
    to build.
    """
    _, public_key = rsa_key_pair
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    header = _b64url(
        json.dumps({"alg": "HS256", "typ": "JWT", "kid": KEY_ID}, separators=(",", ":")).encode()
    )
    payload = _b64url(json.dumps(_valid_claims(fixed_clock()), separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode()
    signature = hmac.new(public_pem, signing_input, hashlib.sha256).digest()
    token = f"{header}.{payload}.{_b64url(signature)}"

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


def Given_the_key_source_is_unreachable_When_verified_Then_it_is_reported_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], fixed_clock: Clock
) -> None:
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    def unreachable(_token: str) -> PyJWK:
        raise jwt.PyJWKClientConnectionError('Fail to fetch data from the url, err: "refused"')

    with pytest.raises(KeySourceUnavailable) as unavailable:
        _verifier(unreachable, fixed_clock)(token)
    assert unavailable.value.reason == REASON_KEY_SOURCE


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


@pytest.fixture(
    params=[b"<html><body>Down for maintenance</body></html>", b"\x80\x81"],
    ids=["not-json", "not-utf-8"],
)
def jwks_server_answering_garbage(request: pytest.FixtureRequest) -> Iterator[str]:
    """A JWKS endpoint that answers ``200`` with a body ``json.load`` cannot read (#121).

    Both bodies get through ``PyJWKClient.fetch_data``'s ``try`` unwrapped, because it wraps
    transport failures only. One is not JSON at all, such as a proxy's maintenance page, and
    raises ``json.JSONDecodeError``. The other is not even UTF-8 and raises
    ``UnicodeDecodeError`` before parsing starts. Both hold on PyJWT 2.15.0.
    """
    body: bytes = request.param

    class _Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
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


def Given_a_JWKS_endpoint_answering_a_body_that_is_not_JSON_When_verified_Then_it_is_unavailable(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    jwks_server_answering_garbage: str,
    fixed_clock: Clock,
) -> None:
    """The key source answered, but with nothing a key can be read from. Nothing here says
    whether the token was good, so this is the same outage as an unreachable source, never a
    refusal. Before #121 the decoding error escaped unwrapped and the request answered 500."""
    private_key, _ = rsa_key_pair
    token = _encode(private_key, _valid_claims(fixed_clock()))

    with pytest.raises(KeySourceUnavailable) as unavailable:
        _verifier(jwks_key_source(jwks_server_answering_garbage), fixed_clock)(token)
    assert unavailable.value.reason == REASON_KEY_SOURCE


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
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], jwks_server_answering_garbage: str
) -> None:
    """The case #121 reported, seen at the HTTP boundary: 503 ``AUTHENTICATION_UNAVAILABLE``,
    where the unwrapped decoding error used to reach the generic handler as a 500."""
    private_key, _ = rsa_key_pair
    app = create_app(_live_settings(jwks_server_answering_garbage))
    token = _encode(private_key, _live_claims())

    with TestClient(app, headers={"Authorization": f"Bearer {token}"}) as client:
        response = client.get(_POSITION_URL)

    assert response.status_code == 503
    assert response.json()["code"] == "AUTHENTICATION_UNAVAILABLE"


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
