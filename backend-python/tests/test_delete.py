"""``POST /internal/delete`` and Qdrant point deletion."""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

from app.services import qdrant_store
from tests.conftest import request


def test_delete_endpoint_uses_store(monkeypatch):
    async def _fake_delete(doc_id):
        assert doc_id == 42
        return 3

    monkeypatch.setattr(qdrant_store, "delete_document", _fake_delete)
    response = request("POST", "/internal/delete", json={"doc_id": 42})
    assert response.status_code == 200, response.text
    assert response.json() == {"doc_id": 42, "deleted_count": 3}


def test_delete_document_filters_by_doc_id_and_is_idempotent(monkeypatch):
    calls: dict = {}

    class FakeClient:
        def count(self, **kwargs):
            calls["count"] = kwargs
            return SimpleNamespace(count=2)

        def delete(self, **kwargs):
            calls["delete"] = kwargs
            return SimpleNamespace()

    class FakeSettings:
        qdrant_collection = "knowbase_chunks"

    monkeypatch.setattr(qdrant_store, "get_client", lambda settings=None: FakeClient())

    async def _fake_ensure(settings):
        return None

    monkeypatch.setattr(qdrant_store, "ensure_collection", _fake_ensure)
    result = asyncio.run(qdrant_store.delete_document(7, settings=FakeSettings()))
    assert result == 2
    condition = calls["count"]["count_filter"].must[0]
    assert condition.key == "doc_id"
    assert condition.match.value == 7
    assert calls["delete"]["points_selector"].filter.must[0].match.value == 7


def test_delete_rejects_non_positive_doc_id():
    response = request("POST", "/internal/delete", json={"doc_id": 0})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
