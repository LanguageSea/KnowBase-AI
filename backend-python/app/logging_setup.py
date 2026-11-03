"""JSON structured logging (one JSON object per line on stdout).

Per ``docs/INTERNAL_API.md`` §0.2 every request line carries exactly these
fields: ``ts``, ``level``, ``logger``, ``trace_id``, ``method``, ``path``,
``status``, ``duration_ms``.

Request-scoped values live in a ``contextvars.ContextVar`` so that handlers
(including exception handlers that produce 400/401/500 responses) emit the same
``trace_id`` as the request middleware without threading the request object
through every call.

Secrets and document contents are never logged: the formatter only serialises
the fields listed above plus explicit ``extra={"fields": {...}}`` metadata.
"""

from __future__ import annotations

import contextvars
import json
import logging
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, MutableMapping

REQUEST_FIELDS = ("trace_id", "method", "path", "status", "duration_ms")
_MAX_MESSAGE_CHARS = 500


@dataclass
class RequestContext:
    """Values shared by every log line emitted while handling one request."""

    trace_id: str | None = None
    method: str | None = None
    path: str | None = None
    status: int | None = None
    duration_ms: float | None = None
    extra: dict[str, Any] = field(default_factory=dict)


_request_context: contextvars.ContextVar[RequestContext | None] = contextvars.ContextVar(
    "kb_request_context", default=None
)


def current_request_context() -> RequestContext | None:
    return _request_context.get()


def set_request_context(context: RequestContext) -> contextvars.Token[RequestContext | None]:
    return _request_context.set(context)


def reset_request_context(token: contextvars.Token[RequestContext | None]) -> None:
    _request_context.reset(token)


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _truncate(value: str) -> str:
    if len(value) <= _MAX_MESSAGE_CHARS:
        return value
    return value[:_MAX_MESSAGE_CHARS] + "…"


class JsonFormatter(logging.Formatter):
    """Render one log record as a single JSON line with the contract fields."""

    def format(self, record: logging.LogRecord) -> str:  # noqa: A003 - stdlib name
        context = _request_context.get()
        payload: dict[str, Any] = {
            "ts": _utc_timestamp(),
            "level": record.levelname,
            "logger": record.name,
        }
        for name in REQUEST_FIELDS:
            value = getattr(record, name, None)
            if value is None and context is not None:
                value = getattr(context, name, None)
            payload[name] = value

        message = record.getMessage()
        if message:
            payload["message"] = _truncate(message)

        explicit = getattr(record, "fields", None)
        if isinstance(explicit, dict):
            payload.update(explicit)

        if record.exc_info and record.exc_info[0] is not None:
            payload["exception"] = record.exc_info[0].__name__

        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging(level: int = logging.INFO) -> None:
    """Install the JSON handler on the root logger (idempotent)."""

    root = logging.getLogger()
    if getattr(root, "_kb_json_configured", False):
        return

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())

    root.handlers = [handler]
    root.setLevel(level)

    # Route uvicorn's own loggers through the JSON handler and drop the
    # duplicate access log: request lines are emitted by our middleware only.
    for name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True

    access = logging.getLogger("uvicorn.access")
    access.handlers = []
    access.propagate = False
    access.disabled = True

    # Transport-level chatter (one line per Qdrant/LLM call) would pollute the
    # per-request log stream; keep warnings and above only.
    for name in ("httpx", "httpcore", "qdrant_client"):
        logging.getLogger(name).setLevel(logging.WARNING)

    setattr(root, "_kb_json_configured", True)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


__all__ = [
    "JsonFormatter",
    "RequestContext",
    "configure_logging",
    "current_request_context",
    "get_logger",
    "reset_request_context",
    "set_request_context",
]
