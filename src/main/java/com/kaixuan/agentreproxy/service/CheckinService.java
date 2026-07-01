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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日签到服务
 * <p>
 * 核心设计：
 * - 不查上游"今日是否已签到"接口（已废弃）
 * - 改为"调上游签到接口 + 落本地 checkin_log"的双重探针
 * - 本地有当日日志 = 已签到（不管上游返回啥）
 * <p>
 *业务码映射：
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
 private static final int CODE_ALREADY_CHECKED_IN =10001;

 private final UpstreamClient upstreamClient;
 private final CheckinLogJdbcRepository checkinLogRepository;
 private final WorkbuddyAccountJdbcRepository accountRepository;
 private final ObjectMapper objectMapper;

 public CheckinService(UpstreamClient upstreamClient,
 CheckinLogJdbcRepository checkinLogRepository,
 WorkbuddyAccountJdbcRepository accountRepository,
 ObjectMapper objectMapper) {
 this.upstreamClient = upstreamClient;
 this.checkinLogRepository = checkinLogRepository;
 this.accountRepository = accountRepository;
 this.objectMapper = objectMapper;
 }

 // ============== 当日时间窗 ==============

 /**
 *计算"今日"的毫秒时间范围 [startMs, endMs]（含）
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
 return new long[]{
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
 * 流式批量签到（串行）
 * <p>
 * 输出顺序：每个账号先发 started，再发 result；最后补一条 summary。
 *
 * @param force true 时跳过本地"当日已签到"过滤，强制调用上游
 */
 public Flux<CheckinStreamEvent> executeStream(List<Long> accountIds, boolean force) {
 return Mono.fromCallable(() -> loadAccounts(accountIds))
 .subscribeOn(Schedulers.boundedElastic())
 .flatMapMany(accounts -> {
 if (accounts.isEmpty()) {
 return Flux.just(CheckinStreamEvent.summary(0,0,0,0L,0));
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

 return Flux.range(0, accounts.size())
 .concatMap(index -> emitAccountStream(
 accounts.get(index),
 alreadyToday,
 index +1,
 accounts.size(),
 okCount,
 alreadyCount,
 errCount
 ))
 .concatWith(Mono.fromSupplier(() -> CheckinStreamEvent.summary(
 okCount.get(),
 alreadyCount.get(),
 errCount.get(),
 System.currentTimeMillis() - startedAt,
 accounts.size()
 )));
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

 private Flux<CheckinStreamEvent> emitAccountStream(WorkbuddyAccountRecord acc,
 Map<Long, CheckinLogRecord> alreadyToday,
 int position,
 int total,
 AtomicInteger okCount,
 AtomicInteger alreadyCount,
 AtomicInteger errCount) {
 String nickname = extractNickname(acc.accountJson());
 CheckinLogRecord existing = alreadyToday.get(acc.id());
 if (existing != null) {
 CheckinExecutionResult executed = alreadyCheckedResult(acc, existing.checkinTime(), "本地已记录今日签到");
 incrementCounters(executed.result(), okCount, alreadyCount, errCount);
 return Flux.just(
 CheckinStreamEvent.started(acc.id(), acc.uid(), nickname, position, total),
 CheckinStreamEvent.result(executed.result(), position, total, executed.checkinTime())
 );
 }

 return Flux.concat(
 Mono.just(CheckinStreamEvent.started(acc.id(), acc.uid(), nickname, position, total)),
 Mono.fromCallable(() -> executeSingle(acc))
 .subscribeOn(Schedulers.boundedElastic())
 .map(executed -> {
 incrementCounters(executed.result(), okCount, alreadyCount, errCount);
 return CheckinStreamEvent.result(executed.result(), position, total, executed.checkinTime());
 })
 );
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
 new CheckinResultItem(acc.id(), acc.uid(), extractNickname(acc.accountJson()), "already_checked_in", message),
 checkinTime
 );
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
 null
 );
 }

 int code = readCode(body);
 String msg = String.valueOf(body.getOrDefault("msg", ""));
 if (code ==0) {
 String extra = serializeData(body.get("data"));
 checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, extra);
 return new CheckinExecutionResult(
 new CheckinResultItem(acc.id(), acc.uid(), nickname, "checked_in", msg),
 now
 );
 }

 if (code == CODE_ALREADY_CHECKED_IN) {
 long[] range = todayRangeMs();
 List<CheckinLogRecord> existed = checkinLogRepository.findByAccountIdInDateRange(
 acc.id(), CHECKIN_TYPE_DAILY, range[0], range[1]);
 Long checkinTime = now;
 if (existed.isEmpty()) {
 checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, "{\"source\":\"upstream-10001\"}");
 } else {
 checkinTime = existed.get(0).checkinTime();
 }
 return new CheckinExecutionResult(
 new CheckinResultItem(acc.id(), acc.uid(), nickname, "already_checked_in", msg),
 checkinTime
 );
 }

 return new CheckinExecutionResult(
 new CheckinResultItem(acc.id(), acc.uid(), nickname, "error", msg.isBlank() ? ("code=" + code) : msg),
 null
 );
 } catch (Exception e) {
 log.warn("签到异常 account_id={} uid={}: {}", acc.id(), acc.uid(), e.getMessage());
 return new CheckinExecutionResult(
 new CheckinResultItem(
 acc.id(),
 acc.uid(),
 nickname,
 "error",
 e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
 ),
 null
 );
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
}
