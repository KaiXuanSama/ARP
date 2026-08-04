package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic SSE 事件流聚合器 —— 把上游的流式事件合并成一个完整的 {@code Message} 对象
 *
 * <h3>为什么需要聚合</h3>
 * 上游 {@code copilot.tencent.com/v1/messages} <b>无论请求体里 {@code stream} 是
 * true / false / 缺失，一律返回 {@code text/event-stream}</b>。
 * 而 Anthropic 官方 SDK 的非流式调用（{@code client.messages.create()} 不带 stream）
 * 期待 {@code application/json} + 完整 Message 体，拿到 SSE 会 JSON 解析失败。
 * 因此非流式请求必须由本服务把事件流"收干"再拼成一个 Message 返回。
 * <p>
 * 调用方：{@code AnthropicController.messages}（仅非流式分支）。
 *
 * <h3>调用方拼接契约（易错点）</h3>
 * 本类按<b>行</b>解析。调用方把 WebClient 的各个 element 合并时
 * <b>必须用 {@code String.join("\n", parts)}</b>：
 * <ul>
 *   <li>WebClient 解码 SSE 后，每个 element 是一个完整的 data 值（裸 JSON，<b>不带尾部换行</b>）</li>
 *   <li>若用 {@code join("")}，会得到 {@code {...}{...}{...}} 这种多 JSON 同行形态，
 *       而 Jackson 的 {@code readTree} 只解析第一个对象就返回 —— 表现为
 *       {@code message_start} 能读到、但所有 {@code content_block_delta} 静默丢失，
 *       {@code content} 恒为空数组</li>
 * </ul>
 * 该行为已由 {@code AnthropicStreamAggregatorTest} 钉住。
 * <p>
 * <strong>上游实测事件序列</strong>(2026-08 抓包确认):
 * <pre>
 * event: message_start        data: {"message":{id,model,role,usage:{...}},"type":"message_start"}
 * event: content_block_start  data: {"content_block":{"thinking":"","type":"thinking"},"index":0,...}
 * event: content_block_delta  data: {"delta":{"thinking":"我们","type":"thinking_delta"},"index":0,...}
 * event: content_block_stop   data: {"index":0,...}
 * event: content_block_start  data: {"content_block":{"text":"","type":"text"},"index":1,...}
 * event: content_block_delta  data: {"delta":{"text":"你好","type":"text_delta"},"index":1,...}
 * event: content_block_stop   data: {"index":1,...}
 * event: message_delta        data: {"delta":{"stop_reason":"end_turn",...},"usage":{input_tokens,output_tokens,...}}
 * event: message_stop         data: {"type":"message_stop"}
 * </pre>
 * <p>
 * <strong>关键细节</strong>:
 * <ul>
 *   <li>上游会输出 <b>thinking 块</b>(index 0)和 <b>text 块</b>(index 1)两个 content block。
 *       聚合时必须按 {@code index} 分组、保留各自 {@code type},否则思维链会污染正文。</li>
 *   <li>{@code message_start} 里的 {@code usage} 全是 0(占位);真实 token 数在
 *       {@code message_delta.usage} 里 —— 后者覆盖前者。</li>
 *   <li>上游 <b>不发 {@code [DONE]}</b> 标记(与 OpenAI 端点不同),流自然结束。</li>
 *   <li>{@code tool_use} 块的参数走 {@code input_json_delta.partial_json},需拼接成
 *       完整 JSON 再解析回对象。</li>
 * </ul>
 * <p>
 * <strong>容错</strong>:任何单行解析失败只跳过该行(记 debug 日志),不中断聚合 ——
 * 宁可少一个 delta,也不要整个非流式请求失败。
 */
