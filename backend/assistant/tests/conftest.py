"""Test configuration for the assistant suite.

Exists for three reasons: to make Hypothesis deterministic, to reset the logging boundary's
process-wide state around every test, and to run every test against an environment that names
none of the assistant's own settings.

ADR-016 says a projection is "stated assumptions in, one reproducible line out", and the
ledger's own property tests generate from a fixed seed for the same reason. A property suite
that picks fresh examples every run can turn a commit that changed nothing red on CI, which
teaches everyone to re-run the build instead of reading it. ``derandomize`` makes the examples
a function of the test itself, so the same inputs run everywhere.

The profile is registered here rather than in ``pyproject.toml`` because ``--strict-config``
rejects ini keys pytest does not define, and ``hypothesis_profile`` is not one of them.
"""

import os
from collections.abc import Callable, Iterator
from datetime import UTC, datetime, timedelta
from typing import Any, Final

import jwt
import pytest
import structlog
from cryptography.hazmat.primitives.asymmetric import rsa
from hypothesis import HealthCheck, Verbosity, settings
from jwt import PyJWK
from jwt.algorithms import RSAAlgorithm

from finassist_assistant.adapters.auth import ASSISTANT_AUDIENCE, KeySource, TokenVerifier
from finassist_assistant.adapters.observability import forget_secrets, reset_logging
from finassist_assistant.adapters.wiring import Clock

settings.register_profile(
    "finassist",
    # Same inputs on every machine and every run: no flaky property test, ever.
    derandomize=True,
    # No .hypothesis/ directory to gitignore or to carry state between runs. With derandomize
    # the failure database buys nothing - a failing example reappears on the next run anyway.
    database=None,
    # These are pure functions over Decimals; a per-example time limit only ever fires because
    # a CI runner was cold, never because the domain was slow.
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
    verbosity=Verbosity.normal,
)
settings.load_profile("finassist")


SETTINGS_ENVIRONMENT_PREFIXES: Final = ("ASSISTANT_", "OTEL_")
"""Prefixes of every environment variable ``Settings`` or the OpenTelemetry SDK reads."""

SETTINGS_ENVIRONMENT_NAMES: Final = frozenset({"ANTHROPIC_API_KEY"})
"""The one setting whose name carries neither prefix."""


def names_a_setting(name: str) -> bool:
    """Whether the environment variable *name* is one ``Settings`` or the OpenTelemetry SDK reads.

    Compared upper-cased, because pydantic-settings matches a name case-insensitively unless a
    model says otherwise, and ``Settings`` does not: on Linux an exported
    ``assistant_dev_seed=true`` is read as ``ASSISTANT_DEV_SEED``, so it must be cleared as one.
    Windows upper-cases every ``os.environ`` key itself, which is why the case-sensitive version
    passed there.
    """
    upper = name.upper()
    return upper.startswith(SETTINGS_ENVIRONMENT_PREFIXES) or upper in SETTINGS_ENVIRONMENT_NAMES


@pytest.fixture(autouse=True)
def _clear_settings_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    """Removes every setting the shell running the suite happens to export.

    ``Settings`` reads the process environment, so a developer with ``ASSISTANT_DEV_SEED=true``
    or ``ASSISTANT_HOST=0.0.0.0`` exported - the values a native run of the assistant wants -
    turned tests asserting a default red, and ones building an app with no seed served seeded
    data. The names are enumerated from the environment rather than listed, so a setting added
    later is cleared without anyone remembering it here. A test that wants a value sets it with
    ``monkeypatch`` itself, after this has run.
    """
    for name in list(os.environ):
        if names_a_setting(name):
            monkeypatch.delenv(name, raising=False)


@pytest.fixture(autouse=True)
def _reset_observability() -> Iterator[None]:
    """Registered secrets, structlog and the root logger are global; no test may leak any.

    Autouse for the whole suite rather than per module, so a module that starts configuring
    logging is isolated without anyone remembering to copy this in. A test that never touches
    the boundary pays for three calls that find nothing to undo.
    """
    forget_secrets()
    yield
    forget_secrets()
    structlog.reset_defaults()
    reset_logging()


# --- token verification (tests/test_auth.py) ------------------------------------------------
#
# Shared here rather than in the test module itself, so a real key pair is generated once per
# module instead of once per test: real keys, a static key source and a fixed clock, so a
# boundary test moves a token's own claims rather than the wall clock.

