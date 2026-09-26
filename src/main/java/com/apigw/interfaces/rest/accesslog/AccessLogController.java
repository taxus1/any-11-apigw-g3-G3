package com.apigw.interfaces.rest.accesslog;

import com.apigw.application.accesslog.AccessLogAppService;
import com.apigw.common.Result;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.infrastructure.store.dto.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 访问流水翻账接口：{@code GET /api/gateway/access-logs}。
 *
 * 可组合筛选：startTime/endTime（时间段）、routeNo、statusCode（可加 requestNo 按追踪号对单）。
 * 分页：pageNum 从 1 开始；pageSize 默认 20、上限 200（传超了按 200 算）。
 * 返回统一 Result 包裹的 PageResult：content / total / pageNum / pageSize / totalPages，
 * 总数与页数由同一条 WHERE 的 COUNT 得出，和当页内容严格对得上。
 */
@RestController
@RequestMapping("/api/gateway/access-logs")
public class AccessLogController {

    private final AccessLogAppService appService;

    public AccessLogController(AccessLogAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public Mono<Result<PageResult<AccessLogVO>>> page(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String routeNo,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) String requestNo,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        AccessLogQuery query = new AccessLogQuery(
                parseTime("startTime", startTime, true),
                parseTime("endTime", endTime, false),
                routeNo, statusCode, requestNo, pageNum, pageSize);
        return appService.page(query).map(Result::ok);
    }

    /** 入参走 ISO-8601（yyyy-MM-ddTHH:mm:ss）；startTime 必填，时间非法给明确报错而不是 500。 */
    private static LocalDateTime parseTime(String name, String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(name + " 必填，格式 yyyy-MM-ddTHH:mm:ss");
            }
            return null;
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 时间格式非法，应为 yyyy-MM-ddTHH:mm:ss：" + raw);
        }
    }
}
