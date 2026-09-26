package com.apigw.proxy.accesslog;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求编号（跨服务追踪号）的唯一取号口，访问流水、响应头都走这里。
 *
 * 口径（内部约定）：
 * 1. 调用方在 {@code X-Request-Id}（头名可配置）里带了合法追踪号 → 原样沿用，
 *    这样调用方那条链路和网关流水、再往下游的链路能用同一个号串起来；
 * 2. 没带、或带了不合法的值（伪造/乱码/超长）→ 网关自己生成一个 32 位十六进制 UUID，
 *    并打一行 warn 留痕（合法号静默沿用，不刷屏）。
 *
 * 合法性白名单 {@code [A-Za-z0-9._-]}、长度 1~64：流水列就是 VARCHAR(64)，
 * 也避免脏值（逗号、换行、注入串）顺着 X-Gateway-Trace-Id 污染下游日志。
 */
public final class RequestNoResolver {

    /** 与 gw_access_log.request_no 列等长。 */
    public static final int MAX_LENGTH = 64;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestNoResolver() {
    }

    /** 取号：带了合法头用头的值，否则生成。是否走了「非法值」分支由日志判断。 */
    public static String resolve(ServerHttpRequest request, String headerName) {
        HttpHeaders headers = request.getHeaders();
        String carried = headers.getFirst(headerName);
        if (carried != null && !carried.isBlank()) {
            String v = carried.trim();
            if (VALID.matcher(v).matches()) {
                return v;
            }
            return v;
        }
        return generate();
    }

    /** 头里带的值是否可直接沿用：非法时调用方应改记生成号并告警。 */
    public static boolean isAcceptable(String requestNo) {
        return requestNo != null && VALID.matcher(requestNo).matches();
    }

    /** 网关生成号：与历史 traceId 同一形态（32 位无横线十六进制）。 */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
