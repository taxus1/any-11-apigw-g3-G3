package com.apigw.proxy;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.proxy.accesslog.AccessLogBatchWriter;
import com.apigw.proxy.accesslog.AccessLogProperties;
import com.apigw.proxy.accesslog.ClientIpResolver;
import com.apigw.proxy.accesslog.RequestNoResolver;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.action.HeaderActionApplier;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.forward.UpstreamResponse;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 转发链路的总编排（一个高优先级 WebFilter）。整条路：
 *
 *   请求进来
 *     → 生成 traceId，先记访问日志第 1 段
 *     → 从路由快照里按配好的条件匹配唯一路由（匹配不到 → 404 NO_ROUTE）
 *     → 转发器里按顺序号执行请求类动作，打到上游
 *     → 上游响应回来：清洗逐跳/报文绑定头，按顺序号执行响应类动作
 *     → 状态码与响应体交回调用方，记访问日志第 2 段
 *
 * 任何一步出岔子都由 {@link #fail} 收口成网关自己的 JSON 答复：
 * 内部堆栈、上游原始错误页一律不往外抛；404/502/504/503 四类错误的状态码、
 * X-Gateway-Error 头、响应体 error 码三者齐备且互不相同。
 *
 * 管理接口（/api 开头）不属转发流量，直接放给后面的 Controller/SCG，不参与匹配。
 */
@Slf4j
@Component
public class GatewayProxyWebFilter implements WebFilter, Ordered {

    /** 比 SCG 自身的转发处理更早：转发流量由我们短路掉，管理流量原样放行。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** 这些路径不是转发流量：管理接口，以及网关自有的健康检查/actuator 端点。 */
    private static final List<String> PASSTHROUGH_PREFIXES = List.of("/api", "/actuator");

    /** 上游响应回给调用方前必须剔除的逐跳头与报文绑定头。 */
    private static final Set<String> STRIPPED_RESPONSE_HEADERS = new LinkedHashSet<>(List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "http2-settings",
            // 上游报的 content-length 绑定的是「上游↔网关」这段报文，绝不能照抄：
            // 网关按自己写出去的字节重算，或交给 Netty 走分块传输
            "content-length"));

    private final RouteCatalog routeCatalog;
    private final RouteMatcher routeMatcher;
    private final UpstreamForwarder forwarder;
    private final AccessLogRecorder accessLog;
    private final AccessLogBatchWriter accessLogDb;
    private final AccessLogProperties accessLogProps;
    private final ObjectMapper objectMapper;

    public GatewayProxyWebFilter(RouteCatalog routeCatalog,
                                 RouteMatcher routeMatcher,
                                 UpstreamForwarder forwarder,
                                 AccessLogRecorder accessLog,
                                 AccessLogBatchWriter accessLogDb,
                                 AccessLogProperties accessLogProps,
                                 ObjectMapper objectMapper) {
        this.routeCatalog = routeCatalog;
        this.routeMatcher = routeMatcher;
        this.forwarder = forwarder;
        this.accessLog = accessLog;
        this.accessLogDb = accessLogDb;
        this.accessLogProps = accessLogProps;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (isPassthrough(path)) {
            return chain.filter(exchange);
        }

        // 请求编号：调用方带了合法追踪号就沿用（跨服务同号串联），没带/不合法就我们生成。
        // 它同时作为本次转发的 traceId，响应经 X-Gateway-Trace-Id 原样带回，便于和流水对账。
        String requestNo = RequestNoResolver.resolve(exchange.getRequest(),
                accessLogProps.requestNoHeader());
        if (!RequestNoResolver.isAcceptable(requestNo)) {
            log.warn("调用方追踪号头 {} 带了不合法值（{}），改用网关生成号",
                    accessLogProps.requestNoHeader(), requestNo);
            requestNo = RequestNoResolver.generate();
        }
        String traceId = requestNo;
        long startNanos = System.nanoTime();
        LocalDateTime occurTime = LocalDateTime.now();
        String method = exchange.getRequest().getMethod() == null
                ? "-" : exchange.getRequest().getMethod().name();
        String clientIp = ClientIpResolver.resolve(exchange.getRequest());
        String appNo = resolveAppNo(exchange);

        // 记账用的「这次到底怎样了」：无论从哪个分支结束，doFinally 都拿它写唯一一条 OUT 日志，
        // 避免成功记一遍、失败又记一遍，让同一次请求在审计里出现两条结果
        AtomicReference<Outcome> outcome = new AtomicReference<>(new Outcome(null, null, "ABORTED"));

        // 所有响应（含错误）都把 traceId 带给调用方，方便和访问日志对账
        exchange.getResponse().getHeaders().set(GatewayErrors.TRACE_HEADER, traceId);
        accessLog.logIncoming(traceId, method, path);

        return routeCatalog.routes()
                .flatMap(routes -> {
                    GatewayRoute route = routeMatcher.match(routes, exchange.getRequest());
                    if (route == null) {
                        outcome.set(new Outcome(null, null, "NO_ROUTE"));
                        return GatewayErrors.write(exchange, objectMapper,
                                UpstreamFailureKind.NO_ROUTE, traceId, null);
                    }
                    outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(), "FORWARDED"));
                    URI targetUri = UpstreamForwarder.resolveTargetUri(
                            route.getUpstream(), exchange.getRequest());
                    // 响应处理必须在 WebClient 的 exchangeToMono 回调内完成（此时仍持有上游连接），
                    // 所以把 writeUpstreamResponse 作为 handler 传进去
                    return forwarder.forward(route, exchange.getRequest(), traceId, targetUri,
                            upstream -> writeUpstreamResponse(exchange, route, upstream))
                            .onErrorResume(err -> {
                                UpstreamFailureKind kind = UpstreamFailureKind.classify(err);
                                outcome.set(new Outcome(route.getRouteNo(), route.getUpstream(),
                                        kind.errorCode()));
                                return fail(exchange, traceId, kind, err);
                            });
                })
                // 路由目录给不出可用配置（Redis 故障且没有旧快照）：这是网关侧故障，不是没配路由
                .onErrorResume(err -> {
                    log.warn("路由配置不可用，无法转发 traceId={}", traceId, err);
                    outcome.set(new Outcome(null, null,
                            UpstreamFailureKind.CONFIG_UNAVAILABLE.errorCode()));
                    return fail(exchange, traceId, UpstreamFailureKind.CONFIG_UNAVAILABLE, err);
                })
                .doFinally(sig -> {
                    Outcome o = outcome.get();
                    // 状态码口径：正常/业务失败都拿到真实码；上游连不上/超时/响应未提交等
                    // 拿不到码的场景（statusCode 为 null）统一记 0，失败请求一样留痕、不漏记
                    int status = exchange.getResponse().getStatusCode() == null
                            ? AccessLogRecord.NO_STATUS
                            : exchange.getResponse().getStatusCode().value();
                    long elapsed = elapsedMillis(startNanos);
                    accessLog.logOutcome(traceId, method, path, o.routeNo(), o.upstream(),
                            status, o.result(), elapsed);
                    // 一行流水在这里「进/出两段」拼齐：入段（编号/来源/应用/方法/路径/发生时间）
                    // 是本次请求栈上的局部量，出段（路由/状态码/耗时）取自只属于本次 exchange 的
                    // AtomicReference，物理上不可能把 A 的路径配到 B 的状态码上
                    AccessLogRecord entry = new AccessLogRecord(
                            traceId, o.routeNo(), appNo, clientIp, method, path,
                            status, elapsed, occurTime);
                    // 唯一动作是非阻塞入队；库慢/库挂只影响后台 writer，绝不在请求路上等
                    accessLogDb.record(entry);
                });
    }

    /**
     * 把上游响应交回调用方：
     * - 状态码原样（上游 4xx/5xx 是业务结果，不是网关故障，网关不改写）；
     * - 剔除逐跳头与 content-length / transfer-encoding，避免和网关实际写出的报文对不上；
     * - 按顺序号执行响应类动作（只作用在响应头上，绝不碰请求）；
     * - 响应体流式写回，不在网关全量缓冲。
     */
    private Mono<Void> writeUpstreamResponse(ServerWebExchange exchange,
                                             GatewayRoute route,
                                             UpstreamResponse upstream) {
        exchange.getResponse().setStatusCode(resolveStatus(upstream.statusCode()));
        HttpHeaders out = exchange.getResponse().getHeaders();
        upstream.headers().keySet().forEach(h -> {
            String lower = h.toLowerCase();
            if (!STRIPPED_RESPONSE_HEADERS.contains(lower)) {
                out.addAll(h, upstream.headers().get(h));
            }
        });
        // 响应类动作按顺序号执行（补头覆盖上游同名头、删头让调用方收不到）
        HeaderActionApplier.applyResponseActions(route, out);

        var response = exchange.getResponse();
        // 提交前最后确认：content-length / transfer-encoding 不由上游说了算，交给 Netty 按实际报文处理
        response.beforeCommit(() -> {
            out.remove(HttpHeaders.CONTENT_LENGTH);
            out.remove(HttpHeaders.TRANSFER_ENCODING);
            return Mono.empty();
        });
        return response.writeWith(upstream.body().map(b -> (DataBuffer) b));
    }

    /**
     * 统一错误收口：把故障写成网关自己的 JSON 答复。
     * 响应可能已提交（响应体写到一半上游断了），那时状态码改不了，GatewayErrors 内部放弃写错误体。
     */
    private Mono<Void> fail(ServerWebExchange exchange, String traceId, UpstreamFailureKind kind,
                            Throwable detail) {
        // 服务端日志留全证据（含堆栈）；调用方只拿得到 kind 的固定文案，拿不到这行
        log.warn("转发失败 kind={} traceId={}", kind.errorCode(), traceId, detail);
        return GatewayErrors.write(exchange, objectMapper, kind, traceId, detail);
    }

    private boolean isPassthrough(String path) {
        for (String prefix : PASSTHROUGH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用编号：鉴权上线前先按约定头 X-App-No（可配置）认，带了非空值就记；
     * 认不出来（没带/空）留空。鉴权落地后改为从鉴权上下文取，口径只在这一处切换。
     */
    private String resolveAppNo(ServerWebExchange exchange) {
        String v = exchange.getRequest().getHeaders().getFirst(accessLogProps.appNoHeader());
        if (v == null || v.isBlank()) {
            return null;
        }
        String trimmed = v.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }

    /** 上游状态码原样透传；非标准码（resolve 返回 null）退化成 502，不让框架抛异常。 */
    private static HttpStatus resolveStatus(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.BAD_GATEWAY;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 一次转发在审计日志里的归宿：命中路由、上游地址、结果码。 */
    private record Outcome(String routeNo, String upstream, String result) {
    }
}
