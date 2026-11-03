"""Stage 4 T4.2: model connection test error classification."""

from __future__ import annotations

import httpx
import pytest

from app.routers import internal
from tests.conftest import run


class FakeResponse:
    def __init__(self, status_code: int):
        self.status_code = status_code


def _install_client(monkeypatch, action):
    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def get(self, *args, **kwargs):
            return action()

    monkeypatch.setattr(internal.httpx, "AsyncClient", lambda **kwargs: FakeClient())
    monkeypatch.setattr(
        internal,
        "validate_model_base_url",
        lambda raw_url, allow_private=False: raw_url.rstrip("/"),
    )


def _post_model_test():
    payload = internal.ModelRuntimeConfig(
        provider="deepseek",
        model_type="CHAT",
        model_name="deepseek-chat",
        base_url="https://example.test/v1",
        api_key="secret",
        params={},
    )
    return run(internal.model_test(payload))


def test_model_test_distinguishes_success_and_auth_failure(monkeypatch):
    _install_client(monkeypatch, lambda: FakeResponse(200))
    success = _post_model_test()
    assert success.success is True
    assert success.error_type is None

    _install_client(monkeypatch, lambda: FakeResponse(401))
    unauthorized = _post_model_test()
    assert unauthorized.success is False
    assert unauthorized.error_type == "AUTH_ERROR"


def test_model_test_classifies_timeout_and_dns(monkeypatch):
    def _timeout():
        raise httpx.ReadTimeout("timed out")

    _install_client(monkeypatch, _timeout)
    timeout = _post_model_test()
    assert timeout.error_type == "TIMEOUT"

    def _dns_error():
        raise httpx.ConnectError("getaddrinfo failed")

    _install_client(monkeypatch, _dns_error)
    dns = _post_model_test()
    assert dns.error_type == "DNS_ERROR"


def test_model_test_rejects_private_address_before_network():
    response = run(
        internal.model_test(
            internal.ModelRuntimeConfig(
                provider="local",
                model_type="CHAT",
                model_name="model",
                base_url="https://169.254.169.254/latest",
                api_key=None,
                params={},
            )
        )
    )
    assert response.success is False
    assert response.error_type == "PRIVATE_ADDRESS"
