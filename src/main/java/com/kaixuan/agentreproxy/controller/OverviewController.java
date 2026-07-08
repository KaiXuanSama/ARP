package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.service.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 总览页数据端点
 * <p>
 * 一次性返回所有统计/趋势/健康度数据，前端只需拉一个端点。
 * <p>
 * 内部走 {@link OverviewService#aggregate()}（同步 JDBC），
 * 用 {@code Mono.fromCallable + boundedElastic} 包装，不阻塞 reactor 线程。
 */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    public Mono<Map<String, Object>> overview() {
        return Mono.fromCallable(overviewService::aggregate)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
