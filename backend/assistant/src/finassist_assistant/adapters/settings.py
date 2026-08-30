"""Configuration, read from the environment once and passed in from there.

Every field maps to a variable documented in ``.env.example`` and the README's Configuration
table, and this module is the only reader of the environment — a second reader elsewhere would
put a documented variable outside the validation this file provides.
"""

from __future__ import annotations

from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]


class Settings(BaseSettings):
    """The assistant's runtime configuration.

    :param service_name: identifies this service in emitted telemetry. Fixed per service
        rather than shared with the ledger, which would merge two services into one stream.
    :param host: interface to bind. Loopback by default — nothing runs the assistant in a
        container yet, so binding every interface would publish it to the local network.
    :param port: TCP port to serve on.
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
    otlp_endpoint: str | None = Field(default=None, alias="OTEL_EXPORTER_OTLP_ENDPOINT")
    traces_export_enabled: bool = Field(default=False, alias="OTEL_EXPORT_ENABLED")
    log_level: LogLevel = Field(default="INFO", alias="ASSISTANT_LOG_LEVEL")
    anthropic_api_key: SecretStr | None = Field(default=None, alias="ANTHROPIC_API_KEY")

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
