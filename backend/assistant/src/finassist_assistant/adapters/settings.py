"""Configuration, read from the environment once and passed in from there.

Every field maps to a variable documented in ``.env.example`` and the README's Configuration
table. Nothing in this module reads the environment a second time, so a test constructs
``Settings`` directly rather than mutating ``os.environ``.
"""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """The assistant's runtime configuration.

    :param service_name: identifies this service in emitted telemetry. Fixed per service
        rather than shared with the ledger, which would merge two services into one stream.
    :param otlp_endpoint: collector base URL. ``None`` disables export entirely (ADR-019).
    :param traces_export_enabled: whether to export spans at all. Paired with the endpoint so
        a developer without a collector is not given an export error for every span.
    :param log_level: the threshold for the structured logger.
    :param anthropic_api_key: secret. Never logged — see ``observability.redact_secrets``.
    """

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    service_name: str = Field(default="finassist-assistant", alias="ASSISTANT_SERVICE_NAME")
    otlp_endpoint: str | None = Field(default=None, alias="OTEL_EXPORTER_OTLP_ENDPOINT")
    traces_export_enabled: bool = Field(default=False, alias="OTEL_TRACES_EXPORT_ENABLED")
    log_level: str = Field(default="INFO", alias="ASSISTANT_LOG_LEVEL")
    anthropic_api_key: str | None = Field(default=None, alias="ANTHROPIC_API_KEY")

    @property
    def traces_enabled(self) -> bool:
        """Export requires both a destination and permission to use it.

        Two conditions rather than one because an endpoint alone is ambiguous: ``.env`` files
        are copied between machines, and a stale endpoint should go quiet rather than retry.
        """
        return self.traces_export_enabled and bool(self.otlp_endpoint)