FIXED_NOW: Final[datetime] = datetime(2026, 1, 1, tzinfo=UTC)
"""The instant :func:`fixed_clock` reports. A leeway boundary is proven by moving a token's own
claims relative to this fixed point, never by moving the clock itself or sleeping."""


@pytest.fixture(scope="module")
def rsa_key_pair() -> tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey]:
    """One RSA key pair, generated once per test module rather than once per test.

    Nothing under test depends on a fresh key for each case, and generating a 2048-bit key -
    the floor :class:`jwt.algorithms.RSAAlgorithm` itself enforces - is the slowest thing in the
    whole suite.
    """
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return private_key, private_key.public_key()


@pytest.fixture
def fixed_clock() -> Clock:
    """A :data:`~finassist_assistant.adapters.wiring.Clock` that always reports
    :data:`FIXED_NOW`."""
    return lambda: FIXED_NOW


@pytest.fixture
def static_key_source(rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey]) -> KeySource:
    """A :data:`~finassist_assistant.adapters.auth.KeySource` that returns one fixed key
    regardless of the token it is asked about.

    Stands in for a real JWKS lookup in every test that is not itself testing that lookup; the
    real ``PyJWKClient`` path is exercised separately, against a real ``http.server``.
    """
    _, public_key = rsa_key_pair
    jwk = PyJWK.from_dict(RSAAlgorithm.to_jwk(public_key, as_dict=True))
    return lambda _token: jwk


# --- authorization (tests/test_authorization.py, and every create_app caller) ----------------
#
# Every existing `create_app` caller in the suite needs a token_verifier that accepts no real
# network call, and most need a valid credential to reach a route BearerAuthentication now
# guards by default. Shared here rather than per module, so "the owner" always means the same
# signed token wherever a test asks for one.

ISSUER: Final = "http://localhost:8081/realms/finassist"
"""The issuer every fixture-minted token below names, matching ``Settings``' own default."""


def _signed_token(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey],
    now: datetime,
    *,
    roles: list[str] | None,
    subject: str = "test-subject",
) -> str:
    """Signs a token valid as of *now*, naming *roles* - the shape ``BearerAuthentication`` reads.

    ``roles=None`` omits the claim entirely, which :class:`TokenVerifier` already treats as
    granting no role at all; a caller wanting that exact case passes it explicitly rather than an
    empty list, so the two "grants nothing" shapes stay distinguishable to whichever test is
    exercising the difference.
    """
    private_key, _ = rsa_key_pair
    claims: dict[str, Any] = {
        "sub": subject,
        "iss": ISSUER,
        "aud": ASSISTANT_AUDIENCE,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(hours=1)).timestamp()),
    }
    if roles is not None:
        claims["roles"] = roles
    return jwt.encode(claims, private_key, algorithm="RS256")


@pytest.fixture
def token_verifier(static_key_source: KeySource, fixed_clock: Clock) -> TokenVerifier:
    """A real :class:`TokenVerifier`, backed by a stand-in key source and a fixed clock.

    Every ``create_app`` caller in this suite passes this rather than letting the default build
    one pointed at a real JWKS endpoint: no test may depend on the network, and every fixture
    below mints a token this exact verifier accepts.
    """
    return TokenVerifier(static_key_source, ISSUER, ASSISTANT_AUDIENCE, fixed_clock)


@pytest.fixture
def headers_for(
    rsa_key_pair: tuple[rsa.RSAPrivateKey, rsa.RSAPublicKey], fixed_clock: Clock
) -> Callable[[list[str] | None], dict[str, str]]:
    """Builds an ``Authorization`` header naming *roles*, signed against :func:`fixed_clock`.

    A callable rather than a single header dict, so one test can ask for more than one role
    shape - the owner, the viewer, the assistant, an unknown role, or no ``roles`` claim at all -
    without a fixture per shape.
    """

    def _build(roles: list[str] | None) -> dict[str, str]:
        token = _signed_token(rsa_key_pair, fixed_clock(), roles=roles)
        return {"Authorization": f"Bearer {token}"}

    return _build


@pytest.fixture
def owner_headers(headers_for: Callable[[list[str] | None], dict[str, str]]) -> dict[str, str]:
    """A valid ``Authorization`` header for the ``owner`` role.

    The default credential most of this suite gives a ``TestClient``: a test exercising the
    portfolio endpoints themselves, rather than authentication or authorization, needs a caller
    admitted on every route without knowing which permission each one needs.
    """
    return headers_for(["owner"])
