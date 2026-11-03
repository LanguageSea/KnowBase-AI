"""FastAPI application for the KnowBase-AI Python service.

Only four internal endpoints exist (see ``docs/INTERNAL_API.md``). The service
binds to loopback only and is never exposed through Nginx.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI

from app import __version__
from app.config import get_settings
from app.errors import register_exception_handlers, short_code
from app.logging_setup import configure_logging, get_logger
from app.middleware import TraceMiddleware
from app.routers.internal import router as internal_router
from app.services import qdrant_store

# Configure JSON logging and validate configuration at import time so a missing
# INTERNAL_TOKEN fails fast with a readable message instead of a silent 500.
configure_logging()
logger = get_logger("app.main")

# Raises ConfigurationError (readable message) when required configuration is
# missing, e.g. INTERNAL_TOKEN.
_settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    try:
        await qdrant_store.ensure_collection(_settings)
        logger.info(
            "qdrant_ready",
            extra={
                "fields": {
                    "collection": _settings.qdrant_collection,
                    "dim": _settings.embedding_dim,
                }
            },
        )
    except Exception as exc:
        # Do not crash the whole service: /internal/health reports DEGRADED and
        # index/search return 503 with a short code.
        logger.error("qdrant_init_failed", extra={"fields": {"error": short_code(exc)}})
    logger.info(
        "service_started",
        extra={"fields": {"service": "ai-service", "version": __version__, "env": _settings.app_env}},
    )
    try:
        yield
    finally:
        qdrant_store.close_client()
        logger.info("service_stopped")


app = FastAPI(
    title="KnowBase-AI AI Service",
    description="Internal document parsing, embedding and retrieval service (see docs/INTERNAL_API.md).",
    version=__version__,
    lifespan=lifespan,
    docs_url=None if _settings.app_env == "prod" else "/docs",
    redoc_url=None,
    openapi_url=None if _settings.app_env == "prod" else "/openapi.json",
)

app.state.settings = _settings

app.add_middleware(TraceMiddleware)
register_exception_handlers(app)
app.include_router(internal_router)


__all__ = ["app"]
