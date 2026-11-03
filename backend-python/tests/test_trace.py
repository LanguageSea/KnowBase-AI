"""Trace-id propagation into structured logs (one JSON line per request)."""

from __future__ import annotations

import json
import logging

from app.logging_setup import JsonFormatter
from tests.conftest import request


def test_trace_id_from_header_reaches_request_log(caplog):
    caplog.set_level(logging.INFO)
    trace_id = "fedcba987654fedcba987654fedcba98"

    response = request(
        "POST",
        "/internal/search",
        json={},  # invalid on purpose: 400 still must be logged
        headers={"X-Trace-Id": trace_id},
    )
    assert response.status_code == 400

    request_records = [
        record
        for record in caplog.records
        if record.name == "app.http" and getattr(record, "path", None) == "/internal/search"
    ]
    assert request_records, "request log line missing"
    record = request_records[-1]
    assert record.trace_id == trace_id
    assert record.method == "POST"
    assert record.status == 400
    assert isinstance(record.duration_ms, float)

    payload = json.loads(JsonFormatter().format(record))
    assert payload["trace_id"] == trace_id
    assert payload["method"] == "POST"
    assert payload["path"] == "/internal/search"
    assert payload["status"] == 400
    for key in ("ts", "level", "logger", "trace_id", "method", "path", "status", "duration_ms"):
        assert key in payload


def test_generated_trace_id_is_logged(caplog):
    caplog.set_level(logging.INFO)

    response = request("GET", "/internal/health", token=None)
    header_trace = response.headers["X-Trace-Id"]

    records = [record for record in caplog.records if record.name == "app.http"]
    assert records
    assert records[-1].trace_id == header_trace
    assert records[-1].status == 401
