package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 腾讯 CodeBuddy 上游 API 客户端
 * <p>
 * 职责：
 * - 注入全局 WebClient.Builder（已配置 JDK DNS 解析器）
 * - 自动附加认证头（API Key 模式优先，缺失则用 JWT）
 * - 提供 Chat 流式与 Billing 非流式调用
 */
@Service
public class UpstreamClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final WorkbuddyInfoService workbuddyInfoService;

    public UpstreamClient(WebClient.Builder builder, WorkbuddyInfoService workbuddyInfoService) {
        this.webClient = builder
                .clone()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.workbuddyInfoService = workbuddyInfoService;
    }

    /**
     * 调用 Billing 类端点（POST 空 body）
     * <p>
     * 4xx 也读取 body 透传，不抛异常——上游常用 HTTP 400 携带业务码（如 10001 已签到）
     *
     * @param path 上游路径，例如 /v2/billing/meter/get-user-resource
     */
    public Mono<org.springframework.http.ResponseEntity<Map<String, Object>>> postBilling(String path) {
        return postBillingJson(path, Map.of());
    }

    /**
     * 调用 Billing 类端点（POST 自定义 body）
     * <p>
     * 4xx 也读取 body 透传，不抛异常——上游常用 HTTP 400 携带业务码
     */
    public Mono<org.springframework.http.ResponseEntity<Map<String, Object>>> postBillingJson(String path, Map<String, Object> body) {
        return postBillingJsonWithInfo(path, body, null);
    }

    /**
     * 调用 Billing 类端点（POST 自定义 body），使用外部传入的账户信息
     * <p>
     * 如果 info 为 null，则回退到本机配置文件
     */
    public Mono<org.springframework.http.ResponseEntity<Map<String, Object>>> postBillingJsonWithInfo(
            String path, Map<String, Object> body, WorkbuddyDesktopInfo info
    ) {
        Mono<WorkbuddyDesktopInfo> infoMono = info != null
                ? Mono.just(info)
                : workbuddyInfoService.getAccountInfoSafely();
        return infoMono
                .flatMap(actualInfo -> webClient.post()
                        .uri(UpstreamConstants.BILLING_BASE_URL + path)
                        .headers(h -> applyAuth(h, actualInfo))
                        .bodyValue(body)
                        .retrieve()
                        .onStatus(s -> true, resp -> reactor.core.publisher.Mono.empty())
                        .toEntity(MAP_TYPE)
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    /**
     * 调用 Chat 补全端点（流式）
     *
     * @param body 完整的请求体（含 model/messages/stream 等）
     */
    public Flux<String> postChatStream(Map<String, Object> body) {
        return workbuddyInfoService.getAccountInfoSafely()
                .flatMapMany(info -> webClient.post()
                        .uri(UpstreamConstants.CHAT_BASE_URL + "/chat/completions")
                        .headers(h -> applyAuth(h, info))
                        .bodyValue(body)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    private void applyAuth(HttpHeaders headers, WorkbuddyDesktopInfo info) {
        // 1) 优先 API Key（推荐模式）
        //    暂未实现手动 API Key 注入，后续在 controller 层补
        // 2) 否则用 JWT
        if (info.auth() != null && info.auth().accessToken() != null
                && !info.auth().accessToken().isBlank()) {
            headers.setBearerAuth(info.auth().accessToken());
            // JWT 模式需要额外 header
            if (info.account() != null && info.account().uid() != null) {
                headers.set("X-User-Id", info.account().uid());
            }
            headers.set("X-Domain", UpstreamConstants.JWT_DOMAIN);
        }
    }
}
