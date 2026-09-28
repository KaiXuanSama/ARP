package com.kaixuan.agentreproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import com.kaixuan.agentreproxy.service.ModelsConfigService;
import com.kaixuan.agentreproxy.service.RequestTextReplaceService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI兼容端点（/v1/*）
 * <p>
 * 让任意 OpenAI SDK（openai-python / langchain / OpenAI Node 等）能以本服务为 base URL
 * 直接调用。上游是 CodeBuddy（本身就走 OpenAI协议），所以这里只做"加 /v1前缀 + 字段补全 +
 * 鉴权透传"的轻量包装。
 * <p>
 * <strong>Chat路由（已迁移到 per-key）</strong>：
 * <li>从请求头 {@code Authorization: Bearer ak-xxxxx}提取下游 API Key</li>
 * <li>按 key 自己的 {@code consumption} 字段(designated / least / most /
 * expiring)选号</li>
 * <li>全局 {@code app_settings.chat.consumption} 端点已删除(2026-07),
 *     chat 路由自此不再有任何"全局设置"概念;选号全部由 per-key consumption 决定</li>
 * <li>key 不存在 /禁用 / 过期 →401 Unauthorized</li>
 * <li>只暴露 OpenAI协议的一小部分（chat/completions + models）；embedding / completions /
 * files等暂未实现</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    /** OpenAI风格 list响应里的硬编码时间戳锚点（秒）。固定值避免每次响应 created变化导致客户端误判模型列表更新 */
    private static final long MODELS_CREATED_AT = 1700000000L; // 2023-11-14T22:13:20Z

    private static final Logger log = LoggerFactory.getLogger(OpenAiController.class);

    private final UpstreamClient upstream;
    private final ModelsConfigService modelsConfig;
    private final SettingsService settingsService;
    private final ChatUsageRefreshScheduler usageRefreshScheduler;
    private final DownstreamApiKeyService downstreamApiKeyService;
    private final RequestTextReplaceService textReplaceService;
    private final ObjectMapper objectMapper;

    /**
     * 是否打印每个 chunk 的原文（排查用）
     * <p>
     * 默认 {@code false}。开启方式（任选一种）：
     * <ul>
     *   <li>{@code application.yml} 里加 {@code custom.chunk-log.enabled: true}</li>
     *   <li>启动参数 {@code --custom.chunk-log.enabled=true}</li>
     *   <li>环境变量 {@code CUSTOM_CHUNKLOG_ENABLED=true}</li>
     * </ul>
     * <p>
     * <strong>为什么要开关而不是直接用 DEBUG 级别</strong>：一次对话动辄上百个 chunk，
     * 全量打印会把日志冲爆；而把整个包设成 DEBUG 又会带出大量无关框架日志。
     * 用独立开关可以只开这一项，且用 INFO 级别输出（无需改 log level 就能看到）。
     * <p>
     * <strong>为什么前缀是 custom.* 而不是 debug.*</strong>：Spring Boot 内置顶层
     * {@code debug} 布尔属性（开启调试日志），自定义 key 挂在 {@code debug} 下会导致
     * "Expecting a boolean but got a Mapping" 绑定冲突。
     */
    @Value("${custom.chunk-log.enabled:false}")
    private boolean chunkLogEnabled;

    public OpenAiController(UpstreamClient upstream,
            ModelsConfigService modelsConfig,
            SettingsService settingsService,
            ChatUsageRefreshScheduler usageRefreshScheduler,
            DownstreamApiKeyService downstreamApiKeyService,
            RequestTextReplaceService textReplaceService,
            ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.modelsConfig = modelsConfig;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
        this.downstreamApiKeyService = downstreamApiKeyService;
        this.textReplaceService = textReplaceService;
        this.objectMapper = objectMapper;
    }

    // ============== Chat Completions ==============

    /**
     * OpenAI兼容的 Chat补全（流式 SSE透传）
     * <p>
     * 强制 stream=true、补 stream_options、校验 messages >=2。
     * <p>
     * 鉴权：从 {@code Authorization: Bearer ak-xxxxx}提取下游 API Key，
     * 按 key 的 consumption 字段选号。key 不存在/禁用/过期 →401。
     */
    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        // 强制上游只接受 stream=true
        body.put("stream", true);
        if (!body.containsKey("stream_options")) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        // 校验 messages >=2,否则上游会返回400
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.size() < 2) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "messages 至少需要2 条(需包含 system消息)"));
        }
        // 提取下游请求的 model —— 必须在 flatMapMany 之前拿到,用于白名单校验
        String requestedModel = body.get("model") instanceof String m ? m.trim() : null;
        return settingsService.resolveAccountForApiKey(authorization)
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()))
                .flatMapMany(ctx -> {
                    // 模型白名单校验(2026-07 新增):
                    //   - key.supportedModels == null  → 未配置,放行所有模型
                    //   - key.supportedModels == []   → 严格不放行任何模型
                    //   - key.supportedModels == [...] → 仅放行白名单内的 model id
                    // 必须在调用 upstream 之前完成 —— 一旦发出请求就拦不住了
                    if (requestedModel == null || requestedModel.isEmpty()) {
                        // OpenAI 协议要求 model 必填;这里显式校验避免上游 400 转 500
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "请求体缺少 model 字段");
                    }
                    if (ctx.supportedModels() != null) {
                        if (ctx.supportedModels().isEmpty()) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "当前 API Key 不允许调用任何模型: " + requestedModel);
                        }
                        if (!ctx.supportedModels().contains(requestedModel)) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "当前 API Key 不支持模型: " + requestedModel);
                        }
                    }
                    // 鉴权通过,累加下游 key 的 call_count(失败不影响主流程,内部 catch)
                    downstreamApiKeyService.recordCall(ctx.keyId());
                    // 触发 3 分钟后的积分用量自动刷新(全局去重 —— 已有定时器则忽略)
                    usageRefreshScheduler.scheduleRefreshAfterChat(ctx.accountId());
                    // 规范化多模态 content 块顺序(必须在文本替换之前 —— 替换引擎只认字符串 content)
                    normalizeMultimodalBlockOrder(body);
                    // 按用户配置的规则做文本替换（未配置时为空操作）
                    textReplaceService.applyToOpenAiBody(body);
                    return upstream.postChatStreamForAccount(ctx.accountId(), body)
                            // 侧路拦截:每条 SSE 文本 element 都检查一次
                            // CodeBuddy 的"结算 chunk"在 [DONE] 之前带 usage 字段
                            // 透传原 element 给客户端,只做 side-effect 解析 + 落库
                            .doOnNext(element -> interceptChatChunk(element, ctx.keyId(), ctx.accountId()));
                });
    }

    /**
     * 规范化多模态 content 块的顺序 —— 保证 {@code text} 块排在 {@code image_url} 块之前
     * <p>
     * <strong>为什么需要</strong>：上游对多模态消息的块顺序有严格约束 ——
     * {@code content} 数组的 <b>index 0 必须是 text 块</b>，{@code image_url} 只能排在后面。
     * 而下游 Agent（如 GitHub Copilot Chat）发出的顺序**可能相反**
     * （2026-09 抓包对比确认）：
     * <pre>
     * 上游期望 (wb-body)：[{type:"text",...}, {type:"image_url",...}]
     * Copilot  实际：      [{image_url:{...}},   {type:"text",...}]      ← 顺序反了
     * </pre>
     * 顺序错误时上游直接拒绝请求。
     * <p>
     * <strong>处理策略</strong>：只调整顺序，<b>不增删任何字段、不补 {@code type} 字段</b>
     * （下游发的 image 块可能缺 {@code type}，但实测不影响，故保持原样透传）。
     * <p>
     * <strong>作用范围</strong>：
     * <ul>
     *   <li>只处理 {@code content} 是<b>数组</b>的消息；字符串 content 不动</li>
     *   <li>只处理<b>同时含</b> text 与 image_url 的消息；纯文本/纯图片消息不动</li>
     *   <li>只对 OpenAI 端点生效。Anthropic 端点的 content 块语义不同
     *       （{@code type:"image"} + {@code source}），由
     *       {@code AnthropicToOpenAiRequestConverter} 单独处理</li>
     * </ul>
     * <p>
     * <strong>失败不阻断</strong>：任何异常只记 warn，请求原样发出（与
     * {@link RequestTextReplaceService} 的约定一致）。
     * <p>
     * <strong>执行时机</strong>：必须在文本替换<b>之前</b> —— 替换引擎只处理字符串 content，
     * 遇到数组会跳过；先重排不影响替换结果，但保证替换能扫到 text 块。
     *
     * @param body OpenAI 请求体（原地修改）
     */
    @SuppressWarnings("unchecked")
    private void normalizeMultimodalBlockOrder(Map<String, Object> body) {
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object content = ((Map<String, Object>) m).get("content");
            if (!(content instanceof List<?> blocks) || blocks.size() < 2) {
                continue;
            }
            try {
                // 收集 text 块与 image_url 块的位置
                List<Object> texts = new ArrayList<>();
                List<Object> images = new ArrayList<>();
                List<Object> others = new ArrayList<>();
                for (Object b : blocks) {
                    if (!(b instanceof Map<?, ?> blk)) {
                        others.add(b);
                        continue;
                    }
                    // image_url 块可能缺 type 字段，故按"是否有 image_url 键"判定
                    if (blk.containsKey("image_url")) {
                        images.add(b);
                    } else if ("text".equals(blk.get("type"))) {
                        texts.add(b);
                    } else {
                        others.add(b);
                    }
                }
                // 只处理"text 与 image 混排"的消息；其余保持原样
                if (texts.isEmpty() || images.isEmpty()) {
                    continue;
                }
                // 判断是否已经符合顺序：所有 text 都在所有 image 之前
                int lastTextIdx = -1;
                int firstImageIdx = Integer.MAX_VALUE;
                for (int i = 0; i < blocks.size(); i++) {
                    Object b = blocks.get(i);
                    if (!(b instanceof Map<?, ?> blk)) {
                        continue;
                    }
                    if (blk.containsKey("image_url")) {
                        firstImageIdx = Math.min(firstImageIdx, i);
                    } else if ("text".equals(blk.get("type"))) {
                        lastTextIdx = Math.max(lastTextIdx, i);
                    }
                }
                if (lastTextIdx < firstImageIdx) {
                    // 已符合顺序（text 全在 image 之前），无需调整
                    continue;
                }
                // 重排：text 块在前，image 块在后，其余块保持在末尾（相对顺序不变）
                List<Object> reordered = new ArrayList<>(blocks.size());
                reordered.addAll(texts);
                reordered.addAll(images);
                reordered.addAll(others);
                ((Map<String, Object>) m).put("content", reordered);
                log.info("[多模态] 已调整 content 块顺序：text×{} → image×{}（其余 {} 块保持末尾）",
                        texts.size(), images.size(), others.size());
            } catch (Exception e) {
                log.warn("[多模态] content 块顺序调整失败，原样发出: {}", e.getMessage());
            }
        }
    }

    /**
     * 拦截流式响应的每个 chunk,识别"含 usage 字段的最终结算 chunk"并落库
     * <p>
     * 实际收到的 element 形态(已通过 log.json 确认 CodeBuddy 是 <b>NDJSON</b>,
     * 不是 OpenAI 标准 SSE):
     * <ul>
     *   <li>{@code {"id":"...","choices":[...],"usage":null}} —— 中间 content chunk(无 credit)</li>
     *   <li>{@code {"id":"...","choices":[],"usage":{"credit":1.23,...}}} —— 结算 chunk(关键)</li>
     *   <li>{@code [DONE]} —— 流结束标记</li>
     * </ul>
     * <p>
     * 旧实现(<b>已废</b>)按 OpenAI SSE 协议解析,只认 {@code data: ...} 前缀;
     * 实际是 NDJSON 时 {@code startsWith("data:")} 永不命中,导致 {@code recordChatUsage}
     * 永远不被调用 —— 这就是"积分不累加、日志不落库"的根因。
     * <p>
     * <strong>新实现</strong>:
     * <ol>
     *   <li>每行 trim 后,若以 {@code data:} 开头(兼容 OpenAI SSE)→ 剥前缀</li>
     *   <li>否则直接当裸 JSON 处理(兼容 CodeBuddy NDJSON)</li>
     *   <li>内容是 {@code [DONE]} / 空 / 非 JSON → 跳过</li>
     *   <li>解析后 {@code usage} 非 null(不只是含 "usage" 字符串)→ 调 {@code recordChatUsage}</li>
     * </ol>
     *
     * @param element   一条 SSE/NDJSON 文本(可能含多个 chunk + 末行 [DONE])
     * @param keyId     下游 key 主键
     * @param accountId 本次 chat 路由命中的上游账号主键(2026-07 新增,落 call_log.account_id)
     */
    private void interceptChatChunk(String element, Long keyId, Long accountId) {
        if (element == null || element.isBlank()) {
            return;
        }
        // 原始 element 全量打印(排查用) —— 由 custom.chunk-log.enabled 开关控制,默认关闭。
        // 打印"未经任何处理"的 element,便于确认上游到底发了什么字段
        // (例如思维链究竟落在 reasoning_content / thinking / 其它字段)。
        if (chunkLogEnabled) {
            log.info("[chunk原文] keyId={} accountId={} len={} >>>{}<<<",
                    keyId, accountId, element.length(), element);
        }
        try {
            // 按 \n 切 —— SSE 和 NDJSON 都用 \n 分隔 chunk
            for (String line : element.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String json;
                if (trimmed.startsWith("data:")) {
                    // OpenAI SSE: data: {...}
                    json = trimmed.substring("data:".length()).trim();
                } else if (trimmed.startsWith("{")) {
                    // NDJSON: {...} 裸 JSON,直接用
                    json = trimmed;
                } else {
                    // event: / id: / retry: / [DONE] 等其它 SSE 字段 → 跳过
                    if (!"[DONE]".equals(trimmed)) {
                        log.debug("interceptChatChunk skip non-data line: {}", trimmed);
                    }
                    continue;
                }
                if (json.isEmpty() || "[DONE]".equals(json)) continue;
                // 逐 chunk 摘要:把 delta 里所有"非空字符串字段"列出来,
                // 一眼看出思维链在哪个字段(content / reasoning_content / 其它)
                if (chunkLogEnabled) {
                    logDeltaSummary(json);
                }
                // 关键:只对"真正有 usage 结算信息"的 chunk 落库
                // 之前用 json.contains("\"usage\"") 嗅探,会把中间 chunk 的
                // "usage":null 也误判为结算 chunk,导致 call_log 记录爆炸(2w+ 无用行)
                // 改成解析后判断 usage 真的存在且非 null —— 性能开销与原版嗅探相当
                // (JSON.parse 一次性完成,后续 recordChatUsage 也会再 parse,这里
                // 复用了 ObjectMapper 单例,实际是两次 parse 的反序列化结果,微优化留待
                // 后续 —— 当前优先级是修 bug)
                if (hasRealUsage(json)) {
                    downstreamApiKeyService.recordChatUsage(keyId, accountId, json);
                }
            }
        } catch (Exception e) {
            // 任何异常不外抛 —— 这是 side-channel,失败仅记日志
            log.warn("interceptChatChunk 异常 keyId={}: {}", keyId, e.getMessage());
        }
    }

    /**
     * 打印单个 chunk 的 delta 字段摘要（排查用）
     * <p>
     * 列出 {@code choices[].delta} 里<b>所有非空字段</b>，目的是一眼看出
     * 思维链到底落在哪个字段（{@code content} / {@code reasoning_content} /
     * {@code thinking} / 厂商自定义字段）。
     * <p>
     * 只打印非空值，避免满屏都是 {@code "reasoning_content": ""} 这种占位字段。
     * 任何异常静默忽略 —— 这只是排查辅助，不能影响主流程。
     */
    private void logDeltaSummary(String json) {
        try {
            var node = objectMapper.readTree(json);
            var choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // 结算 chunk（choices 为空）—— 单独标记出来
                var usage = node.path("usage");
                if (usage.isObject() && !usage.isEmpty()) {
                    log.info("[chunk摘要] 结算chunk usage={}", usage);
                }
                return;
            }
            for (var choice : choices) {
                var delta = choice.path("delta");
                if (!delta.isObject()) {
                    continue;
                }
                var parts = new java.util.ArrayList<String>();
                delta.properties().forEach(e -> {
                    var v = e.getValue();
                    // 只收集“有实际内容”的字段
                    boolean meaningful =
                            (v.isTextual() && !v.asText().isEmpty())
                                    || (v.isArray() && !v.isEmpty())
                                    || (v.isObject() && !v.isEmpty());
                    if (meaningful) {
                        parts.add(e.getKey() + "=" + v);
                    }
                });
                String finish = choice.path("finish_reason").asText("");
                if (!parts.isEmpty() || !finish.isEmpty()) {
                    log.info("[chunk摘要] delta非空字段: {}{}",
                            parts.isEmpty() ? "(无)" : String.join(", ", parts),
                            finish.isEmpty() ? "" : " | finish_reason=" + finish);
                }
            }
        } catch (Exception ignored) {
            // 排查日志失败不影响主流程
        }
    }

    /**
     * 解析 chunk JSON,判断 {@code usage} 是否真的存在且非 null
     * <p>
     * 与之前 {@code json.contains("\"usage\"")} 的关键差异:
     * <ul>
     *   <li>中间 content chunk 形如 {@code {"choices":[{...}],"usage":null}}
     *       —— 旧逻辑会误命中,新逻辑正确跳过</li>
     *   <li>最终结算 chunk 形如 {@code {"choices":[],"usage":{"credit":...}}}
     *       —— 新逻辑正确识别为真结算,落库</li>
     *   <li>chunk 缺 usage 字段 —— 跳过(可能是上游异常返回)</li>
     * </ul>
     * <p>
     * 解析失败时降级为 false(不落库),让 recordChatUsage 不被无关 chunk 触发
     */
    private boolean hasRealUsage(String json) {
        try {
            var node = objectMapper.readTree(json);
            var usage = node.get("usage");
            // 1) usage 字段不存在 → false
            // 2) usage 是 null(JSON null / Java null) → false
            // 3) usage 是空对象 {} → false
            // 4) usage 是非空对象 {credit:..., ...} → true
            return usage != null && !usage.isNull() && usage.isObject() && usage.size() > 0;
        } catch (Exception e) {
            // 解析失败,降级为 false —— 宁愿漏过结算 chunk 也不要把无关 chunk 写库
            log.debug("hasRealUsage 解析失败,降级为 false: {}", e.getMessage());
            return false;
        }
    }

    // ============== Models ==============

    /**
     * 数据源由 {@link ModelsConfigService} 决定，优先级：环境变量 {@code MODELS_CONFIG_PATH} →
     * 工作目录
     * {@code modelsConfig.json} → classpath 内置
     * {@code models-config.default.json}。改完配置重启服务生效。
     * <p>
     * 输出 shape 严格对齐 OpenAI：
     *
     * <pre>
     * { "object": "list", "data": [ { "id": "auto", "object": "model", "created": 1700000000,
     *                                  "owned_by": "virtual", "context_length": 172032 } ] }
     * </pre>
     * <p>
     * 字段对应：
     * <ul>
     * <li>{@code id} ← ModelConfig.id</li>
     * <li>{@code owned_by} ← ModelConfig.family（CodeBuddy 的"族"概念，作为 owner 占位）</li>
     * <li>{@code context_length}← ModelConfig.contextLength</li>
     * <li>{@code created} ← 全列表共用同一锚点时间（OpenAI 官方也是 created_at 风格）</li>
     * </ul>
     * <p>
     * <strong>Per-key 模型白名单(2026-07 新增)</strong>:
     * <ul>
     *   <li>请求头 {@code Authorization: Bearer ak-xxxxx} 携带下游 API Key → 按 key 的
     *       {@code supportedModels} 字段过滤全集</li>
     *   <li>{@code supportedModels == null} → 不限制(回退全集)</li>
     *   <li>{@code supportedModels == []} → 严格不放行,响应 data 为空数组</li>
     *   <li>{@code supportedModels == ["a","b"]} → 交叉过滤;白名单里找不到的 model id 静默丢弃</li>
     *   <li>未带 Authorization / Key 格式错 / Key 不存在 → 视为匿名,回退全集</li>
     * </ul>
     */
    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return settingsService.resolveApiKeyRecord(authorization)
                .map(this::filterModelsByApiKey)
                .defaultIfEmpty(getAllModels())
                .map(data -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("object", "list");
                    body.put("data", data);
                    return body;
                });
    }

    /**
     * 把 {@code /v1/models} 的全集按 API Key 白名单过滤
     * <p>
     * 调用方应保证 {@code keyRec != null};但这里仍做 null 防御,意外 null 走全集
     */
    private List<Map<String, Object>> filterModelsByApiKey(
            com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord keyRec) {
        if (keyRec == null) {
            return getAllModels();
        }
        java.util.List<String> whitelist = keyRec.supportedModels();
        // null → 不限制,回退全集
        if (whitelist == null) {
            return getAllModels();
        }
        // 空 → 严格不放行(空数组)
        if (whitelist.isEmpty()) {
            return List.of();
        }
        // 非空 → 交叉过滤;白名单里找不到的 model id(已删 / 改名)静默丢弃
        java.util.Set<String> allowed = new java.util.HashSet<>(whitelist);
        return modelsConfig.getModels().stream()
                .filter(m -> allowed.contains(m.id()))
                .map(OpenAiController::toOpenAiModelEntry)
                .toList();
    }

    /** 全模型列表(OpenAI 标准格式) */
    private List<Map<String, Object>> getAllModels() {
        return modelsConfig.getModels().stream()
                .map(OpenAiController::toOpenAiModelEntry)
                .toList();
    }

    private static Map<String, Object> toOpenAiModelEntry(ModelConfig m) {
        // LinkedHashMap 保字段顺序，输出对 OpenAI SDK 更友好
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", m.id());
        model.put("object", "model");
        model.put("created", MODELS_CREATED_AT);
        model.put("owned_by", m.family());
        model.put("context_length", m.contextLength());
        return model;
    }
}
