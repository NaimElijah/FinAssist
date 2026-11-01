"""Reserved for the adapter behind ``LlmPort``. None exists.

Whether the owner's financial data may be sent to a third-party LLM API is an open question in the
requirements, and an adapter here would settle it by implication, so nothing is written until
that decision is made (ADR-021). The unused ``anthropic`` dependency was removed in #341.
"""
