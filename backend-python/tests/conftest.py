"""Shared pytest fixtures.

Environment variables are set *before* importing ``app`` so the settings
singleton never picks up real credentials from the repository-root ``.env``.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any

# --- test configuration (must precede app imports) -------------------------
os.environ["APP_ENV"] = "test"
os.environ["INTERNAL_TOKEN"] = "test-internal-token-0123456789"
os.environ["QDRANT_URL"] = "http://127.0.0.1:6333"
os.environ["QDRANT_COLLECTION"] = "knowbase_chunks"
os.environ["QDRANT_API_KEY"] = ""
os.environ["EMBEDDING_DIM"] = "1024"
os.environ["LLM_BASE_URL"] = ""
os.environ["LLM_API_KEY"] = ""
os.environ["LLM_CHAT_MODEL"] = "deepseek-chat"
os.environ["LLM_EMBEDDING_BASE_URL"] = ""
os.environ["LLM_EMBEDDING_API_KEY"] = ""
os.environ["LLM_EMBEDDING_MODEL"] = ""
os.environ["PARSE_MAX_CHARS"] = "600"
os.environ["PARSE_OVERLAP"] = "80"
os.environ["SEARCH_TOP_K"] = "5"
os.environ["SEARCH_SCORE_THRESHOLD"] = "0.2"

import httpx  # noqa: E402
import pytest  # noqa: E402

from app.main import app  # noqa: E402

TOKEN = os.environ["INTERNAL_TOKEN"]
AUTH_HEADERS = {"X-Internal-Token": TOKEN}


def request(
    method: str,
    path: str,
    *,
    json: Any = None,
    headers: dict[str, str] | None = None,
    token: str | None = TOKEN,
) -> httpx.Response:
    """Issue one request against the ASGI app (no real socket)."""

    request_headers = dict(headers or {})
    if token is not None:
        request_headers.setdefault("X-Internal-Token", token)

    async def _run() -> httpx.Response:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.request(method, path, json=json, headers=request_headers)

    return asyncio.run(_run())


def run(coro):
    return asyncio.run(coro)


@pytest.fixture
def api():
    return request


@pytest.fixture
def auth_headers() -> dict[str, str]:
    return dict(AUTH_HEADERS)
