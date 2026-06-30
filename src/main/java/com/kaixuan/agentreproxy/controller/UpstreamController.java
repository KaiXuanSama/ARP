package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.UsageQueryRequest;
import com.kaixuan.agentreproxy.service.BillingQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Billing 上游代理端点（前端账户管理页面专用）
 * <p>
 * 只暴露 CodeBuddy 的 Billing 相关接口，OpenAI 兼容端点（/v1/chat/completions、/v1/models）由 {@link OpenAiController} 单独承载。
 * <p>
 * 真实接口（来自官方页面抓包）：
 * <ul>
 *   <li>get-user-resource        资源包列表（Cycle*Precise 周期内精确值）</li>
 *   <li>get-user-request-usage   时段用量明细（含 total）</li>
 * </ul>
 * 注：上游 Billing 端点可能在 HTTP 400 携带业务码，UpstreamClient 已配置 4xx 透传 body。
 * 路径变量 {accountId} 是数据库 workbuddy_account.id 主键；UpstreamClient 内部按 id 查凭证。
 * 凭证解析：accessToken 优先，否则用 api_key；都没有则 MissingCredentialException → 400。
 */
@RestController
@RequestMapping("/api")
public class UpstreamController {

    private final BillingQueryService billingQueryService;

    public UpstreamController(BillingQueryService billingQueryService) {
        this.billingQueryService = billingQueryService;
    }

    @PostMapping("/billing/{accountId}/user-resource")
    public Mono<ResponseEntity<Map<String, Object>>> getUserResource(@PathVariable Long accountId) {
        return billingQueryService.queryCredit(accountId);
    }

    @PostMapping("/billing/{accountId}/user-request-usage")
    public Mono<ResponseEntity<Map<String, Object>>> getUserRequestUsage(
            @PathVariable Long accountId,
            @RequestBody(required = false) UsageQueryRequest req
    ) {
        return billingQueryService.queryUsage(accountId, req);
    }
}
