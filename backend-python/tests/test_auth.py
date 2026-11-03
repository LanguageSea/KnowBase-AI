"""Authentication contract: 401 without/with a wrong token, 200 with the token."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.services import qdrant_store
from tests.conftest import request


@pytest.fixture
def qdrant_up(monkeypatch):
    async def _component(settings=None):
        return {"name": "qdrant", "status": "UP", "latency_ms": 1, "detail": None}

    monkeypatch.setattr(qdrant_store, "health_component", _component)


def test_health_without_token_is_401(qdrant_up):
    response = request("GET", "/internal/health", token=None)
    assert response.status_code == 401
    body = response.json()
    assert body["code"] == "UNAUTHORIZED"
    assert "trace_id" in body
    assert "test-internal-token" not in response.text


def test_health_with_wrong_token_is_401(qdrant_up):
    response = request("GET", "/internal/health", token="definitely-wrong-token")
    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"


def test_parse_without_token_is_401(tmp_path):
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(tmp_path / "a.txt"),
            "file_name": "a.txt",
            "ext": "txt",
        },
        token=None,
    )
    assert response.status_code == 401
    assert response.json()["code"] == "UNAUTHORIZED"


def test_health_with_token_is_200(qdrant_up):
    response = request("GET", "/internal/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "ai-service"
    components = {component["name"]: component for component in body["components"]}
    assert components["qdrant"]["status"] == "UP"
    assert components["qdrant"]["detail"] is None
    assert components["embedding"]["provider"] == "local-hash-v1"


def test_health_degraded_returns_503(monkeypatch):
    async def _component(settings=None):
        return {"name": "qdrant", "status": "DOWN", "latency_ms": 0, "detail": "CONNECTION_REFUSED"}

    monkeypatch.setattr(qdrant_store, "health_component", _component)
    response = request("GET", "/internal/health")
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "DEGRADED"
    assert body["components"][0]["detail"] == "CONNECTION_REFUSED"


def test_trace_header_is_echoed_on_unauthorized():
    response = request("GET", "/internal/health", token=None, headers={"X-Trace-Id": "trace-abc-123456"})
    assert response.status_code == 401
    assert response.headers["X-Trace-Id"] == "trace-abc-123456"
    assert response.json()["trace_id"] == "trace-abc-123456"
