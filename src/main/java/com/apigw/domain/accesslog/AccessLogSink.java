package com.apigw.domain.accesslog;

import java.util.List;

/**
 * 流水落库口（端口）。实现必须保证：传入的一批要么整批落进去、要么抛异常，
 * 不允许「半条记录」（同一行的部分列）写入——见 JdbcAccessLogSink 的多行单语句实现。
 *
 * 这是写流水链路上唯一碰库的边界：库抖动只在这里体现为异常，由上层吞掉并告警，
 * 绝不让它冒到转发主路径上。
 */
public interface AccessLogSink {

    /**
     * 批量落一批流水。
     * @throws Exception 落库失败（连接不上/约束冲突等）；调用方按「最多丢日志」处理，不得影响转发
     */
    void saveBatch(List<AccessLogRecord> batch) throws Exception;
}
