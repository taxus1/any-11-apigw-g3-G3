package com.apigw.proxy.accesslog;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求编号口径：调用方带了合法追踪号原样沿用；没带/非法由网关生成 32 位号。
 */
class RequestNoResolverTest {

    @Test
    void adoptsCallerRequestId_whenValid() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/x")
                .header("X-Request-Id", "trace-abc_123.XYZ").build();
        String no = RequestNoResolver.resolve(req, "X-Request-Id");
        assertThat(no).isEqualTo("trace-abc_123.XYZ");
        assertThat(RequestNoResolver.isAcceptable(no)).isTrue();
    }

    @Test
    void generates_whenAbsent() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/x").build();
        String no = RequestNoResolver.resolve(req, "X-Request-Id");
        assertThat(no).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void flagsIllegalValues_commaBlankOrTooLong() {
        MockServerHttpRequest comma = MockServerHttpRequest.get("/x")
                .header("X-Request-Id", "a,b").build();
        String no = RequestNoResolver.resolve(comma, "X-Request-Id");
        assertThat(RequestNoResolver.isAcceptable(no)).isFalse();

        MockServerHttpRequest blank = MockServerHttpRequest.get("/x")
                .header("X-Request-Id", "   ").build();
        assertThat(RequestNoResolver.isAcceptable(RequestNoResolver.resolve(blank, "X-Request-Id")))
                .isTrue(); // 空白视同没带 → 走生成

        String tooLong = "x".repeat(65);
        assertThat(RequestNoResolver.isAcceptable(tooLong)).isFalse();
    }

    @Test
    void generatedIdsAreUnique() {
        assertThat(RequestNoResolver.generate()).isNotEqualTo(RequestNoResolver.generate());
    }
}
