package com.apigw.proxy.accesslog;

import com.apigw.domain.accesslog.AccessLogSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 访问流水装配。
 *
 * {@code apigw.accesslog.enabled=true}（默认）时装配真正的批量落库器，
 * JdbcAccessLogSink 由组件扫描自行装配；置 false 时只装 {@link NoopAccessLogBatchWriter}，
 * 转发链路完全不感知差别（零入队、零碰库），用于库维护/临时关停流水。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccessLogProperties.class)
public class AccessLogConfig {

    @Bean
    @ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public AccessLogBatchWriter accessLogBatchWriter(AccessLogSink sink, AccessLogProperties props) {
        return new AccessLogBatchWriter(sink, props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled", havingValue = "false")
    public AccessLogBatchWriter noopAccessLogBatchWriter(AccessLogProperties props) {
        return new NoopAccessLogBatchWriter(props);
    }
}
