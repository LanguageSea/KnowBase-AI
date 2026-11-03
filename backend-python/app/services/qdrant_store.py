"""Qdrant access layer (docs/INTERNAL_API.md §1/§3/§4).

* collection auto-created with ``size=EMBEDDING_DIM`` and ``Distance.COSINE``
* payload indexes on ``doc_id`` and ``kb_id`` (existing ones are left alone)
* deterministic point ids: ``uuid5(NAMESPACE_URL, "knowbase:chunk:{doc_id}:{seq}")``
* ``upsert`` with ``wait=True`` so a subsequent count/search sees the points
"""

from __future__ import annotations

import functools
import time
import uuid
from threading import Lock
from typing import Any, Iterable, Sequence

from qdrant_client import QdrantClient, models
from starlette.concurrency import run_in_threadpool

from app.config import Settings, get_settings
from app.errors import (
    AppError,
    dependency_unavailable,
    embedding_dim_mismatch,
    short_code,
)

POINT_NAMESPACE = uuid.NAMESPACE_URL
POINT_ID_PREFIX = "knowbase:chunk"

_client: QdrantClient | None = None
_client_lock = Lock()
_collection_ready = False


def point_id(doc_id: int, seq: int) -> str:
    return str(uuid.uuid5(POINT_NAMESPACE, f"{POINT_ID_PREFIX}:{doc_id}:{seq}"))


def get_client(settings: Settings | None = None) -> QdrantClient:
    """Return the process-wide Qdrant client (created on first use)."""

    global _client
    settings = settings or get_settings()
    with _client_lock:
        if _client is None:
            _client = QdrantClient(
                url=settings.qdrant_url,
                api_key=settings.qdrant_api_key,
                timeout=10,
                prefer_grpc=False,
                check_compatibility=False,
            )
        return _client


def set_client(client: QdrantClient | None) -> None:
    """Test helper: inject or reset the client singleton."""

    global _client, _collection_ready
    with _client_lock:
        _client = client
        _collection_ready = False


def close_client() -> None:
    global _client, _collection_ready
    with _client_lock:
        client, _client = _client, None
        _collection_ready = False
    if client is not None:
        try:
            client.close()
        except Exception:  # pragma: no cover - best effort shutdown
            pass


def _vectors_config_dim(info: Any) -> int | None:
    try:
        vectors = info.config.params.vectors
    except AttributeError:  # pragma: no cover - defensive
        return None
    if isinstance(vectors, dict):
        first = next(iter(vectors.values()), None)
        return getattr(first, "size", None)
    return getattr(vectors, "size", None)


def _call(fn, *args, dependency: str = "qdrant", **kwargs):
    """Run a blocking Qdrant call off the event loop, mapping errors to 503."""

    try:
        return fn(*args, **kwargs)
    except AppError:
        raise
    except Exception as exc:
        raise dependency_unavailable(short_code(exc), dependency) from exc


async def _call_async(fn, *args, dependency: str = "qdrant", **kwargs):
    call = functools.partial(_call, fn, *args, dependency=dependency, **kwargs)
    return await run_in_threadpool(call)


def ensure_collection_sync(client: QdrantClient, settings: Settings) -> None:
    """Create the collection + payload indexes if they are missing."""

    exists = _call(client.collection_exists, settings.qdrant_collection)

    if not exists:
        _call(
            client.create_collection,
            collection_name=settings.qdrant_collection,
            vectors_config=models.VectorParams(
                size=settings.embedding_dim,
                distance=models.Distance.COSINE,
            ),
        )
    else:
        info = _call(client.get_collection, settings.qdrant_collection)
        actual_dim = _vectors_config_dim(info)
        if actual_dim is not None and actual_dim != settings.embedding_dim:
            raise embedding_dim_mismatch(actual_dim, settings.embedding_dim)

    info = _call(client.get_collection, settings.qdrant_collection)
    indexed = set(getattr(info, "payload_schema", {}) or {})
    for field_name in ("doc_id", "kb_id"):
        if field_name in indexed:
            continue
        try:
            _call(
                client.create_payload_index,
                collection_name=settings.qdrant_collection,
                field_name=field_name,
                field_schema=models.PayloadSchemaType.INTEGER,
            )
        except AppError:
            # Concurrent creator already added it — verify and continue.
            info = _call(client.get_collection, settings.qdrant_collection)
            if field_name not in set(getattr(info, "payload_schema", {}) or {}):
                raise


