"""The composition root: the one place that knows about frameworks and wires them together.

This sits beside the bounded contexts rather than inside one, because it belongs to none of
them. It is named ``adapters`` for the same reason each context has an ``adapters`` package —
it is the layer allowed to import FastAPI, structlog and OpenTelemetry — and because the
architecture rules key on that segment, so the exemption it needs is the one it has earned
rather than a special case written for it.

Nothing here may be imported by a context. Frameworks travel inward only through the ports a
context declares, and ``tests/test_architecture.py`` holds that.

See ADR-019.
"""
