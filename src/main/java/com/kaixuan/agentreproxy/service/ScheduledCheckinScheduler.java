package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.CheckinResultItem;
import com.kaixuan.agentreproxy.entity.AppSettingRecord;
import com.kaixuan.agentreproxy.repository.AppSettingJdbcRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时签到调度器
 * <p>
 * 配置来源:复用 {@code app_settings} 表,读取 key = {@code schedule.dailyCheckin} 的 JSON:
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "time": "08:00"
 * }
 * }</pre>
 * <p>
 * 当前实现策略:
 * <ul>
 *   <li>单线程 daemon 调度器,每 30 秒检查一次配置和当前本地时间</li>
 *   <li>当 enabled=true 且当前 {@code HH:mm} 等于配置 time 时,触发一次全账号签到</li>
 *   <li>用 {@code lastRunKey = yyyy-MM-dd HH:mm} 防止同一分钟内重复触发</li>
 *   <li>真正签到复用 {@link CheckinService#executeBatch(List)},内部已有今日已签短路与 5 线程并发</li>
 * </ul>
 * <p>
 * 不使用 Spring {@code @Scheduled}:本项目没有启用 {@code @EnableScheduling},而且这里需要用户通过
 * app_settings 动态开关/改时间;固定轮询配置更直观,无需重启服务。
 */
@Component
public class ScheduledCheckinScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCheckinScheduler.class);

    /** app_settings key,与前端 Settings.vue 的 SCHEDULE_KEY 保持一致 */
    public static final String KEY_SCHEDULE_DAILY_CHECKIN = "schedule.dailyCheckin";

    /** 前端保存的时间格式 */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 轮询间隔:30 秒。小于 1 分钟,确保不会错过 HH:mm 这一分钟 */
    private static final long POLL_INTERVAL_SECONDS = 30L;

    private final AppSettingJdbcRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final CheckinService checkinService;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上一次成功触发的日期+时间,形如 2026-07-02 08:00 */
    private volatile String lastRunKey = "";

    public ScheduledCheckinScheduler(AppSettingJdbcRepository settingRepository,
                                     ObjectMapper objectMapper,
                                     CheckinService checkinService) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
        this.checkinService = checkinService;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduled-checkin");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeTick, 5, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("ScheduledCheckinScheduler 启动完成:poll={}s,key={}",
                POLL_INTERVAL_SECONDS, KEY_SCHEDULE_DAILY_CHECKIN);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 捕获所有异常,防止调度线程因为未捕获异常而停止。
     */
    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            log.warn("定时签到调度 tick 异常: {}", e.getMessage(), e);
        }
    }

    private void tick() {
        DailyCheckinSetting setting = loadSetting();
        if (!setting.enabled()) {
            return;
        }
        if (setting.time() == null || setting.time().isBlank()) {
            log.debug("定时签到已启用但未配置时间,跳过");
            return;
        }

        LocalTime targetTime;
        try {
            targetTime = LocalTime.parse(setting.time(), HH_MM);
        } catch (DateTimeParseException e) {
            log.warn("定时签到配置时间格式非法: time={},应为 HH:mm", setting.time());
            return;
        }

        LocalDate today = LocalDate.now();
        String nowHHmm = LocalTime.now().format(HH_MM);
        String targetHHmm = targetTime.format(HH_MM);
        if (!nowHHmm.equals(targetHHmm)) {
            return;
        }

        String runKey = today + " " + targetHHmm;
        if (runKey.equals(lastRunKey)) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("定时签到已在执行中,跳过本轮 runKey={}", runKey);
            return;
        }

        // 先标记,避免同一分钟重复触发。即使本次失败,也不会在同一分钟内疯狂重试。
        lastRunKey = runKey;
        try {
            runCheckin(runKey);
        } finally {
            running.set(false);
        }
    }

    /**
     * 读取 app_settings 中的 schedule.dailyCheckin 配置。
     * <p>
     * 缺失 / JSON 非法 / 字段非法时都降级为 disabled,不影响服务运行。
     */
    private DailyCheckinSetting loadSetting() {
        Optional<AppSettingRecord> recOpt = settingRepository.findByKey(KEY_SCHEDULE_DAILY_CHECKIN);
        if (recOpt.isEmpty()) {
            return DailyCheckinSetting.disabled();
        }
        String value = recOpt.get().value();
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())) {
            return DailyCheckinSetting.disabled();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            boolean enabled = root.path("enabled").asBoolean(false);
            String time = root.path("time").isMissingNode() || root.path("time").isNull()
                    ? null
                    : root.path("time").asText(null);
            return new DailyCheckinSetting(enabled, time);
        } catch (Exception e) {
            log.warn("解析定时签到配置失败 value={},err={}", value, e.getMessage());
            return DailyCheckinSetting.disabled();
        }
    }

    /**
     * 执行全账号定时签到。
     * <p>
     * {@code CheckinService.executeBatch(null)} 语义:作用于所有账号,内部会过滤本地今日已签到账号。
     * 此方法运行在 scheduled-checkin daemon 线程中,调用 {@code block()} 是安全的(不在 reactor event-loop 上)。
     */
    private void runCheckin(String runKey) {
        log.info("定时签到触发 runKey={}", runKey);
        try {
            List<CheckinResultItem> results = checkinService.executeBatch(null).block();
            if (results == null) {
                log.warn("定时签到完成但结果为空 runKey={}", runKey);
                return;
            }
            long checked = results.stream().filter(r -> "checked_in".equals(r.status())).count();
            long already = results.stream().filter(r -> "already_checked_in".equals(r.status())).count();
            long error = results.stream().filter(r -> "error".equals(r.status())).count();
            log.info("定时签到完成 runKey={},total={},checked={},already={},error={}",
                    runKey, results.size(), checked, already, error);
        } catch (Exception e) {
            log.warn("定时签到执行失败 runKey={}: {}", runKey, e.getMessage(), e);
        }
    }

    /** 定时签到配置值对象 */
    private record DailyCheckinSetting(boolean enabled, String time) {
        static DailyCheckinSetting disabled() {
            return new DailyCheckinSetting(false, null);
        }
    }
}
