package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.dto.AuthCredential;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.exception.MissingCredentialException;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

/**
 * 腾讯 CodeBuddy 上游 API 客户端
 * <p>
 * 凭证解析（accessToken / apiKey）下沉到本类内部，对调用方透明：
 * 调用方只需要提供 {@code accountId}，由 {@link #resolveAuth(Long)} 查数据库并构造 {@link AuthCredential}。
 * <p>
 * 认证优先级（见 {@link AuthCredential.SourceMode}）：
 * <ul>
 *   <li>有 accessToken → JWT 全套头（Authorization + X-User-Id + X-Domain）</li>
 *   <li>否则有 apiKey → 仅 Authorization（不带 X-User-Id / X-Domain）</li>
 *   <li>都没有 → 抛 {@link MissingCredentialException}</li>
 * </ul>
 */
@Service
public class UpstreamClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final WorkbuddyInfoService workbuddyInfoService;
    private final WorkbuddyAccountJdbcRepository accountRepository;

    public UpstreamClient(WebClient.Builder builder,
                          WorkbuddyInfoService workbuddyInfoService,
                          WorkbuddyAccountJdbcRepository accountRepository) {
        this.webClient = builder
                .clone()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.workbuddyInfoService = workbuddyInfoService;
        this.accountRepository = accountRepository;
    }

    // ============== Billing（按 accountId 路由） ==============

    /**
     * 调用 Billing 类端点（POST 空 body）。accountId 来自数据库主键，路径用 {accountId} 而非 uid
     * <p>
     * 4xx 也读取 body 透传，不抛异常——上游常用 HTTP 400 携带业务码（如 10001 已签到）
     */
    public Mono<ResponseEntity<Map<String, Object>>> postBillingForAccount(Long accountId, String path) {
        return postBillingJsonForAccount(accountId, path, Map.of());
    }

    /**
     * 调用 Billing 类端点（POST 自定义 body），按 accountId 路由
     * <p>
     * 整条链路是响应式的：先在 boundedElastic 上查 DB 拿凭证（不阻塞 reactor 线程），
     * 再用凭证拼头调上游。绝不能在 reactor 事件循环上 .block()
     */
    public Mono<ResponseEntity<Map<String, Object>>> postBillingJsonForAccount(
            Long accountId, String path, Map<String, Object> body
    ) {
        return resolveAuth(accountId)
                .flatMap(cred -> webClient.post()
                        .uri(UpstreamConstants.BILLING_BASE_URL + path)
                        .headers(h -> applyAuth(h, cred))
                        .bodyValue(body)
                        .exchangeToMono(resp -> resp.bodyToMono(MAP_TYPE)
                                .defaultIfEmpty(new java.util.HashMap<>())
                                .map(bodyMap -> ResponseEntity
                                        .status(resp.statusCode())
                                        .headers(resp.headers().asHttpHeaders())
                                        .body(bodyMap)))
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    // ============== Chat（暂保留旧路径，后续单独立项改造） ==============

     /**
     * 调用 Chat 补全端点（流式 SSE透传），按 accountId 路由
     * <p>
     *逻辑:
     * <ul>
     * <li>先按 accountId解析凭证({@link #resolveAuth(Long)})</li>
     * <li>JWT 模式 → Authorization + X-User-Id + X-Domain</li>
     * <li>API Key 模式 →仅 Authorization</li>
     * <li>之后直接把上游 SSE chunk透传给下游,本服务不做任何加工</li>
     * </ul>
     */
     public Flux<String> postChatStreamForAccount(Long accountId, Map<String, Object> body) {
     return resolveAuth(accountId)
     .flatMapMany(cred -> webClient.post()
     .uri(UpstreamConstants.CHAT_BASE_URL + "/chat/completions")
     .headers(h -> applyAuth(h, cred))
     .bodyValue(body)
     .retrieve()
     .bodyToFlux(String.class)
     .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
     }

    // ============== Anthropic Messages（原生透传） ==============

    /**
     * 调用上游 Anthropic Messages 端点（流式 SSE 透传），按 accountId 路由
     * <p>
     * <strong>上游原生支持 Anthropic 协议</strong>(2026-08 实测确认) —— 本服务不做任何
     * 协议转换，只做"鉴权换头 + 透传"。请求体/响应体格式与 Anthropic 官方一致。
     * <p>
     * <strong>路径前缀陷阱</strong>:Anthropic 端点是 {@code /v1/messages}，
     * 而 Chat 端点是 {@code /v2/chat/completions} —— <b>前缀不同</b>，
     * 因此这里用 {@link UpstreamConstants#ANTHROPIC_BASE_URL} 而非 {@code CHAT_BASE_URL}。
     * <p>
     * <strong>始终是 SSE</strong>:上游无视请求体里的 {@code stream} 字段，
     * 一律返回 {@code text/event-stream}。非流式响应由
     * {@code AnthropicController} 调用 {@code AnthropicStreamAggregator} 聚合后返回。
     * <p>
     * 用 {@code exchangeToFlux} 而非 {@code retrieve()}:后者遇上游 4xx 会抛
     * {@code WebClientResponseException}(不是 {@code ResponseStatusException}),
     * 最终被 Spring 默认 handler 渲染成 500，下游看不到真实错因。
     * 这里显式把上游的错误状态码与 body 透传出去。
     */
    public Flux<String> postAnthropicMessagesForAccount(Long accountId, Map<String, Object> body) {
        return resolveAuth(accountId)
                .flatMapMany(cred -> webClient.post()
                        .uri(UpstreamConstants.ANTHROPIC_BASE_URL + UpstreamConstants.PATH_ANTHROPIC_MESSAGES)
                        .headers(h -> applyAuth(h, cred))
                        .bodyValue(body)
                        .exchangeToFlux(resp -> {
                            if (resp.statusCode().isError()) {
                                // 上游 4xx/5xx：读完整 body 后抛 UpstreamErrorException，
                                // 由 AnthropicWebExceptionHandler 渲染成 Anthropic 错误格式
                                return resp.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMapMany(errBody -> Flux.error(
                                                new UpstreamErrorException(
                                                        resp.statusCode().value(), errBody)));
                            }
                            return resp.bodyToFlux(String.class);
                        })
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    /**
     * 上游返回错误状态码时抛出，携带原始状态码与响应体
     * <p>
     * 让上层能把上游的真实错因（如 {@code 11102 model service info not found}）
     * 透传给下游，而不是笼统的 500。
     */
    public static class UpstreamErrorException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public UpstreamErrorException(int statusCode, String responseBody) {
            super("上游返回 " + statusCode + ": " + abbreviate(responseBody));
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }

        private static String abbreviate(String s) {
            if (s == null) return "";
            String t = s.trim();
            return t.length() > 300 ? t.substring(0, 300) + "..." : t;
        }
    }

    /**
     * 调用 Chat 补全端点（流式 SSE 透传）
     * <p>
     * @deprecated OpenAI /v1/chat/completions 已改走 {@link #postChatStreamForAccount(Long, Map)} + 设置表指定账号。
     *这个旧方法仍保留给尚未迁移的调用方，继续使用本机 .info 的 JWT。
     *
     * @param body 完整的请求体（含 model/messages/stream 等）
     */
     @Deprecated
    public Flux<String> postChatStream(Map<String, Object> body) {
        return workbuddyInfoService.getAccountInfoSafely()
                .flatMapMany(info -> webClient.post()
                        .uri(UpstreamConstants.CHAT_BASE_URL + "/chat/completions")
                        .headers(h -> applyLegacyAuth(h, info))
                        .bodyValue(body)
                        .retrieve()
                        .bodyToFlux(String.class)
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    // ============== 旧路径（已废弃） ==============

    /**
     * @deprecated 用 {@link #postBillingJsonForAccount(Long, String, Map)} 替代
     */
    @Deprecated
    public Mono<ResponseEntity<Map<String, Object>>> postBillingJsonWithInfo(
            String path, Map<String, Object> body, WorkbuddyDesktopInfo info
    ) {
        return workbuddyInfoService.getAccountInfoSafely()
                .flatMap(actualInfo -> webClient.post()
                        .uri(UpstreamConstants.BILLING_BASE_URL + path)
                        .headers(h -> applyLegacyAuth(h, actualInfo))
                        .bodyValue(body)
                        .exchangeToMono(resp -> resp.bodyToMono(MAP_TYPE)
                                .defaultIfEmpty(new java.util.HashMap<>())
                                .map(bodyMap -> ResponseEntity
                                        .status(resp.statusCode())
                                        .headers(resp.headers().asHttpHeaders())
                                        .body(bodyMap)))
                        .timeout(Duration.ofSeconds(UpstreamConstants.TIMEOUT_SECONDS)));
    }

    // ============== 凭证解析 ==============

    /**
     * 根据 accountId 从数据库读取 accessToken / apiKey，构造 {@link AuthCredential}
     * <p>
     * 纯响应式实现：DB 查询在 boundedElastic 上跑，调用方需用 flatMap 串到下游。
     * <p>
     * accountId 为 null → 立即抛 {@link MissingCredentialException}(由 flatMap 透传为 onError)
     * 账户不存在 → 同上
     * accessToken 与 apiKey 都没有 → 同上
     */
    public Mono<AuthCredential> resolveAuth(Long accountId) {
        if (accountId == null) {
            return Mono.error(new MissingCredentialException("accountId 不能为空"));
        }
        return Mono.fromCallable(() -> accountRepository.findById(accountId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new MissingCredentialException("账户不存在 (id=" + accountId + ")"));
                    }
                    return Mono.just(buildCredential(opt.get()));
                });
    }

    private AuthCredential buildCredential(WorkbuddyAccountRecord rec) {
        String token = trimToNull(rec.accessToken());
        String apiKey = trimToNull(rec.apiKey());
        String uid = rec.uid();
        if (token != null) {
            return new AuthCredential(token, null, uid, AuthCredential.SourceMode.JWT);
        }
        if (apiKey != null) {
            return new AuthCredential(null, apiKey, null, AuthCredential.SourceMode.API_KEY);
        }
        throw new MissingCredentialException(
                "账户(id=" + rec.id() + ", uid=" + uid + ") 缺少 accessToken 与 apiKey，至少需要其中一个");
    }

    // ============== 头拼接 ==============

    private void applyAuth(HttpHeaders headers, AuthCredential cred) {
        switch (cred.sourceMode()) {
            case JWT -> {
                headers.setBearerAuth(cred.accessToken());
                if (cred.uidForHeader() != null && !cred.uidForHeader().isBlank()) {
                    headers.set("X-User-Id", cred.uidForHeader());
                }
                headers.set("X-Domain", UpstreamConstants.JWT_DOMAIN);
            }
            case API_KEY -> {
                // API Key 模式只带 Authorization，不带 X-User-Id / X-Domain
                headers.setBearerAuth(cred.apiKey());
            }
        }
    }

    /**
     * 旧路径用的头拼接（仅 JWT 模式，从 WorkbuddyDesktopInfo 拿 token）。
     * 留作 Chat / postBillingJsonWithInfo 的过渡实现
     */
    private void applyLegacyAuth(HttpHeaders headers, WorkbuddyDesktopInfo info) {
        if (info != null && info.auth() != null
                && info.auth().accessToken() != null
                && !info.auth().accessToken().isBlank()) {
            headers.setBearerAuth(info.auth().accessToken());
            if (info.account() != null && info.account().uid() != null) {
                headers.set("X-User-Id", info.account().uid());
            }
            headers.set("X-Domain", UpstreamConstants.JWT_DOMAIN);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
