package com.apigw.proxy;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.accesslog.AccessLogRecord;
import com.apigw.domain.accesslog.AccessLogSink;
import com.apigw.proxy.accesslog.AccessLogBatchWriter;
import com.apigw.proxy.accesslog.AccessLogProperties;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.route.RoutesChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 转发链路端到端测试（真实 Netty 服务端 + 真实 WebClient 上游 + JDK HttpServer 上游，无 Redis）。
 *
 * 覆盖题目硬性要求：
 * - 按条件匹配路由，把请求（方法/路径/查询串/请求体）真正送到上游并把响应带回来；
 * - 请求补头覆盖调用方同名头、删头上游收不到；响应补头/删头作用到回给调用方的响应；
 * - 路径前缀边界（/order/ 命中 /order/abc）；多路由稳定定序；
 * - 无路由回 404 且带 X-Gateway-Error: NO_ROUTE，前端一眼认出是网关没找到路；
 * - 上游连不上 502 UPSTREAM_UNAVAILABLE、上游超时 504 UPSTREAM_TIMEOUT，与 404 区分开；
 * - 上游 content-length 等报文绑定头不原样照抄，响应长度仍与实际内容对得上；
 * - 新建路由经变更事件刷新后立刻走通，不重启；
 * - traceId 通过 X-Gateway-Trace-Id 回给调用方，与访问日志两段串联。
 */
class GatewayProxyFilterTest {

    private FakeUpstream upstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;

