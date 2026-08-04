package com.kaixuan.agentreproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Anthropic 兼容端点（{@code /v1/messages}）
 * <p>
 * 让 Anthropic 官方 SDK（{@code anthropic-python} / {@code @anthropic-ai/sdk} /
 * Claude Code 等）能以本服务为 {@code base_url} 直接调用。
 * <p>
 * <strong>为什么不做协议转换</strong>：2026-08 实测确认上游
 * {@code copilot.tencent.com/v1/messages} <b>原生支持 Anthropic 协议</b>
 * （返回标准的 {@code message_start} / {@code content_block_delta} /
 * {@code message_delta} 事件流）。因此本控制器只做四件事：
 * <ol>
 *   <li><b>鉴权</b>：支持 {@code x-api-key}（Anthropic SDK 默认头）与
 *       {@code Authorization: Bearer}（本项目既有惯例），前者优先</li>
 *   <li><b>选号</b>：复用 {@link SettingsService#resolveAccountForApiKey} 的
 *       per-key consumption 路由，与 OpenAI 端点完全一致</li>
 *   <li><b>模型白名单校验</b>：与 OpenAI 端点同语义（null 放行 / 空数组全拒 / 白名单严格匹配）</li>
 *   <li><b>流式透传</b>：上游本身就是标准 Anthropic SSE，零加工直通</li>
 * </ol>
 *
 * <h3>仅支持流式（有意为之）</h3>
 * 本端点要求请求体带 {@code stream: true}，否则返回 400。
 * 与 {@code /v1/chat/completions} 强制 {@code stream=true} 是同一种取舍。
 * <p>
 * 背景：上游<b>无视请求体里的 {@code stream} 字段</b>，无论 true / false / 缺失，
 * 一律返回 {@code text/event-stream}。若要支持非流式，就必須在本服务内
 * “收干整个事件流再拼成完整 Message”。该聚合逻辑已实现于
 * {@code AnthropicStreamAggregator}，但尚未调通（聚合后 {@code content}
 * 为空数组），而实际使用场景（Claude Code / SDK 流式对话）几乎总是流式，
 * 故暂不投入。需要时再启用聚合器即可。
 *
 * <h3>计费差异（重要）</h3>
 * 上游 Anthropic 端点的 {@code message_delta.usage} <b>只有 token 数，没有
 * {@code credit} 字段</b>（与 OpenAI 端点的 {@code usage.credit} 不同）。
 * 现有 {@link DownstreamApiKeyService#recordChatUsage} 在解析不到 credit 时
 * 只落日志、不累加 {@code used_credits} —— 这正是我们要的语义：
 * <b>Anthropic 端点如实记录调用日志与 token 数，但不累加积分</b>。
 * <p>
 * <b>副作用</b>：下游 key 的 {@code credit_limit} 对本端点<b>不生效</b>
 * （因为 used_credits 不增长）。这是有意为之 —— 与其按 token 编一个
 * 对不上上游账单的假积分，不如如实反映"上游未提供计费信息"。
 * 需要卡额度的场景请用 {@code call_count} 或在上游侧限制。
 */
@RestController
@RequestMapping("/v1")
public class AnthropicController {

    private static final Logger log = LoggerFactory.getLogger(AnthropicController.class);

    private final UpstreamClient upstream;
    private final SettingsService settingsService;
    private final ChatUsageRefreshScheduler usageRefreshScheduler;
    private final DownstreamApiKeyService downstreamApiKeyService;
    private final ObjectMapper objectMapper;

    public AnthropicController(UpstreamClient upstream,
            SettingsService settingsService,
            ChatUsageRefreshScheduler usageRefreshScheduler,
            DownstreamApiKeyService downstreamApiKeyService,
            ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
        this.downstreamApiKeyService = downstreamApiKeyService;
        this.objectMapper = objectMapper;
    }

    /**
     * Anthropic 兼容的 Messages 端点
     * <p>
     * 返回类型是 {@code Mono<ResponseEntity<?>>} 而非固定的 Flux/Mono，
     * 因为流式与非流式的 Content-Type 不同（{@code text/event-stream} vs
     * {@code application/json}），需要在运行时决定。
     *
     * @param apiKeyHeader  {@code x-api-key}（Anthropic SDK 默认发这个头）
     * @param authorization {@code Authorization: Bearer ak-xxx}（本项目既有惯例）
     * @param body          Anthropic 标准请求体（model / messages / max_tokens / system / tools ...）
     */
    @PostMapping("/messages")
    public Mono<ResponseEntity<?>> messages(
            @RequestHeader(value = "x-api-key", required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {

        // ---- 鉴权头归一：x-api-key 优先，回退 Authorization ----
        // SettingsService.extractApiKey 已兼容"裸 key 无 Bearer 前缀"，
        // 所以 x-api-key 的值可以直接传进去
        String credential = (apiKeyHeader != null && !apiKeyHeader.isBlank())
                ? apiKeyHeader
                : authorization;

        // ---- 基础请求体校验（在调上游前拦下，避免 400 转 500）----
        String requestedModel = body.get("model") instanceof String m ? m.trim() : null;
        if (requestedModel == null || requestedModel.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请求体缺少 model 字段"));
        }
        Object messages = body.get("messages");
        if (!(messages instanceof List<?> list) || list.isEmpty()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "messages 不能为空"));
        }

        // ---- 只支持流式（与 OpenAI 端点同样的取舍）----
        // 不像 OpenAI 端点那样静默强制改写 stream=true，而是显式报错：
        // Anthropic SDK 的非流式调用会把响应当成完整 Message 解析，
        // 静默返回 SSE 会让 SDK 报一个难以理解的 JSON 解析错误，
        // 不如直接告知“本端点需要 stream: true”。
        if (!Boolean.TRUE.equals(body.get("stream"))) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "本端点目前仅支持流式调用，请在请求体中设置 stream: true"));
        }

        return settingsService.resolveAccountForApiKey(credential)
                .onErrorMap(IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()))
                .flatMap(ctx -> {
                    // 模型白名单校验（与 OpenAI 端点同语义）
                    if (ctx.supportedModels() != null) {
                        if (ctx.supportedModels().isEmpty()) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "当前 API Key 不允许调用任何模型: " + requestedModel));
                        }
                        if (!ctx.supportedModels().contains(requestedModel)) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "当前 API Key 不支持模型: " + requestedModel));
                        }
                    }

                    // 鉴权通过 → 累加 call_count + 触发积分刷新（两者内部都不抛异常）
                    downstreamApiKeyService.recordCall(ctx.keyId());
                    usageRefreshScheduler.scheduleRefreshAfterChat(ctx.accountId());

                    // 流式透传：上游本身就是标准 Anthropic SSE，本服务零加工
                    Flux<String> upstreamFlux = upstream
                            .postAnthropicMessagesForAccount(ctx.accountId(), body)
                            .doOnNext(element -> interceptMessageChunk(
                                    element, ctx.keyId(), ctx.accountId()));

                    return Mono.just(ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(upstreamFlux));
                });
    }

    /**
     * 侧路拦截 Anthropic SSE，识别 {@code message_delta} 结算事件并落库
     * <p>
     * 与 {@code OpenAiController.interceptChatChunk} 的关键差异：
     * <ul>
     *   <li>上游 Anthropic 流是<b>标准 SSE</b>（{@code event:} + {@code data:} 行），
     *       不是 OpenAI 端点那种 NDJSON</li>
     *   <li>结算信息在 {@code message_delta} 事件的 {@code usage} 里，
     *       <b>没有 {@code credit} 字段</b> —— 落库后
     *       {@link DownstreamApiKeyService#recordChatUsage} 解析不到 credit，
     *       会只记日志不累加积分（方案 a：如实反映上游未提供计费）</li>
     *   <li>上游<b>不发 {@code [DONE]}</b> 标记，流自然结束</li>
     * </ul>
     * <p>
     * 任何异常只记 warn 日志 —— 这是 side-channel，SSE 已在透传给客户端，
     * 计费/日志失败不应影响主流程（与 OpenAI 端点的约定一致）。
     */
    private void interceptMessageChunk(String element, Long keyId, Long accountId) {
        if (element == null || element.isBlank()) {
            return;
        }
        try {
            for (String line : element.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // 同时兼容 "data: {...}" 与裸 "{...}"。
                // WebClient 的 bodyToFlux(String.class) 在解码 text/event-stream 时，
                // Spring 已把 SSE 的 "event:" / "data:" 字段剥掉，只把 data 值发下来；
                // 若只认 "data:" 前缀，这里会全部跳过 —— 表现为 Anthropic 调用
                // 完全不落 call_log。
                String json;
                if (trimmed.startsWith("data:")) {
                    json = trimmed.substring("data:".length()).trim();
                } else if (trimmed.startsWith("{")) {
                    json = trimmed;
                } else {
                    continue;
                }
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                // 只对 message_delta（携带最终 usage 的结算事件）落库，
                // 避免每个 content_block_delta 都写一行导致日志表爆炸
                if (isMessageDeltaWithUsage(json)) {
                    downstreamApiKeyService.recordChatUsage(keyId, accountId, json);
                }
            }
        } catch (Exception e) {
            log.warn("interceptMessageChunk 异常 keyId={}: {}", keyId, e.getMessage());
        }
    }

    /**
     * 判断是否为携带真实 usage 的 {@code message_delta} 结算事件
     * <p>
     * 上游实测形态：
     * <pre>{@code
     * {"delta":{"stop_reason":"end_turn",...},"type":"message_delta",
     *  "usage":{"input_tokens":93,"output_tokens":10,"total_tokens":103}}
     * }</pre>
     * <p>
     * 注意 {@code message_start} 也带 usage，但全是 0 占位，必须靠
     * {@code type == "message_delta"} 区分，否则会重复落库两次。
     */
    private boolean isMessageDeltaWithUsage(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (!"message_delta".equals(node.path("type").asText())) {
                return false;
            }
            var usage = node.get("usage");
            return usage != null && !usage.isNull() && usage.isObject() && usage.size() > 0;
        } catch (Exception e) {
            log.debug("isMessageDeltaWithUsage 解析失败，降级 false: {}", e.getMessage());
            return false;
        }
    }
}
