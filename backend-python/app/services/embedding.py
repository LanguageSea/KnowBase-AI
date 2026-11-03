"""Embedding providers.

Two implementations, selected purely by configuration (docs/INTERNAL_API.md §5):

* ``OpenAICompatibleEmbedding`` — used when ``LLM_EMBEDDING_BASE_URL`` **and**
  ``LLM_EMBEDDING_API_KEY`` are both set. A real failure (timeout/401/5xx) is
  surfaced as 503 ``DEPENDENCY_UNAVAILABLE``; there is **no** silent fallback.
* ``LocalHashEmbedding`` (``local-hash-v1``) — deterministic hashing of
  character bigrams, single CJK characters and latin words into
  ``EMBEDDING_DIM`` buckets with L2 normalisation. Same text -> same vector,
  but no semantic generalisation (documented limitation).
"""

from __future__ import annotations

import hashlib
import math
import re
from abc import ABC, abstractmethod
from functools import lru_cache
from typing import Iterable

import httpx

from app.config import Settings, get_settings
from app.errors import dependency_unavailable, embedding_dim_mismatch, short_code
from app.services.model_security import ModelEndpointError, validate_model_base_url

EMBEDDING_TIMEOUT_SECONDS = 30.0
EMBEDDING_BATCH_SIZE = 64

_CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
_WORD_RE = re.compile(r"[a-z0-9]+")

# Relative weights for the hashing bag-of-tokens model.
_WEIGHT_WORD = 1.0
_WEIGHT_BIGRAM = 1.0
_WEIGHT_CJK_CHAR = 0.5


class EmbeddingProvider(ABC):
    """Interface shared by real and fallback embedding backends."""

    name: str
    dim: int

    @abstractmethod
    async def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one vector per input text, preserving input order."""


class LocalHashEmbedding(EmbeddingProvider):
    """Deterministic hashing embedding (no external calls, offline-friendly)."""

    name = "local-hash-v1"

    def __init__(self, dim: int = 1024) -> None:
        if dim < 8:
            raise ValueError("dim must be >= 8")
        self.dim = dim

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self.embed_one(text) for text in texts]

    def embed_one(self, text: str) -> list[float]:
        vector = [0.0] * self.dim
        for token, weight in iter_tokens(text):
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            bucket = int.from_bytes(digest, "big") % self.dim
            vector[bucket] += weight
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0.0:
            # Empty/whitespace text: keep a unit vector so cosine stays defined.
            vector[0] = 1.0
            return vector
        return [value / norm for value in vector]


def iter_tokens(text: str) -> Iterable[tuple[str, float]]:
    """Yield ``(token, weight)`` pairs for the hashing model."""

    normalized = " ".join(text.split()).lower()

    for word in _WORD_RE.findall(normalized):
        yield f"w:{word}", _WEIGHT_WORD

    for char in normalized:
        if _CJK_RE.match(char):
            yield f"c:{char}", _WEIGHT_CJK_CHAR

    for index in range(len(normalized) - 1):
        pair = normalized[index : index + 2]
        if " " in pair:
            continue
        yield f"b:{pair}", _WEIGHT_BIGRAM


class OpenAICompatibleEmbedding(EmbeddingProvider):
    """``POST {base_url}/embeddings`` against any OpenAI-compatible endpoint."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        dim: int,
        *,
        allow_private: bool = False,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.dim = dim
        self.allow_private = allow_private
        self.name = f"openai-compatible:{model}"

    def _endpoint(self) -> str:
        if self.base_url.endswith("/embeddings"):
            return self.base_url
        return f"{self.base_url}/embeddings"

    def _validated_endpoint(self) -> str:
        try:
            base_url = validate_model_base_url(
                self.base_url, allow_private=self.allow_private
            )
        except ModelEndpointError as exc:
            raise dependency_unavailable(exc.code, "embedding") from exc
        if base_url.endswith("/embeddings"):
            return base_url
        return f"{base_url}/embeddings"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        vectors: list[list[float]] = []
        async with httpx.AsyncClient(timeout=EMBEDDING_TIMEOUT_SECONDS) as client:
            for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
                batch = texts[start : start + EMBEDDING_BATCH_SIZE]
                try:
                    response = await client.post(
                        self._validated_endpoint(),
                        headers={
                            "Authorization": f"Bearer {self.api_key}",
                            "Content-Type": "application/json",
                        },
                        json={"model": self.model, "input": batch},
                    )
                except Exception as exc:  # network/timeout — never fall back
                    raise dependency_unavailable(short_code(exc), "embedding") from exc

                if response.status_code >= 400:
                    raise dependency_unavailable(f"HTTP_{response.status_code}", "embedding")

                try:
                    payload = response.json()
                    data = sorted(payload["data"], key=lambda item: item.get("index", 0))
                    batch_vectors = [list(item["embedding"]) for item in data]
                except (KeyError, TypeError, ValueError) as exc:
                    raise dependency_unavailable("INVALID_RESPONSE", "embedding") from exc

                if len(batch_vectors) != len(batch):
                    raise dependency_unavailable("INVALID_RESPONSE", "embedding")
                for vector in batch_vectors:
                    if len(vector) != self.dim:
                        raise embedding_dim_mismatch(len(vector), self.dim)
                vectors.extend(batch_vectors)
        return vectors


def build_embedding_provider(
    settings: Settings | None = None,
    runtime_config: dict | None = None,
) -> EmbeddingProvider:
    settings = settings or get_settings()
    if runtime_config:
        base_url = str(runtime_config.get("base_url") or "")
        model = str(runtime_config.get("model_name") or "")
        api_key = str(runtime_config.get("api_key") or "")
        if base_url and model:
            return OpenAICompatibleEmbedding(
                base_url=base_url,
                api_key=api_key,
                model=model,
                dim=settings.embedding_dim,
                allow_private=getattr(settings, "allow_private_model_urls", False),
            )
    if settings.openai_embedding_configured:
        return OpenAICompatibleEmbedding(
            base_url=settings.llm_embedding_base_url or "",
            api_key=settings.llm_embedding_api_key or "",
            model=settings.llm_embedding_model,
            dim=settings.embedding_dim,
            allow_private=getattr(settings, "allow_private_model_urls", False),
        )
    return LocalHashEmbedding(dim=settings.embedding_dim)


@lru_cache(maxsize=1)
def get_embedding_provider() -> EmbeddingProvider:
    return build_embedding_provider()


def reset_embedding_provider_cache() -> None:
    get_embedding_provider.cache_clear()


__all__ = [
    "EmbeddingProvider",
    "LocalHashEmbedding",
    "OpenAICompatibleEmbedding",
    "build_embedding_provider",
    "get_embedding_provider",
    "iter_tokens",
    "reset_embedding_provider_cache",
]