    private DisposableServer server;
    private String baseUrl;
    private WebClient client;
    private RecordingSink dbSink;
    private AccessLogBatchWriter dbWriter;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);

        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800))
                .doOnConnected(c -> c.addHandlerLast(
                        new io.netty.handler.timeout.ReadTimeoutHandler(
                                800, java.util.concurrent.TimeUnit.MILLISECONDS)));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();

        // 落库链路用内存 sink + 真批量 writer，顺带验证一笔一行、状态码/路由/追踪号不串
        dbSink = new RecordingSink();
        dbWriter = new AccessLogBatchWriter(dbSink, AccessLogProperties.defaults());
        dbWriter.start();
        var filter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), new UpstreamForwarder(webClient),
                new AccessLogRecorder(), dbWriter, AccessLogProperties.defaults(),
                new ObjectMapper());

        // 链尾 WebHandler：到这里的只有被判定为非转发流量（/api），回一个占位 200
        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        HttpHandler httpHandler = adapter;

        server = HttpServer.create()
                .handle(new ReactorHttpHandlerAdapter(httpHandler))
                .bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        upstream.close();
        dbWriter.shutdown();
    }

    // ---- 造路由的小工具 ----

    private GatewayRule cond(String type, String name, String value, int sort) {
        return GatewayRule.create("REQUEST", type, name, value, sort);
    }

    private GatewayRule act(String type, String name, String value, int sort) {
        return GatewayRule.create(type.startsWith("RESP_") ? "RESPONSE" : "REQUEST",
                type, name, value, sort);
    }

    private GatewayRoute route(String no, String upstreamBase,
                               List<GatewayRule> conditions, List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create(no, no, upstreamBase, 1, null);
        r.replaceRules(conditions, actions);
        return r;
    }

    private void loadRoutes(GatewayRoute... routes) {
        store.setRoutes(List.of(routes));
        catalog.refresh().block();
    }

    // ---- 用例 ----

    @Test
    void forwardsMethodPathQueryAndBody_toMatchedUpstream() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of()));

        String resp = client.post()
                .uri(baseUrl + "/order/abc?from=cart")
                .header("Content-Type", "text/plain")
                .bodyValue("hello-body")
                .retrieve().bodyToMono(String.class).block();

        assertThat(resp).contains("\"method\":\"POST\"")
                .contains("\"path\":\"/order/abc\"")
                .contains("\"query\":\"from=cart\"");
        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestMethod().toString()).isEqualTo("POST");
        assertThat(got.getRequestURI().getPath()).isEqualTo("/order/abc");
    }

    @Test
    void requestAddHeader_overwritesCallerHeader_andRemoveHeaderStripsIt() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of(
                        act("REQ_ADD_HEADER", "X-Gw", "from-gw", 1),
                        act("REQ_REMOVE_HEADER", "X-Internal", null, 2))));

        client.get().uri(baseUrl + "/order/1")
                .header("X-Gw", "caller-wants-this")   // 应被覆盖
                .header("X-Internal", "secret")        // 应被删除
                .retrieve().bodyToMono(String.class).block();

        HttpExchange got = upstream.lastExchange();
        assertThat(got.getRequestHeaders().getFirst("X-Gw")).isEqualTo("from-gw");
        assertThat(got.getRequestHeaders().get("X-Internal"))
                .as("删头后上游绝不能收到 X-Internal").isNullOrEmpty();
        // Host 必须是上游的，而不是网关收到的调用方 Host
        assertThat(got.getRequestHeaders().getFirst("Host")).contains("127.0.0.1:" + upstream.port());
        assertThat(got.getRequestHeaders().getFirst("X-Gateway-Trace-Id")).isNotBlank();
    }

    @Test
    void responseAddAndRemoveHeaders_landOnCallerResponse_notOnRequest() {
        upstream.setCustomBody("{\"ok\":true}");
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)),
                List.of(
                        act("RESP_ADD_HEADER", "X-Trace", "t-1", 1),
                        act("RESP_REMOVE_HEADER", "Content-Type", null, 2))));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();

        assertThat(resp.headers().asHttpHeaders().getFirst("X-Trace")).isEqualTo("t-1");
        // 上游本来回了 Content-Type，响应动作要求删掉，调用方就拿不到
        assertThat(resp.headers().asHttpHeaders().get("Content-Type")).isNullOrEmpty();
        // 响应动作不串到请求：上游收到的请求里不该出现 X-Trace
        assertThat(upstream.lastExchange().getRequestHeaders().getFirst("X-Trace")).isNull();
        resp.releaseBody().block();
    }

    @Test
    void pathBoundary_trailingSlashPrefixHits_andLookalikeMisses() {
        loadRoutes(
                route("order", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        // /order/abc 命中
        client.get().uri(baseUrl + "/order/abc").retrieve().bodyToMono(String.class).block();
        assertThat(upstream.lastExchange().getRequestURI().getPath()).isEqualTo("/order/abc");

        // /order 自身对 /order/ 前缀不命中 → 网关 404 NO_ROUTE，不是上游 404
        var missExact = client.get().uri(baseUrl + "/order").exchange().block();
        assertThat(missExact.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missExact.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        missExact.releaseBody().block();

        // /ordering 这种「只是字符串前缀像」的绝不命中
        var lookalike = client.get().uri(baseUrl + "/ordering").exchange().block();
        assertThat(lookalike.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(lookalike.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        lookalike.releaseBody().block();
    }

    @Test
    void multipleMatches_areOrderedStably() {
        FakeUpstream upstream2;
        try {
            upstream2 = new FakeUpstream();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            loadRoutes(
                    route("broad", upstream.baseUrl(),
                            List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()),
                    route("specific", upstream2.baseUrl(),
                            List.of(cond("PATH_PREFIX", null, "/order/abc/", 1)), List.of()));

            // 更长前缀稳定走 specific 上游，连打多次结果一致（不能时有时无）
            for (int i = 0; i < 3; i++) {
                client.get().uri(baseUrl + "/order/abc/x").retrieve().bodyToMono(String.class).block();
                assertThat(upstream2.lastExchange()).isNotNull();
                assertThat(upstream2.lastExchange().getRequestURI().getPath()).isEqualTo("/order/abc/x");
            }
        } finally {
            upstream2.close();
        }
    }

    @Test
    void noRoute_returns404MarkedAsGatewayNoRoute_notBackendError() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/nope").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error")).isEqualTo("NO_ROUTE");
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).contains("NO_ROUTE").contains("traceId");
    }

    @Test
    void upstreamConnectRefused_is502DistinctFrom404() {
        loadRoutes(route("dead", "http://127.0.0.1:1",
                List.of(cond("PATH_PREFIX", null, "/dead/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/dead/x").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("UPSTREAM_UNAVAILABLE");
        String body = resp.bodyToMono(String.class).block();
        // 只能看到网关的固定文案，不能是内部堆栈
        assertThat(body).contains("UPSTREAM_UNAVAILABLE").doesNotContain("Exception");
    }

    @Test
    void upstreamHang_is504DistinctFrom502and404() {
        upstream.setHangForever(true);
        loadRoutes(route("slow", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/slow/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/slow/x").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"))
                .isEqualTo("UPSTREAM_TIMEOUT");
        resp.releaseBody().block();
    }

    @Test
    void upstreamContentLength_isNotBlindlyCopied_butBodyIsIntact() {
        // 上游故意回一个与 content-length 不一致的内容，验证网关不照抄该头也不截断内容
        String payload = "{\"x\":\"this body is intentionally longer than declared length\"}";
        upstream.setCustomBody(payload);
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).isEqualTo(payload);
        // 连接能干净读完、没有因为报文绑定头错乱导致截断/挂起，即说明长度由网关/框架按实际报文处理
        assertThat(body.length()).isEqualTo(payload.length());
    }

    @Test
    void upstreamStatus_isPassedThrough_including4xx5xx() {
        upstream.setResponseStatus(418);
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/1").exchange().block();
        assertThat(resp.statusCode().value()).isEqualTo(418);
        resp.releaseBody().block();
    }

    @Test
    void newlyCreatedRoute_takesEffectImmediatelyViaChangeEvent() {
        // 初始只有一条路由
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));
        var before = client.get().uri(baseUrl + "/fresh/1").exchange().block();
        assertThat(before.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        before.releaseBody().block();

        // 「新建」另一条前缀的路由并发事件（模拟管理接口写完 Redis），不重启、不等轮询
        GatewayRoute fresh = route("fresh", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/fresh/", 1)), List.of());
        store.setRoutes(List.of(
                route("order", upstream.baseUrl(),
                        List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()),
                fresh));
        catalog.onRoutesChanged(RoutesChangedEvent.created("fresh"));

        var after = client.get().uri(baseUrl + "/fresh/1").exchange().block();
        assertThat(after.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.bodyToMono(String.class).block()).contains("\"path\":\"/fresh/1\"");
    }

    @Test
    void managementApi_isPassedThrough_notProxied() {
        var resp = client.get().uri(baseUrl + "/api/gateway/routes").exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        resp.releaseBody().block();
    }

    @Test
    void traceId_isReturnedOnBothSuccessAndError() {        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var ok = client.get().uri(baseUrl + "/order/1").exchange().block();
        String traceOk = ok.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(traceOk).isNotBlank();
        ok.releaseBody().block();

        var notFound = client.get().uri(baseUrl + "/missing").exchange().block();
        String traceErr = notFound.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(traceErr).isNotBlank();
        // 不同请求的 traceId 不能串
        assertThat(traceOk).isNotEqualTo(traceErr);
        notFound.releaseBody().block();
    }

    @Test
    void accessLogRow_onePerRequest_carriesRequestNoRouteStatusAndTiming() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var resp = client.get().uri(baseUrl + "/order/9?x=1")
                .header("X-Request-Id", "caller-trace-777")
                .header("X-App-No", "app-mall")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        // 调用方追踪号原样回带，跨服务用它对账
        assertThat(resp.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id"))
                .isEqualTo("caller-trace-777");
        resp.releaseBody().block();

        await().untilAsserted(() -> assertThat(dbSink.rows).hasSize(1));
        AccessLogRecord row = dbSink.rows.get(0);
        assertThat(row.requestNo()).isEqualTo("caller-trace-777");
        assertThat(row.routeNo()).isEqualTo("order");
        assertThat(row.appNo()).isEqualTo("app-mall");
        assertThat(row.method()).isEqualTo("GET");
        assertThat(row.path()).isEqualTo("/order/9"); // 只记路径，不带查询串
        assertThat(row.statusCode()).isEqualTo(200);
        assertThat(row.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(row.occurTime()).isNotNull();
    }

    @Test
    void accessLogRow_recordsFailuresAndMissingRouteWithZeroOrGatewayStatus() {
        loadRoutes(route("dead", "http://127.0.0.1:1",
                List.of(cond("PATH_PREFIX", null, "/dead/", 1)), List.of()));

        // 上游连不上：502 也必须留痕，不能只记成功的
        var bad = client.get().uri(baseUrl + "/dead/x").exchange().block();
        assertThat(bad.statusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        bad.releaseBody().block();
        // 没匹配上路由：404，route 为空
        var none = client.get().uri(baseUrl + "/nope").exchange().block();
        assertThat(none.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        none.releaseBody().block();

        await().untilAsserted(() -> assertThat(dbSink.rows).hasSize(2));
        AccessLogRecord failed = dbSink.rows.get(0);
        assertThat(failed.routeNo()).isEqualTo("dead");
        assertThat(failed.statusCode()).isEqualTo(502);
        AccessLogRecord noRoute = dbSink.rows.get(1);
        assertThat(noRoute.routeNo()).isNull();
        assertThat(noRoute.statusCode()).isEqualTo(404);
        // 两段并发信息不串：路径各自跟各自的状态码
        assertThat(failed.path()).isEqualTo("/dead/x");
        assertThat(noRoute.path()).isEqualTo("/nope");
    }

    @Test
    void accessLogRow_generatesRequestNo_whenCallerOmittedOrInvalid() {
        loadRoutes(route("order", upstream.baseUrl(),
                List.of(cond("PATH_PREFIX", null, "/order/", 1)), List.of()));

        var ok1 = client.get().uri(baseUrl + "/order/a").exchange().block();
        String gen = ok1.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(gen).hasSize(32);
        ok1.releaseBody().block();
        // 非法值（含逗号）不沿用，改用生成号，不把脏值写进追踪号
        var ok2 = client.get().uri(baseUrl + "/order/b")
                .header("X-Request-Id", "bad,id").exchange().block();
        String replaced = ok2.headers().asHttpHeaders().getFirst("X-Gateway-Trace-Id");
        assertThat(replaced).hasSize(32).isNotEqualTo("bad,id");
        ok2.releaseBody().block();

        await().untilAsserted(() -> assertThat(dbSink.rows).hasSize(2));
        assertThat(dbSink.rows).extracting(AccessLogRecord::requestNo)
                .containsExactly(gen, replaced);
    }

    /** 测试用落库口：把整批行收进内存，供断言；批次语义与真 sink 一致（每次给一批）。 */
    static final class RecordingSink implements AccessLogSink {
        final java.util.List<AccessLogRecord> rows =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void saveBatch(java.util.List<AccessLogRecord> batch) {
            rows.addAll(batch);
        }
    }
}
