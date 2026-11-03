"""``POST /internal/search``: empty scope, bounds and Qdrant filtering."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.services import qdrant_store
from tests.conftest import request


def test_empty_kb_ids_returns_empty_hits_without_qdrant(monkeypatch):
    def _boom(*args, **kwargs):
        raise AssertionError("Qdrant must not be called when kb_ids is empty")

    monkeypatch.setattr(qdrant_store, "get_client", _boom)
    monkeypatch.setattr(qdrant_store, "search", _boom)

    response = request("POST", "/internal/search", json={"kb_ids": [], "query": "报销流程"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["hits"] == []
    assert body["query"] == "报销流程"
    assert body["embedding_provider"] == "local-hash-v1"


@pytest.mark.parametrize("top_k", [0, 21, -1])
def test_top_k_out_of_range_is_400(top_k):
    response = request(
        "POST", "/internal/search", json={"kb_ids": [1], "query": "报销", "top_k": top_k}
    )
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["trace_id"]


@pytest.mark.parametrize("threshold", [-0.1, 1.1])
def test_score_threshold_out_of_range_is_400(threshold):
    response = request(
        "POST",
        "/internal/search",
        json={"kb_ids": [1], "query": "报销", "score_threshold": threshold},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_search_filters_by_kb_ids_and_threshold(monkeypatch):
    captured: dict = {}

    class FakeClient:
        def query_points(self, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(
                points=[
                    SimpleNamespace(
                        score=0.81234567,
                        payload={"doc_id": 7, "kb_id": 3, "seq": 2, "content": "报销流程原文"},
                    )
                ]
            )

    class FakeSettings:
        qdrant_collection = "knowbase_chunks"

    monkeypatch.setattr(qdrant_store, "get_client", lambda settings=None: FakeClient())

    async def _collection_ready(settings=None):
        return None

    monkeypatch.setattr(qdrant_store, "ensure_collection", _collection_ready)

    async def _run():
        return await qdrant_store.search(
            kb_ids=[3, 4],
            vector=[0.1] * 1024,
            top_k=4,
            score_threshold=0.35,
            settings=FakeSettings(),
        )

    import asyncio

    result = asyncio.run(_run())
    assert result == [
        {"doc_id": 7, "kb_id": 3, "seq": 2, "score": 0.812346, "content": "报销流程原文"}
    ]
    assert captured["limit"] == 4
    assert captured["score_threshold"] == 0.35
    condition = captured["query_filter"].must[0]
    assert condition.key == "kb_id"
    assert condition.match.any == [3, 4]


def test_search_endpoint_uses_store(monkeypatch):
    async def _fake_search(**kwargs):
        assert kwargs["kb_ids"] == [1]
        assert kwargs["top_k"] == 3
        return [
            {"doc_id": 5, "kb_id": 1, "seq": 1, "score": 0.75, "content": "片段内容"}
        ]

    monkeypatch.setattr(qdrant_store, "search", _fake_search)
    response = request(
        "POST",
        "/internal/search",
        json={"kb_ids": [1], "query": "报销流程", "top_k": 3, "score_threshold": 0.3},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["hits"][0] == {
        "doc_id": 5,
        "kb_id": 1,
        "seq": 1,
        "score": 0.75,
        "content": "片段内容",
    }
