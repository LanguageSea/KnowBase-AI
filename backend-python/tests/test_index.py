"""``POST /internal/index`` against a fake Qdrant client (no network)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest

from app.services import qdrant_store
from tests.conftest import request


class FakeQdrant:
    def __init__(self, dim: int = 1024, exists: bool = True) -> None:
        self.dim = dim
        self.exists = exists
        self.created = False
        self.upserted: list = []
        self.indexes: list[str] = []
        self.query_kwargs: dict | None = None

    def collection_exists(self, name: str) -> bool:
        return self.exists

    def create_collection(self, collection_name: str, vectors_config) -> None:
        self.created = True
        self.exists = True

    def get_collection(self, name: str):
        return SimpleNamespace(
            config=SimpleNamespace(params=SimpleNamespace(vectors=SimpleNamespace(size=self.dim))),
            payload_schema={"doc_id": object(), "kb_id": object()},
        )

    def create_payload_index(self, collection_name: str, field_name: str, field_schema) -> None:
        self.indexes.append(field_name)

    def upsert(self, collection_name: str, points, wait: bool = True):
        self.upserted.extend(points)
        return SimpleNamespace(status="completed")

    def query_points(self, **kwargs):
        self.query_kwargs = kwargs
        return SimpleNamespace(points=[])


@pytest.fixture
def fake_qdrant():
    fake = FakeQdrant()
    qdrant_store.set_client(fake)
    yield fake
    qdrant_store.set_client(None)


def test_index_upserts_deterministic_point_ids(fake_qdrant):
    response = request(
        "POST",
        "/internal/index",
        json={
            "doc_id": 999001,
            "kb_id": 42,
            "chunks": [
                {"seq": 0, "content": "第一段：报销需要发票。"},
                {"seq": 1, "content": "第二段：部门负责人审批。"},
            ],
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body == {
        "doc_id": 999001,
        "vector_count": 2,
        "collection": "knowbase_chunks",
        "embedding_provider": "local-hash-v1",
        "dim": 1024,
    }

    points = fake_qdrant.upserted
    assert len(points) == 2
    expected_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "knowbase:chunk:999001:0"))
    assert points[0].id == expected_id
    assert points[0].payload["doc_id"] == 999001
    assert points[0].payload["kb_id"] == 42
    assert points[0].payload["seq"] == 0
    assert points[0].payload["content"] == "第一段：报销需要发票。"
    assert len(points[0].vector) == 1024


def test_index_creates_missing_collection(fake_qdrant):
    fake_qdrant.exists = False
    response = request(
        "POST",
        "/internal/index",
        json={"doc_id": 1, "kb_id": 1, "chunks": [{"seq": 0, "content": "内容"}]},
    )
    assert response.status_code == 200
    assert fake_qdrant.created is True


def test_index_rejects_empty_chunks():
    response = request("POST", "/internal/index", json={"doc_id": 1, "kb_id": 1, "chunks": []})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_index_rejects_non_continuous_seq(fake_qdrant):
    response = request(
        "POST",
        "/internal/index",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "chunks": [{"seq": 0, "content": "a"}, {"seq": 2, "content": "b"}],
        },
    )
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert fake_qdrant.upserted == []


def test_index_rejects_blank_content(fake_qdrant):
    response = request(
        "POST",
        "/internal/index",
        json={"doc_id": 1, "kb_id": 1, "chunks": [{"seq": 0, "content": "   "}]},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_index_reports_dimension_mismatch(fake_qdrant):
    fake_qdrant.dim = 512
    response = request(
        "POST",
        "/internal/index",
        json={"doc_id": 1, "kb_id": 1, "chunks": [{"seq": 0, "content": "内容"}]},
    )
    assert response.status_code == 500
    assert response.json()["code"] == "EMBEDDING_DIM_MISMATCH"
    assert "512" in response.json()["message"]
    assert "1024" in response.json()["message"]
