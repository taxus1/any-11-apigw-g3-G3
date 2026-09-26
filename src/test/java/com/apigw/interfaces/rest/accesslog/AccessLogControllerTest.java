package com.apigw.interfaces.rest.accesslog;

import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.domain.accesslog.AccessLogSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 翻流水接口端到端：真实容器 + H2，从「写进去」到「按条件翻出来」走一遍。
 * 重点核对分页四元组（pageNum/pageSize/total/totalPages）与实际数据对得上、
 * 条件可组合、参数非法给业务失败而不是 500。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 用内联属性钉死 H2：优先级高于环境变量，避免容器里注入的 DB_URL 指向真实 MySQL 把测试数据源顶掉
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:apigw-it;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/access_log.h2.sql",
        "logging.level.com.apigw=warn"
})
class AccessLogControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient web;

    @Autowired
    AccessLogSink sink;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM gw_access_log");
    }

    private void seed(AccessLogRecord... rows) throws Exception {
        sink.saveBatch(List.of(rows));
    }

    private AccessLogRecord row(String no, String route, int status, String t) {
        return new AccessLogRecord(no, route, "app-" + no, "3.3.3.3", "POST",
                "/x/" + no, status, 9, LocalDateTime.parse(t));
    }

    @Test
    void pageByTimeRouteAndStatus_paginationNumbersMatch() throws Exception {
        seed(
                row("1", "order", 200, "2026-09-26T10:00:00"),
                row("2", "order", 502, "2026-09-26T10:01:00"),
                row("3", "order", 502, "2026-09-26T10:02:00"),
                row("4", "pay", 200, "2026-09-26T10:03:00"),
                row("5", "order", 0, "2026-09-26T10:04:00"));

        // 时间段 + 路由 + 状态码：order 的 502 恰好 2 条
        web.get().uri("/api/gateway/access-logs"
                        + "?startTime=2026-09-26T10:00:00&endTime=2026-09-26T11:00:00"
                        + "&routeNo=order&statusCode=502&pageSize=20")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.pageNum").isEqualTo(1)
                .jsonPath("$.data.pageSize").isEqualTo(20)
                .jsonPath("$.data.totalPages").isEqualTo(1)
                .jsonPath("$.data.content[*].requestNo").value(v ->
                        org.assertj.core.api.Assertions.assertThat((List<String>) v)
                                .containsExactly("3", "2")); // 倒序
    }

    @Test
    void zeroStatus_isQueryable() throws Exception {
        seed(row("9", "order", 0, "2026-09-26T10:04:00"));
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00"
                        + "&endTime=2026-09-26T11:00:00&statusCode=0")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.content[0].statusCode").isEqualTo(0);
    }

    @Test
    void missingStart_isBusinessFailNot500() {
        web.get().uri("/api/gateway/access-logs?routeNo=order")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isEqualTo("startTime 必填，格式 yyyy-MM-ddTHH:mm:ss");
    }

    @Test
    void badTimeFormat_isBusinessFail() {
        web.get().uri("/api/gateway/access-logs?startTime=2026/09/26")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(1);
    }

    @Test
    void pageSizeCapped_andEmptyWindowReturnsEmptyPage() {
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00"
                        + "&endTime=2026-09-26T11:00:00&pageNum=1&pageSize=99999")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.pageSize").isEqualTo(200)
                .jsonPath("$.data.total").isEqualTo(0)
                .jsonPath("$.data.totalPages").isEqualTo(0)
                .jsonPath("$.data.content").isArray();
    }

    @SuppressWarnings("unchecked")
    @Test
    void rowsCarryAllColumns() throws Exception {
        seed(row("7", "order", 200, "2026-09-26T10:10:00"));
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00"
                        + "&endTime=2026-09-26T11:00:00&requestNo=7")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.content[0]").value(o -> {
                    Map<String, Object> m = (Map<String, Object>) o;
                    org.assertj.core.api.Assertions.assertThat(m).containsKeys(
                            "id", "requestNo", "routeNo", "appNo", "clientIp",
                            "method", "path", "statusCode", "elapsedMs", "occurTime");
                    org.assertj.core.api.Assertions.assertThat(m.get("appNo")).isEqualTo("app-7");
                    org.assertj.core.api.Assertions.assertThat(m.get("clientIp")).isEqualTo("3.3.3.3");
                });
    }
}
