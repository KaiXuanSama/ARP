package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.dto.UsageQueryRequest;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Billing 查询编排：调用上游 → 提取必要字段 → 写入数据库 extra 字段
 * <p>
 * 不做缓存，每次都真实查询上游；extra 仅作为"上一次查询结果的快照"，
 * 供其他功能读取，避免频繁调用上游。
 * <p>
 * 入参从 uid 改为 accountId（数据库主键），凭证由 {@link UpstreamClient} 内部按 accountId 解析。
 * 写 extra 时按 uid 作 key —— extra 是按 uid 索引的，前端 localStorage 也按 uid 缓存。
 * <p>
 * 真实接口（经官方页面抓包确认）：
 * <ul>
 *   <li>POST /v2/billing/meter/get-user-resource         资源包列表</li>
 *   <li>POST /v2/billing/meter/get-user-request-usage   时段用量明细（含 total）</li>
 * </ul>
 */
@Service
public class BillingQueryService {

    /** 上游接受的时间格式:yyyy-MM-dd HH:mm:ss(注意空格分隔,不是 T) */
    private static final DateTimeFormatter UPSTREAM_DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UpstreamClient upstreamClient;
    private final AccountExtraService accountExtraService;
    private final WorkbuddyAccountJdbcRepository accountRepository;

    public BillingQueryService(UpstreamClient upstreamClient,
                               AccountExtraService accountExtraService,
                               WorkbuddyAccountJdbcRepository accountRepository) {
        this.upstreamClient = upstreamClient;
        this.accountExtraService = accountExtraService;
        this.accountRepository = accountRepository;
    }

    /**
     * 查询积分资源包 → 写入 extra.credit
     */
    public Mono<ResponseEntity<Map<String, Object>>> queryCredit(Long accountId) {
        return upstreamClient.postBillingForAccount(accountId, UpstreamConstants.PATH_USER_RESOURCE)
                .map(resp -> {
                    Map<String, Object> credit = persistCredit(accountId, resp);
                    return withExtra(resp, credit == null ? null : Map.of("credit", credit));
                });
    }

    /**
     * 查询时段用量明细 → 写入 extra.usage
     * <p>
     * 不传日期时默认查最近 7 天
     */
    public Mono<ResponseEntity<Map<String, Object>>> queryUsage(Long accountId, UsageQueryRequest req) {
        Map<String, Object> body = new HashMap<>();
        if (req != null) {
            if (req.startTime() != null) body.put("startTime", normalizeTime(req.startTime()));
            if (req.endTime() != null) body.put("endTime", normalizeTime(req.endTime()));
            if (req.pageNum() != null) body.put("pageNum", req.pageNum());
            if (req.pageSize() != null) body.put("pageSize", req.pageSize());
        }
        if (!body.containsKey("startTime") || !body.containsKey("endTime")) {
            // 默认查最近 7 天
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(6);
            body.put("startTime", start.atStartOfDay().format(UPSTREAM_DT_FMT));
            body.put("endTime", LocalDateTime.of(end, LocalTime.of(23, 59, 59)).format(UPSTREAM_DT_FMT));
        }
        if (!body.containsKey("pageNum")) body.put("pageNum", 1);
        if (!body.containsKey("pageSize")) body.put("pageSize", 50);

        return upstreamClient.postBillingJsonForAccount(accountId, UpstreamConstants.PATH_USER_REQUEST_USAGE, body)
                .map(resp -> {
                    Map<String, Object> usage = persistUsage(accountId, resp);
                    return withExtra(resp, usage == null ? null : Map.of("usage", usage));
                });
    }

    // ============== 私有：抽取精简 snapshot ==============

