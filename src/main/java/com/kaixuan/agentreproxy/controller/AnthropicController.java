package com.kaixuan.agentreproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.ChatRoutingContext;
import com.kaixuan.agentreproxy.service.AnthropicStreamAggregator;
import com.kaixuan.agentreproxy.service.AnthropicToOpenAiRequestConverter;
import com.kaixuan.agentreproxy.service.ChatUsageRefreshScheduler;
import com.kaixuan.agentreproxy.service.OpenAiToAnthropicStreamConverter;
import com.kaixuan.agentreproxy.service.RequestTextReplaceService;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import com.kaixuan.agentreproxy.service.SettingsService;
import com.kaixuan.agentreproxy.service.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
 * <h3>两种上游路径（由 {@code custom.anthropic.bridge-via-openai} 切换）</h3>
 * <table border="1">
 *   <tr><th></th><th>桥接模式（默认）</th><th>直连模式</th></tr>
 *   <tr><td>上游端点</td><td>{@code /v2/chat/completions}</td><td>{@code /v1/messages}</td></tr>
 *   <tr><td>转换</td><td>请求+响应双向翻译</td><td>零转换透传</td></tr>
 *   <tr><td>{@code credit} 计费</td><td>✅ 正常</td><td>❌ 上游不返回</td></tr>
 *   <tr><td>{@code credit_limit}</td><td>✅ 生效</td><td>❌ 失效</td></tr>
 *   <tr><td>思维链</td><td>✅ （靠注入 {@code reasoning_effort}）</td><td>✅ 原生</td></tr>
 * </table>
 * <p>
 * 默认走桥接模式是为了解决 <b>Claude Code 场景无法计费</b>的问题：
 * Claude Code 硬编码走 Anthropic 协议，而上游 Anthropic 端点不给 {@code credit}。
 *
 * <h3>非流式适配</h3>
 * 上游<b>无视请求体里的 {@code stream} 字段</b>，一律返回 SSE。因此：
 * <ul>
 *   <li>{@code stream: true} → 透传 / 翻译后的 SSE</li>
 *   <li>否则 → 收干整个流，用 {@link AnthropicStreamAggregator} 聚合成
 *       完整 Message 后以 JSON 返回</li>
 * </ul>
 * <p>
 * ❗ 非流式拼接必須用 {@code String.join("\n", parts)}：用 {@code ""} 拼会产生
 * {@code {...}{...}} 这种多 JSON 同行形态，Jackson 只解析第一个，导致 content 恒空。
 *
 * <h3>计费差异（重要）</h3>
 * 上游 <b>Anthropic 端点</b>的 {@code message_delta.usage} 只有 token 数，
 * <b>没有 {@code credit} 字段</b>（3 层排查确认：已落库数据 / 全流递归扇描 / 响应头）。
 * <p>
 * 因此引入了<b>桥接模式</b>（默认开启）：改调上游 OpenAI 端点拿 credit，
 * 再把响应翻译回 Anthropic 格式。这样：
 * <ul>
 *   <li>{@code call_count} ✅ 累加</li>
 *   <li>{@code used_credits} ✅ 累加（桥接模式下）</li>
 *   <li>{@code credit_limit} ✅ 生效（桥接模式下）</li>
 * </ul>
 * 直连模式（{@code bridge-via-openai=false}）下，后两项仍无法工作。
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
    private final AnthropicToOpenAiRequestConverter requestConverter;
    private final RequestTextReplaceService textReplaceService;
    private final ObjectMapper objectMapper;

    /**
     * 是否通过 OpenAI 端点桥接（默认 {@code true}）
     * <p>
     * <strong>为什么默认开启</strong>：上游 Anthropic 端点实测<b>不返回 {@code credit}</b>，
     * 导致 {@code used_credits} 不累加、{@code credit_limit} 形同虚设。而 OpenAI 端点带 credit，
     * 所以默认走“请求转 OpenAI → 响应翻译回 Anthropic”。
     * <p>
     * 设为 {@code false} 可回退到直连上游 Anthropic 端点（零转换，但无计费）：
     * {@code --custom.anthropic.bridge-via-openai=false}
     */
    @Value("${custom.anthropic.bridge-via-openai:true}")
    private boolean bridgeViaOpenAi;

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
            AnthropicToOpenAiRequestConverter requestConverter,
            RequestTextReplaceService textReplaceService,
            ObjectMapper objectMapper) {
        this.upstream = upstream;
        this.settingsService = settingsService;
        this.usageRefreshScheduler = usageRefreshScheduler;
        this.downstreamApiKeyService = downstreamApiKeyService;
        this.aggregator = aggregator;
        this.requestConverter = requestConverter;
        this.textReplaceService = textReplaceService;
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

                    // 按用户配置的规则做文本替换（未配置时为空操作）。
                    // 在协议转换之前做 —— 规则按 Anthropic 结构定位字段，
                    // 与后续走桥接还是直连无关。
                    textReplaceService.applyToAnthropicBody(body);

                    // ============ 两条上游路径，由 bridgeViaOpenAi 开关决定 ============
                    // A) 桥接模式（默认）：请求转 OpenAI → 上游 → 响应翻译回 Anthropic
                    //    优点：能拿到 usage.credit，积分统计与 credit_limit 正常工作
                    // B) 直连模式：直接调上游 Anthropic 端点，零转换
                    //    优点：协议原生，无转换风险；缺点：上游不给 credit，无法计费
                    if (bridgeViaOpenAi) {
                        return Mono.just(ResponseEntity.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(bridgeThroughOpenAi(body, requestedModel, ctx)));
                    }

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
     * 桥接模式：请求转 OpenAI → 调上游 → 响应翻译回 Anthropic SSE
     * <p>
     * <strong>核心目的</strong>：上游 Anthropic 端点不返回 {@code credit}，
     * 而 OpenAI 端点返回。走这条路径可以在保持下游 Anthropic 协议兼容的同时，
     * 正常统计积分消耗、让 {@code credit_limit} 生效。
     * <p>
     * <strong>转换器是有状态的</strong>：{@link OpenAiToAnthropicStreamConverter}
     * 需要跨 chunk 维护"当前开着哪个 content block"，因此<b>每个请求必须新建实例</b>，
     * 不能做成 Spring 单例 Bean。
     * <p>
     * <strong>计费拦截时机</strong>：在翻译<b>之前</b>拦截原始 OpenAI chunk
     * （翻译后 credit 字段就被丢掉了），这样 {@code recordChatUsage} 能拿到
     * 带 credit 的原文落库。
     *
     * @param anthropicBody  下游发来的 Anthropic 请求体
     * @param requestedModel 下游请求的模型（转换失败时兜底用）
     * @param ctx            路由上下文（accountId / keyId）
     */
    private Flux<ServerSentEvent<String>> bridgeThroughOpenAi(Map<String, Object> anthropicBody,
            String requestedModel, ChatRoutingContext ctx) {
        Map<String, Object> openAiBody;
        try {
            openAiBody = requestConverter.convert(anthropicBody);
        } catch (Exception e) {
            log.error("Anthropic → OpenAI 请求体转换失败", e);
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请求体转换失败: " + e.getMessage()));
        }

        // 桥接模式下真正发给上游的是转换后的 OpenAI 体 —— 排查时必须能看到它，
        // 否则只看到 Anthropic 侧的替换结果，无法确认转换过程有没有把内容改回去
        if (chunkLogEnabled) {
            try {
                String json = objectMapper.writeValueAsString(openAiBody);
                log.info("[请求体-桥接后-发给上游OpenAI] 总长={} 字符\n{}", json.length(), json);
            } catch (Exception e) {
                log.warn("[请求体-桥接后] 序列化失败: {}", e.getMessage());
            }
        }

        // 有状态转换器：每个请求一个实例
        OpenAiToAnthropicStreamConverter converter =
                new OpenAiToAnthropicStreamConverter(objectMapper, requestedModel);

        return upstream.postChatStreamForAnthropicBridge(ctx.accountId(), openAiBody)
                // 先拦截原始 OpenAI chunk 落库（此时 credit 还在）
                .doOnNext(element -> interceptOpenAiChunkForBilling(
                        element, ctx.keyId(), ctx.accountId()))
                // 再翻译成 Anthropic 事件
                .flatMapIterable(converter::convert)
                // 流正常结束时补发收尾事件（message_delta + message_stop）
                .concatWith(Flux.defer(() -> Flux.fromIterable(converter.finish())))
                // ❗ 必须用 ServerSentEvent 显式携带 event 名：
                //   Anthropic SDK 依赖 `event:` 行判断事件类型。
                //   若直接返回 Flux<String>，WebFlux 会把整个字符串当作 data 值再包一层
                //   `data:`，产出 `data:event: message_start` 这种畸形报文，
                //   客户端解析不到任何事件（表现为 "empty or malformed response"）。
                .map(ev -> ServerSentEvent.<String>builder()
                        .event(ev.name())
                        .data(ev.data())
                        .build());
    }

    /**
     * 桥接模式下的计费拦截 —— 复用 OpenAI 端点的结算 chunk 识别逻辑
     * <p>
     * 与 {@link #interceptMessageChunk} 的差异：这里处理的是<b>原始 OpenAI chunk</b>
     * （带 {@code usage.credit}），而非 Anthropic 事件。落库后
     * {@code DownstreamApiKeyService.recordChatUsage} 能解析出 credit 并累加
     * {@code used_credits} —— 这正是桥接模式存在的意义。
     */
    private void interceptOpenAiChunkForBilling(String element, Long keyId, Long accountId) {
        if (element == null || element.isBlank()) {
            return;
        }
        if (chunkLogEnabled) {
            log.info("[桥接-OpenAI原文] keyId={} accountId={} len={} >>>{}<<<",
                    keyId, accountId, element.length(), element);
        }
        try {
            for (String line : element.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
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
                // 只对带真实 usage 的结算 chunk 落库（与 OpenAiController 同逻辑）
                var node = objectMapper.readTree(json);
                var usage = node.get("usage");
                if (usage != null && !usage.isNull() && usage.isObject() && !usage.isEmpty()) {
                    downstreamApiKeyService.recordChatUsage(keyId, accountId, json);
                }
            }
        } catch (Exception e) {
            log.warn("桥接计费拦截异常 keyId={}: {}", keyId, e.getMessage());
        }
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
