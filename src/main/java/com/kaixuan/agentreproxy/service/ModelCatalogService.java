package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型目录服务 —— 从上游 /v3/config 拉取真实目录，存内存（2026-10 动态化）
 *
 * <h3>设计决策（与用户确认）</h3>
 * <ul>
 *   <li><b>纯内存、无 TTL、无定时器</b>：启动时自动拉一次 + 管理面板手动更新，
 *       此外不再自动获取 —— 模型变更是低频事件，把控制权交给管理员，
 *       同时把对上游控制面的请求面压到最小（防封）</li>
 *   <li><b>无回退</b>：不保留任何静态清单兜底。目录为空（无账号 / 从未成功拉取）时
 *       {@code /v1/models} 直接报错 —— 宁可诚实报错，不返回过期假清单</li>
 *   <li><b>刷新失败保旧值</b>：手动刷新全部账号失败时保留当前目录不动</li>
 *   <li><b>选号：随机洗牌 + 顺序遍历 + 失败跳过 + 首个成功即止</b>（借鉴
 *       codebuddy2api sync_all 的防封节奏）：每次刷新随机打乱账号顺序，避免固定账号
 *       总是先被打；单账号失败（凭证过期 / 12403 UA 拒绝 / 网络错）记日志跳过；
 *       第一个返回有效目录的账号即成功，不再打其余账号</li>
 * </ul>
 *
 * <h3>条目过滤与映射（对齐 codebuddy2api fetch_model_entries）</h3>
 * <ul>
 *   <li>剔除非聊天模型：{@code tags} 含 {@code text-to-image} / {@code image-to-image} /
 *       {@code text-to-video} / {@code image-to-video} 任一标签的条目（图像/视频生成模型），
 *       {@code tags} 类型无法识别的同样剔除</li>
 *   <li>剔除坏条目：非对象 / 缺 {@code id} / 空 id</li>
 *   <li>id 去重（后出现的跳过）</li>
 *   <li>映射：{@code id→id}、{@code vendor→family}（空则 "unknown"）、
 *       {@code maxInputTokens→contextLength}</li>
 * </ul>
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

    /** 非聊天模型标签：命中任一则从目录剔除（图像/视频生成） */
    private static final Set<String> NON_CHAT_TAGS = Set.of(
            "text-to-image", "image-to-image", "text-to-video", "image-to-video");

    /** 刷新单飞：同一时刻只允许一次刷新在跑 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** 当前目录（不可变快照，替换式更新） */
    private volatile List<ModelConfig> models = List.of();

    /**
     * 上游原始条目快照（保留全部字段，供管理面板「查看模型列表」展示模型能力与约束）。
     * <p>
     * 与 {@link #models} 同批写入：原始数组深拷贝为不可变文本快照（JsonNode 本身可变，
     * 拷贝时经 toString 固化），发布后只读。仅用于展示，不参与路由/白名单逻辑。
     */
    private volatile List<JsonNode> rawModels = List.of();

    /** 目录元信息（来源账号 / 抓取时间 / 条数），供管理面板展示 */
    private volatile Map<String, Object> meta = Map.of();

    private final UpstreamClient upstreamClient;
    private final WorkbuddyAccountJdbcRepository accountRepository;

    public ModelCatalogService(UpstreamClient upstreamClient,
                               WorkbuddyAccountJdbcRepository accountRepository) {
        this.upstreamClient = upstreamClient;
        this.accountRepository = accountRepository;
    }

    // ============== 启动首拉 ==============

    /**
     * 启动完成后异步首拉（不阻塞启动；失败仅记日志，目录留空等手动更新）。
     * <p>
     * 与"无自动获取"哲学一致：首拉失败不做自动重试 —— 管理员在面板手动更新即可。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh()
                .subscribe(
                        m -> log.info("[模型目录] 启动首拉成功：{} 个模型（来源账号 #{} {}）",
                                m.get("count"), m.get("sourceAccountId"), m.get("sourceLabel")),
                        e -> log.error("[模型目录] 启动首拉失败（目录为空，请稍后在管理面板手动更新）: {}",
                                e.getMessage()));
    }

    // ============== 刷新 ==============

    /**
     * 刷新模型目录：全部启用账号随机洗牌后顺序尝试，首个成功即止。
     *
     * @return 刷新结果元信息（count / sourceAccountId / sourceLabel / fetchedAt）
     * @throws IllegalStateException 无可用账号 / 全部账号失败 / 已有刷新在进行中
     *                               <p>
     *                               失败时<b>当前目录保留不动</b>。
     */
    public Mono<Map<String, Object>> refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return Mono.error(new IllegalStateException("模型目录刷新已在进行中，请稍候"));
        }
        // DB 查询与遍历都在 boundedElastic 上跑（绝不在事件循环上阻塞）
        return Mono.fromCallable(this::doRefresh)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> refreshing.set(false));
    }

    private Map<String, Object> doRefresh() {
        List<WorkbuddyAccountRecord> accounts = accountRepository.findAll().stream()
                .filter(WorkbuddyAccountRecord::isEnabled)
                .toList();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("没有可用账号（启用状态），无法拉取模型目录");
        }

        // 随机洗牌：避免固定账号总是先被打上游（防封，借鉴 codebuddy2api sync_all）
        List<WorkbuddyAccountRecord> shuffled = new ArrayList<>(accounts);
        Collections.shuffle(shuffled);

        List<String> failures = new ArrayList<>();
        for (WorkbuddyAccountRecord account : shuffled) {
            try {
                JsonNode rawModels = upstreamClient.fetchModelConfig(account.id())
                        .block(java.time.Duration.ofSeconds(30));
                List<ModelConfig> parsed = parseModels(rawModels);
                if (parsed.isEmpty()) {
                    // 上游目录为空视为该账号失败（正常账号不会为空），顺延下一个
                    failures.add("#" + account.id() + " 目录为空");
                    continue;
                }
                // 首个成功即止：存快照 + 原始条目 + 元信息
                this.models = List.copyOf(parsed);
                this.rawModels = copyRaw(rawModels);
                this.meta = buildMeta(account, parsed.size());
                log.info("[模型目录] 刷新成功：{} 个模型（来源账号 #{} {}，失败跳过 {} 个账号：{}）",
                        parsed.size(), account.id(), labelOf(account), failures.size(), failures);
                return this.meta;
            } catch (Exception e) {
                // 单账号失败：凭证过期 / 12403 UA 拒绝 / 网络错 —— 记日志跳过，顺延下一个
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failures.add("#" + account.id() + " " + reason);
                log.debug("[模型目录] 账号 #{} 拉取失败，顺延下一个: {}", account.id(), reason);
            }
        }
        throw new IllegalStateException("模型目录刷新失败：所有账号均未成功（" + failures + "）");
    }

    /** 过滤 + 映射上游原始条目（调用方在 boundedElastic） */
    private static List<ModelConfig> parseModels(JsonNode rawModels) {
        List<ModelConfig> out = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        if (rawModels == null) {
            return out;
        }
        for (JsonNode entry : rawModels) {
            if (!entry.isObject()) {
                continue;
            }
            String id = entry.path("id").asText("");
            if (id.isBlank() || !seenIds.add(id)) {
                continue;   // 缺 id / 空 id / 重复 id
            }
            if (hasNonChatTag(entry.path("tags"))) {
                continue;   // 图像/视频生成模型
            }
            String vendor = entry.path("vendor").asText("");
            int contextLength = entry.path("maxInputTokens").asInt(0);
            out.add(new ModelConfig(id, vendor.isBlank() ? "unknown" : vendor, contextLength));
        }
        return out;
    }

    /** tags 命中非聊天标签 → true（剔除）；tags 缺失视为聊天模型；类型无法识别 → true（防御剔除） */
    private static boolean hasNonChatTag(JsonNode tags) {
        if (tags.isMissingNode() || tags.isNull()) {
            return false;
        }
        if (tags.isTextual()) {
            return NON_CHAT_TAGS.contains(tags.asText());
        }
        if (tags.isArray()) {
            for (JsonNode t : tags) {
                if (t.isTextual() && NON_CHAT_TAGS.contains(t.asText())) {
                    return true;
                }
            }
            return false;
        }
        return true;   // 无法识别的类型，防御性剔除
    }

    private static Map<String, Object> buildMeta(WorkbuddyAccountRecord source, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("sourceAccountId", source.id());
        m.put("sourceLabel", labelOf(source));
        m.put("fetchedAt", System.currentTimeMillis());
        return m;
    }

    /** 展示用账号标签：优先 uid（库内没有独立昵称列时 uid 即唯一标识） */
    private static String labelOf(WorkbuddyAccountRecord account) {
        return account.uid() == null ? String.valueOf(account.id()) : account.uid();
    }

    // ============== 查询 ==============

    /** 当前目录（只读快照；从未成功拉取时为空列表） */
    public List<ModelConfig> getModels() {
        return models;
    }

    /**
     * 上游原始条目快照（含全部字段；从未成功拉取时为空列表）。
     * <p>
     * 返回的节点经 toString 固化后重新解析，调用方拿到的是与本服务状态隔离的
     * 只读副本 —— 即使下游序列化/修改也不会影响内存目录。
     */
    public List<JsonNode> getRawModels() {
        return rawModels;
    }

    /** 目录元信息（count / sourceAccountId / sourceLabel / fetchedAt；从未拉取时为空 map） */
    public Map<String, Object> getMeta() {
        return meta;
    }

    /**
     * 原始数组 → 不可变快照：JsonNode 树是可变 DOM，直接引用存字段等于埋雷
     * （上游响应的解析树被后续复用/修改会污染目录）。这里经 toString 固化，
     * 重新解析出独立副本；单条解析失败防御跳过，不影响其余条目。
     */
    private static List<JsonNode> copyRaw(JsonNode rawModels) {
        if (rawModels == null || !rawModels.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>(rawModels.size());
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (JsonNode entry : rawModels) {
            if (!entry.isObject()) {
                continue;
            }
            try {
                out.add(mapper.readTree(entry.toString()));
            } catch (Exception e) {
                // 理论上 toString→readTree 不会失败（来源就是合法 JSON）；防御性跳过
            }
        }
        return List.copyOf(out);
    }
}
