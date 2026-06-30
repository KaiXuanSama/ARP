package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.dto.CheckinResultItem;
import com.kaixuan.agentreproxy.dto.CheckinStatusItem;
import com.kaixuan.agentreproxy.entity.CheckinLogRecord;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.repository.CheckinLogJdbcRepository;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
import java.util.Optional;

/**
 * 每日签到服务
 * <p>
 * 核心设计：
 *   - 不查上游"今日是否已签到"接口（已废弃）
 *   - 改为"调上游签到接口 + 落本地 checkin_log"的双重探针
 *   - 本地有当日日志 = 已签到（不管上游返回啥）
 * <p>
 * 业务码映射：
 *   - code=0     → 签到成功 → 落库 → status="checked_in"
 *   - code=10001 → 当日已签到 → 本地无记录则补录 → status="already_checked_in"
 *   - 其他       → 业务错误，不落库 → status="error"
 */
@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 签到类型（当前唯一一种，未来可扩展） */
    public static final String CHECKIN_TYPE_DAILY = "daily";

    /** 上游业务码：当日已签到 */
    private static final int CODE_ALREADY_CHECKED_IN = 10001;

    private final UpstreamClient upstreamClient;
    private final AccountExtraService accountExtraService;
    private final CheckinLogJdbcRepository checkinLogRepository;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final ObjectMapper objectMapper;

    public CheckinService(UpstreamClient upstreamClient,
                          AccountExtraService accountExtraService,
                          CheckinLogJdbcRepository checkinLogRepository,
                          WorkbuddyAccountJdbcRepository accountRepository,
                          ObjectMapper objectMapper) {
        this.upstreamClient = upstreamClient;
        this.accountExtraService = accountExtraService;
        this.checkinLogRepository = checkinLogRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
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
        // 一次性查所有命中记录，再按 accountId 分组取最新一条
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
     * 内部在 boundedElastic 线程池上执行（同步阻塞 JDBC + HTTP），与 reactor 事件循环解耦
     */
    public Mono<List<CheckinResultItem>> executeBatch(List<Long> accountIds) {
        return Mono.fromCallable(() -> {
            List<WorkbuddyAccountRecord> accounts = loadAccounts(accountIds);
            if (accounts.isEmpty()) {
                return Collections.<CheckinResultItem>emptyList();
            }
            long[] range = todayRangeMs();
            List<Long> ids = accounts.stream().map(WorkbuddyAccountRecord::id).toList();
            Map<Long, CheckinLogRecord> alreadyToday = new HashMap<>();
            for (CheckinLogRecord rec : checkinLogRepository.findInDateRange(ids, CHECKIN_TYPE_DAILY, range[0], range[1])) {
                alreadyToday.putIfAbsent(rec.accountId(), rec);
            }
            // 已签到的直接收集到结果（不动上游）
            List<CheckinResultItem> results = new ArrayList<>(accounts.size());
            List<WorkbuddyAccountRecord> pending = new ArrayList<>();
            for (WorkbuddyAccountRecord acc : accounts) {
                if (alreadyToday.containsKey(acc.id())) {
                    results.add(new CheckinResultItem(acc.id(), acc.uid(), extractNickname(acc.accountJson()),
                            "already_checked_in", "本地已记录今日签到"));
                } else {
                    pending.add(acc);
                }
            }
            // 串行执行：逐个调上游（executeSingle 内部已含 block，整体被 fromCallable + boundedElastic 调度）
            for (WorkbuddyAccountRecord acc : pending) {
                results.add(executeSingle(acc));
            }
            // 按入参顺序返回
            Map<Long, CheckinResultItem> byId = new HashMap<>();
            for (CheckinResultItem r : results) {
                byId.put(r.accountId(), r);
            }
            List<CheckinResultItem> ordered = new ArrayList<>(accounts.size());
            for (WorkbuddyAccountRecord acc : accounts) {
                CheckinResultItem r = byId.get(acc.id());
                if (r != null) ordered.add(r);
            }
            return ordered;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private CheckinResultItem executeSingle(WorkbuddyAccountRecord acc) {
        String nickname = extractNickname(acc.accountJson());
        WorkbuddyDesktopInfo info = accountExtraService.readAccountInfo(acc.uid());
        if (info == null || info.auth() == null || info.auth().accessToken() == null
                || info.auth().accessToken().isBlank()) {
            return new CheckinResultItem(acc.id(), acc.uid(), nickname, "error", "账户缺少 accessToken");
        }

        long now = System.currentTimeMillis();
        try {
            ResponseEntity<Map<String, Object>> resp = upstreamClient
                    .postBillingJsonWithInfo(UpstreamConstants.PATH_DAILY_CHECKIN, Map.of(), info)
                    .block();

            Map<String, Object> body = resp == null ? null : resp.getBody();
            if (body == null) {
                return new CheckinResultItem(acc.id(), acc.uid(), nickname, "error", "上游返回空 body");
            }
            int code = readCode(body);
            String msg = String.valueOf(body.getOrDefault("msg", ""));
            if (code == 0) {
                // 签到成功 → 落库
                String extra = serializeData(body.get("data"));
                checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, extra);
                return new CheckinResultItem(acc.id(), acc.uid(), nickname, "checked_in", msg);
            } else if (code == CODE_ALREADY_CHECKED_IN) {
                // 当日已签到 → 本地无记录则补录（防御性）
                long[] range = todayRangeMs();
                List<CheckinLogRecord> existed = checkinLogRepository.findByAccountIdInDateRange(
                        acc.id(), CHECKIN_TYPE_DAILY, range[0], range[1]);
                if (existed.isEmpty()) {
                    checkinLogRepository.insert(acc.id(), CHECKIN_TYPE_DAILY, now, "{\"source\":\"upstream-10001\"}");
                }
                return new CheckinResultItem(acc.id(), acc.uid(), nickname, "already_checked_in", msg);
            } else {
                // 业务错误 → 不落库
                return new CheckinResultItem(acc.id(), acc.uid(), nickname, "error", msg.isBlank() ? ("code=" + code) : msg);
            }
        } catch (Exception e) {
            log.warn("签到异常 account_id={} uid={}: {}", acc.id(), acc.uid(), e.getMessage());
            return new CheckinResultItem(acc.id(), acc.uid(), nickname, "error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ============== 工具 ==============

    private List<WorkbuddyAccountRecord> loadAccounts(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return accountRepository.findAll();
        }
        List<WorkbuddyAccountRecord> result = new ArrayList<>(accountIds.size());
        for (Long id : accountIds) {
            if (id == null) continue;
            // findByUid 不行，需要 findById
            Optional<WorkbuddyAccountRecord> rec = findById(id);
            rec.ifPresent(result::add);
        }
        return result;
    }

    /**
     * 通过 id 查找账号（WorkbuddyAccountJdbcRepository 现成没有此方法，
     * 简单用 findAll() 过滤实现，数据量小不构成性能问题）
     */
    private Optional<WorkbuddyAccountRecord> findById(Long id) {
        return accountRepository.findAll().stream().filter(a -> id.equals(a.id())).findFirst();
    }

    private int readCode(Map<String, Object> body) {
        Object c = body.get("code");
        if (c instanceof Number n) return n.intValue();
        if (c instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private String serializeData(Object data) {
        if (data == null) return null;
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractNickname(String accountJson) {
        if (accountJson == null || accountJson.isBlank()) return "未定义";
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
