package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.dto.CheckinExecutionResult;
import com.kaixuan.agentreproxy.dto.CheckinResultItem;
import com.kaixuan.agentreproxy.dto.CheckinStatusItem;
import com.kaixuan.agentreproxy.dto.CheckinStreamEvent;
import com.kaixuan.agentreproxy.entity.CheckinLogRecord;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.CheckinLogJdbcRepository;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日签到服务
 * <p>
 * 核心设计：
 * - 不查上游"今日是否已签到"接口（已废弃）
 * - 改为"调上游签到接口 + 落本地 checkin_log"的双重探针
 * - 本地有当日日志 = 已签到（不管上游返回啥）
 * <p>
 * 业务码映射：
 * - code=0 → 签到成功 → 落库 → status="checked_in"
 * - code=10001 → 当日已签到 → 本地无记录则补录 → status="already_checked_in"
 * -其他 →业务错误，不落库 → status="error"
 */
@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    /** 签到类型（当前唯一一种，未来可扩展） */
    public static final String CHECKIN_TYPE_DAILY = "daily";

    /** 上游业务码：当日已签到 */
    private static final int CODE_ALREADY_CHECKED_IN = 10001;

    /**
     * 并发签到的并发度。
     * <p>
     * 与 {@code CheckinExecutorConfig.CHECKIN_CONCURRENCY} 保持一致 —— 留常量便于阅读。
     * 实际线程数由容器注入的 Executor 决定;此处常量仅用于"账号数 &lt; 并发度时短路走串行"
     * 的判断(避免为 1~2 个账号拉起线程池的开销)。
     */
    private static final int CHECKIN_CONCURRENCY = 5;

    private final UpstreamClient upstreamClient;
    private final CheckinLogJdbcRepository checkinLogRepository;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService checkinExecutor;

    public CheckinService(UpstreamClient upstreamClient,
            CheckinLogJdbcRepository checkinLogRepository,
            WorkbuddyAccountJdbcRepository accountRepository,
            ObjectMapper objectMapper,
            @Qualifier("checkinExecutor") ExecutorService checkinExecutor) {
        this.upstreamClient = upstreamClient;
        this.checkinLogRepository = checkinLogRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
        this.checkinExecutor = checkinExecutor;
    }

    // ============== 当日时间窗 ==============

    /**
     * 计算"今日"的毫秒时间范围 [startMs, endMs]（含）
     * <p>
     * 按系统默认时区划分"今天"，避免 SQLite strftime 时区陷阱
     */
    public static long[] todayRangeMs() {
        return todayRangeMs(ZoneId.systemDefault());
    }

    public static long[] todayRangeMs(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDateTime start = LocalDateTime.of(today, LocalTime.MIN);
        LocalDateTime end = LocalDateTime.of(today, LocalTime.MAX);
        return new long[] {
                start.atZone(zone).toInstant().toEpochMilli(),
                end.atZone(zone).toInstant().toEpochMilli()
        };
    }

    // ============== 状态查询 ==============

    /**
     * 批量查询账号的当日签到状态
     * <p>
     * accountIds 为 null/空 → 查所有账号
     */
    public List<CheckinStatusItem> queryStatus(List<Long> accountIds) {
        List<WorkbuddyAccountRecord> accounts = loadAccounts(accountIds);
        if (accounts.isEmpty()) {
            return Collections.emptyList();
        }

        long[] range = todayRangeMs();
        List<Long> ids = accounts.stream().map(WorkbuddyAccountRecord::id).toList();
        List<CheckinLogRecord> hits = checkinLogRepository.findInDateRange(ids, CHECKIN_TYPE_DAILY, range[0], range[1]);
        Map<Long, CheckinLogRecord> latestByAccount = new HashMap<>();
        for (CheckinLogRecord rec : hits) {
            latestByAccount.putIfAbsent(rec.accountId(), rec);
        }

        List<CheckinStatusItem> result = new ArrayList<>(accounts.size());
        for (WorkbuddyAccountRecord acc : accounts) {
            CheckinLogRecord hit = latestByAccount.get(acc.id());
            String nickname = extractNickname(acc.accountJson());
            if (hit == null) {
                result.add(new CheckinStatusItem(acc.id(), acc.uid(), nickname, false, null, null));
            } else {
                result.add(new CheckinStatusItem(acc.id(), acc.uid(), nickname, true,
                        hit.checkinTime(), hit.checkinType()));
            }
        }
        return result;
    }

    // ============== 执行签到 ==============

    /**
     * 批量签到（串行调用上游，响应式实现）
     * <p>
     * accountIds 为 null/空 → 给所有"今日未签到"的账号签到
     * <p>
     * 内部在 boundedElastic线程池上执行（同步阻塞 JDBC + HTTP），与 reactor事件循环解耦
     */
    public Mono<List<CheckinResultItem>> executeBatch(List<Long> accountIds) {
        return Mono.fromCallable(() -> {
            List<WorkbuddyAccountRecord> accounts = loadAccounts(accountIds);
            List<CheckinExecutionResult> executed = executeBatchInternal(accounts);
            Map<Long, CheckinResultItem> byId = new HashMap<>();
            for (CheckinExecutionResult item : executed) {
                byId.put(item.result().accountId(), item.result());
            }

            List<CheckinResultItem> ordered = new ArrayList<>(accounts.size());
            for (WorkbuddyAccountRecord acc : accounts) {
                CheckinResultItem item = byId.get(acc.id());
                if (item != null) {
                    ordered.add(item);
                }
            }
            return ordered;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式批量签到（5 线程并发）
     * <p>
     * 输出形态:
     * <ol>
     * <li>按 accounts 原顺序逐个 emit {@code started} —— 前端立即看到"签到中"态</li>
     * <li>已签到账号在 {@code started} 之后立即 emit {@code result}（短路,不上线程池）</li>
     * <li>未签到账号用 {@code checkinExecutor} 并发跑 {@code executeSingle},
     * 由 {@code flatMapSequential} 保证 {@code result} 按 accounts 原顺序 emit</li>
     * <li>最后 emit {@code summary}</li>
     * </ol>
     * <p>
     * <strong>并发度</strong>:固定 5 线程,见
     * {@link com.kaixuan.agentreproxy.config.CheckinExecutorConfig}。
     * 账号数 &lt;= 5 时改走串行(直接用 {@code concatMap})—— 避免线程池调度开销。
     *
     * @param force true 时跳过本地"当日已签到"过滤,强制调用上游
     */
    public Flux<CheckinStreamEvent> executeStream(List<Long> accountIds, boolean force) {
        return Mono.fromCallable(() -> loadAccounts(accountIds))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(accounts -> {
                    if (accounts.isEmpty()) {
                        return Flux.just(CheckinStreamEvent.summary(0, 0, 0, 0L, 0));
                    }

                    long startedAt = System.currentTimeMillis();
                    // force 模式下不查本地记录，alreadyToday 始终为空 map
                    Mono<Map<Long, CheckinLogRecord>> alreadyTodayMono;
                    if (force) {
                        alreadyTodayMono = Mono.just(Collections.emptyMap());
                    } else {
                        long[] range = todayRangeMs();
                        List<Long> ids = accounts.stream().map(WorkbuddyAccountRecord::id).toList();
                        alreadyTodayMono = Mono.fromCallable(() -> buildAlreadyTodayMap(ids, range[0], range[1]))
                                .subscribeOn(Schedulers.boundedElastic());
                    }

                    return alreadyTodayMono.flatMapMany(alreadyToday -> {
                        AtomicInteger okCount = new AtomicInteger();
                        AtomicInteger alreadyCount = new AtomicInteger();
                        AtomicInteger errCount = new AtomicInteger();

                        // 段1:按顺序逐个 emit started;已签到账号在 started 后立即 emit result(不进线程池)
                        Flux<CheckinStreamEvent> startedAndShortCircuit = Flux.range(0, accounts.size())
                                .concatMap(index -> emitStartedOrShortCircuit(
                                        accounts.get(index), alreadyToday,
                                        index + 1, accounts.size(),
                                        okCount, alreadyCount, errCount));

                        // 段2:对需要调上游的账号,并发跑 executeSingle,然后按 accounts 原顺序 emit result
                        Flux<CheckinStreamEvent> resultsConcurrent = emitResultsConcurrent(
                                accounts, alreadyToday, okCount, alreadyCount, errCount);

                        return startedAndShortCircuit
                                .concatWith(resultsConcurrent)
                                .concatWith(Mono.fromSupplier(() -> CheckinStreamEvent.summary(
                                        okCount.get(),
                                        alreadyCount.get(),
                                        errCount.get(),
                                        System.currentTimeMillis() - startedAt,
                                        accounts.size())));
                    });
                });
    }

    /**
     * 段1:emit {@code started} 事件;若账号已签到,再立即 emit {@code result}(短路,不进线程池)。
     * <p>
     * 用 {@code concatMap} 保 accounts 顺序;前端会按这个顺序看到"开始"事件,
     * 视觉上"开始"逐个出现而不是并发刷出。
     */
    private Flux<CheckinStreamEvent> emitStartedOrShortCircuit(
            WorkbuddyAccountRecord acc,
            Map<Long, CheckinLogRecord> alreadyToday,
            int position,
            int total,
            AtomicInteger okCount,
            AtomicInteger alreadyCount,
            AtomicInteger errCount) {
        String nickname = extractNickname(acc.accountJson());
        CheckinLogRecord existing = alreadyToday.get(acc.id());
        if (existing != null) {
            // 已签到:started + result 两事件在主线程内立刻 emit
            CheckinExecutionResult executed = alreadyCheckedResult(acc, existing.checkinTime(), "本地已记录今日签到");
            incrementCounters(executed.result(), okCount, alreadyCount, errCount);
            return Flux.concat(
                    Mono.just(CheckinStreamEvent.started(acc.id(), acc.uid(), nickname, position, total)),
                    Mono.just(CheckinStreamEvent.result(executed.result(), position, total, executed.checkinTime())));
        }
        return Flux.just(CheckinStreamEvent.started(acc.id(), acc.uid(), nickname, position, total));
    }

    /**
     * 段2:对所有"未签到"账号,在 {@code checkinExecutor} 上并发跑 {@code executeSingle},
     * 并发完成后,按 accounts 原顺序 emit {@code result} 事件。
     * <p>
     * 关键实现:
     * <ul>
     * <li>{@code flatMapSequential} 内部维护"按 index 的结果缓冲",只有当上游 emit N 号 result 时,
     * 下游才按顺序看到 N 号 —— 这是 NDJSON 流场景下保持账号视觉顺序的常用 reactor 模式</li>
     * <li>{@code Mono.fromCallable(executeSingle).subscribeOn(Schedulers.fromExecutor(checkinExecutor))}
     * 把阻塞的 JDBC + HTTP 移到 5 线程池</li>
     * <li>accounts.size() &lt;= 并发度时直接用 {@code concatMap} 走串行,避免线程池调度开销</li>
     * </ul>
     */
    private Flux<CheckinStreamEvent> emitResultsConcurrent(
            List<WorkbuddyAccountRecord> accounts,
            Map<Long, CheckinLogRecord> alreadyToday,
            AtomicInteger okCount,
            AtomicInteger alreadyCount,
            AtomicInteger errCount) {
        // 只对"未签到"的账号做并发
        return Flux.range(0, accounts.size())
                .filter(index -> !alreadyToday.containsKey(accounts.get(index).id()))
                .flatMapSequential(index -> {
                    WorkbuddyAccountRecord acc = accounts.get(index);
                    int position = index + 1;
                    int total = accounts.size();
                    return Mono.fromCallable(() -> executeSingle(acc))
                            .subscribeOn(Schedulers.fromExecutor(checkinExecutor))
                            .map(executed -> {
                                incrementCounters(executed.result(), okCount, alreadyCount, errCount);
                                return CheckinStreamEvent.result(executed.result(), position, total,
                                        executed.checkinTime());
                            });
                });
    }

    private List<CheckinExecutionResult> executeBatchInternal(List<WorkbuddyAccountRecord> accounts) {
        if (accounts.isEmpty()) {
            return Collections.emptyList();
        }

        long[] range = todayRangeMs();
        List<Long> ids = accounts.stream().map(WorkbuddyAccountRecord::id).toList();
        Map<Long, CheckinLogRecord> alreadyToday = buildAlreadyTodayMap(ids, range[0], range[1]);

        // 账号数 <= 并发度时走串行:小批量下线程池调度 + Future 编排的开销不划算
        if (accounts.size() <= CHECKIN_CONCURRENCY) {
            return executeBatchSerial(accounts, alreadyToday);
        }
        return executeBatchParallel(accounts, alreadyToday);
    }

    /**
     * 串行版:逐个账号调上游。保留为小批量场景的快路径,避免线程池调度开销。
     */
    private List<CheckinExecutionResult> executeBatchSerial(
            List<WorkbuddyAccountRecord> accounts,
            Map<Long, CheckinLogRecord> alreadyToday) {
        List<CheckinExecutionResult> results = new ArrayList<>(accounts.size());
        for (WorkbuddyAccountRecord acc : accounts) {
            CheckinLogRecord existing = alreadyToday.get(acc.id());
            if (existing != null) {
                results.add(alreadyCheckedResult(acc, existing.checkinTime(), "本地已记录今日签到"));
                continue;
            }
            results.add(executeSingle(acc));
        }
        return results;
    }

    /**
     * 并发版:把"需要调上游的账号"分发给 {@code checkinExecutor} 跑,
     * 用 {@code CompletableFuture.allOf(...).join()} 收齐,最后按原账号顺序拼回结果。
     * <p>
     * 设计要点:
     * <ul>
     * <li>"已签到"账号在主线程直接短路成 {@code alreadyCheckedResult},不占线程资源</li>
     * <li>每个 {@code executeSingle} 内部已 try/catch,异常被吞成 {@code "error"} 结果 —— Future
     * 不会抛</li>
     * <li>结果按 accounts 原顺序拼回,前端 {@code /api/checkin/execute} 响应顺序稳定</li>
     * </ul>
     */
    private List<CheckinExecutionResult> executeBatchParallel(
            List<WorkbuddyAccountRecord> accounts,
            Map<Long, CheckinLogRecord> alreadyToday) {
        // positions[i] = accounts.get(i) 在最终结果列表里的下标;用于并发结果回填
        WorkbuddyAccountRecord[] toSubmit = new WorkbuddyAccountRecord[accounts.size()];
        boolean[] needsSubmit = new boolean[accounts.size()];
        List<CompletableFuture<CheckinExecutionResult>> futures = new ArrayList<>(accounts.size());
        int submitCount = 0;
        for (int i = 0; i < accounts.size(); i++) {
            WorkbuddyAccountRecord acc = accounts.get(i);
            CheckinLogRecord existing = alreadyToday.get(acc.id());
            if (existing != null) {
                continue; // 已签到短路,后面单独写
            }
            toSubmit[i] = acc;
            needsSubmit[i] = true;
            submitCount++;
        }

        for (int i = 0; i < accounts.size(); i++) {
            if (needsSubmit[i]) {
                // 闭包陷阱:循环变量 i 不是 effectively final,在 supplyAsync 异步执行时已变 —— 必须捕获当前值
                final int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> executeSingle(toSubmit[idx]), checkinExecutor));
            }
        }

        // 等待所有并发任务完成。executeSingle 内部已经 try/catch,这里 join 不会抛
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // 按原顺序填回结果
        List<CheckinExecutionResult> results = new ArrayList<>(accounts.size());
        int futureIdx = 0;
        for (int i = 0; i < accounts.size(); i++) {
            WorkbuddyAccountRecord acc = accounts.get(i);
            CheckinLogRecord existing = alreadyToday.get(acc.id());
            if (existing != null) {
                results.add(alreadyCheckedResult(acc, existing.checkinTime(), "本地已记录今日签到"));
            } else {
                results.add(futures.get(futureIdx).join());
                futureIdx++;
            }
        }
        log.info("并发签到完成:total={}, submitted={}, already={}", accounts.size(), submitCount,
                accounts.size() - submitCount);
        return results;
    }

    private Map<Long, CheckinLogRecord> buildAlreadyTodayMap(List<Long> ids, long startMs, long endMs) {
        Map<Long, CheckinLogRecord> alreadyToday = new HashMap<>();
        for (CheckinLogRecord rec : checkinLogRepository.findInDateRange(ids, CHECKIN_TYPE_DAILY, startMs, endMs)) {
            alreadyToday.putIfAbsent(rec.accountId(), rec);
        }
        return alreadyToday;
    }

    private void incrementCounters(CheckinResultItem result,
            AtomicInteger okCount,
            AtomicInteger alreadyCount,
            AtomicInteger errCount) {
        if ("checked_in".equals(result.status())) {
            okCount.incrementAndGet();
            return;
        }
        if ("already_checked_in".equals(result.status())) {
            alreadyCount.incrementAndGet();
            return;
        }
        errCount.incrementAndGet();
    }

    private CheckinExecutionResult alreadyCheckedResult(WorkbuddyAccountRecord acc, Long checkinTime, String message) {
        return new CheckinExecutionResult(
                new CheckinResultItem(acc.id(), acc.uid(), extractNickname(acc.accountJson()), "already_checked_in",
                        message),
                checkinTime);
    }

    private CheckinExecutionResult executeSingle(WorkbuddyAccountRecord acc) {
        String nickname = extractNickname(acc.accountJson());
        long now = System.currentTimeMillis();
        try {
            ResponseEntity<Map<String, Object>> resp = upstreamClient
                    .postBillingForAccount(acc.id(), UpstreamConstants.PATH_DAILY_CHECKIN)
                    .block();

            Map<String, Object> body = resp == null ? null : resp.getBody();
            if (body == null) {
                return new CheckinExecutionResult(
                        new CheckinResultItem(acc.id(), acc.uid(), nickname, "error", "上游返回空 body"),
                        null);
            }

            int code = readCode(body);
            String msg = String.valueOf(body.getOrDefault("msg", ""));
            if (code == 0) {
                String extra = serializeData(body.get("data"));
                // code=0 成功：覆写当日同类型的所有旧记录（含 10001 占位），保证"每个账号每天只一条成功记录"
                long[] range = todayRangeMs();
                int deleted = checkinLogRepository.deleteByAccountIdInDateRange(
                        acc.id(), CHECKIN_TYPE_DAILY, range[0], range[1]);
                if (deleted > 0) {
                    log.info("签到成功 code=0,覆写当日旧记录 account_id={} 删除{}条", acc.id(), deleted);
                }
                checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, extra);
                return new CheckinExecutionResult(
                        new CheckinResultItem(acc.id(), acc.uid(), nickname, "checked_in", msg),
                        now);
            }

            if (code == CODE_ALREADY_CHECKED_IN) {
                long[] range = todayRangeMs();
                List<CheckinLogRecord> existed = checkinLogRepository.findByAccountIdInDateRange(
                        acc.id(), CHECKIN_TYPE_DAILY, range[0], range[1]);
                // 如果当日已有"成功"记录（extra 不含 upstream-10001），不再写占位 —— 成功优先
                CheckinLogRecord successRecord = existed.stream()
                        .filter(r -> !isUpstreamAlreadyMarker(r.extra()))
                        .findFirst()
                        .orElse(null);
                Long checkinTime;
                if (successRecord != null) {
                    // 已有成功记录，本次 10001 不再落库
                    checkinTime = successRecord.checkinTime();
                } else if (existed.isEmpty()) {
                    // 当日完全没记录：补一条占位
                    checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, "{\"source\":\"upstream-10001\"}");
                    checkinTime = now;
                } else {
                    // 只有 10001 占位（理论上不会出现多条），复用最早一条
                    checkinTime = existed.get(0).checkinTime();
                }
                return new CheckinExecutionResult(
                        new CheckinResultItem(acc.id(), acc.uid(), nickname, "already_checked_in", msg),
                        checkinTime);
            }

            return new CheckinExecutionResult(
                    new CheckinResultItem(acc.id(), acc.uid(), nickname, "error",
                            msg.isBlank() ? ("code=" + code) : msg),
                    null);
        } catch (Exception e) {
            log.warn("签到异常 account_id={} uid={}: {}", acc.id(), acc.uid(), e.getMessage());
            return new CheckinExecutionResult(
                    new CheckinResultItem(
                            acc.id(),
                            acc.uid(),
                            nickname,
                            "error",
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    null);
        }
    }

    // ============== 工具 ==============

    private List<WorkbuddyAccountRecord> loadAccounts(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return accountRepository.findAll();
        }
        List<Long> ids = accountIds.stream()
                .filter(Objects::nonNull)
                .toList();
        return accountRepository.findAllByIds(ids);
    }

    private int readCode(Map<String, Object> body) {
        Object c = body.get("code");
        if (c instanceof Number n) {
            return n.intValue();
        }
        if (c instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private String serializeData(Object data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractNickname(String accountJson) {
        if (accountJson == null || accountJson.isBlank()) {
            return "未定义";
        }
        try {
            var obj = objectMapper.readTree(accountJson);
            var nick = obj.path("account").path("nickname");
            if (!nick.isMissingNode() && !nick.isNull() && !nick.asText().isBlank()) {
                return nick.asText();
            }
            nick = obj.path("nickname");
            if (!nick.isMissingNode() && !nick.isNull() && !nick.asText().isBlank()) {
                return nick.asText();
            }
        } catch (Exception ignored) {
        }
        return "未定义";
    }

    /**
     * 判断 extra 是否是上游 10001 占位标记
     * <p>
     * 落库时 code=10001 写 {"source":"upstream-10001"}，code=0 写上游 data 完整对象（不包含此字段）。
     * 用 extra 是否含此字段区分两种签到状态。
     */
    private static boolean isUpstreamAlreadyMarker(String extra) {
        if (extra == null || extra.isBlank()) {
            return false;
        }
        return extra.contains("\"source\":\"upstream-10001\"");
    }

 // ==============历史签到日志分页查询 ==============

 /**
 * 分页查询指定账号的签到历史日志
 * <p>
 * 基于 offset 分页（支持跳页）。排序字段经白名单校验防 SQL 注入。
 * extra 字段反序列化为 Object（通常是 Map）便于前端消费；解析失败返回原始字符串。
 *
 * @param req查询请求（accountId必填）
 * @return 分页结果，list 元素为 {@link com.kaixuan.agentreproxy.dto.CheckinLogItem}
 */
 public com.kaixuan.agentreproxy.dto.PageResult<com.kaixuan.agentreproxy.dto.CheckinLogItem> queryHistoryPage(
 com.kaixuan.agentreproxy.dto.CheckinLogQueryRequest req) {
 if (req == null || req.accountId() == null) {
 throw new IllegalArgumentException("accountId 不能为空");
 }
 long accountId = req.accountId();
 String type = req.safeCheckinType();
 int pageNum = req.safePageNum();
 int pageSize = req.safePageSize();
 String orderBy = req.safeOrderBy();
 boolean asc = req.safeAsc();
 int offset = (pageNum -1) * pageSize;

 long total = checkinLogRepository.countByAccountIdAndType(accountId, type);
 java.util.List<com.kaixuan.agentreproxy.entity.CheckinLogRecord> records =
 checkinLogRepository.findPageByAccountIdAndType(accountId, type, orderBy, asc, offset, pageSize);

 java.util.List<com.kaixuan.agentreproxy.dto.CheckinLogItem> items = records.stream()
 .map(this::toCheckinLogItem)
 .toList();
 return com.kaixuan.agentreproxy.dto.PageResult.of(total, items, pageNum, pageSize);
 }

 /**
 * 把 CheckinLogRecord转成 CheckinLogItem，extra 反序列化为 Object
 */
 private com.kaixuan.agentreproxy.dto.CheckinLogItem toCheckinLogItem(
 com.kaixuan.agentreproxy.entity.CheckinLogRecord rec) {
 Object extraObj = null;
 if (rec.extra() != null && !rec.extra().isBlank()) {
 try {
 extraObj = objectMapper.readValue(rec.extra(), Object.class);
 } catch (Exception ignored) {
 extraObj = rec.extra(); // 解析失败回退为原始字符串
 }
 }
 return new com.kaixuan.agentreproxy.dto.CheckinLogItem(
 rec.id(), rec.accountId(), rec.checkinType(), rec.checkinTime(), extraObj);
 }
}
