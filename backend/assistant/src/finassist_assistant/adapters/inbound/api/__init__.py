"""FastAPI routers and their request/response models.

The Pydantic models here are transport types. They are mapped to domain values on the way in
and built from domain values on the way out; they never travel inward as-is.
"""
