"""Ports this service owns, declared as typing.Protocol.

The application depends on these abstractions; adapters implement them. Nothing in this
package may import an adapter, httpx, FastAPI or the Anthropic SDK.
"""
