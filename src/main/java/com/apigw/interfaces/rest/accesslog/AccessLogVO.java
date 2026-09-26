package com.apigw.interfaces.rest.accesslog;

import java.time.LocalDateTime;

/**
 * 流水一行的对外视图。字段口径与 gw_access_log 列一一对应；
 * statusCode=0 即「拿不到状态码」（上游连不上/超时/响应未提交/连接中断）。
 */
public record AccessLogVO(Long id,
                          String requestNo,
                          String routeNo,
                          String appNo,
                          String clientIp,
                          String method,
                          String path,
                          Integer statusCode,
                          Long elapsedMs,
                          LocalDateTime occurTime) {
}