    private Map<String, Object> persistCredit(Long accountId, ResponseEntity<Map<String, Object>> resp) {
        if (accountId == null) return null;
        String uid = resolveUid(accountId);
        if (uid == null) return null;
        Map<String, Object> body = resp.getBody();
        if (body == null) return null;

        Object codeObj = body.get("code");
        if (!(codeObj instanceof Number code) || code.intValue() != 0) return null;

        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map<?, ?> dataRaw)) return null;
        Map<String, Object> data = castMap(dataRaw);
        if (data == null) return null;

        Object respObj = data.get("Response");
        if (!(respObj instanceof Map<?, ?> responseRaw)) return null;
        Map<String, Object> responseMap = castMap(responseRaw);
        if (responseMap == null) return null;

        Object inner = responseMap.get("Data");
        if (!(inner instanceof Map<?, ?> innerRaw)) return null;
        Map<String, Object> innerData = castMap(innerRaw);
        if (innerData == null) return null;

        Map<String, Object> credit = new HashMap<>();
        credit.put("fetchedAt", System.currentTimeMillis());
        credit.put("totalCount", innerData.get("TotalCount"));
        credit.put("totalDosage", innerData.get("TotalDosage"));

        // 提取每个资源包的关键字段
        // 官方页面用的是 CycleCapacity*Precise（带小数的精确值），与之一致
        Object accountsObj = innerData.get("Accounts");
        if (accountsObj instanceof List<?> accounts) {
            List<Map<String, Object>> packages = new ArrayList<>();
            for (Object a : accounts) {
                if (a instanceof Map<?, ?> amRaw) {
                    Map<String, Object> am = castMap(amRaw);
                    if (am == null) continue;
                    Map<String, Object> pkg = new HashMap<>();
                    pkg.put("packageName", String.valueOf(am.getOrDefault("PackageName", "")));
                    pkg.put("packageCode", String.valueOf(am.getOrDefault("PackageCode", "")));
                    pkg.put("cycleCapacitySize", asDouble(am.get("CycleCapacitySizePrecise"), asNumber(am.get("CycleCapacitySize"), 0)));
                    pkg.put("cycleCapacityRemain", asDouble(am.get("CycleCapacityRemainPrecise"), asNumber(am.get("CycleCapacityRemain"), 0)));
                    pkg.put("cycleCapacityUsed", asDouble(am.get("CycleCapacityUsedPrecise"), asNumber(am.get("CycleCapacityUsed"), 0)));
                    pkg.put("cycleStartTime", String.valueOf(am.getOrDefault("CycleStartTime", "")));
                    pkg.put("cycleEndTime", String.valueOf(am.getOrDefault("CycleEndTime", "")));
                    pkg.put("status", asNumber(am.get("Status"), 0));
                    packages.add(pkg);
                }
            }
            credit.put("packages", packages);
        }

        accountExtraService.mergeKey(uid, "credit", credit);
        return credit;
    }

    private Map<String, Object> persistUsage(Long accountId, ResponseEntity<Map<String, Object>> resp) {
        if (accountId == null) return null;
        String uid = resolveUid(accountId);
        if (uid == null) return null;
        Map<String, Object> body = resp.getBody();
        if (body == null) return null;

        Object codeObj = body.get("code");
        if (!(codeObj instanceof Number code) || code.intValue() != 0) return null;

        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map<?, ?> dataRaw)) return null;
        Map<String, Object> data = castMap(dataRaw);
        if (data == null) return null;

        Map<String, Object> usage = new HashMap<>();
        usage.put("fetchedAt", System.currentTimeMillis());
        usage.put("total", asNumber(data.get("total"), 0));
        accountExtraService.mergeKey(uid, "usage", usage);
        return usage;
    }

    /**
     * 按 accountId 查 uid（仅在响应 code=0 时调用，前面已经走过上游，DB 查询很轻）
     */
    private String resolveUid(Long accountId) {
        Optional<WorkbuddyAccountRecord> rec = accountRepository.findById(accountId);
        return rec.map(WorkbuddyAccountRecord::uid).orElse(null);
    }

    /**
     * 把精简 snapshot 附加到响应 body 的 extra 字段
     */
    private ResponseEntity<Map<String, Object>> withExtra(
            ResponseEntity<Map<String, Object>> resp,
            Map<String, Object> extra
    ) {
        Map<String, Object> body = resp.getBody();
        if (body == null) body = new HashMap<>();
        if (extra != null) body.put("extra", extra);
        return ResponseEntity.status(resp.getStatusCode()).body(body);
    }

    // ============== 工具 ==============

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        for (Object k : raw.keySet()) {
            if (!(k instanceof String)) return null;
        }
        return (Map<String, Object>) raw;
    }

    /**
     * 把任意宽松格式的日期时间字符串规范化为上游要求的 "yyyy-MM-dd HH:mm:ss"
     * <p>
     * 接受 "2026-06-2300:00:00" / "2026-06-23T00:00:00" / "2026-06-23 00:00:00" 等
     */
    private static String normalizeTime(String s) {
        if (s == null) return null;
        String trimmed = s.trim().replace('T', ' ').replace('/', '-').replace('.', '-');
        // 尝试多种模式
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
        };
        for (String p : patterns) {
            try {
                if (p.equals("yyyy-MM-dd")) {
                    return java.time.LocalDate.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern(p))
                            .atStartOfDay().format(UPSTREAM_DT_FMT);
                }
                return java.time.LocalDateTime.parse(trimmed,
                        java.time.format.DateTimeFormatter.ofPattern(p)).format(UPSTREAM_DT_FMT);
            } catch (Exception ignored) {
            }
        }
        return s; // 解析失败原样返回（让上游报错以便排查）
    }

    private static int asNumber(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
