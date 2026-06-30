package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ModelsConfigService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容端点（/v1/*）
 * <p>
 * 让任意 OpenAI SDK（openai-python / langchain / OpenAI Node 等）能以本服务为 base URL 直接调用。
 * 上游是 CodeBuddy（本身就走 OpenAI 协议），所以这里只做"加 /v1 前缀 + 字段补全 + 鉴权透传"的轻量包装。
 * <p>
 * <strong>当前限制</strong>：
 * <ul>
 *   <li>Chat 端点仍走本机 .info 的 JWT（{@code UpstreamClient.postChatStream} 的旧路径），多账户路由尚未迁移 —— 跟之前对账过的 TODO 保持一致</li>
 *   <li>只暴露 OpenAI 协议的一小部分（chat/completions + models）；embedding / completions / files 等暂未实现</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1")
public class OpenAiController {

    /** OpenAI 风格 list 响应里的硬编码时间戳锚点（秒）。固定值避免每次响应 created 变化导致客户端误判模型列表更新 */
    private static final long MODELS_CREATED_AT = 1700000000L; // 2023-11-14T22:13:20Z

    private final UpstreamClient upstream;
    private final ModelsConfigService modelsConfig;

    public OpenAiController(UpstreamClient upstream, ModelsConfigService modelsConfig) {
        this.upstream = upstream;
        this.modelsConfig = modelsConfig;
    }

    // ============== Chat Completions ==============

    /**
     * OpenAI 兼容的 Chat 补全（流式 SSE 透传）
     * <p>
     * 强制 stream=true、补 stream_options、校验 messages >= 2。
     */
    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatCompletions(@RequestBody Map<String, Object> body) {
        // 强制上游只接受 stream=true
        body.put("stream", true);
        if (!body.containsKey("stream_options")) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        // 校验 messages >= 2，否则上游会返回 400
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.size() < 2) {
            return Flux.error(new IllegalArgumentException("messages 至少需要 2 条（需包含 system 消息）"));
        }
        return upstream.postChatStream(body);
    }

    // ============== Models ==============

    /**
     * 数据源由 {@link ModelsConfigService} 决定，优先级：环境变量 {@code MODELS_CONFIG_PATH} → 工作目录
     * {@code modelsConfig.json} → classpath 内置 {@code models-config.default.json}。改完配置重启服务生效。
     * <p>
     * 输出 shape 严格对齐 OpenAI：
     * <pre>
     * { "object": "list", "data": [ { "id": "auto", "object": "model", "created": 1700000000,
     *                                  "owned_by": "virtual", "context_length": 172032 } ] }
     * </pre>
     * <p>
     * 字段对应：
     * <ul>
     *   <li>{@code id}            ← ModelConfig.id</li>
     *   <li>{@code owned_by}      ← ModelConfig.family（CodeBuddy 的"族"概念，作为 owner 占位）</li>
     *   <li>{@code context_length}← ModelConfig.contextLength</li>
     *   <li>{@code created}       ← 全列表共用同一锚点时间（OpenAI 官方也是 created_at 风格）</li>
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
