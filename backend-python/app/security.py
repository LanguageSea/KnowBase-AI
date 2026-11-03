"""Internal-token authentication.

Python never parses JWTs and never looks at users: the Java side authorises and
calls this service with a shared secret. Comparison is constant time.
"""

from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Header

from app.config import get_settings
from app.errors import unauthorized

HeaderToken = Annotated[str | None, Header(alias="X-Internal-Token")]


async def require_internal_token(x_internal_token: HeaderToken = None) -> None:
    """FastAPI dependency enforcing ``X-Internal-Token`` on every endpoint."""

    expected = get_settings().internal_token
    if not x_internal_token:
        raise unauthorized()
    provided = x_internal_token.encode("utf-8")
    if not secrets.compare_digest(provided, expected.encode("utf-8")):
        raise unauthorized()


__all__ = ["require_internal_token"]
