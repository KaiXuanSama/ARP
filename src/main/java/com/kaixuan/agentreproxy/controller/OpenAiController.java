package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.ModelsConfigService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
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
 * <ul>
 * <li>从请求头 {@code Authorization: Bearer ak-xxxxx}提取下游 API Key</li>
 * <li>按 key 自己的 {@code consumption} 字段（designated / least / most /
 * expiring）选号</li>
 * <li>全局 {@code app_settings.chat.consumption} 设置已废弃，不再被 chat路由消费</li>
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

    private final UpstreamClient upstream;
    private final ModelsConfigService modelsConfig;
    private final SettingsService settingsService;
    private final ChatUsageRefreshScheduler usageRefreshScheduler;

    public OpenAiController(UpstreamClient upstream,
            ModelsConfigService modelsConfig,
            SettingsService settingsService,
            ChatUsageRefreshScheduler usageRefreshScheduler) {
        this.upstream = upstream;
        this.modelsConfig = modelsConfig;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
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
                .flatMapMany(accountId -> {
                    // 触发3 分钟后的积分用量自动刷新(全局去重 —— 已有定时器则忽略)
                    usageRefreshScheduler.scheduleRefreshAfterChat(accountId);
                    return upstream.postChatStreamForAccount(accountId, body);
                });
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
