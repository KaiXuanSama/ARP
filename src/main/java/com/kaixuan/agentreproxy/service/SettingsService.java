package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.dto.ConsumptionSettingValue;
import com.kaixuan.agentreproxy.dto.UpdateConsumptionSettingRequest;
import com.kaixuan.agentreproxy.entity.AppSettingRecord;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.AppSettingJdbcRepository;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用设置 service
 * <p>
 * 职责:
 * <ul>
 * <li>GET /api/settings —— 返回全部设置项(value 已解析为 Map)</li>
 * <li>PUT /api/settings/chat.consumption —— 校验 + 清洗 + 持久化</li>
 * </ul>
 * <p>
 * 校验规则(针对 chat.consumption):
 * <ol>
 * <li>mode 必填,且必须 ∈ {designated, least, expiring, most}</li>
 * <li>mode="designated" 时,accountId 必填且必须指向 workbuddy_account 中已存在的记录</li>
 * <li>mode≠"designated" 时,无论请求里是否带 accountId,都会被清空(满足"非指定模式清除指定账号字段"的业务规则)</li>
 * </ol>
 * <p>
 * 持久化策略:INSERT OR REPLACE by key,不存在则创建,存在则更新 value + updated_at
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Billing extra.credit.packages.cycleEndTime 的持久化格式（来自上游原样字符串） */
    private static final DateTimeFormatter CREDIT_END_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String KEY_CHAT_CONSUMPTION = "chat.consumption";

    /** 允许的 mode 值集合 */
    private static final java.util.Set<String> ALLOWED_MODES = java.util.Set.of(
            "designated", "least", "expiring", "most");

    private final AppSettingJdbcRepository repository;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final ObjectMapper objectMapper;

    public SettingsService(AppSettingJdbcRepository repository,
            WorkbuddyAccountJdbcRepository accountRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    // ============== 读 ==============

    /**
     * 拿全部设置项(value 反序列化为 Map 便于前端 JSON.parse 后的 object 还原)
     */
    public List<AppSettingResponse> getAll() {
        List<AppSettingResponse> out = new ArrayList<>();
        for (AppSettingRecord rec : repository.findAll()) {
            out.add(toResponse(rec));
        }
        return out;
    }

    /**
     * 按 key 拿一条(找不到时返回空 Optional)
     */
    public Optional<AppSettingResponse> getOne(String key) {
        return repository.findByKey(key).map(this::toResponse);
    }

    /**
     * 读取 "chat.consumption" 的强类型 value
     * <p>
     * 专供 Settings 页面与 OpenAI Chat 路由使用,避免前端每次都去拆通用的 {key,value,updatedAt} 外壳。
     * 找不到时返回 Optional.empty() → controller返404。
     */
    public Optional<ConsumptionSettingValue> getConsumptionValue() {
        return repository.findByKey(KEY_CHAT_CONSUMPTION)
                .map(rec -> readConsumptionValue(rec.value()));
    }

    /**
     * 为 OpenAI Chat 路由解析当前生效的“指定账号”主键
     * <p>
     * 规则:
     * <ol>
     * <li>若根本未保存过设置 → 抛400,提示先去系统设置保存</li>
     * <li>若 mode不是 "designated" → 抛400,说明当前仅支持指定模式</li>
     * <li>若 mode="designated"但 accountId为空 → 抛400</li>
     * <li>若 accountId 指向的账号已删除 → 抛400</li>
     * </ol>
     * <p>
     * 之所以放在 service 而不是 controller:
     * - 校验规则是业务规则,而不是 HTTP细节
     * -未来“量少优先 / 临期优先 /量多优先”实现时,仍然从这里扩展
     */
    public Long resolveDesignatedChatAccountId() {
        ConsumptionSettingValue setting = getConsumptionValue()
                .orElseThrow(() -> new IllegalArgumentException("未配置对话消耗方式，请先在系统设置中保存"));

        if (!"designated".equals(setting.mode())) {
            throw new IllegalArgumentException("当前不是“指定模式”");
        }
        return resolveDesignatedChatAccountId(setting);
    }

    /**
     * 为 OpenAI Chat 路由解析当前生效的账号主键（响应式包装）
     * <p>
     * 该方法内部会做阻塞式 JDBC + extra JSON解析，因此必须整体包进 {@code boundedElastic}。
     * 当前支持：
     * <ul>
     * <li>{@code designated} —— 使用设置表中的指定 accountId</li>
     * <li>{@code expiring} —— 从所有账号的 {@code extra.credit.packages}
     * 中挑选“最先过期且余量&gt;0”的包所属账号</li>
     * </ul>
     * 其余模式继续返回400，提示“暂未实现”。
     */
    public Mono<Long> resolveChatAccountId() {
        return Mono.fromCallable(this::resolveChatAccountIdBlocking)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * “临期优先”账号选择器
     * <p>
     * 数据来源：SQLite {@code workbuddy_account.extra.credit.packages}（Billing 快照）
     * 选择规则：
     * <ol>
     * <li>拉取全部账号</li>
     * <li>仅保留有可用凭证（accessToken / apiKey）且 extra 中存在 credit.packages 的账号</li>
     * <li>剔除 {@code cycleCapacityRemain <=0} 的包</li>
     * <li>按 {@code cycleEndTime} 升序，取最先到期的包所属账号</li>
     * </ol>
     * <p>
     * 若所有账号都没有可用的流量包快照，返回400，引导用户先刷新积分。
     */
    public Long resolveExpiringPriorityChatAccountId() {
        List<ExpiringPackageCandidate> candidates = new ArrayList<>();
        for (WorkbuddyAccountRecord acc : accountRepository.findAll()) {
            if (acc.id() == null || !hasUsableCredential(acc)) {
                continue;
            }
            candidates.addAll(readPositiveCreditPackages(acc));
        }

        candidates.sort(Comparator
                .comparing(ExpiringPackageCandidate::cycleEndTime)
                .thenComparing(ExpiringPackageCandidate::accountId)
                .thenComparing(c -> c.packageCode() == null ? "" : c.packageCode()));

        return candidates.stream()
                .findFirst()
                .map(picked -> {
                    // 打印选中的账号信息,便于排查"为什么这次请求走了这个账号"
                    log.info(
                            "[chat.consumption=expiring] 选中账号 accountId={}, uid={}, packageCode={}, cycleEndTime={}, remain={}",
                            picked.accountId(), picked.uid(), picked.packageCode(),
                            picked.cycleEndTime(), picked.cycleCapacityRemain());
                    return picked.accountId();
                })
                .orElseThrow(() -> {
                    log.warn("[chat.consumption=expiring] 没有可用的临期流量包账号:扫描了 {} 个候选",
                            candidates.size());
                    return new IllegalArgumentException(
                            "当前没有可用的临期流量包账号，请先刷新积分数据并确认账号仍有余量");
                });
    }

    // ============== 写(只暴露 chat.consumption 这一个 key,避免泛 key 写入带来的校验扩散)
    // ==============

    /**
     * 更新 "chat.consumption"
     * <p>
     * 业务规则:
     * - mode="designated" 时,accountId 必填且指向已存在的账户
     * - mode≠"designated" 时,清空 accountId 后再存
     */
    public ConsumptionSettingValue updateConsumption(UpdateConsumptionSettingRequest req) {
        if (req == null || req.mode() == null || req.mode().isBlank()) {
            throw new IllegalArgumentException("mode 必填");
        }
        String mode = req.mode().trim();
        if (!ALLOWED_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "mode 非法: " + mode + "（允许值: designated / least / expiring / most）");
        }

        Long accountId = null;
        if ("designated".equals(mode)) {
            Long incoming = req.accountId();
            if (incoming == null) {
                throw new IllegalArgumentException("mode=designated 时 accountId 必填");
            }
            if (accountRepository.findById(incoming).isEmpty()) {
                throw new IllegalArgumentException("accountId 不存在: " + incoming);
            }
            accountId = incoming;
        }
        // 非 designated 模式:accountId 保持 null(满足"清除指定账号字段"业务规则)

        ConsumptionSettingValue value = new ConsumptionSettingValue(mode, accountId);
        try {
            String json = objectMapper.writeValueAsString(value.toMap());
            repository.upsert(KEY_CHAT_CONSUMPTION, json);
            return value;
        } catch (Exception e) {
            throw new IllegalStateException("序列化或持久化设置失败: " + e.getMessage(), e);
        }
    }

    // ============== 内部 ==============

    private AppSettingResponse toResponse(AppSettingRecord rec) {
        Object value;
        try {
            // 尝试解析成 Map;如果解析失败则降级为 String,前端按需处理
            value = objectMapper.readValue(rec.value(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            value = rec.value();
        }
        return new AppSettingResponse(rec.key(), value, rec.updatedAt());
    }

    private ConsumptionSettingValue readConsumptionValue(String json) {
        try {
            return objectMapper.readValue(json, ConsumptionSettingValue.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析 chat.consumption失败: " + e.getMessage(), e);
        }
    }

    private Long resolveChatAccountIdBlocking() {
        ConsumptionSettingValue setting = getConsumptionValue()
                .orElseThrow(() -> new IllegalArgumentException("未配置对话消耗方式，请先在系统设置中保存"));

        String mode = setting.mode();
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("chat.consumption 缺少 mode，请先在系统设置中重新保存");
        }

        return switch (mode) {
            case "designated" -> resolveDesignatedChatAccountId(setting);
            case "expiring" -> resolveExpiringPriorityChatAccountId();
            case "least", "most" ->
                throw new IllegalArgumentException("当前仅支持“指定模式 / 临期优先”，其余消耗方式暂未实现");
            default -> throw new IllegalArgumentException("未知的对话消耗方式: " + mode);
        };
    }

    private Long resolveDesignatedChatAccountId(ConsumptionSettingValue setting) {
        Long accountId = setting.accountId();
        if (accountId == null) {
            throw new IllegalArgumentException("当前为指定模式，但未选择指定账号");
        }
        if (accountRepository.findById(accountId).isEmpty()) {
            throw new IllegalArgumentException("指定账号不存在: " + accountId);
        }
        return accountId;
    }

    private boolean hasUsableCredential(WorkbuddyAccountRecord acc) {
        return (acc.accessToken() != null && !acc.accessToken().isBlank())
                || (acc.apiKey() != null && !acc.apiKey().isBlank());
    }

    private List<ExpiringPackageCandidate> readPositiveCreditPackages(WorkbuddyAccountRecord acc) {
        if (acc.extra() == null || acc.extra().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> extra = objectMapper.readValue(
                    acc.extra(), new TypeReference<Map<String, Object>>() {
                    });
            Object creditObj = extra.get("credit");
            if (!(creditObj instanceof Map<?, ?> creditRaw)) {
                return List.of();
            }
            Map<String, Object> credit = castMap(creditRaw);
            if (credit == null) {
                return List.of();
            }
            Object packagesObj = credit.get("packages");
            if (!(packagesObj instanceof List<?> packages)) {
                return List.of();
            }

            List<ExpiringPackageCandidate> out = new ArrayList<>();
            for (Object pkgObj : packages) {
                if (!(pkgObj instanceof Map<?, ?> pkgRaw)) {
                    continue;
                }
                Map<String, Object> pkg = castMap(pkgRaw);
                if (pkg == null) {
                    continue;
                }

                double remain = asDouble(pkg.get("cycleCapacityRemain"), 0D);
                if (remain <= 0D) {
                    continue;
                }

                String cycleEndTime = stringValue(pkg.get("cycleEndTime"));
                LocalDateTime parsedEnd = parseCycleEndTime(cycleEndTime);
                if (parsedEnd == null) {
                    continue;
                }

                out.add(new ExpiringPackageCandidate(
                        acc.id(),
                        acc.uid(),
                        stringValue(pkg.get("packageCode")),
                        parsedEnd,
                        remain));
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 credit.packages失败 accountId={}, uid={}, err={}",
                    acc.id(), acc.uid(), e.getMessage());
            return List.of();
        }
    }

    private LocalDateTime parseCycleEndTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), CREDIT_END_TIME_FMT);
        } catch (Exception e) {
            log.warn("解析 cycleEndTime失败 value={}, err={}", raw, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        for (Object k : raw.keySet()) {
            if (!(k instanceof String))
                return null;
        }
        return (Map<String, Object>) raw;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n)
            return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private record ExpiringPackageCandidate(
            Long accountId,
            String uid,
            String packageCode,
            LocalDateTime cycleEndTime,
            double cycleCapacityRemain) {
    }
}
