package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import com.kaixuan.agentreproxy.service.ModelsConfigService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OpenAiController(UpstreamClient upstream,
            ModelsConfigService modelsConfig,
            SettingsService settingsService,
            ChatUsageRefreshScheduler usageRefreshScheduler,
            DownstreamApiKeyService downstreamApiKeyService) {
        this.upstream = upstream;
        this.modelsConfig = modelsConfig;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
        this.downstreamApiKeyService = downstreamApiKeyService;
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
        return settingsService.resolveAccountForApiKey(authorization)
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()))
                .flatMapMany(ctx -> {
                    // 鉴权通过,累加下游 key 的 call_count(失败不影响主流程,内部 catch)
                    downstreamApiKeyService.recordCall(ctx.keyId());
                    // 触发 3 分钟后的积分用量自动刷新(全局去重 —— 已有定时器则忽略)
                    usageRefreshScheduler.scheduleRefreshAfterChat(ctx.accountId());
                    return upstream.postChatStreamForAccount(ctx.accountId(), body)
                            // 侧路拦截:每条 SSE 文本 element 都检查一次
                            // CodeBuddy 的"结算 chunk"在 [DONE] 之前带 usage 字段
                            // 透传原 element 给客户端,只做 side-effect 解析 + 落库
                            .doOnNext(element -> interceptChatChunk(element, ctx.keyId()));
                });
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
     *   <li>含 {@code "usage"} 字段 → 调 {@code recordChatUsage}</li>
     * </ol>
     *
     * @param element 一条 SSE/NDJSON 文本(可能含多个 chunk + 末行 [DONE])
     * @param keyId   下游 key 主键
     */
    private void interceptChatChunk(String element, Long keyId) {
        if (element == null || element.isBlank()) {
            return;
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
                // 快速嗅探:有 "usage" 字段才走完整解析(避免每个 chunk 都做 JSON parse)
                if (json.contains("\"usage\"")) {
                    downstreamApiKeyService.recordChatUsage(keyId, json);
                }
            }
        } catch (Exception e) {
            // 任何异常不外抛 —— 这是 side-channel,失败仅记日志
            log.warn("interceptChatChunk 异常 keyId={}: {}", keyId, e.getMessage());
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
     */
    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels() {
        List<Map<String, Object>> data = modelsConfig.getModels().stream()
                .map(OpenAiController::toOpenAiModelEntry)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "list");
        body.put("data", data);
        return Mono.just(body);
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
