package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.UsageQueryRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话后自动刷新积分/用量的调度器
 * <p>
 * 业务规则（用户需求）：
 * <ol>
 *   <li>第一次调用 /v1/chat/completions 触发一个 3 分钟定时器</li>
 *   <li>定时器到达后,自动调用上游 user-resource / user-request-usage 获取最新数据并落库
 *       (复用 {@link BillingQueryService} 的方法)</li>
 *   <li>3 分钟内重复调用 chat 不会再次触发,也不会重置原有计时器
 *       —— 也就是说全局最多一个计时器在跑</li>
 *   <li>定时器触发完成后自动清除,下一次 chat 又能重新触发新的 3 分钟</li>
 * </ol>
 * <p>
 * <strong>实现说明</strong>:
 * <ul>
 *   <li>用单个 {@link ScheduledExecutorService} (daemon=true),1 线程足够</li>
 *   <li>用 {@link AtomicReference} 持有当前 {@link ScheduledFuture},实现"有就跳过、无就创建"</li>
 *   <li>不阻塞 chat 流本身 —— chat 订阅时仅做一次 CAS 提交,不等待刷新结果</li>
 *   <li>触发时调用的上游接口用 accountId (主键) 路由 —— 复用 BillingQueryService</li>
 * </ul>
 */
