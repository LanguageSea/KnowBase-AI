package com.knowbase.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import com.knowbase.config.SecurityProperties;
import org.springframework.stereotype.Component;

/**
 * Validates model endpoints before saving/testing them and again before real calls.
 *
 * <p>The host is resolved to every address it advertises. One private or metadata
 * address is enough to reject the endpoint, preventing public DNS names from
 * being used as an SSRF bypass.
 */
@Component
public class SsrfGuard {

    private final boolean allowPrivate;

    public SsrfGuard(SecurityProperties properties) {
        this.allowPrivate = properties.privateModelUrlsAllowed();
    }

    public URI validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw invalid("模型地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowPrivate && "http".equals(scheme))) {
            throw new ModelEndpointException("INVALID_SCHEME",
                    allowPrivate ? "模型地址只允许 https（本地开发可显式允许 http）" : "模型地址只允许 https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("模型地址缺少主机名");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid("模型地址不能包含用户信息、查询参数或片段");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw invalid("模型地址端口不合法");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            if (!allowPrivate) {
                throw new ModelEndpointException("PRIVATE_ADDRESS", "禁止访问环回地址");
            }
            return normalize(uri);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new ModelEndpointException("DNS_ERROR", "无法解析模型服务域名");
        }
        if (addresses.length == 0) {
            throw new ModelEndpointException("DNS_ERROR", "无法解析模型服务域名");
        }
        for (InetAddress address : addresses) {
            if (isForbidden(address) && !allowPrivate) {
                throw new ModelEndpointException("PRIVATE_ADDRESS", "禁止访问私网、环回或云元数据地址");
            }
        }
        return normalize(uri);
    }

    private static URI normalize(URI uri) {
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    private static ModelEndpointException invalid(String message) {
        return new ModelEndpointException("INVALID_URL", message);
    }

    static boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // Carrier-grade NAT 100.64/10, benchmarking 198.18/15 and 0/8.
            if (first == 0 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))) {
                return true;
            }
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 (unique local) is not consistently covered by isSiteLocalAddress.
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
