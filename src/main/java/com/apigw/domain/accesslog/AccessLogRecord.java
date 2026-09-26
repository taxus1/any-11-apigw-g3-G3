package com.apigw.domain.accesslog;

import java.time.LocalDateTime;

/**
 * 访问流水一行（不可变）。一笔请求从进到出收集齐了才生成它，生成后不再改，
 * 因此落库时不会出现「半条记录」，也不存在两段信息被并发改写。
 *
 * 字段口径：
 * - {@code requestNo}  请求编号：调用方带了追踪号就沿用，没带由网关生成（见 RequestNoResolver）；
 * - {@code routeNo}    命中路由编号；没匹配上/配置读不出来为 null，库里即空；
 * - {@code appNo}      调用方应用编号；鉴权上线前认 X-App-No，认不出来为 null；
 * - {@code clientIp}   来源地址，统一走 ClientIpResolver，与鉴权、限流一个口径；
 * - {@code statusCode} 最终状态码；拿不到（上游连不上/超时/响应未提交/连接中断）一律填 0；
 * - {@code elapsedMs}  请求进来到响应结束的毫秒数；
 * - {@code occurTime}  发生时间取「请求进来」的时刻，不是落库时刻——事后翻账看的是业务发生点。
 */
public record AccessLogRecord(String requestNo,
                              String routeNo,
                              String appNo,
                              String clientIp,
                              String method,
                              String path,
                              int statusCode,
                              long elapsedMs,
                              LocalDateTime occurTime) {

    public AccessLogRecord {
        if (requestNo == null || requestNo.isBlank()) {
            throw new IllegalArgumentException("requestNo 不能为空");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 不能为空");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (occurTime == null) {
            throw new IllegalArgumentException("occurTime 不能为空");
        }
        // 长度钉死在表列范围内，超长截断而不是让整条 INSERT 失败把一批都带崩
        requestNo = clip(requestNo, 64);
        routeNo = clipToNull(routeNo, 64);
        appNo = clipToNull(appNo, 64);
        clientIp = clipToNull(clientIp, 64);
        method = clip(method, 10);
        path = clip(path, 1024);
        if (statusCode < 0) {
            statusCode = 0;
        }
        if (elapsedMs < 0) {
            elapsedMs = 0;
        }
    }

    /** 库里「拿不到状态码」的统一填法：0。成功/业务失败都是真实 HTTP 码，不会与 0 撞。 */
    public static final int NO_STATUS = 0;

    private static String clip(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String clipToNull(String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return clip(v.trim(), max);
    }
}
