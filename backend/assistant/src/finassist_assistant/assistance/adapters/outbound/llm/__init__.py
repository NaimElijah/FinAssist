"""Anthropic client behind LlmPort.

Prompt, model id and retry behaviour live here. The API key is read from the environment and
redacted at the logging boundary - never at a call site, and never logged.
"""
