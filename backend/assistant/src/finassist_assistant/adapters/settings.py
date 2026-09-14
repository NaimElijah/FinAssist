"""Configuration, read from the environment once and passed in from there.

Every field maps to a variable documented in ``.env.example`` and the README's Configuration
table, and this module is the only reader of the environment — a second reader elsewhere would
put a documented variable outside the validation this file provides.
"""

from __future__ import annotations

from typing import Annotated, Final, Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict

LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]

# `TrustedHostMiddleware` reads `*` as any host, and `*.example.com` as a suffix.
_WILDCARD: Final = "*"

# A port's colon and an IPv6 literal's colon or bracket. The middleware compares only the text
# before a Host header's first colon, so an allowed entry holding one of these matches no request.
_PORT_OR_BRACKET: Final = (":", "[")


class Settings(BaseSettings):
    """The assistant's runtime configuration.

    :param service_name: identifies this service in emitted telemetry. Fixed per service
        rather than shared with the ledger, which would merge two services into one stream.
    :param host: interface to bind. Loopback by default — nothing runs the assistant in a
        container yet, so binding every interface would publish it to the local network.
    :param port: TCP port to serve on.
    :param allowed_hosts: the host names a request's ``Host`` header may name; any other is
        refused with 403 (ADR-027). Comma-separated in the environment, as the ledger's
        ``LEDGER_ALLOWED_HOSTS`` is, so the field opts out of pydantic-settings' JSON decoding of
        list fields and is split here instead.
    :param otlp_endpoint: collector base URL. ``None`` disables export entirely (ADR-019).
    :param traces_export_enabled: whether to export spans at all. Paired with the endpoint so
        a developer without a collector is not given an export error for every span.
    :param log_level: the threshold for the structured logger. A closed set, so a typo is
        refused at construction with the field named rather than raising a bare ``KeyError``
        from inside the logging setup.
    :param anthropic_api_key: secret. ``SecretStr`` so it cannot reach a ``repr``, a
        ``model_dump`` or a validation error — the redaction boundary covers log events, not
        the accidental printing of this object.
    """

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    service_name: str = Field(default="finassist-assistant", alias="ASSISTANT_SERVICE_NAME")
    host: str = Field(default="127.0.0.1", alias="ASSISTANT_HOST")
    port: int = Field(default=8000, ge=1, le=65535, alias="ASSISTANT_PORT")
    allowed_hosts: Annotated[list[str], NoDecode] = Field(
        default=["localhost", "127.0.0.1"], alias="ASSISTANT_ALLOWED_HOSTS"
    )
    otlp_endpoint: str | None = Field(default=None, alias="OTEL_EXPORTER_OTLP_ENDPOINT")
    traces_export_enabled: bool = Field(default=False, alias="OTEL_EXPORT_ENABLED")
    log_level: LogLevel = Field(default="INFO", alias="ASSISTANT_LOG_LEVEL")
    anthropic_api_key: SecretStr | None = Field(default=None, alias="ANTHROPIC_API_KEY")

    @field_validator("allowed_hosts", mode="before")
    @classmethod
    def split_allowed_hosts(cls, value: object) -> object:
        """The environment supplies one comma-separated string; code may pass a list instead."""
        return value.split(",") if isinstance(value, str) else value

    @field_validator("allowed_hosts")
    @classmethod
    def require_literal_host_names(cls, hosts: list[str]) -> list[str]:
        """Drops blank entries and lower-cases the rest; refuses any list the check cannot honour.

        An empty list refuses every request, which looks like an outage rather than the
        configuration mistake it is; the ledger refuses one for the same reason. An entry with a
        port or a bracket is refused for that reason too. ``TrustedHostMiddleware`` compares only
        the text before a ``Host`` header's first colon, so ``localhost:8000``, ``[::1]`` and
        ``::1`` match no request, and one listed alone refuses them all.

        A wildcard is refused because the middleware reads ``*`` as any host and ``*.example.com``
        as a suffix, either of which quietly switches the defence off. The ledger's list has no
        wildcards.

        :raises ValueError: if no name is left, or a name holds a wildcard, a colon or a bracket.
        """
        names = [host.strip().lower() for host in hosts if host.strip()]
        if not names:
            raise ValueError("names no host, so every request would be refused")
        if any(_WILDCARD in name for name in names):
            raise ValueError("must name hosts literally; a wildcard admits hosts nobody listed")
        if any(mark in name for name in names for mark in _PORT_OR_BRACKET):
            raise ValueError(
                "must name hosts without a port or brackets, which no Host header would match"
            )
        return names

    @property
    def traces_enabled(self) -> bool:
        """Export requires both a destination and permission to use it.

        Two conditions rather than one because an endpoint alone is ambiguous: ``.env`` files
        are copied between machines, and a stale endpoint should go quiet rather than retry.
        """
        return self.traces_export_enabled and bool(self.otlp_endpoint)

    @property
    def anthropic_api_key_value(self) -> str | None:
        """The key itself, for the one caller that must register it for redaction."""
        return self.anthropic_api_key.get_secret_value() if self.anthropic_api_key else None
