package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.constants.SupportedModels;
import com.kaixuan.agentreproxy.service.BillingQueryService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final BillingQueryService billingQueryService;

    public UpstreamController(UpstreamClient upstream, BillingQueryService billingQueryService) {
        this.upstream = upstream;
        this.billingQueryService = billingQueryService;
    }

    // ============== Billing ==============
    // 真实接口（来自官方页面抓包）：
    //   get-user-resource        资源包列表（Cycle*Precise 周期内精确值）
    //   get-user-request-usage   时段用量明细（含 total）
    // 注：上游 Billing 端点可能在 HTTP 400 携带业务码，UpstreamClient 已配置 4xx 透传 body
    // 路径变量 {accountId} 是数据库 workbuddy_account.id 主键；UpstreamClient 内部按 id 查凭证
    // 凭证解析：accessToken 优先，否则用 api_key；都没有则 MissingCredentialException → 400

    @PostMapping("/billing/{accountId}/user-resource")
    public Mono<ResponseEntity<Map<String, Object>>> getUserResource(@PathVariable Long accountId) {
        return billingQueryService.queryCredit(accountId);
    }

    @PostMapping("/billing/{accountId}/user-request-usage")
    public Mono<ResponseEntity<Map<String, Object>>> getUserRequestUsage(
            @PathVariable Long accountId,
            @RequestBody(required = false) com.kaixuan.agentreproxy.dto.UsageQueryRequest req
    ) {
        return billingQueryService.queryUsage(accountId, req);
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
