-- 网关访问流水：一笔请求一行。
-- 这张表线上已建好，这里是权威 DDL 与索引说明，新环境照此建表即可。
-- MySQL 8.0 / InnoDB / utf8mb4。

CREATE TABLE IF NOT EXISTS `gw_access_log` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键，只服务于分页稳定排序，不是业务编号',
    `request_no`  VARCHAR(64)  NOT NULL COMMENT '请求编号：优先沿用调用方追踪号（X-Request-Id），没带就网关生成；响应经 X-Gateway-Trace-Id 带回',
    `route_no`    VARCHAR(64)  NULL     COMMENT '命中的路由编号；没匹配上路由/配置不可用时为空',
    `app_no`      VARCHAR(64)  NULL     COMMENT '调进来的应用编号（鉴权未上线前取 X-App-No）；认不出来为空',
    `client_ip`   VARCHAR(64)  NULL     COMMENT '客户端来源地址，取值口径与鉴权、限流共用同一个 ClientIpResolver',
    `method`      VARCHAR(10)  NOT NULL COMMENT '请求方法，GET/POST/...',
    `path`        VARCHAR(1024) NOT NULL COMMENT '请求路径（不含查询串）',
    `status_code` INT          NOT NULL COMMENT '返回给调用方的 HTTP 状态码；0 表示拿不到状态码（上游连不上/超时/响应未提交/连接中断）',
    `elapsed_ms`  BIGINT       NOT NULL COMMENT '本次转发总耗时（毫秒），请求进来到响应结束',
    `occur_time`  DATETIME(3)  NOT NULL COMMENT '发生时间（请求进来的时刻），毫秒精度',
    PRIMARY KEY (`id`),
    -- 按时间段翻流水是最高频场景：时间范围扫描 + 倒序展示都走这条，且排序列在索引里，免 filesort
    KEY `idx_occur_time_id` (`occur_time`, `id`),
    -- 时间 + 路由：组合筛选「某段时间某条路由」
    KEY `idx_route_occur` (`route_no`, `occur_time`),
    -- 时间 + 状态码：组合筛选「某段时间的失败/超时（如 500/502/504/0）」
    KEY `idx_status_occur` (`status_code`, `occur_time`),
    -- 按请求编号（调用方追踪号）反查整条跨服务链路；不唯一：调用方可能重号，我们只负责记账
    KEY `idx_request_no` (`request_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网关访问流水（一笔请求一行，异步批量落库）';
