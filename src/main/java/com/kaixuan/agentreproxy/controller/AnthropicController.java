package com.kaixuan.agentreproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.service.AnthropicStreamAggregator;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li><b>流式与非流式适配</b>：见下</li>
 * </ol>
 *
 * <h3>非流式适配（本控制器存在的主要理由）</h3>
 * 上游<b>无视请求体里的 {@code stream} 字段</b>，无论 true / false / 缺失，
 * 一律返回 {@code text/event-stream}。而 Anthropic SDK 的非流式调用
 * （{@code client.messages.create()} 不带 stream）期待 {@code application/json}
 * + 完整 Message 体，直接透传会让 SDK JSON 解析崩溃。因此：
 * <ul>
 *   <li>{@code stream: true} → 原样透传上游 SSE（零加工，最省开销）</li>
 *   <li>否则 → 收干整个流，用 {@link AnthropicStreamAggregator} 聚合成
 *       完整 Message 后以 JSON 返回</li>
 * </ul>
 * <p>
 * ❗ 非流式拼接必須用 {@code String.join("\n", parts)}：用 {@code ""} 拼会产生
 * {@code {...}{...}} 这种多 JSON 同行形态，Jackson 只解析第一个，导致 content 恒空。
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
    private final AnthropicStreamAggregator aggregator;
    private final ObjectMapper objectMapper;

    /**
     * 是否打印每个 chunk 的原文（排查用），与 {@code OpenAiController} 共用同一开关
     * <p>
     * 默认 {@code false}。开启：{@code --custom.chunk-log.enabled=true}
     */
    @Value("${custom.chunk-log.enabled:false}")
    private boolean chunkLogEnabled;

    public AnthropicController(UpstreamClient upstream,
            SettingsService settingsService,
            ChatUsageRefreshScheduler usageRefreshScheduler,
            DownstreamApiKeyService downstreamApiKeyService,
            AnthropicStreamAggregator aggregator,
            ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
        this.downstreamApiKeyService = downstreamApiKeyService;
        this.aggregator = aggregator;
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

        // ---- 是否流式：只有显式 true 才透传 SSE，否则聚合为完整 Message ----
        // 上游无视请求体里的 stream 字段（一律返 SSE），所以“非流式”完全由
        // 本服务内部实现：收干整个事件流 → AnthropicStreamAggregator 拼成 Message。
        boolean wantStream = Boolean.TRUE.equals(body.get("stream"));

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

                    // 上游始终返回 SSE，这里按下游意图决定透传还是聚合
                    Flux<String> upstreamFlux = upstream
                            .postAnthropicMessagesForAccount(ctx.accountId(), body)
                            .doOnNext(element -> interceptMessageChunk(
                                    element, ctx.keyId(), ctx.accountId()));

                    if (wantStream) {
                        // 流式：原样透传，上游本身就是标准 Anthropic SSE，本服务零加工
                        return Mono.just(ResponseEntity.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(upstreamFlux));
                    }

                    // 非流式：收干全流 → 聚合成完整 Message → JSON 返回
                    //
                    // ❗ 必須用 "\n" 而不是 "" 拼接：WebClient 解码 SSE 后，每个 element 是
                    //   一个完整的 data 值（裸 JSON，不带尾部换行）。用 "" 拼会得到
                    //   {...}{...}{...} 这种“多个 JSON 挤在一行”的形态，而 Jackson 的
                    //   readTree 只会解析第一个对象就返回 —— 结果 message_start 能读到，
                    //   但所有 content_block_delta 全部丢失，content 恒为空数组。
                    //   这个 bug 已由 AnthropicStreamAggregatorTest 钉住。
                    return upstreamFlux
                            .collectList()
                            .map(parts -> {
                                String full = String.join("\n", parts);
                                Map<String, Object> message =
                                        aggregator.aggregate(full, requestedModel);
                                return ResponseEntity.ok()
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .body(message);
                            });
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
        // 原始 element 全量打印（排查用）—— 与 OpenAI 端点共用 custom.chunk-log.enabled 开关，
        // 便于把两个端点的 chunk 放在同一份日志里对照
        if (chunkLogEnabled) {
            log.info("[Anthropic chunk原文] keyId={} accountId={} len={} >>>{}<<<",
                    keyId, accountId, element.length(), element);
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
