package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.dto.ChatAccountPreview;
import com.kaixuan.agentreproxy.dto.ChatRoutingContext;
import com.kaixuan.agentreproxy.dto.ConsumptionSettingValue;
import com.kaixuan.agentreproxy.dto.UpdateConsumptionSettingRequest;
import com.kaixuan.agentreproxy.entity.AppSettingRecord;
import com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.AppSettingJdbcRepository;
import com.kaixuan.agentreproxy.repository.DownstreamApiKeyJdbcRepository;
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
    private final DownstreamApiKeyJdbcRepository downstreamKeyRepository;
    private final ObjectMapper objectMapper;

    public SettingsService(AppSettingJdbcRepository repository,
            WorkbuddyAccountJdbcRepository accountRepository,
            DownstreamApiKeyJdbcRepository downstreamKeyRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.downstreamKeyRepository = downstreamKeyRepository;
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
     * 为 OpenAI Chat 路由按下游 API Key 解析目标账号(响应式包装)。
     * <p>
     * <strong>这是 /v1/chat/completions 当前的权威入口</strong> —— 全局
     * {@code chat.consumption}
     * 设置已废弃,chat 路由改为按请求头 {@code Authorization: Bearer ak-...} 提取 key,
     * 再按 key 自己的 {@code consumption} 字段选号,实现"每个下游 key 独立消耗策略"。
     * <p>
     * 校验链(任一失败抛 {@link IllegalArgumentException} → controller 映射 401):
     * <ol>
     * <li>key 不为空且以 {@code ak-} 开头</li>
     * <li>key 在 {@code downstream_api_key} 表中存在</li>
     * <li>key 处于启用状态({@code enabled=1})</li>
     * <li>key 未过期({@code expires_at} 为 null 或 &gt; now)</li>
     * <li>key 的 {@code consumption} 字段合法,且按该 mode 能选出账号</li>
     * </ol>
     * <p>
     * 选号复用 {@link #resolveExpiringPriorityChatAccountId()} /
     * {@link #resolveLeastPriorityChatAccountId()} /
     * {@link #resolveMostPriorityChatAccountId()} /
     * {@link #resolveDesignatedChatAccountId(ConsumptionSettingValue)} ——
     * 不重写选号逻辑,只是把"全局设置"换成"per-key 设置"。
     * <p>
     * <strong>creditLimit / usedCredits 本期仍不累加</strong> —— 字段已存但 chat 路由不消费,
     * 等计费模块上线再接。
     *
     * @param bearerToken 请求头 Authorization 的值,形如 "Bearer ak-xxxxx"
     * @return {@link ChatRoutingContext} 同时携带目标 accountId 和被命中的下游 keyId
     */
    public Mono<ChatRoutingContext> resolveAccountForApiKey(String bearerToken) {
        return Mono.fromCallable(() -> resolveAccountForApiKeyBlocking(bearerToken))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 同步版:按 key 解析账号 + keyId。跑在 boundedElastic 上。
     * <p>
     * 抛 {@link IllegalArgumentException} 表示鉴权/校验失败,controller 应映射为 401。
     */
    private ChatRoutingContext resolveAccountForApiKeyBlocking(String bearerToken) {
        String apiKey = extractApiKey(bearerToken);
        DownstreamApiKeyRecord keyRec = downstreamKeyRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("无效的 API Key"));

        if (!keyRec.isEnabled()) {
            throw new IllegalArgumentException("API Key 已禁用: " + keyRec.label());
        }
        if (keyRec.expiresAt() != null && keyRec.expiresAt() < System.currentTimeMillis()) {
            throw new IllegalArgumentException("API Key 已过期: " + keyRec.label());
        }

        // 额度检查:creditLimit = null 表示不限;否则 usedCredits >= creditLimit 直接 401
        // —— 本期不消耗上游信用,避免给"无意义的请求"打白条
        var usage = downstreamKeyRepository.findUsageSnapshot(keyRec.id());
        if (usage != null && usage.hasLimit() && usage.isExhausted()) {
            log.warn("[chat] key={} (id={}) 额度已用完: used={}, limit={}",
                    keyRec.label(), keyRec.id(), usage.usedCredits(), usage.creditLimit());
            throw new IllegalArgumentException("额度已用完: " + keyRec.label());
        }

        String mode = keyRec.consumption() == null || keyRec.consumption().isBlank()
                ? "designated" : keyRec.consumption().trim();

        Long accountId = switch (mode) {
            case "designated" -> {
                Long designatedId = keyRec.designatedAccountId();
                if (designatedId == null) {
                    throw new IllegalArgumentException(
                            "key=" + keyRec.label() + " 为指定模式,但未配置 designatedAccountId");
                }
                if (accountRepository.findById(designatedId).isEmpty()) {
                    throw new IllegalArgumentException(
                            "key=" + keyRec.label() + " 指定账号不存在: " + designatedId);
                }
                log.info("[chat] key={} (id={}) designated → accountId={}",
                        keyRec.label(), keyRec.id(), designatedId);
                yield designatedId;
            }
            case "expiring" -> {
                Long picked = resolveExpiringPriorityChatAccountId();
                log.info("[chat] key={} (id={}) expiring → accountId={}",
                        keyRec.label(), keyRec.id(), picked);
                yield picked;
            }
            case "least" -> {
                Long picked = resolveLeastPriorityChatAccountId();
                log.info("[chat] key={} (id={}) least → accountId={}",
                        keyRec.label(), keyRec.id(), picked);
                yield picked;
            }
            case "most" -> {
                Long picked = resolveMostPriorityChatAccountId();
                log.info("[chat] key={} (id={}) most → accountId={}",
                        keyRec.label(), keyRec.id(), picked);
                yield picked;
            }
            default -> throw new IllegalArgumentException(
                    "key=" + keyRec.label() + " consumption 非法: " + mode);
        };
        return new ChatRoutingContext(accountId, keyRec.id());
    }

    /**
     * 从 {@code Authorization: Bearer ak-xxxxx} 提取 {@code ak-xxxxx}。
     * <p>
     * 兼容三种形态:大小写 Bearer / 多个空格 / 纯 key(无 Bearer 前缀,兜底)
     */
    private static String extractApiKey(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("缺少 Authorization 头");
        }
        String trimmed = bearerToken.trim();
        String key = trimmed;
        // 兼容 "Bearer xxx" / "bearer xxx"
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            String scheme = trimmed.substring(0, space);
            if ("Bearer".equalsIgnoreCase(scheme)) {
                key = trimmed.substring(space + 1).trim();
            }
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("Authorization 头缺少 token");
        }
        return key;
    }

    /**
     * 流量包候选的选择模式
     * <ul>
     * <li>{@code EXPIRING} —— 到期时间最早优先</li>
     * <li>{@code LEAST} ——余量最少优先</li>
     * <li>{@code MOST} ——余量最多优先</li>
     * </ul>
     */
    private enum PackagePickMode {
        EXPIRING,
        LEAST,
        MOST
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
        return selectPackageCandidate(PackagePickMode.EXPIRING)
                .map(picked -> {
                    // 打印选中的账号信息,便于排查"为什么这次请求走了这个账号"
                    log.info(
                            "[chat.consumption=expiring] 选中账号 accountId={}, uid={}, packageCode={}, cycleEndTime={}, remain={}",
                            picked.accountId(), picked.uid(), picked.packageCode(),
                            picked.cycleEndTime(), picked.cycleCapacityRemain());
                    return picked.accountId();
                })
                .orElseThrow(() -> {
                    log.warn("[chat.consumption=expiring] 没有可用的临期流量包账号:扫描了 0 个候选");
                    return new IllegalArgumentException(
                            "当前没有可用的临期流量包账号，请先刷新积分数据并确认账号仍有余量");
                });
    }

    /**
     * “量少优先”账号选择器
     * <p>
     * 从所有账号的 credit.packages 中聚合 {@code Σ cycleCapacityRemain},
     * 选出"剩余全部积分"最少的账号.
     * <p>
     * 与“临期优先”的差异:这里以"账号"为粒度,不是按单个包。
     * 用户的“量”指该账号的剩余全部积分(所有 flow 包余量之和),
     * 同一个账号多个包之间不再做更细区分。
     */
    public Long resolveLeastPriorityChatAccountId() {
        return selectAccountByTotalRemain(PackagePickMode.LEAST)
                .map(picked -> {
                    log.info(
                            "[chat.consumption=least] 选中账号 accountId={}, uid={}, totalRemain={}",
                            picked.accountId(), picked.uid(), picked.cycleCapacityRemain());
                    return picked.accountId();
                })
                .orElseThrow(() -> {
                    log.warn("[chat.consumption=least] 没有可用的量少优先账号:扫描了 0 个候选");
                    return new IllegalArgumentException(
                            "当前没有可用的量少优先账号，请先刷新积分数据并确认账号仍有余量");
                });
    }

    /**
     * “量大优先”账号选择器
     * <p>
     * 从所有账号的 credit.packages 中聚合 {@code Σ cycleCapacityRemain},
     * 选出"剩余全部积分"最多的账号.
     * <p>
     * 与“量少优先”同粒度,排序方向相反。
     */
    public Long resolveMostPriorityChatAccountId() {
        return selectAccountByTotalRemain(PackagePickMode.MOST)
                .map(picked -> {
                    log.info(
                            "[chat.consumption=most] 选中账号 accountId={}, uid={}, totalRemain={}",
                            picked.accountId(), picked.uid(), picked.cycleCapacityRemain());
                    return picked.accountId();
                })
                .orElseThrow(() -> {
                    log.warn("[chat.consumption=most] 没有可用的量大优先账号:扫描了 0 个候选");
                    return new IllegalArgumentException(
                            "当前没有可用的量大优先账号，请先刷新积分数据并确认账号仍有余量");
                });
    }

    /**
     * 预览模式:在不真正修改 {@code chat.consumption} 设置的情况下,
     * 返回"如果按当前 mode 路由,后端会选哪个账号"等诊断信息.
     * <p>
     * 当前实现:
     * <ul>
     * <li>{@code expiring} —— 返回最先过期且余量&gt;0 的包所属账号</li>
     * <li>{@code least} / {@code most} —— 暂未实现,抛 400</li>
     * <li>{@code designated} —— 没有"预览"语义(直接读设置里的 accountId),抛 400</li>
     * </ul>
     * 该方法内部跑 JDBC + extra JSON 解析,调用方需要在 boundedElastic 线程池里调
     */
    public ChatAccountPreview previewChatAccountId(String mode) {
        String normalized = mode == null ? "" : mode.trim();
        return switch (normalized) {
            case "expiring" -> {
                ExpiringPackageCandidate picked = selectPackageCandidate(PackagePickMode.EXPIRING).orElseThrow(() -> {
                    log.warn("[preview chat.consumption=expiring] 没有可用的临期流量包账号");
                    return new IllegalArgumentException(
                            "当前没有可用的临期流量包账号，请先刷新积分数据并确认账号仍有余量");
                });
                log.info(
                        "[preview chat.consumption=expiring] 选中账号 accountId={}, uid={}, packageCode={}, cycleEndTime={}, remain={}",
                        picked.accountId(), picked.uid(), picked.packageCode(),
                        picked.cycleEndTime(), picked.cycleCapacityRemain());
                yield new ChatAccountPreview(
                        picked.accountId(),
                        picked.uid(),
                        "expiring",
                        picked.packageCode(),
                        picked.cycleEndTime(),
                        picked.cycleCapacityRemain());
            }
            case "least" -> {
                ExpiringPackageCandidate picked = selectAccountByTotalRemain(PackagePickMode.LEAST).orElseThrow(() -> {
                    log.warn("[preview chat.consumption=least] 没有可用的量少优先账号");
                    return new IllegalArgumentException(
                            "当前没有可用的量少优先账号，请先刷新积分数据并确认账号仍有余量");
                });
                // 账号粒度选中,无单一 packageCode / cycleEndTime;totalRemain 反映账号总余量
                log.info(
                        "[preview chat.consumption=least] 选中账号 accountId={}, uid={}, totalRemain={}",
                        picked.accountId(), picked.uid(), picked.cycleCapacityRemain());
                yield new ChatAccountPreview(
                        picked.accountId(),
                        picked.uid(),
                        "least",
                        null, // 账号粒度:无单一包 code
                        null, // 账号粒度:无单一到期时间
                        picked.cycleCapacityRemain());
            }
            case "most" -> {
                ExpiringPackageCandidate picked = selectAccountByTotalRemain(PackagePickMode.MOST).orElseThrow(() -> {
                    log.warn("[preview chat.consumption=most] 没有可用的量大优先账号");
                    return new IllegalArgumentException(
                            "当前没有可用的量大优先账号，请先刷新积分数据并确认账号仍有余量");
                });
                log.info(
                        "[preview chat.consumption=most] 选中账号 accountId={}, uid={}, totalRemain={}",
                        picked.accountId(), picked.uid(), picked.cycleCapacityRemain());
                yield new ChatAccountPreview(
                        picked.accountId(),
                        picked.uid(),
                        "most",
                        null,
                        null,
                        picked.cycleCapacityRemain());
            }
            case "designated" ->
                throw new IllegalArgumentException(
                        "“指定模式”没有预览语义,请直接读取 GET /api/settings/chat.consumption");
            default ->
                throw new IllegalArgumentException("未知的消耗方式: " + normalized);
        };
    }

    /**
     * * 批量预览多个 mode 命中的账号
     * <p>
     * 一次调用查多个 mode,前端不用发 N 次 HTTP,节省带宽
     * <p>
     * 行为:
     * <ul>
     * <li>输入 modes 是去重后的列表(可以重复,内部去重)</li>
     * <li>每个 mode 独立调 previewChatAccountId,捕获异常 → 失败 mode 对应 null</li>
     * <li>输入为 null/空 → 返回空 Map</li>
     * <li>返回值 Map:key=mode,value=ChatAccountPreview 或 null(失败)</li>
     * </ul>
     * <p>
     * 例:
     * 
     * <pre>
     * 输入 ["least", "most", "expiring", "designated"]
     * 输出 {
     *   "least":    ChatAccountPreview(...),
     *   "most":     ChatAccountPreview(...),
     *   "expiring": ChatAccountPreview(...),
     *   "designated": null   // designated 无预览语义,记录为失败
     * }
     * </pre>
     */
    public Map<String, ChatAccountPreview> previewChatAccountIdBatch(List<String> modes) {
        if (modes == null || modes.isEmpty()) {
            return Map.of();
        }
        // 去重 + trim + 过滤空串
        java.util.LinkedHashMap<String, ChatAccountPreview> result = new java.util.LinkedHashMap<>();
        for (String raw : modes) {
            if (raw == null)
                continue;
            String m = raw.trim();
            if (m.isEmpty() || result.containsKey(m))
                continue;
            try {
                ChatAccountPreview preview = previewChatAccountId(m);
                result.put(m, preview);
            } catch (Exception e) {
                // 单个 mode 失败不影响其他,记录为 null
                log.warn("[preview batch] mode={} 失败: {}", m, e.getMessage());
                result.put(m, null);
            }
        }
        return result;
    }

    /**
     * * 内部:从所有账号中选出符合模式的"目标".
     * <p>
     * 两种粒度:
     * <ul>
     * <li>{@link PackagePickMode#EXPIRING} —— 单包粒度,选"最先过期且余量&gt;0"的包所属账号</li>
     * <li>{@link PackagePickMode#LEAST} / {@link PackagePickMode#MOST} —— 账号粒度,
     * 按"账号全部包余量求和"升序/降序选账号</li>
     * </ul>
     * 不抛异常 —— 找不到时返回空 Optional,由调用方决定如何提示.
     * 这是给 {@link #resolveExpiringPriorityChatAccountId()} /
     * {@link #resolveLeastPriorityChatAccountId()} /
     * {@link #resolveMostPriorityChatAccountId()} /
     * {@link #previewChatAccountId(String)} 共用的核心选择器.
     */
    private java.util.Optional<ExpiringPackageCandidate> selectPackageCandidate(PackagePickMode mode) {
        if (mode == PackagePickMode.EXPIRING) {
            return selectEarliestExpiringPackage();
        }
        return selectAccountByTotalRemain(mode);
    }

    /**
     * 临期优先:扫描所有账号的所有正余量包,按 {@code cycleEndTime} 升序,取最先到期的包所属账号.
     * <p>
     * 粒度到"包"——临期场景下,同一个账号的多个包中"最先过期"的那个就是这次最该消费的;
     * 选到账号后,该账号整体作为路由目标
     */
    private java.util.Optional<ExpiringPackageCandidate> selectEarliestExpiringPackage() {
        List<ExpiringPackageCandidate> candidates = new ArrayList<>();
        for (WorkbuddyAccountRecord acc : accountRepository.findAll()) {
            if (acc.id() == null || !hasUsableCredential(acc)) {
                continue;
            }
            candidates.addAll(readPositiveCreditPackages(acc));
        }
        candidates.sort(comparatorFor(PackagePickMode.EXPIRING));
        return candidates.stream().findFirst();
    }

    /**
     * 量少优先 / 量大优先:按"账号全部包余量求和"为粒度,选总余量最小/最大的账号.
     * <p>
     * 设计依据:用户的"量"指的是该账号"剩余的全部积分"(即所有 flow 包余量之和),
     * 而不是某个特定包。同一账号多个包之间不做更细区分 —— 路由一旦确定走哪个账号,
     * 上游如何扣减是上游的事。
     * <p>
     * 返回的 {@code ExpiringPackageCandidate} 借用了 record 结构但字段语义不一样:
     * <ul>
     * <li>{@code cycleEndTime} = null(账号粒度无单一到期时间)</li>
     * <li>{@code packageCode} = null(无单一命中包)</li>
     * <li>{@code cycleCapacityRemain} = 该账号的
     * {@code Σ packages.cycleCapacityRemain}</li>
     * </ul>
     * 这是为了复用 record 不增加新的内部类。调用方拿到的 preview 会把这些字段当 null 处理。
     */
    private java.util.Optional<ExpiringPackageCandidate> selectAccountByTotalRemain(PackagePickMode mode) {
        List<ExpiringPackageCandidate> accountSummaries = new ArrayList<>();
        for (WorkbuddyAccountRecord acc : accountRepository.findAll()) {
            if (acc.id() == null || !hasUsableCredential(acc)) {
                continue;
            }
            // 聚合:该账号所有"余量 > 0"包的 cycleCapacityRemain 之和
            double totalRemain = 0D;
            boolean hasAnyPositivePackage = false;
            for (Map<String, Object> pkg : readCreditPackageMaps(acc)) {
                double remain = asDouble(pkg.get("cycleCapacityRemain"), 0D);
                if (remain > 0D) {
                    totalRemain += remain;
                    hasAnyPositivePackage = true;
                }
            }
            if (!hasAnyPositivePackage) {
                // 没有任何正余量包的账号不参与"量"比较 —— 否则所有 0 都会并列
                continue;
            }
            accountSummaries.add(new ExpiringPackageCandidate(
                    acc.id(),
                    acc.uid(),
                    null, // 账号粒度无单一 packageCode
                    null, // 账号粒度无单一 cycleEndTime
                    totalRemain));
        }
        accountSummaries.sort(comparatorFor(mode));
        return accountSummaries.stream().findFirst();
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
            case "least" -> resolveLeastPriorityChatAccountId();
            case "most" -> resolveMostPriorityChatAccountId();
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

    /**
     * 解析 {@code extra.credit.packages} 原始 map 列表.
     * <p>
     * 跟 {@link #readPositiveCreditPackages} 共享 extra JSON 解析逻辑;
     * 不做"余量&gt;0"和"到期时间合法"过滤,留给调用方按需使用 —— {@code expiring} 需要过滤,
     * {@code least/most} 要聚合所有包(包括 0 余量)所以不过滤.
     * <p>
     * 解析失败时返回空列表(不抛),由调用方决定如何提示.
     */
    private List<Map<String, Object>> readCreditPackageMaps(WorkbuddyAccountRecord acc) {
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
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object pkgObj : packages) {
                if (!(pkgObj instanceof Map<?, ?> pkgRaw)) {
                    continue;
                }
                Map<String, Object> pkg = castMap(pkgRaw);
                if (pkg == null) {
                    continue;
                }
                out.add(pkg);
            }
            return out;
        } catch (Exception e) {
            log.warn("解析 credit.packages失败 accountId={}, uid={}, err={}",
                    acc.id(), acc.uid(), e.getMessage());
            return List.of();
        }
    }

    private List<ExpiringPackageCandidate> readPositiveCreditPackages(WorkbuddyAccountRecord acc) {
        List<ExpiringPackageCandidate> out = new ArrayList<>();
        for (Map<String, Object> pkg : readCreditPackageMaps(acc)) {
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

    private Comparator<ExpiringPackageCandidate> comparatorFor(PackagePickMode mode) {
        return switch (mode) {
            case EXPIRING -> Comparator
                    .comparing(ExpiringPackageCandidate::cycleEndTime)
                    .thenComparing(ExpiringPackageCandidate::accountId)
                    .thenComparing(c -> c.packageCode() == null ? "" : c.packageCode());
            // least/most 候选粒度是"账号",packageCode 与 cycleEndTime 必为 null,
            // 不参与排序 —— 仅以总余量 + accountId 做 tie-breaker
            case LEAST -> Comparator
                    .comparing(ExpiringPackageCandidate::cycleCapacityRemain)
                    .thenComparing(ExpiringPackageCandidate::accountId);
            case MOST -> Comparator
                    .comparing(ExpiringPackageCandidate::cycleCapacityRemain, Comparator.reverseOrder())
                    .thenComparing(ExpiringPackageCandidate::accountId);
        };
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
