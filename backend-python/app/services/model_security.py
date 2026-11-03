"""Model endpoint validation shared by tests and real outbound calls.

Only HTTPS endpoints are accepted by default. Private, loopback, link-local and
cloud-metadata destinations are rejected after DNS resolution. A single global
development switch may allow HTTP/private destinations for a local Ollama.
"""

from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from urllib.parse import urlsplit


@dataclass(frozen=True)
class ModelEndpointError(ValueError):
    code: str
    message: str


_METADATA_HOSTS = {
    "metadata",
    "metadata.google.internal",
    "instance-data",
    "instance-data.ec2.internal",
}


def validate_model_base_url(raw_url: str, *, allow_private: bool = False) -> str:
    value = (raw_url or "").strip()
    try:
        parsed = urlsplit(value)
    except ValueError as exc:
        raise ModelEndpointError("INVALID_URL", "模型地址格式不正确") from exc

    scheme = parsed.scheme.lower()
    if scheme != "https" and not (allow_private and scheme == "http"):
        message = "模型地址只允许 https（本地开发可显式允许 http）" if allow_private else "模型地址只允许 https"
        raise ModelEndpointError("INVALID_SCHEME", message)
    if not parsed.hostname:
        raise ModelEndpointError("INVALID_URL", "模型地址缺少主机名")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ModelEndpointError("INVALID_URL", "模型地址不能包含用户信息、查询参数或片段")

    host = parsed.hostname.lower().rstrip(".")
    if host in _METADATA_HOSTS or host == "localhost" or host.endswith(".localhost"):
        if not allow_private:
            raise ModelEndpointError("PRIVATE_ADDRESS", "禁止访问环回或云元数据地址")
    else:
        try:
            addresses = {
                item[4][0]
                for item in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)
            }
        except socket.gaierror as exc:
            raise ModelEndpointError("DNS_ERROR", "无法解析模型服务域名") from exc
        if not addresses:
            raise ModelEndpointError("DNS_ERROR", "无法解析模型服务域名")
        for address in addresses:
            ip = ipaddress.ip_address(address)
            if not ip.is_global and not allow_private:
                raise ModelEndpointError("PRIVATE_ADDRESS", "禁止访问私网、环回或云元数据地址")

    return value.rstrip("/")


__all__ = ["ModelEndpointError", "validate_model_base_url"]
