"""Trace-id middleware: propagate ``X-Trace-Id`` and emit one JSON request line."""

from __future__ import annotations

import re
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from app.logging_setup import (
    RequestContext,
    get_logger,
    reset_request_context,
    set_request_context,
)

logger = get_logger("app.http")

TRACE_HEADER = "X-Trace-Id"
_TRACE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{8,64}$")


def normalize_trace_id(raw: str | None) -> str:
    """Reuse a well-formed incoming trace id, otherwise mint a 32-char hex id."""

    if raw is not None:
        candidate = raw.strip()
        if _TRACE_ID_PATTERN.fullmatch(candidate):
            return candidate
    return uuid.uuid4().hex


class TraceMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        trace_id = normalize_trace_id(request.headers.get(TRACE_HEADER))
        context = RequestContext(
            trace_id=trace_id,
            method=request.method,
            path=request.url.path,
        )
        token = set_request_context(context)
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            response.headers[TRACE_HEADER] = trace_id
            return response
        except Exception:
            # Unhandled errors are turned into a 500 response by the outer
            # exception handler; keep a single request log line either way.
            status = 500
            raise
        finally:
            context.status = status
            context.duration_ms = round((time.perf_counter() - started) * 1000, 1)
            logger.info(
                "http_request",
                extra={
                    "trace_id": trace_id,
                    "method": context.method,
                    "path": context.path,
                    "status": context.status,
                    "duration_ms": context.duration_ms,
                },
            )
            reset_request_context(token)


__all__ = ["TraceMiddleware", "normalize_trace_id"]
