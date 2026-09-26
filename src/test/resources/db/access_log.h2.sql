-- 测试用建表（H2，MySQL 兼容模式）。列定义与 access_log.mysql.sql 一一对应。
CREATE TABLE gw_access_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    request_no  VARCHAR(64)  NOT NULL,
    route_no    VARCHAR(64)  NULL,
    app_no      VARCHAR(64)  NULL,
    client_ip   VARCHAR(64)  NULL,
    method      VARCHAR(10)  NOT NULL,
    path        VARCHAR(1024) NOT NULL,
    status_code INT          NOT NULL,
    elapsed_ms  BIGINT       NOT NULL,
    occur_time  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_occur_time_id (occur_time, id),
    KEY idx_route_occur (route_no, occur_time),
    KEY idx_status_occur (status_code, occur_time),
    KEY idx_request_no (request_no)
);
