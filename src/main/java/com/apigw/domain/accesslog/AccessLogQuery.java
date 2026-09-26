package com.apigw.domain.accesslog;

import java.time.LocalDateTime;

/**
 * 流水查询条件，全部可组合（AND 拼起来），null/空表示不筛该维：
 *
 * @param startTime 发生时间起（含），必填——翻流水必须落在确定时间窗内，也逼查询走时间索引
 * @param endTime   发生时间止（不含）；null 按到当前时刻
 * @param routeNo   命中路由编号精确匹配
 * @param statusCode HTTP 状态码精确匹配；0 专门用来翻「拿不到状态码」的失败请求
 * @param requestNo  请求编号/调用方追踪号精确匹配，用于跨服务对账
 */
public record AccessLogQuery(LocalDateTime startTime,
                             LocalDateTime endTime,
                             String routeNo,
                             Integer statusCode,
                             String requestNo,
                             int pageNum,
                             int pageSize) {

    /** 每页条数硬上限：翻账接口不允许一次拖全表。 */
    public static final int MAX_PAGE_SIZE = 200;
    /** 时间窗硬上限（天）：再宽的范围请走离线数仓，不让一条查询把线上库拖垮。 */
    public static final int MAX_RANGE_DAYS = 31;

    public AccessLogQuery {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime 必填");
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime 不能早于 startTime");
        }
        if (startTime.plusDays(MAX_RANGE_DAYS).isBefore(endTime)) {
            throw new IllegalArgumentException(
                    "查询时间跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        routeNo = blankToNull(routeNo);
        requestNo = blankToNull(requestNo);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
