"""Stage 4 T4.2: SSRF checks for model endpoints."""

from __future__ import annotations

import socket

import pytest

from app.services.model_security import ModelEndpointError, validate_model_base_url


@pytest.mark.parametrize(
    "url",
    [
        "https://127.0.0.1/v1",
        "https://10.0.0.8/v1",
        "https://172.16.0.8/v1",
        "https://192.168.1.8/v1",
        "https://169.254.169.254/latest/meta-data",
        "https://[::1]/v1",
        "https://[fd00::1]/v1",
    ],
)
def test_rejects_private_loopback_and_metadata_addresses(url):
    with pytest.raises(ModelEndpointError) as exc:
        validate_model_base_url(url)
    assert exc.value.code == "PRIVATE_ADDRESS"


def test_rejects_http_unless_explicitly_allowed():
    with pytest.raises(ModelEndpointError) as exc:
        validate_model_base_url("http://8.8.8.8/v1")
    assert exc.value.code == "INVALID_SCHEME"

    assert validate_model_base_url("http://127.0.0.1:11434/v1", allow_private=True) == (
        "http://127.0.0.1:11434/v1"
    )


def test_rejects_userinfo_and_query_parameters():
    with pytest.raises(ModelEndpointError) as exc:
        validate_model_base_url("https://user:pass@8.8.8.8/v1?target=internal")
    assert exc.value.code == "INVALID_URL"


def test_dns_failure_has_stable_code(monkeypatch):
    def _fail(*args, **kwargs):
        raise socket.gaierror("not found")

    monkeypatch.setattr(socket, "getaddrinfo", _fail)
    with pytest.raises(ModelEndpointError) as exc:
        validate_model_base_url("https://model.invalid/v1")
    assert exc.value.code == "DNS_ERROR"


def test_accepts_public_https_address():
    assert validate_model_base_url("https://8.8.8.8/v1/") == "https://8.8.8.8/v1"
