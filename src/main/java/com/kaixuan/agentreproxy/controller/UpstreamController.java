package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.constants.SupportedModels;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 腾讯 CodeBuddy 上游 API 代理端点
 * <p>
 * 统一为前端提供"无 token、无 Base URL 差异、无错误码映射"的对接面
 */
@RestController
@RequestMapping("/api")
public class UpstreamController {

    private final UpstreamClient upstream;

    public UpstreamController(UpstreamClient upstream) {
        this.upstream = upstream;
    }

    // ============== Billing ==============

    @PostMapping("/billing/user-resource")
    public Mono<Map<String, Object>> getUserResource() {
        return upstream.postBilling("/v2/billing/meter/get-user-resource");
    }

    @PostMapping("/billing/checkin-status")
    public Mono<Map<String, Object>> checkinStatus() {
        return upstream.postBilling("/v2/billing/meter/checkin-status");
    }

    @PostMapping("/billing/daily-checkin")
    public Mono<Map<String, Object>> dailyCheckin() {
        return upstream.postBilling("/v2/billing/meter/daily-checkin");
    }

    @PostMapping("/billing/payment-type")
    public Mono<Map<String, Object>> getPaymentType() {
        return upstream.postBilling("/v2/billing/meter/get-payment-type");
    }

    @PostMapping("/billing/dosage-notify")
    public Mono<Map<String, Object>> getDosageNotify() {
        return upstream.postBilling("/v2/billing/meter/get-dosage-notify");
    }

    // ============== Chat ==============

    /**
     * Chat 补全（流式 SSE 透传）
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
     * 本地硬编码模型列表（上游 /v2/v1/models 返回 404）
     */
    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels() {
        List<Map<String, Object>> data = SupportedModels.MODELS.stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.id(),
                        "family", m.family(),
                        "context_length", m.contextLength()
                ))
                .toList();
        return Mono.just(Map.of("object", "list", "data", data));
    }
}