async def ensure_collection(settings: Settings | None = None) -> None:
    global _collection_ready
    settings = settings or get_settings()
    client = get_client(settings)
    if _collection_ready:
        return
    await _call_async(ensure_collection_sync, client, settings)
    _collection_ready = True


def mark_collection_stale() -> None:
    global _collection_ready
    _collection_ready = False


async def upsert_chunks(
    *,
    doc_id: int,
    kb_id: int,
    seqs: Sequence[int],
    contents: Sequence[str],
    vectors: Sequence[Sequence[float]],
    settings: Settings | None = None,
) -> int:
    settings = settings or get_settings()
    if not (len(seqs) == len(contents) == len(vectors)):
        raise internal_vector_error(len(seqs), len(contents), len(vectors))

    await ensure_collection(settings)
    client = get_client(settings)

    points = [
        models.PointStruct(
            id=point_id(doc_id, seq),
            vector=list(vector),
            payload={"doc_id": doc_id, "kb_id": kb_id, "seq": seq, "content": content},
        )
        for seq, content, vector in zip(seqs, contents, vectors)
    ]
    await _call_async(
        client.upsert,
        collection_name=settings.qdrant_collection,
        points=points,
        wait=True,
    )
    return len(points)


def internal_vector_error(seq_count: int, content_count: int, vector_count: int) -> AppError:
    from app.errors import internal_error

    return internal_error(
        f"embedding result count mismatch: {seq_count} chunks, {content_count} contents, {vector_count} vectors"
    )


async def search(
    *,
    kb_ids: Iterable[int],
    vector: Sequence[float],
    top_k: int,
    score_threshold: float,
    settings: Settings | None = None,
) -> list[dict[str, Any]]:
    settings = settings or get_settings()
    kb_id_list = list(kb_ids)
    if not kb_id_list:
        # Contract: authorised empty scope must never touch Qdrant.
        return []

    # An empty knowledge base may not have created the collection yet. Treat it
    # as zero hits instead of surfacing Qdrant NOT_FOUND as a dependency outage.
    await ensure_collection(settings)
    client = get_client(settings)
    query_filter = models.Filter(
        must=[
            models.FieldCondition(
                key="kb_id",
                match=models.MatchAny(any=kb_id_list),
            )
        ]
    )
    response = await _call_async(
        client.query_points,
        collection_name=settings.qdrant_collection,
        query=list(vector),
        query_filter=query_filter,
        limit=top_k,
        score_threshold=score_threshold,
        with_payload=True,
        with_vectors=False,
    )

    hits: list[dict[str, Any]] = []
    for point in getattr(response, "points", []) or []:
        payload = point.payload or {}
        hits.append(
            {
                "doc_id": int(payload.get("doc_id", 0)),
                "kb_id": int(payload.get("kb_id", 0)),
                "seq": int(payload.get("seq", 0)),
                "score": round(float(point.score), 6),
                "content": str(payload.get("content", "")),
            }
        )
    return hits


async def delete_document(doc_id: int, settings: Settings | None = None) -> int:
    """Delete every point for one document. Repeating the call returns 0."""

    settings = settings or get_settings()
    await ensure_collection(settings)
    client = get_client(settings)
    selector = models.Filter(
        must=[models.FieldCondition(key="doc_id", match=models.MatchValue(value=doc_id))]
    )
    count_result = await _call_async(
        client.count,
        collection_name=settings.qdrant_collection,
        count_filter=selector,
        exact=True,
    )
    deleted_count = int(getattr(count_result, "count", 0))
    if deleted_count:
        await _call_async(
            client.delete,
            collection_name=settings.qdrant_collection,
            points_selector=models.FilterSelector(filter=selector),
            wait=True,
        )
    return deleted_count


async def health_component(settings: Settings | None = None) -> dict[str, Any]:
    """Probe Qdrant; returns a health component dict (never raises)."""

    settings = settings or get_settings()
    started = time.perf_counter()
    try:
        client = get_client(settings)
        await run_in_threadpool(client.get_collections)
    except Exception as exc:
        return {
            "name": "qdrant",
            "status": "DOWN",
            "latency_ms": round((time.perf_counter() - started) * 1000, 1),
            "detail": short_code(exc),
        }
    return {
        "name": "qdrant",
        "status": "UP",
        "latency_ms": int(round((time.perf_counter() - started) * 1000)),
        "detail": None,
    }


__all__ = [
    "close_client",
    "delete_document",
    "ensure_collection",
    "ensure_collection_sync",
    "get_client",
    "health_component",
    "mark_collection_stale",
    "point_id",
    "search",
    "set_client",
    "upsert_chunks",
]
