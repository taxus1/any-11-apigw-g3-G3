package com.apigw.proxy.accesslog;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 访问流水链路参数（前缀 apigw.accesslog）。
 *
 * 攒批的三条边界（都在这，改参数不用翻代码）：
 * @param enabled      总开关。关掉时转发主路径连入队都不做（库维护/临时下线场景）
 * @param tableName    落库表名（库表已建好，允许环境间改名）
 * @param batchSize    条数边界：手里攒到这么多就立刻落一次
 * @param linger       时间边界：第一条进来后最多等这么久必落一次（低峰期也不会一直攒着）
 * @param queueCapacity 入队有界队列容量：反应式线程只做非阻塞入队，满了丢流水+计数告警，绝不反压转发
 * @param shutdownWait 正常退出时的收尾宽限：停进程先把队列里没落完的冲一次库，超过这个时间就停
 * @param requestNoHeader 调用方追踪号的请求头名；带了合法值就沿用
 * @param appNoHeader    应用编号头（鉴权上线前的过渡取法，认不出来为空）
 */
@ConfigurationProperties(prefix = "apigw.accesslog")
public record AccessLogProperties(boolean enabled,
                                  String tableName,
                                  int batchSize,
                                  Duration linger,
                                  int queueCapacity,
                                  Duration shutdownWait,
                                  String requestNoHeader,
                                  String appNoHeader) {

    public AccessLogProperties {
        if (tableName == null || tableName.isBlank()) {
            tableName = "gw_access_log";
        }
        if (batchSize <= 0) {
            batchSize = 200;
        }
        if (batchSize > 1000) {
            // 单批太大 = 单条巨型 SQL + 长事务，反而抖；硬封顶
            batchSize = 1000;
        }
        if (linger == null || linger.isNegative() || linger.isZero()) {
            linger = Duration.ofSeconds(1);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 10_000;
        }
        if (shutdownWait == null || shutdownWait.isNegative()) {
            shutdownWait = Duration.ofSeconds(5);
        }
        if (requestNoHeader == null || requestNoHeader.isBlank()) {
            requestNoHeader = "X-Request-Id";
        }
        if (appNoHeader == null || appNoHeader.isBlank()) {
            appNoHeader = "X-App-No";
        }
    }

    /** 默认参数（测试用）。 */
    public static AccessLogProperties defaults() {
        return new AccessLogProperties(true, "gw_access_log", 200, Duration.ofSeconds(1),
                10_000, Duration.ofSeconds(5), "X-Request-Id", "X-App-No");
    }
}
