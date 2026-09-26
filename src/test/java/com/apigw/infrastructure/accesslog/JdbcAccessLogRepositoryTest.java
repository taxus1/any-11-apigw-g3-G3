package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.interfaces.rest.accesslog.AccessLogVO;
import com.apigw.proxy.accesslog.AccessLogProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 落库 + 分页查询的仓储测试（H2 MySQL 模式，建表脚本与生产 DDL 同构）。
 *
 * 重点：
 * - 多行单语句整批落，行数、字段值正确；
 * - 时间/路由/状态码条件可组合，总数、总页数、当前页条数严格对得上；
 * - 排序为时间倒序（同毫秒内 id 倒序兜底），翻页不重不漏；
 * - statusCode=0 的「无状态码失败」与真实码可区分、可单独筛；
 * - 时间窗必填、跨度封顶、pageSize 封顶。
 */
class JdbcAccessLogRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcAccessLogSink sink;
    private JdbcAccessLogQueryRepository queryRepo;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("accesslog-" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                .addScript("classpath:db/access_log.h2.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        AccessLogProperties props = AccessLogProperties.defaults();
        sink = new JdbcAccessLogSink(jdbc, props);
        queryRepo = new JdbcAccessLogQueryRepository(jdbc, props);
    }

    private AccessLogRecord row(String no, String route, int status, String minute) {
        return new AccessLogRecord(no, route, "app", "2.2.2.2", "GET", "/p/" + no,
                status, 5, LocalDateTime.parse("2026-09-26T" + minute));
    }

    @Test
    void batchInsert_isAtomicMultiRowAndFieldsRoundTrip() {
        sink.saveBatch(List.of(
                row("n1", "rA", 200, "10:00:00"),
                row("n2", null, AccessLogRecord.NO_STATUS, "10:00:01")));

        List<AccessLogVO> all = jdbc.query(
                "SELECT * FROM gw_access_log ORDER BY id",
                (rs, i) -> new AccessLogVO(rs.getLong("id"), rs.getString("request_no"),
                        rs.getString("route_no"), rs.getString("app_no"), rs.getString("client_ip"),
                        rs.getString("method"), rs.getString("path"), rs.getInt("status_code"),
                        rs.getLong("elapsed_ms"), rs.getTimestamp("occur_time").toLocalDateTime()));
        assertThat(all).hasSize(2);
        assertThat(all.get(0).routeNo()).isEqualTo("rA");
        assertThat(all.get(1).routeNo()).isNull();
        assertThat(all.get(1).statusCode()).isZero();
        assertThat(all.get(0).clientIp()).isEqualTo("2.2.2.2");
    }

    @Test
    void combinedFiltersAndPagination_numbersMatchExactly() {
        List<AccessLogRecord> rows = new ArrayList<>();
        // rA: 200 x3, 502 x2；rB: 200 x2；另有一笔无状态码
        rows.add(row("a1", "rA", 200, "10:00:00"));
        rows.add(row("a2", "rA", 200, "10:01:00"));
        rows.add(row("a3", "rA", 200, "10:02:00"));
        rows.add(row("a4", "rA", 502, "10:03:00"));
        rows.add(row("a5", "rA", 502, "10:04:00"));
        rows.add(row("b1", "rB", 200, "10:05:00"));
        rows.add(row("b2", "rB", 200, "10:06:00"));
        rows.add(row("z0", "rA", 0, "10:07:00"));
        sink.saveBatch(rows);

        var q = new AccessLogQuery(
                LocalDateTime.parse("2026-09-26T09:00:00"),
                LocalDateTime.parse("2026-09-26T11:00:00"),
                null, null, null, 1, 20);
        assertThat(queryRepo.page(q).total()).isEqualTo(8);

        // 路由 + 状态码组合：rA 的 200 恰好 3 条
        var ra200 = new AccessLogQuery(q.startTime(), q.endTime(), "rA", 200, null, 1, 20);
        PageResult<AccessLogVO> p = queryRepo.page(ra200);
        assertThat(p.total()).isEqualTo(3);
        assertThat(p.totalPages()).isEqualTo(1);
        assertThat(p.content()).extracting(AccessLogVO::requestNo)
                .containsExactly("a3", "a2", "a1"); // 时间倒序

        // 分页：pageSize=2 → 6 条共 3 页，第 3 页 2 条（a1、a2）
        var paged = new AccessLogQuery(q.startTime(), q.endTime(), "rA", null, null, 1, 2);
        PageResult<AccessLogVO> page1 = queryRepo.page(paged);
        assertThat(page1.total()).isEqualTo(6);
        assertThat(page1.totalPages()).isEqualTo(3);
        assertThat(page1.content()).hasSize(2);
        var page3 = new AccessLogQuery(q.startTime(), q.endTime(), "rA", null, null, 3, 2);
        assertThat(queryRepo.page(page3).content()).extracting(AccessLogVO::requestNo)
                .containsExactly("a2", "a1");
        // 三页并起来不重不漏
        List<String> allNos = new ArrayList<>();
        for (int n = 1; n <= 3; n++) {
            queryRepo.page(new AccessLogQuery(q.startTime(), q.endTime(), "rA", null, null, n, 2))
                    .content().forEach(v -> allNos.add(v.requestNo()));
        }
        assertThat(allNos).containsExactlyInAnyOrder("a1", "a2", "a3", "a4", "a5", "z0");

        // 0 状态码单独可筛（查「拿不到状态码」的事故）
        var zero = new AccessLogQuery(q.startTime(), q.endTime(), null, 0, null, 1, 20);
        assertThat(queryRepo.page(zero).content()).extracting(AccessLogVO::requestNo)
                .containsExactly("z0");

        // 按追踪号精确对单
        var byNo = new AccessLogQuery(q.startTime(), q.endTime(), null, null, "b2", 1, 20);
        assertThat(queryRepo.page(byNo).content()).singleElement()
                .extracting(AccessLogVO::routeNo).isEqualTo("rB");
    }

    @Test
    void timeWindowEndIsExclusive_andBoundsClamped() {
        sink.saveBatch(List.of(
                row("t1", "r", 200, "10:00:00"),
                row("t2", "r", 200, "10:00:05")));

        var q = new AccessLogQuery(
                LocalDateTime.parse("2026-09-26T10:00:00"),
                LocalDateTime.parse("2026-09-26T10:00:05"),
                null, null, null, 1, 20);
        PageResult<AccessLogVO> p = queryRepo.page(q);
        assertThat(p.content()).extracting(AccessLogVO::requestNo).containsExactly("t1"); // 止不含
    }

    @Test
    void queryGuards_requiredStart_order_rangeAndPageCap() {
        assertThatThrownBy(() -> new AccessLogQuery(null, null, null, null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        var s = LocalDateTime.parse("2026-09-26T10:00:00");
        assertThatThrownBy(() -> new AccessLogQuery(s, s.minusSeconds(1), null, null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccessLogQuery(s, s.plusDays(32), null, null, null, 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
        var capped = new AccessLogQuery(s, s.plusHours(1), null, null, null, 0, 99999);
        assertThat(capped.pageNum()).isEqualTo(1);
        assertThat(capped.pageSize()).isEqualTo(AccessLogQuery.MAX_PAGE_SIZE);
    }
}