@Component
public class ChatUsageRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChatUsageRefreshScheduler.class);

    /** 3 分钟 = 180 秒 = 180_000 ms */
    private static final long DELAY_MS = 3 * 60 * 1000L;

    /** usage 查询默认查最近 7 天(与前端 usageQueryBody(days=7) 对齐) */
    private static final int USAGE_DAYS = 7;

    /** 上游时间格式 "yyyy-MM-dd HH:mm:ss" */
    private static final DateTimeFormatter UPSTREAM_DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BillingQueryService billingQueryService;

    /** 调度线程池:daemon 1 线程。线程名带前缀方便 jstack 识别 */
    private ScheduledExecutorService scheduler;

    /**
     * 当前正在等待的定时任务
     * <p>
     * {@code null} = 无定时器,可被新 chat 触发
     * 非空 = 已有定时器,新 chat 触发会被忽略
     */
    private final java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> pending = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 在当前定时器等待期间(3 分钟内)被 chat 触发的所有账号主键
     * <p>
     * 使用 {@link java.util.concurrent.CopyOnWriteArrayList} 保证线程安全:
     * <ul>
     *   <li>chat 流(可能在 reactor 线程)写入时无锁</li>
     *   <li>3 分钟后定时器线程遍历时读到一致快照</li>
     *   <li>重复 accountId 会被 dedupe 成单条</li>
     * </ul>
     * <p>
     * 定时器触发时遍历此列表,顺序为每个账号拉一次 credit + usage
     */
    private final java.util.concurrent.CopyOnWriteArrayList<Long> pendingAccountIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public ChatUsageRefreshScheduler(BillingQueryService billingQueryService) {
        this.billingQueryService = billingQueryService;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-usage-refresh");
            t.setDaemon(true);
            return t;
        });
        log.info("ChatUsageRefreshScheduler 启动完成 (delay={}ms = 3min)", DELAY_MS);
    }

    @PreDestroy
    public void stop() {
        ScheduledFuture<?> p = pending.getAndSet(null);
        if (p != null) {
            p.cancel(false);
        }
        pendingAccountIds.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 调度一次 3 分钟后的刷新
     * <p>
     * 行为(用户需求):
     * <ol>
     *   <li>收集:把传入的 accountId 追加到"待刷新列表"(全局去重)</li>
     *   <li>首次触发:如果当前没有定时器,创建一个 3 分钟后的定时任务</li>
     *   <li>重复触发:如果定时器已在跑,只追加到列表,不再创建新定时器,也不重置剩余时间</li>
     * </ol>
     * <p>
     * 定时器触发时会遍历整个待刷新列表,为每个账号拉一次 credit + usage。
     * <p>
     * 注:即使在 3 分钟内切换不同账号发起 chat,所有触发过的账号都会在
     * 定时器到期时被一起刷新,符合"用户视角每个用过 chat 的账号都该被刷新"的预期。
     *
     * @param accountId 触发本次调度的账号主键
     */
    public void scheduleRefreshAfterChat(Long accountId) {
        if (accountId == null) {
            return;
        }
        // 1) 永远先把 accountId 收集进待刷新列表(dedupe 防止重复)
        if (!pendingAccountIds.contains(accountId)) {
            pendingAccountIds.add(accountId);
            log.info("chat 触发:accountId={} 已加入待刷新列表 (当前 {} 个)",
                    accountId, pendingAccountIds.size());
        }

        // 2) 尝试创建新定时器:已有 → 跳过
        if (pending.get() != null) {
            log.debug("已有定时器在跑,忽略新 chat 触发的定时器创建 (当前列表 accountIds={})",
                    pendingAccountIds);
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(
                this::onTrigger,
                DELAY_MS,
                TimeUnit.MILLISECONDS);
        if (!pending.compareAndSet(null, future)) {
            // race: 另一个线程在我们 schedule 之前/之后抢占了定时器
            future.cancel(false);
            log.info("race: 定时器已被其他线程设置,取消本次 schedule");
            return;
        }
        log.info("chat 调用触发:将在 {}ms 后自动刷新 {} 个账号的积分用量",
                DELAY_MS, pendingAccountIds.size());
    }

    /**
     * 定时器触发时调用的方法
     * <p>
     * 1. 取出当前待刷新列表(快照),为每个账号顺序拉一次 credit + usage
     * 2. 清空 pending 状态 + 待刷新列表,允许下次 chat 重新触发
     * <p>
     * 顺序执行:单线程 daemon scheduler,每账号拉 credit + usage 串行,
     * 避免对上游并发请求过猛。
     * <p>
     * 注:不抛异常到 scheduler (daemon 线程里抛未捕获异常会被 JVM 默默吞掉),
     * 所以这里 try-catch 包住,失败仅记日志
     */
    private void onTrigger() {
        // 取出快照 —— 触发期间(本方法内)新加进列表的 accountId 不在本轮处理
        // (因为它们是"刚加进来"的,留到下一个 3 分钟周期更合理)
        java.util.List<Long> snapshot = new java.util.ArrayList<>(pendingAccountIds);
        log.info("3 分钟定时器触发:开始刷新 {} 个账号的积分用量 ({})",
                snapshot.size(), snapshot);
        if (snapshot.isEmpty()) {
            // 极小概率:列表被并发清空。安全释放 pending 即可
            pending.set(null);
            return;
        }
        try {
            for (Long accountId : snapshot) {
                refreshOneAccount(accountId);
            }
        } catch (Exception e) {
            log.error("定时刷新流程异常 (已处理 {} 个)", snapshot.size(), e);
        } finally {
            // 无论成功失败,都清空 pending + 列表 —— 让下次 chat 能重新触发 3 分钟计时
            pending.set(null);
            pendingAccountIds.clear();
        }
    }

    /**
     * 刷新单个账号的 credit + usage
     * <p>
     * 注:此方法在 daemon scheduler 线程内执行,内部的 {@code .block()} 是允许的(不在 reactor 线程上)
     */
    private void refreshOneAccount(Long accountId) {
        try {
            // 1) 刷新 credit (拉取 + 落库 + 返回 extra)
            billingQueryService.queryCredit(accountId)
                    .doOnSuccess(resp -> log.info("定时刷新 credit 成功 accountId={} status={}",
                            accountId, resp.getStatusCode().value()))
                    .doOnError(e -> log.warn("定时刷新 credit 失败 accountId={}: {}",
                            accountId, e.getMessage()))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .block();

            // 2) 刷新 usage (最近 7 天)
            UsageQueryRequest req = buildUsageRequest();
            billingQueryService.queryUsage(accountId, req)
                    .doOnSuccess(resp -> log.info("定时刷新 usage 成功 accountId={} status={}",
                            accountId, resp.getStatusCode().value()))
                    .doOnError(e -> log.warn("定时刷新 usage 失败 accountId={}: {}",
                            accountId, e.getMessage()))
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .block();
        } catch (Exception e) {
            // 单个账号失败不影响其他账号
            log.warn("刷新单个账号失败 accountId={}: {}", accountId, e.getMessage());
        }
    }

    /**
     * 构造"最近 7 天"用量查询 body
     * <p>
     * 起始日 00:00:00,结束日 23:59:59 —— 与 BillingQueryService 的 UPSTREAM_DT_FMT 对齐
     */
    private UsageQueryRequest buildUsageRequest() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(USAGE_DAYS - 1L);
        String startStr = start.atStartOfDay().format(UPSTREAM_DT_FMT);
        String endStr = LocalDateTime.of(end, LocalTime.of(23, 59, 59)).format(UPSTREAM_DT_FMT);
        return new UsageQueryRequest(startStr, endStr, 1, 50);
    }

    /**
     * 调试 / 监控用:当前是否有定时器在等
     */
    public boolean hasPendingRefresh() {
        return pending.get() != null;
    }
}
