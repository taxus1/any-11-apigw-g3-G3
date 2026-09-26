package com.apigw.proxy.accesslog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;

/**
 * 客户端来源地址的统一取值口。<b>鉴权、限流、访问流水必须共用这一个类</b>，
 * 不许各写一套，否则会出现「流水里的来源和限流计数/鉴权白名单对不上」的账。
 *
 * 取值顺序（内部约定，从前到后取第一个合法值，都没有就返回 null）：
 * <ol>
 *   <li>{@code X-Forwarded-For} 链表里<b>最左</b>一个非空、非 {@code unknown}、
 *       形状像 IP 的地址——最左是调用链里最初的那个客户端（LB 会往右追加自己看到的对端）；</li>
 *   <li>{@code X-Real-IP} 头（单个地址，要求形状像 IP）；</li>
 *   <li>TCP 连接对端地址 {@code request.getRemoteAddress()}（传输层真值，头可伪造它伪造不了）。</li>
 * </ol>
 *
 * 安全前提：XFF 是可伪造的。这套口径成立的前提是网关只部署在受控 LB/代理之后，
 * 由 LB 重写/清洗 XFF；将来若要在不可信入口直接暴露，需在此类加「可信代理跳数」逻辑，
 * 从右往左跳过可信节点后再取，而不是裸信最左值。口径升级也只改这一个类。
 */
public final class ClientIpResolver {

    /** XFF 链表里的占位垃圾值（Nginx 缺地址时会写 unknown），直接跳过。 */
    private static final String UNKNOWN = "unknown";

    private static final Pattern IPV4 = Pattern.compile(
            "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_SHAPE = Pattern.compile("[0-9A-Fa-f:%.]+");

    private ClientIpResolver() {
    }

    public static String resolve(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        String ip = fromForwardedFor(headers.getFirst("X-Forwarded-For"));
        if (ip != null) {
            return ip;
        }
        ip = asIpOrNull(headers.getFirst("X-Real-IP"));
        if (ip != null) {
            return ip;
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? null : remote.getAddress().getHostAddress();
    }

    /** 取 XFF 最左合法地址；整条链都不合法返回 null，由调用方继续往后找。 */
    static String fromForwardedFor(String xff) {
        if (xff == null || xff.isBlank()) {
            return null;
        }
        for (String token : xff.split(",")) {
            String ip = asIpOrNull(token);
            if (ip != null) {
                return ip;
            }
        }
        return null;
    }

    /**
     * 只做「形状」校验，不做 DNS 反查（InetAddress.getByName 会触发解析，绝不能在请求路径上用）。
     * 空串/unknown/域名/带空格乱码一律视为不合法。
     */
    static String asIpOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty() || UNKNOWN.equalsIgnoreCase(v)) {
            return null;
        }
        if (IPV4.matcher(v).matches() && octetsInRange(v)) {
            return v;
        }
        // IPv6（含 zone/映射形态）：必须含冒号且只由合法字符构成，挡掉域名和脏值
        if (v.indexOf(':') >= 0 && IPV6_SHAPE.matcher(v).matches()) {
            return v;
        }
        return null;
    }

    private static boolean octetsInRange(String ipv4) {
        for (String part : ipv4.split("\\.")) {
            int n = Integer.parseInt(part);
            if (n > 255) {
                return false;
            }
        }
        return true;
    }
}
