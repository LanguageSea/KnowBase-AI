"""Unified error body ``{code, message, trace_id}`` and application errors.

See ``docs/INTERNAL_API.md`` §0.1 for the status/code matrix.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.logging_setup import current_request_context, get_logger

logger = get_logger("app.error")

UNAUTHORIZED = "UNAUTHORIZED"
VALIDATION_ERROR = "VALIDATION_ERROR"
NOT_FOUND = "NOT_FOUND"
FILE_TOO_LARGE = "FILE_TOO_LARGE"
NO_TEXT_LAYER = "NO_TEXT_LAYER"
EMPTY_CONTENT = "EMPTY_CONTENT"
PAGE_LIMIT_EXCEEDED = "PAGE_LIMIT_EXCEEDED"
PARSE_TIMEOUT = "PARSE_TIMEOUT"
UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
INTERNAL_ERROR = "INTERNAL_ERROR"
DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE"
EMBEDDING_DIM_MISMATCH = "EMBEDDING_DIM_MISMATCH"


class AppError(Exception):
    """An error that maps onto the documented error body."""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


def unauthorized() -> AppError:
    return AppError(401, UNAUTHORIZED, "missing or invalid X-Internal-Token")


def validation_error(message: str) -> AppError:
    return AppError(400, VALIDATION_ERROR, message)


def not_found(message: str) -> AppError:
    return AppError(404, NOT_FOUND, message)


def file_too_large(message: str) -> AppError:
    return AppError(413, FILE_TOO_LARGE, message)


def no_text_layer(message: str = "document contains no extractable text") -> AppError:
    return AppError(422, NO_TEXT_LAYER, message)


def empty_content(message: str = "document contains no content") -> AppError:
    return AppError(422, EMPTY_CONTENT, message)


def page_limit_exceeded(actual: int, maximum: int) -> AppError:
    return AppError(422, PAGE_LIMIT_EXCEEDED, f"document has {actual} pages, maximum is {maximum}")


def parse_timeout(seconds: int) -> AppError:
    return AppError(504, PARSE_TIMEOUT, f"document parsing exceeded {seconds} seconds")


def unsupported_format(ext: str) -> AppError:
    return AppError(400, UNSUPPORTED_FORMAT, f"unsupported file extension: {ext}")


def internal_error(message: str = "unexpected internal error") -> AppError:
    return AppError(500, INTERNAL_ERROR, message)


def embedding_dim_mismatch(actual: int, expected: int) -> AppError:
    return AppError(
        500,
        EMBEDDING_DIM_MISMATCH,
        f"embedding dimension mismatch: got {actual}, expected {expected}",
    )


def dependency_unavailable(detail_code: str, dependency: str | None = None) -> AppError:
    target = f"{dependency} " if dependency else ""
    return AppError(503, DEPENDENCY_UNAVAILABLE, f"{target}dependency unavailable: {detail_code}")


def short_code(exc: BaseException) -> str:
    """Map an exception to a short, non-leaking detail code."""

    name = type(exc).__name__
    text = str(exc).lower()

    status = getattr(exc, "status_code", None)
    if status is None:
        response = getattr(exc, "response", None)
        status = getattr(response, "status_code", None)
    if isinstance(status, int):
        if status == 401:
            return "HTTP_401"
        return f"HTTP_{status}"

    if isinstance(exc, TimeoutError) or "timeout" in name.lower() or "timed out" in text:
        return "TIMEOUT"
    if "ConnectError" in name or "ConnectionError" in name or "connection refused" in text:
        return "CONNECTION_REFUSED"
    if "Connection" in name:
        return "CONNECTION_REFUSED"
    return "DEPENDENCY_ERROR"


def _error_response(status_code: int, code: str, message: str) -> JSONResponse:
    context = current_request_context()
    trace_id = context.trace_id if context is not None else None
    body: dict[str, Any] = {"code": code, "message": message, "trace_id": trace_id}
    return JSONResponse(status_code=status_code, content=body, headers={"X-Trace-Id": trace_id} if trace_id else None)


def _format_validation_errors(errors: list[dict[str, Any]]) -> str:
    parts: list[str] = []
    for error in errors[:3]:
        location = ".".join(str(item) for item in error.get("loc", ()) if item != "body")
        message = error.get("msg", "invalid value")
        parts.append(f"{location or 'body'}: {message}")
    summary = "; ".join(parts) or "request body failed validation"
    return summary[:500]


async def app_error_handler(_: Request, exc: AppError) -> JSONResponse:
    return _error_response(exc.status_code, exc.code, exc.message)


async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    logger.info("validation_failed")
    return _error_response(400, VALIDATION_ERROR, _format_validation_errors(list(exc.errors())))


async def http_exception_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    if exc.status_code == 404:
        return _error_response(404, NOT_FOUND, "resource not found")
    if exc.status_code == 405:
        return _error_response(405, VALIDATION_ERROR, "method not allowed")
    if exc.status_code == 413:
        return _error_response(413, FILE_TOO_LARGE, "payload too large")
    if exc.status_code == 401:
        return _error_response(401, UNAUTHORIZED, "missing or invalid X-Internal-Token")
    return _error_response(exc.status_code, INTERNAL_ERROR, "request failed")


async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    # Type name only: no stack trace, no message (may contain paths or payloads).
    logger.error("unhandled_exception", extra={"fields": {"error": type(exc).__name__}})
    return _error_response(500, INTERNAL_ERROR, "unexpected internal error")


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, app_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)


__all__ = [
    "AppError",
    "DEPENDENCY_UNAVAILABLE",
    "EMPTY_CONTENT",
    "EMBEDDING_DIM_MISMATCH",
    "FILE_TOO_LARGE",
    "INTERNAL_ERROR",
    "NOT_FOUND",
    "NO_TEXT_LAYER",
    "PAGE_LIMIT_EXCEEDED",
    "PARSE_TIMEOUT",
    "UNAUTHORIZED",
    "UNSUPPORTED_FORMAT",
    "VALIDATION_ERROR",
    "dependency_unavailable",
    "embedding_dim_mismatch",
    "empty_content",
    "file_too_large",
    "internal_error",
    "no_text_layer",
    "not_found",
    "page_limit_exceeded",
    "parse_timeout",
    "register_exception_handlers",
    "short_code",
    "unauthorized",
    "unsupported_format",
    "validation_error",
]
