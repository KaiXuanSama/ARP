package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.CheckinLogJdbcRepository;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总览页数据聚合服务
 * <p>
 * 一次性查出所有统计数据，返回结构化 Map 给 controller。
 * 所有 JDBC 操作同步执行（调用方需包在 {@code Mono.fromCallable + boundedElastic} 中）。
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final CheckinLogJdbcRepository checkinLogRepository;
    private final ObjectMapper objectMapper;

    public OverviewService(JdbcTemplate jdbcTemplate,
                           WorkbuddyAccountJdbcRepository accountRepository,
                           CheckinLogJdbcRepository checkinLogRepository,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountRepository = accountRepository;
        this.checkinLogRepository = checkinLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 聚合全部总览数据
     */
    public Map<String, Object> aggregate() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", buildStats());
        result.put("callTrend", buildCallTrend());
        result.put("callByKey", buildCallByKey());
        result.put("callByModel", buildCallByModel());
        result.put("creditHealth", buildCreditHealth());
        return result;
    }

    // ============== 第一层：核心统计卡片 ==============

    private Map<String, Object> buildStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 上游账号统计
        List<WorkbuddyAccountRecord> accounts = accountRepository.findAll();
        int accountTotal = accounts.size();
        int accountEnabled = (int) accounts.stream().filter(WorkbuddyAccountRecord::isEnabled).count();
        int accountDisabled = accountTotal - accountEnabled;
        stats.put("accounts", Map.of(
                "total", accountTotal,
                "enabled", accountEnabled,
                "disabled", accountDisabled));

        // 下游 Key 统计
        long now = System.currentTimeMillis();
        List<Map<String, Object>> keyRows = jdbcTemplate.queryForList(
                "SELECT enabled, expires_at FROM downstream_api_key");
        int keyTotal = keyRows.size();
        int keyEnabled = 0;
        int keyDisabled = 0;
        int keyExpired = 0;
        for (Map<String, Object> row : keyRows) {
            int enabled = ((Number) row.get("enabled")).intValue();
            Object expiresAtObj = row.get("expires_at");
            Long expiresAt = expiresAtObj == null ? null : ((Number) expiresAtObj).longValue();
            if (expiresAt != null && expiresAt < now) {
                keyExpired++;
            } else if (enabled == 1) {
                keyEnabled++;
            } else {
                keyDisabled++;
            }
        }
        stats.put("keys", Map.of(
                "total", keyTotal,
                "enabled", keyEnabled,
                "disabled", keyDisabled,
                "expired", keyExpired));

        // 今日调用
        long[] todayRange = todayRangeMs();
        Long todayCalls = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM downstream_api_key_call_log " +
                        "WHERE created_at BETWEEN ? AND ?",
                Long.class, todayRange[0], todayRange[1]);
        stats.put("todayCalls", todayCalls == null ? 0L : todayCalls);

        // 总积分余量 —— 聚合所有账号 extra.credit.packages[].cycleCapacityRemain
        // 只聚合 enabled 的账号(停用的账号不会被实际调用,余量无意义)
        List<WorkbuddyAccountRecord> enabledAccounts = accounts.stream()
                .filter(WorkbuddyAccountRecord::isEnabled)
                .toList();
        double totalCreditRemain = 0;
        int creditAccountCount = 0;
        for (WorkbuddyAccountRecord acc : enabledAccounts) {
            double remain = readTotalCreditRemain(acc.extra());
            if (remain > 0) {
                totalCreditRemain += remain;
                creditAccountCount++;
            }
        }
        stats.put("totalCredit", Map.of(
                "remain", totalCreditRemain,
                "accountCount", creditAccountCount));

        // 今日签到
        List<Long> enabledAccountIds = accounts.stream()
                .filter(WorkbuddyAccountRecord::isEnabled)
                .map(WorkbuddyAccountRecord::id)
                .toList();
        int checkinDone = 0;
        if (!enabledAccountIds.isEmpty()) {
            checkinDone = checkinLogRepository.findInDateRange(
                    enabledAccountIds, CheckinService.CHECKIN_TYPE_DAILY,
                    todayRange[0], todayRange[1]).stream()
                    .map(r -> r.accountId())
                    .distinct()
                    .toList()
                    .size();
        }
        stats.put("todayCheckin", Map.of(
                "done", checkinDone,
                "total", enabledAccountIds.size()));

        return stats;
    }

    // ============== 第二层：7 天调用分布 ==============

    /**
     * 近 7 天按日期 × key_id 的堆叠柱状图数据
     */
    private List<Map<String, Object>> buildCallTrend() {
        long[] range = last7DaysRangeMs();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS dt, " +
                        "       key_id, COUNT(*) AS cnt " +
                        "FROM downstream_api_key_call_log " +
                        "WHERE created_at BETWEEN ? AND ? " +
                        "GROUP BY dt, key_id " +
                        "ORDER BY dt ASC, key_id ASC",
                range[0], range[1]);

        // 查 key label 映射
        Map<Long, String> keyLabels = queryKeyLabels();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row.get("dt"));
            Object keyIdObj = row.get("key_id");
            Long keyId = keyIdObj == null ? null : ((Number) keyIdObj).longValue();
            item.put("keyId", keyId);
            item.put("keyLabel", keyId != null ? keyLabels.getOrDefault(keyId, "Key#" + keyId) : "未知");
            item.put("count", ((Number) row.get("cnt")).longValue());
            result.add(item);
        }
        return result;
    }

    /**
     * 近 7 天按 key 的调用占比
     */
    private List<Map<String, Object>> buildCallByKey() {
        long[] range = last7DaysRangeMs();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT key_id, COUNT(*) AS cnt " +
                        "FROM downstream_api_key_call_log " +
                        "WHERE created_at BETWEEN ? AND ? " +
                        "GROUP BY key_id " +
                        "ORDER BY cnt DESC",
                range[0], range[1]);

        Map<Long, String> keyLabels = queryKeyLabels();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            Object keyIdObj = row.get("key_id");
            Long keyId = keyIdObj == null ? null : ((Number) keyIdObj).longValue();
            item.put("keyId", keyId);
            item.put("keyLabel", keyId != null ? keyLabels.getOrDefault(keyId, "Key#" + keyId) : "未知");
            item.put("count", ((Number) row.get("cnt")).longValue());
            result.add(item);
        }
        return result;
    }

    /**
     * 近 7 天按模型的调用占比
     * <p>
     * model 字段从 call_log.content (JSON) 中提取
     */
    private List<Map<String, Object>> buildCallByModel() {
        long[] range = last7DaysRangeMs();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT content FROM downstream_api_key_call_log " +
                        "WHERE created_at BETWEEN ? AND ?",
                range[0], range[1]);

        Map<String, Long> modelCounts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String content = (String) row.get("content");
            String model = extractModelFromChunk(content);
            if (model != null && !model.isEmpty()) {
                modelCounts.merge(model, 1L, Long::sum);
            }
        }

        // 按 count 降序排序
        List<Map<String, Object>> result = new ArrayList<>();
        modelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("model", e.getKey());
                    item.put("count", e.getValue());
                    result.add(item);
                });
        return result;
    }

    // ============== 第三层：积分健康度 ==============

    private List<Map<String, Object>> buildCreditHealth() {
        List<WorkbuddyAccountRecord> accounts = accountRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (WorkbuddyAccountRecord acc : accounts) {
            if (acc.extra() == null || acc.extra().isBlank()) continue;

            Map<String, Object> extra;
            try {
                extra = objectMapper.readValue(acc.extra(), new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("解析 extra 失败 accountId={}: {}", acc.id(), e.getMessage());
                continue;
            }

            Object creditObj = extra.get("credit");
            if (!(creditObj instanceof Map<?, ?> creditMap)) continue;

            Object packagesObj = creditMap.get("packages");
            if (!(packagesObj instanceof List<?> packagesList) || packagesList.isEmpty()) continue;

            String nickname = extractNickname(acc.accountJson());
            Long fetchedAt = creditMap.get("fetchedAt") instanceof Number n ? n.longValue() : null;

            // 聚合所有流量包：总余量、总容量、未用尽的最早到期时间
            double totalRemain = 0;
            double totalCapacity = 0;
            String nearestEndTime = null;
            long nearestEndMs = Long.MAX_VALUE;

            for (Object pkgObj : packagesList) {
                if (!(pkgObj instanceof Map<?, ?> pkg)) continue;
                double remain = toDouble(pkg.get("cycleCapacityRemain"));
                double capacity = toDouble(pkg.get("cycleCapacitySize"));
                totalRemain += remain;
                totalCapacity += capacity;

                // 只看未用尽的包(remain > 0)的到期时间
                if (remain > 0) {
                    Object endObj = pkg.get("cycleEndTime");
                    if (endObj instanceof String endStr && !endStr.isBlank()) {
                        try {
                            long endMs = java.time.LocalDateTime.parse(endStr,
                                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                            if (endMs < nearestEndMs) {
                                nearestEndMs = endMs;
                                nearestEndTime = endStr;
                            }
                        } catch (Exception ignored) {
                            // 格式不对就跳过
                        }
                    }
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("accountId", acc.id());
            item.put("uid", acc.uid());
            item.put("nickname", nickname);
            item.put("enabled", acc.isEnabled());
            item.put("totalRemain", totalRemain);
            item.put("totalCapacity", totalCapacity);
            item.put("nearestEndTime", nearestEndTime);
            item.put("fetchedAt", fetchedAt);
            result.add(item);
        }
        return result;
    }

    // ============== 工具方法 ==============

    private long[] todayRangeMs() {
        return CheckinService.todayRangeMs(ZoneId.systemDefault());
    }

    /**
     * 最近 7 天的毫秒时间范围 [startMs, endMs]（含今天）
     */
    private long[] last7DaysRangeMs() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(6);
        long startMs = LocalDateTime.of(start, LocalTime.MIN).atZone(zone).toInstant().toEpochMilli();
        long endMs = LocalDateTime.of(today, LocalTime.MAX).atZone(zone).toInstant().toEpochMilli();
        return new long[]{startMs, endMs};
    }

    /**
     * 查询所有下游 key 的 id → label 映射
     */
    private Map<Long, String> queryKeyLabels() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, label FROM downstream_api_key");
        Map<Long, String> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            map.put(((Number) row.get("id")).longValue(), (String) row.get("label"));
        }
        return map;
    }

    /**
     * 从 chunk JSON 中提取 model 字段
     */
    private String extractModelFromChunk(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(content);
            JsonNode modelNode = node.get("model");
            return modelNode != null && modelNode.isTextual() ? modelNode.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 accountJson 中提取昵称
     */
    private String extractNickname(String accountJson) {
        if (accountJson == null || accountJson.isBlank()) return "未知";
        try {
            JsonNode node = objectMapper.readTree(accountJson);
            // 嵌套: obj.account.nickname
            JsonNode nn = node.path("account").path("nickname");
            if (nn.isTextual() && !nn.asText().isBlank()) return nn.asText();
            // 数组: obj.accounts[0].nickname
            JsonNode arr = node.path("accounts");
            if (arr.isArray() && !arr.isEmpty()) {
                nn = arr.get(0).path("nickname");
                if (nn.isTextual() && !nn.asText().isBlank()) return nn.asText();
            }
            // 扁平: obj.nickname
            nn = node.path("nickname");
            if (nn.isTextual() && !nn.asText().isBlank()) return nn.asText();
        } catch (Exception e) {
            // fall through
        }
        return "未知";
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /**
     * 读取账号 extra.credit.packages[] 中所有 cycleCapacityRemain 之和
     * <p>
     * 解析失败 / extra 为空 / credit.packages 为空 → 返回 0
     */
    private double readTotalCreditRemain(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return 0;
        try {
            Map<String, Object> extra = objectMapper.readValue(extraJson, new TypeReference<>() {});
            Object credit = extra.get("credit");
            if (!(credit instanceof Map<?, ?> creditMap)) return 0;
            Object packages = creditMap.get("packages");
            if (!(packages instanceof List<?> packagesList) || packagesList.isEmpty()) return 0;
            double total = 0;
            for (Object pkg : packagesList) {
                if (pkg instanceof Map<?, ?> pkgMap) {
                    total += toDouble(pkgMap.get("cycleCapacityRemain"));
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }
}
