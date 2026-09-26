package com.apigw.application.accesslog;

import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.infrastructure.accesslog.JdbcAccessLogQueryRepository;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.interfaces.rest.accesslog.AccessLogVO;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 访问流水查询应用服务。
 *
 * JDBC 是阻塞调用，绝不能在 WebFlux 事件循环线程上跑：统一 {@code subscribeOn(boundedElastic)}
 * 丢到弹性阻塞工作池，查询慢/连接池打满都只会占阻塞池线程，不卡转发。
 */
@Service
public class AccessLogAppService {

    private final JdbcAccessLogQueryRepository repository;

    public AccessLogAppService(JdbcAccessLogQueryRepository repository) {
        this.repository = repository;
    }

    public Mono<PageResult<AccessLogVO>> page(AccessLogQuery query) {
        return Mono.fromCallable(() -> repository.page(query))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
