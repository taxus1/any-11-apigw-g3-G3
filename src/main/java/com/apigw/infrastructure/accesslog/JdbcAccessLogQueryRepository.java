package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.interfaces.rest.accesslog.AccessLogVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问流水查询仓储（JDBC）。
 *
 * 两条 SQL：一条 {@code COUNT(*)} 算总数，一条取当页，WHERE 由同一套条件拼出，
 * 保证「总条数 / 总页数 / 当页内容」三者口径完全一致，不会多也不会少。
 *
 * 排序固定 {@code occur_time DESC, id DESC}：
 * - 翻流水天然是「最新的在前」；
 * - 加 id 做同毫秒内的稳定次序，翻页不重不漏。
 * 时间列在索引 idx_occur_time_id 里，连排序也走索引序，不做 filesort。
 *
 * 表名同写入侧，只允许安全标识符，杜绝拼接注入；业务值全部走占位符。
 */
@Repository
public class JdbcAccessLogQueryRepository {

    private static final String IDENTIFIER = "[A-Za-z0-9_]+";

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    public JdbcAccessLogQueryRepository(JdbcTemplate jdbcTemplate,
                                        com.apigw.proxy.accesslog.AccessLogProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        String t = props.tableName();
        if (t == null || !t.matches(IDENTIFIER)) {
            throw new IllegalArgumentException("非法的流水表名：" + t);
        }
        this.table = t;
    }

    public PageResult<AccessLogVO> page(AccessLogQuery q) {
        SqlAndArgs where = buildWhere(q);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + where.sql(), Long.class, where.args().toArray());
        long totalCount = total == null ? 0L : total;

        int offset = (q.pageNum() - 1) * q.pageSize();
        List<Object> pageArgs = new ArrayList<>(where.args());
        pageArgs.add(q.pageSize());
        pageArgs.add(offset);

        List<AccessLogVO> content = jdbcTemplate.query(
                "SELECT id, request_no, route_no, app_no, client_ip, method, path, "
                        + "status_code, elapsed_ms, occur_time FROM " + table
                        + where.sql()
                        + " ORDER BY occur_time DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AccessLogVO(
                        rs.getLong("id"),
                        rs.getString("request_no"),
                        rs.getString("route_no"),
                        rs.getString("app_no"),
                        rs.getString("client_ip"),
                        rs.getString("method"),
                        rs.getString("path"),
                        rs.getInt("status_code"),
                        rs.getLong("elapsed_ms"),
                        rs.getTimestamp("occur_time").toLocalDateTime()),
                pageArgs.toArray());

        return new PageResult<>(content, totalCount, q.pageNum(), q.pageSize());
    }

    /**
     * WHERE 与参数由同一处生成，count 和分页共用。
     * 时间窗必带（startTime 必填），其余条件可叠加；参数化查询，没有字符串拼接值。
     */
    private SqlAndArgs buildWhere(AccessLogQuery q) {
        StringBuilder sql = new StringBuilder(" WHERE occur_time >= ? AND occur_time < ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.valueOf(q.startTime()));
        args.add(Timestamp.valueOf(q.endTime()));
        if (q.routeNo() != null) {
            sql.append(" AND route_no = ?");
            args.add(q.routeNo());
        }
        if (q.statusCode() != null) {
            sql.append(" AND status_code = ?");
            args.add(q.statusCode());
        }
        if (q.requestNo() != null) {
            sql.append(" AND request_no = ?");
            args.add(q.requestNo());
        }
        return new SqlAndArgs(sql.toString(), args);
    }

    private record SqlAndArgs(String sql, List<Object> args) {
    }
}
