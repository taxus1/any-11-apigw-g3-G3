package com.apigw.common.exception;

import com.apigw.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

/**
 * 全局异常处理（WebFlux 版）：所有异常收口成统一 Result，不要在每个 Service 里自己 try-catch 吞掉。
 * 校验失败（@Valid）在 WebFlux 下抛出 WebExchangeBindException。
 *
 * 每个分支都要打日志：框架只看得到 Result 里的 message，不打日志的话
 * 系统级故障（如 Redis 连不上）在服务端日志里不留痕迹，排查极困难。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Mono<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Mono.just(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<Result<Void>> handleValid(WebExchangeBindException e) {
        FieldError error = e.getFieldErrors().stream().findFirst().orElse(null);
        String msg = error == null ? "参数校验失败" : error.getDefaultMessage();
        log.warn("参数校验失败 field={} msg={}", error == null ? "-" : error.getField(), msg);
        return Mono.just(Result.fail(msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<Result<Void>> handleIllegalArg(IllegalArgumentException e) {
        // 入参非法（时间格式、时间跨度、分页越界等）是调用方问题，按业务失败收口而不是系统 500
        log.warn("请求参数非法：{}", e.getMessage());
        return Mono.just(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public Mono<Result<Void>> handleOther(Exception e) {
        log.error("未处理的系统异常（多为配置或依赖故障）", e);
        return Mono.just(Result.fail(500, "系统异常：" + e.getMessage()));
    }
}
