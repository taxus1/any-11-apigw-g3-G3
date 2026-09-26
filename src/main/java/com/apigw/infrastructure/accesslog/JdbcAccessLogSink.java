package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.domain.accesslog.AccessLogSink;
import com.apigw.proxy.accesslog.AccessLogProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 流水的 JDBC 落库实现。
 *
 * 「不写半条记录」的做法：一批用<b>一条多行 INSERT</b>
 * （{@code INSERT INTO ... VALUES (?,?,..),(?,?,..)}）。在 InnoDB 下它是单个隐式事务，
 * 整批原子：成功就是整批可见，失败一条都不会进库，不存在「A 行进了 B 行没进」之外更糟的
 * 「同一行进了一半列」——那在单行 INSERT 里本身也不可能发生，多行单语句同时也避免了半批可见。
 *
 * 不依赖网关主路径的事务上下文（那里本来就没有）：连接从连接池借、自动提交下单语句即事务。
 * 表名走配置但只允许 {@code [A-Za-z0-9_]}，其余字符拒绝，杜绝表名位注入。
 */
@Component
public class JdbcAccessLogSink implements AccessLogSink {

    private static final String IDENTIFIER = "[A-Za-z0-9_]+";

    private final JdbcTemplate jdbcTemplate;
    private final String insertSql;

    public JdbcAccessLogSink(JdbcTemplate jdbcTemplate, AccessLogProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        String table = properties.tableName();
        if (table == null || !table.matches(IDENTIFIER)) {
            throw new IllegalArgumentException("非法的流水表名：" + table);
        }
        this.insertSql = "INSERT INTO " + table + " ("
                + "request_no, route_no, app_no, client_ip, method, path, "
                + "status_code, elapsed_ms, occur_time"
                + ") VALUES (?,?,?,?,?,?,?,?,?)";
    }

    @Override
    public void saveBatch(List<AccessLogRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        // 一条多行 INSERT：单语句 = 单事务，整批全成全败，不会有半批/半条可见
        StringBuilder sql = new StringBuilder(insertSql);
        List<Object> args = new ArrayList<>(batch.size() * 9);
        for (int i = 1; i < batch.size(); i++) {
            sql.append(",(?,?,?,?,?,?,?,?,?)");
        }
        for (AccessLogRecord r : batch) {
            args.add(r.requestNo());
            args.add(r.routeNo());
            args.add(r.appNo());
            args.add(r.clientIp());
            args.add(r.method());
            args.add(r.path());
            args.add(r.statusCode());
            args.add(r.elapsedMs());
            args.add(Timestamp.valueOf(r.occurTime()));
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }
}
