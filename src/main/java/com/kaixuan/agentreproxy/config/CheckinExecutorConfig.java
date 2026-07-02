package com.kaixuan.agentreproxy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 签到服务专用的线程池配置
 * <p>
 * 固定 5 个线程,与下游 API Key 的"量大/量少/临期"选号策略无关,
 * 仅服务于 {@code CheckinService.executeBatch} / {@code executeStream}:
 * 账号多时多账号签到并发执行,降低整体耗时。
 * <p>
 * <strong>为什么不用 {@code Schedulers.boundedElastic()}</strong>:
 * <ul>
 * <li>boundedElastic 默认 size = CPU * 10,会跟 WebFlux 的 I/O 线程抢资源</li>
 * <li>用户明确要求"五个线程",显式固定线程数最贴合需求</li>
 * <li>需要给线程起可读名字,便于栈追踪/日志</li>
 * </ul>
 * <p>
 * 关闭策略:Spring 容器关闭时由 {@code @Bean(destroyMethod="shutdown")} 兜底。
 */
@Configuration
public class CheckinExecutorConfig {

    /** 固定 5 线程 —— 用户明确指定的并发数 */
    public static final int CHECKIN_CONCURRENCY = 5;

    @Bean(name = "checkinExecutor", destroyMethod = "shutdown")
    public ExecutorService checkinExecutor() {
        return Executors.newFixedThreadPool(CHECKIN_CONCURRENCY, namedThreadFactory("checkin"));
    }

    /**
     * 命名前缀的线程工厂 —— 线程名形如 "checkin-1" / "checkin-2" ...
     * 便于 jstack / async-profiler 排查时一眼识别签到线程
     */
    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
            // 守护线程 —— 避免主进程退出时签到线程持有 JVM
            t.setDaemon(true);
            return t;
        };
    }
}
