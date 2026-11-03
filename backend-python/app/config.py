"""Configuration loading for the AI service.

Contract: ``docs/INTERNAL_API.md``.

Design notes
------------
* Every value comes from a real environment variable.
* In non-prod environments (``APP_ENV != "prod"``) the repository-root ``.env``
  and then ``backend-python/.env`` are loaded with ``override=False`` so that
  real environment variables always win.
* ``INTERNAL_TOKEN`` is the only strictly required value: a missing token would
  silently disable authentication, so startup fails with a readable message.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BASE_DIR.parent


class ConfigurationError(RuntimeError):
    """Raised when the service configuration is missing or invalid."""


def _load_dev_env() -> None:
    """Load ``.env`` files for local development only (never in prod)."""

    app_env = os.getenv("APP_ENV", "dev").strip().lower()
    if app_env == "prod":
        return
    try:
        from dotenv import load_dotenv
    except ImportError:  # pragma: no cover - python-dotenv is a declared dependency
        return
    for candidate in (REPO_ROOT / ".env", BASE_DIR / ".env"):
        if candidate.is_file():
            load_dotenv(candidate, override=False)


def _raw(name: str) -> str | None:
    value = os.getenv(name)
    if value is None:
        return None
    value = value.strip()
    return value


def _int_env(name: str, default: int, *, minimum: int | None = None, maximum: int | None = None) -> int:
    raw = _raw(name)
    if raw in (None, ""):
        value = default
    else:
        try:
            value = int(raw)
        except ValueError as exc:
            raise ConfigurationError(f"{name} must be an integer, got {raw!r}") from exc
    if minimum is not None and value < minimum:
        raise ConfigurationError(f"{name} must be >= {minimum}, got {value}")
    if maximum is not None and value > maximum:
        raise ConfigurationError(f"{name} must be <= {maximum}, got {value}")
    return value


def _float_env(name: str, default: float, *, minimum: float, maximum: float) -> float:
    raw = _raw(name)
    if raw in (None, ""):
        value = default
    else:
        try:
            value = float(raw)
        except ValueError as exc:
            raise ConfigurationError(f"{name} must be a number, got {raw!r}") from exc
    if not minimum <= value <= maximum:
        raise ConfigurationError(f"{name} must be within [{minimum}, {maximum}], got {value}")
    return value


@dataclass(frozen=True)
class Settings:
    app_env: str
    internal_token: str
    qdrant_url: str
    qdrant_collection: str
    qdrant_api_key: str | None
    embedding_dim: int
    llm_base_url: str | None
    llm_api_key: str | None
    llm_chat_model: str
    llm_embedding_base_url: str | None
    llm_embedding_api_key: str | None
    llm_embedding_model: str
    parse_max_chars: int
    parse_overlap: int
    parse_max_pages: int
    parse_timeout_seconds: int
    search_top_k: int
    search_score_threshold: float
    allow_private_model_urls: bool

    # -- derived helpers -------------------------------------------------

    @property
    def openai_chat_configured(self) -> bool:
        return bool(self.llm_api_key)

    @property
    def openai_embedding_configured(self) -> bool:
        return bool(self.llm_embedding_base_url) and bool(self.llm_embedding_api_key)


def build_settings() -> Settings:
    _load_dev_env()

    token = _raw("INTERNAL_TOKEN")
    if not token:
        raise ConfigurationError(
            "INTERNAL_TOKEN is required: set it in the environment or in the repository-root .env "
            "(the Python service refuses to start without internal API authentication)."
        )

    parse_max_chars = _int_env("PARSE_MAX_CHARS", 600, minimum=100, maximum=2000)
    parse_overlap = _int_env("PARSE_OVERLAP", 80, minimum=0)
    if parse_overlap >= parse_max_chars:
        raise ConfigurationError(
            f"PARSE_OVERLAP ({parse_overlap}) must be smaller than PARSE_MAX_CHARS ({parse_max_chars})"
        )

    return Settings(
        app_env=(_raw("APP_ENV") or "dev").lower(),
        internal_token=token,
        qdrant_url=_raw("QDRANT_URL") or "http://127.0.0.1:6333",
        qdrant_collection=_raw("QDRANT_COLLECTION") or "knowbase_chunks",
        qdrant_api_key=_raw("QDRANT_API_KEY") or None,
        embedding_dim=_int_env("EMBEDDING_DIM", 1024, minimum=8, maximum=8192),
        llm_base_url=_raw("LLM_BASE_URL") or None,
        llm_api_key=_raw("LLM_API_KEY") or None,
        llm_chat_model=_raw("LLM_CHAT_MODEL") or "deepseek-chat",
        llm_embedding_base_url=_raw("LLM_EMBEDDING_BASE_URL") or None,
        llm_embedding_api_key=_raw("LLM_EMBEDDING_API_KEY") or None,
        llm_embedding_model=_raw("LLM_EMBEDDING_MODEL") or "text-embedding-3-small",
        parse_max_chars=parse_max_chars,
        parse_overlap=parse_overlap,
        parse_max_pages=_int_env("PARSE_MAX_PAGES", 300, minimum=1, maximum=5000),
        parse_timeout_seconds=_int_env("PARSE_TIMEOUT_SECONDS", 60, minimum=5, maximum=300),
        search_top_k=_int_env("SEARCH_TOP_K", 5, minimum=1, maximum=20),
        search_score_threshold=_float_env("SEARCH_SCORE_THRESHOLD", 0.2, minimum=0.0, maximum=1.0),
        allow_private_model_urls=(_raw("ALLOW_PRIVATE_MODEL_URLS") or "false").lower()
        in {"1", "true", "yes", "on"},
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return build_settings()
