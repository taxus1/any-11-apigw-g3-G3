package com.apigw.proxy.accesslog;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源地址取值口径测试。这个口径是鉴权、限流、流水三家的共同约定，
 * 用例即约定文档：改取法必须同时认这些断言。
 */
class ClientIpResolverTest {

    @Test
    void takesLeftmostValidOfForwardedFor() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1, 10.1.1.1")
                .build();
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void skipsUnknownAndGarbageTokens_inForwardedFor() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "unknown, , not-an-ip, 198.51.100.9")
                .build();
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("198.51.100.9");
    }

    @Test
    void fallsBackToRealIp_whenXffMissingOrAllInvalid() {
        MockServerHttpRequest noXff = MockServerHttpRequest.get("/x")
                .header("X-Real-IP", "192.0.2.50").build();
        assertThat(ClientIpResolver.resolve(noXff)).isEqualTo("192.0.2.50");

        MockServerHttpRequest junkXff = MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "evil.example.com")
                .header("X-Real-IP", "192.0.2.51").build();
        assertThat(ClientIpResolver.resolve(junkXff)).isEqualTo("192.0.2.51");
    }

    @Test
    void fallsBackToSocketRemoteAddress_whenNoHeaders() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/x")
                .remoteAddress(new java.net.InetSocketAddress("127.0.0.1", 54321)).build();
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("127.0.0.1");
    }

    @Test
    void spoofedXffWithPrivateOrV6IsAcceptedAsShapedLiteral() {
        // 形状合法即采用（信任边界由部署侧 LB 保证，类的 javadoc 已写明前提）
        MockServerHttpRequest v6 = MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "2001:db8::1").build();
        assertThat(ClientIpResolver.resolve(v6)).isEqualTo("2001:db8::1");
    }

    @Test
    void rejectsOutOfRangeOctetsAndDomains() {
        assertThat(ClientIpResolver.asIpOrNull("999.1.1.1")).isNull();
        assertThat(ClientIpResolver.asIpOrNull("1.2.3")).isNull();
        assertThat(ClientIpResolver.asIpOrNull("example.com")).isNull();
        assertThat(ClientIpResolver.asIpOrNull("")).isNull();
        assertThat(ClientIpResolver.asIpOrNull("unknown")).isNull();
        assertThat(ClientIpResolver.asIpOrNull("10.0.0.255")).isEqualTo("10.0.0.255");
    }
}