@Component
public class AnthropicStreamAggregator {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamAggregator.class);

    private final ObjectMapper objectMapper;

    public AnthropicStreamAggregator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把完整的 SSE 文本聚合成 Anthropic 标准 Message Map
     *
     * @param sseText      上游返回的全部 SSE 文本(多个 event/data 行)
     * @param fallbackModel 上游没给 model 时的兜底值(用下游请求里的 model)
     * @return Anthropic {@code Message} 结构的 Map,可直接序列化为 JSON 响应
     */
    public Map<String, Object> aggregate(String sseText, String fallbackModel) {
        // ---- 累积状态 ----
        String messageId = null;
        String model = null;
        String role = "assistant";
        String stopReason = null;
        String stopSequence = null;
        Map<String, Object> usage = null;
        // index -> 该 block 的累积状态(用 TreeMap 保证按 index 升序输出)
        Map<Integer, BlockAccumulator> blocks = new TreeMap<>();

        if (sseText != null && !sseText.isBlank()) {
            for (String line : sseText.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // 提取 JSON 载荷。必须同时兼容两种形态:
                //   1) "data: {...}" —— 原始 SSE 文本(直连上游抓包时的样子)
                //   2) "{...}"      —— 裸 JSON。WebClient 用 bodyToFlux(String.class)
                //      解码 text/event-stream 时,Spring 的 ServerSentEventHttpMessageReader
                //      已经把 "event:" / "data:" 这些 SSE 字段剥掉了,只把 data 的
                //      值发给下游。若只认 "data:" 前缀,这里会全部跳过 ——
                //      表现为非流式响应 content 为空数组、usage 全 0。
                String json;
                if (trimmed.startsWith("data:")) {
                    json = trimmed.substring("data:".length()).trim();
                } else if (trimmed.startsWith("{")) {
                    json = trimmed;
                } else {
                    // "event: xxx" / "id:" / "retry:" 等 SSE 元数据行 → 跳过
                    continue;
                }
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                try {
                    JsonNode node = objectMapper.readTree(json);
                    String type = node.path("type").asText("");
                    switch (type) {
                        case "message_start" -> {
                            JsonNode msg = node.path("message");
                            messageId = textOrNull(msg, "id", messageId);
                            model = textOrNull(msg, "model", model);
                            role = textOrNull(msg, "role", role);
                            // message_start 的 usage 是 0 占位,先收着,后面被 message_delta 覆盖
                            Map<String, Object> u = toMap(msg.path("usage"));
                            if (u != null) {
                                usage = u;
                            }
                        }
                        case "content_block_start" -> {
                            int idx = node.path("index").asInt(0);
                            JsonNode cb = node.path("content_block");
                            BlockAccumulator acc = blocks.computeIfAbsent(idx, k -> new BlockAccumulator());
                            acc.type = cb.path("type").asText(acc.type);
                            // tool_use 块在 start 就带 id / name
                            acc.toolId = textOrNull(cb, "id", acc.toolId);
                            acc.toolName = textOrNull(cb, "name", acc.toolName);
                            // 初始 text / thinking 可能非空(通常是 "")
                            appendIfPresent(acc, cb.path("text").asText(null), cb.path("thinking").asText(null));
                        }
                        case "content_block_delta" -> {
                            int idx = node.path("index").asInt(0);
                            JsonNode delta = node.path("delta");
                            BlockAccumulator acc = blocks.computeIfAbsent(idx, k -> new BlockAccumulator());
                            String deltaType = delta.path("type").asText("");
                            switch (deltaType) {
                                case "text_delta" -> {
                                    if (acc.type == null) acc.type = "text";
                                    acc.text.append(delta.path("text").asText(""));
                                }
                                case "thinking_delta" -> {
                                    if (acc.type == null) acc.type = "thinking";
                                    acc.thinking.append(delta.path("thinking").asText(""));
                                }
                                case "signature_delta" ->
                                    // thinking 块的签名(Anthropic 用于校验思维链完整性)
                                    acc.signature = delta.path("signature").asText(acc.signature);
                                case "input_json_delta" -> {
                                    if (acc.type == null) acc.type = "tool_use";
                                    acc.partialJson.append(delta.path("partial_json").asText(""));
                                }
                                default -> {
                                    // 未知 delta 类型 —— 兜底按 text / thinking 字段取值
                                    appendIfPresent(acc, delta.path("text").asText(null),
                                            delta.path("thinking").asText(null));
                                }
                            }
                        }
                        case "message_delta" -> {
                            JsonNode delta = node.path("delta");
                            stopReason = textOrNull(delta, "stop_reason", stopReason);
                            stopSequence = textOrNull(delta, "stop_sequence", stopSequence);
                            // 真实 token 数在这里,覆盖 message_start 的 0 占位
                            Map<String, Object> u = toMap(node.path("usage"));
                            if (u != null) {
                                usage = u;
                            }
                        }
                        case "error" -> {
                            // 上游流中错误 —— 记日志,让 content 保持已收到的部分
                            log.warn("Anthropic 上游流内错误事件: {}", json);
                        }
                        default -> {
                            // content_block_stop / message_stop / ping 等无需聚合
                        }
                    }
                } catch (Exception e) {
                    // 单行坏数据不影响整体聚合
                    log.debug("Anthropic SSE 行解析失败,跳过: {}", e.getMessage());
                }
            }
        }

        // ---- 拼装 content 数组 ----
        List<Map<String, Object>> content = new ArrayList<>();
        for (BlockAccumulator acc : blocks.values()) {
            Map<String, Object> block = acc.toBlock(objectMapper);
            if (block != null) {
                content.add(block);
            }
        }

        // ---- 组装 Anthropic Message ----
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", messageId != null ? messageId : "msg_unknown");
        result.put("type", "message");
        result.put("role", role != null ? role : "assistant");
        result.put("model", model != null ? model : fallbackModel);
        result.put("content", content);
        // Anthropic 协议:正常结束是 end_turn;上游没给就兜底
        result.put("stop_reason", stopReason != null ? stopReason : "end_turn");
        result.put("stop_sequence", stopSequence);
        result.put("usage", usage != null ? usage : defaultUsage());
        return result;
    }

    /** 单个 content block 的累积状态 */
    private static final class BlockAccumulator {
        String type;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder partialJson = new StringBuilder();
        String signature;
        String toolId;
        String toolName;

        /** 转成 Anthropic content block Map;完全空的块返回 null(不输出) */
        Map<String, Object> toBlock(ObjectMapper mapper) {
            String t = type != null ? type : "text";
            Map<String, Object> block = new LinkedHashMap<>();
            switch (t) {
                case "thinking" -> {
                    if (thinking.length() == 0) {
                        return null;
                    }
                    block.put("type", "thinking");
                    block.put("thinking", thinking.toString());
                    if (signature != null) {
                        block.put("signature", signature);
                    }
                }
                case "tool_use" -> {
                    block.put("type", "tool_use");
                    block.put("id", toolId != null ? toolId : "toolu_unknown");
                    block.put("name", toolName);
                    // partial_json 拼完后解析成对象;失败则给空对象(不让整个响应崩)
                    Object input = Map.of();
                    String raw = partialJson.toString();
                    if (!raw.isBlank()) {
                        try {
                            input = mapper.readValue(raw, Map.class);
                        } catch (Exception e) {
                            log.debug("tool_use input JSON 解析失败,降级空对象: {}", e.getMessage());
                        }
                    }
                    block.put("input", input);
                }
                default -> {
                    if (text.length() == 0) {
                        return null;
                    }
                    block.put("type", "text");
                    block.put("text", text.toString());
                }
            }
            return block;
        }
    }

    private static void appendIfPresent(BlockAccumulator acc, String text, String thinking) {
        if (text != null && !text.isEmpty()) {
            acc.text.append(text);
        }
        if (thinking != null && !thinking.isEmpty()) {
            acc.thinking.append(thinking);
        }
    }

    private static String textOrNull(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return fallback;
        }
        String s = v.asText();
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.convertValue(node, Map.class);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> defaultUsage() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", 0);
        u.put("output_tokens", 0);
        return u;
    }
}
