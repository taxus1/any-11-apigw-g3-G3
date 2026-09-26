package com.apigw.proxy.accesslog;

import com.apigw.domain.accesslog.AccessLogRecord;

/**
 * 流水关闭时的空实现：{@code apigw.accesslog.enabled=false} 下装配，
 * 转发链路照调 {@link #record}，什么都不发生（不碰队列、不碰库）。
 * 丢弃/失败计数恒为 0。
 */
public class NoopAccessLogBatchWriter extends AccessLogBatchWriter {

    public NoopAccessLogBatchWriter(AccessLogProperties props) {
        super(null, props);
    }

    @Override
    public void record(AccessLogRecord entry) {
        // 开关关闭：不落流水
    }

    @Override
    public void start() {
        // 不起 worker 线程
    }

    @Override
    public void shutdown() {
        // 无队列无 worker，无需收尾
    }
}
