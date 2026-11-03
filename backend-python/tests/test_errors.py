"""Error-body contract: validation failures must be 400 with trace_id."""

from __future__ import annotations

from tests.conftest import request


def test_missing_field_is_400_validation_error():
    response = request("POST", "/internal/search", json={"query": "报销流程"})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["message"]
    assert "detail" not in body
    assert body["trace_id"]


def test_wrong_type_is_400_validation_error():
    response = request("POST", "/internal/search", json={"kb_ids": "not-a-list", "query": "x"})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_empty_query_is_400_validation_error():
    response = request("POST", "/internal/search", json={"kb_ids": [1], "query": "   "})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


def test_unknown_route_is_structured_404():
    response = request("GET", "/internal/does-not-exist")
    assert response.status_code == 404
    body = response.json()
    assert body["code"] == "NOT_FOUND"
    assert body["trace_id"]


def test_validation_error_reuses_incoming_trace_id():
    response = request(
        "POST",
        "/internal/search",
        json={},
        headers={"X-Trace-Id": "abc123def456abc123def456abc12345"},
    )
    assert response.status_code == 400
    assert response.json()["trace_id"] == "abc123def456abc123def456abc12345"
    assert response.headers["X-Trace-Id"] == "abc123def456abc123def456abc12345"


def test_invalid_trace_id_is_replaced_with_32_hex():
    response = request("POST", "/internal/search", json={}, headers={"X-Trace-Id": "bad id!"})
    trace_id = response.headers["X-Trace-Id"]
    assert len(trace_id) == 32
    assert all(char in "0123456789abcdef" for char in trace_id)
