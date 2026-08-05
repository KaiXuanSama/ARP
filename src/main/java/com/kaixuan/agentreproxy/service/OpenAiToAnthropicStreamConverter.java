package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI SSE 流 → Anthropic SSE 事件流 转换器（<b>有状态</b>，每个请求一个实例）
 *
 * <h3>为什么有状态</h3>
 * Anthropic 协议对事件序列有<b>严格契约</b>：
 * <ul>
 *   <li>{@code message_start} 必须最先发，且只发一次</li>
 *   <li>每个 {@code content_block_start} 必须有配对的 {@code content_block_stop}</li>
 *   <li>{@code index} 必须从 0 连续递增</li>
 *   <li>{@code message_delta}（带 usage）→ {@code message_stop} 收尾</li>
 * </ul>
 * 而 OpenAI 流是"平铺"的：{@code reasoning_content} / {@code content} / {@code tool_calls}
 * 混在同一个 delta 里，切换时没有任何边界标记。因此必须靠本类维护
 * "当前开着哪个 block"的状态，在类型切换时补发 stop/start。
 * <p>
 * <b>违反契约的后果</b>：Anthropic SDK 会静默卡住或抛难以理解的解析错误 —— 不好排查，
 * 所以事件配对逻辑由 {@code OpenAiToAnthropicStreamConverterTest} 用例钉住。
 *
 * <h3>映射关系</h3>
 * <table border="1">
 *   <tr><th>OpenAI</th><th>Anthropic</th></tr>
 *   <tr><td>{@code delta.reasoning_content}</td><td>{@code thinking_delta.thinking}</td></tr>
 *   <tr><td>{@code delta.content}</td><td>{@code text_delta.text}</td></tr>
 *   <tr><td>{@code delta.tool_calls[].function.arguments}</td><td>{@code input_json_delta.partial_json}</td></tr>
 *   <tr><td>{@code delta.tool_calls[].id}</td><td>{@code tool_use.id}（格式一致，直接透传）</td></tr>
 *   <tr><td>{@code finish_reason: stop}</td><td>{@code stop_reason: end_turn}</td></tr>
 *   <tr><td>{@code finish_reason: tool_calls}</td><td>{@code stop_reason: tool_use}</td></tr>
 *   <tr><td>{@code finish_reason: length}</td><td>{@code stop_reason: max_tokens}</td></tr>
 *   <tr><td>{@code usage.prompt_tokens}</td><td>{@code usage.input_tokens}</td></tr>
 *   <tr><td>{@code usage.completion_tokens}</td><td>{@code usage.output_tokens}</td></tr>
 * </table>
 *
 * <h3>计费副产品（本方案的核心目的）</h3>
 * OpenAI 端点的结算 chunk 带 {@code usage.credit}，而 Anthropic 端点<b>没有</b>。
 * 走本转换器后，{@code credit} 可正常落库累加，{@code credit_limit} 重新生效。
 * 注意 {@code credit} <b>不</b>透传给下游（Anthropic 协议无此字段），仅内部记账。
 */
public class OpenAiToAnthropicStreamConverter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiToAnthropicStreamConverter.class);

    /** 当前打开的 block 类型 */
    private enum BlockType { NONE, THINKING, TEXT, TOOL_USE }

    private final ObjectMapper objectMapper;
    private final String fallbackModel;

    // ---- 状态 ----
    private boolean messageStarted = false;
    private boolean messageStopped = false;
    private BlockType currentBlock = BlockType.NONE;
    private int nextIndex = 0;
    /** OpenAI tool_calls 的 index → 本地已分配的 Anthropic block index */
    private final Map<Integer, Integer> toolIndexMap = new LinkedHashMap<>();
    private String messageId = null;
    private String model = null;
    private String stopReason = null;
    private Map<String, Object> finalUsage = null;

    public OpenAiToAnthropicStreamConverter(ObjectMapper objectMapper, String fallbackModel) {
        this.objectMapper = objectMapper;
        this.fallbackModel = fallbackModel;
    }

    /**
     * 处理一个 OpenAI SSE element，返回应发给下游的 Anthropic 事件列表（可能为空）
     * <p>
     * element 可能包含多行（NDJSON / SSE 混合），逐行处理。
     *
     * @param element 上游原始文本
     * @return Anthropic 事件列表；无输出时返回空列表
     */
    public List<Event> convert(String element) {
        if (element == null || element.isBlank()) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
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
            if (json.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(json)) {
                out.addAll(finish());
                continue;
            }
            try {
                out.addAll(handleChunk(objectMapper.readTree(json)));
            } catch (Exception e) {
                log.debug("chunk 解析失败，跳过: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 流结束时补发收尾事件（幂等，重复调用只生效一次） */
    public List<Event> finish() {
        if (messageStopped) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
        // 关掉还开着的 block
        addIfNotNull(out, closeCurrentBlock());
        // message_delta（带 stop_reason + usage）
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", stopReason != null ? stopReason : "end_turn");
        delta.put("stop_sequence", null);
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("type", "message_delta");
        md.put("delta", delta);
        md.put("usage", finalUsage != null ? finalUsage : defaultUsage());
        addIfNotNull(out, event("message_delta", md));
        // message_stop
        addIfNotNull(out, event("message_stop", Map.of("type", "message_stop")));
        messageStopped = true;
        return out;
    }

    // ============== 内部处理 ==============

    private List<Event> handleChunk(JsonNode node) {
        List<Event> out = new ArrayList<>();

        // 记录 id / model（首个 chunk 就有）
        if (messageId == null) {
            JsonNode id = node.get("id");
            if (id != null && !id.isNull()) {
                messageId = "msg_" + id.asText();
            }
        }
        if (model == null) {
            JsonNode m = node.get("model");
            if (m != null && !m.isNull()) {
                model = m.asText();
            }
        }

        // usage（结算 chunk）—— 转成 Anthropic 格式留到 finish() 用
        JsonNode usage = node.get("usage");
        if (usage != null && usage.isObject() && !usage.isEmpty()) {
            finalUsage = convertUsage(usage);
        }

        // 首个 chunk 前先发 message_start
        if (!messageStarted) {
            addIfNotNull(out, emitMessageStart());
        }

        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return out;
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");

        if (delta != null && delta.isObject()) {
            // 1) 思维链
            String reasoning = textOf(delta, "reasoning_content");
            if (!reasoning.isEmpty()) {
                out.addAll(ensureBlock(BlockType.THINKING, null, null));
                addIfNotNull(out, emitDelta(currentIndex(), Map.of(
                        "type", "thinking_delta", "thinking", reasoning)));
            }

            // 2) 正文
            String content = textOf(delta, "content");
            if (!content.isEmpty()) {
                out.addAll(ensureBlock(BlockType.TEXT, null, null));
                addIfNotNull(out, emitDelta(currentIndex(), Map.of(
                        "type", "text_delta", "text", content)));
            }

            // 3) 工具调用
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    out.addAll(handleToolCall(tc));
                }
            }
        }

        // finish_reason
        String fr = textOf(choice, "finish_reason");
        if (!fr.isEmpty()) {
            stopReason = mapStopReason(fr);
        }
        return out;
    }

    /**
     * 处理单个 tool_call 分片
     * <p>
     * OpenAI 的分片形态（实测）：
     * <pre>
     * 首片: {"id":"call_00_xxx","type":"function","function":{"name":"search_code","arguments":""},"index":0}
     * 续片: {"function":{"name":"","arguments":"{"},"index":0}
     * 续片: {"function":{"name":"","arguments":"\""},"index":0}
     * </pre>
     * 首片带 id + name，后续只有 arguments 增量。
     */
    private List<Event> handleToolCall(JsonNode tc) {
        List<Event> out = new ArrayList<>();
        int oaIndex = tc.has("index") ? tc.get("index").asInt() : 0;
        String id = textOf(tc, "id");
        JsonNode fn = tc.get("function");
        String name = fn != null ? textOf(fn, "name") : "";
        String args = fn != null ? textOf(fn, "arguments") : "";

        // 首片：带 id 或 name → 开一个新的 tool_use block
        if (!id.isEmpty() || !name.isEmpty()) {
            if (!toolIndexMap.containsKey(oaIndex)) {
                out.addAll(ensureBlock(BlockType.TOOL_USE, id, name));
                toolIndexMap.put(oaIndex, currentIndex());
            }
        }

        // arguments 增量 → input_json_delta
        if (!args.isEmpty()) {
            Integer idx = toolIndexMap.get(oaIndex);
            if (idx == null) {
                // 没见过首片（异常流）→ 补开一个
                out.addAll(ensureBlock(BlockType.TOOL_USE, id, name));
                idx = currentIndex();
                toolIndexMap.put(oaIndex, idx);
            }
            addIfNotNull(out, emitDelta(idx, Map.of(
                    "type", "input_json_delta", "partial_json", args)));
        }
        return out;
    }

    /**
     * 确保当前打开的是指定类型的 block；类型变化时先 stop 旧的再 start 新的
     * <p>
     * 这是本类的<b>核心不变式</b>：任何时刻最多只有一个 block 打开，
     * 且每个 start 都有对应的 stop。
     */
    private List<Event> ensureBlock(BlockType type, String toolId, String toolName) {
        // tool_use 每次都要新开（多个工具并行调用）
        boolean needNew = (currentBlock != type) || (type == BlockType.TOOL_USE);
        if (!needNew) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
        addIfNotNull(out, closeCurrentBlock());

        Map<String, Object> cb = new LinkedHashMap<>();
        switch (type) {
            case THINKING -> {
                cb.put("type", "thinking");
                cb.put("thinking", "");
            }
            case TEXT -> {
                cb.put("type", "text");
                cb.put("text", "");
            }
            case TOOL_USE -> {
                cb.put("type", "tool_use");
                cb.put("id", toolId != null && !toolId.isEmpty() ? toolId : "toolu_" + nextIndex);
                cb.put("name", toolName != null ? toolName : "");
                cb.put("input", Map.of());
            }
            default -> {
                return out;
            }
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "content_block_start");
        ev.put("index", nextIndex);
        ev.put("content_block", cb);
        addIfNotNull(out, event("content_block_start", ev));
        currentBlock = type;
        return out;
    }

    /** 关闭当前 block（若有），index 递增；无 block 时返回 null */
    private Event closeCurrentBlock() {
        if (currentBlock == BlockType.NONE) {
            return null;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "content_block_stop");
        ev.put("index", nextIndex);
        Event e = event("content_block_stop", ev);
        nextIndex++;
        currentBlock = BlockType.NONE;
        return e;
    }

    private int currentIndex() {
        return nextIndex;
    }

    private Event emitMessageStart() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", 0);
        usage.put("output_tokens", 0);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", messageId != null ? messageId : "msg_unknown");
        msg.put("type", "message");
        msg.put("role", "assistant");
        msg.put("model", model != null ? model : fallbackModel);
        msg.put("content", List.of());
        msg.put("stop_reason", null);
        msg.put("stop_sequence", null);
        msg.put("usage", usage);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "message_start");
        ev.put("message", msg);
        messageStarted = true;
        return event("message_start", ev);
    }

    private Event emitDelta(int index, Map<String, Object> delta) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "content_block_delta");
        ev.put("index", index);
        ev.put("delta", delta);
        return event("content_block_delta", ev);
    }

    /**
     * OpenAI usage → Anthropic usage
     * <p>
     * <b>credit 不透传</b>：Anthropic 协议没有该字段，下游 SDK 见到未知字段虽不会报错，
     * 但没必要暴露内部计费信息。credit 由 controller 侧单独从原始 chunk 提取落库。
     */
    private Map<String, Object> convertUsage(JsonNode usage) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", intOf(usage, "prompt_tokens"));
        u.put("output_tokens", intOf(usage, "completion_tokens"));
        // 缓存字段：OpenAI 的 prompt_cache_hit_tokens 对应 Anthropic 的 cache_read_input_tokens
        int cacheRead = intOf(usage, "prompt_cache_hit_tokens");
        if (cacheRead == 0) {
            cacheRead = intOf(usage, "cache_read_input_tokens");
        }
        u.put("cache_read_input_tokens", cacheRead);
        u.put("cache_creation_input_tokens", intOf(usage, "cache_creation_input_tokens"));
        return u;
    }

    private static String mapStopReason(String finishReason) {
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls", "function_call" -> "tool_use";
            case "length" -> "max_tokens";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    /**
     * 渲染成一个事件
     * <p>
     * <strong>❗ 只返回 data 载荷（裸 JSON），不含 {@code event:} / {@code data:} 前缀</strong>。
     * <p>
     * 原因：controller 用 {@code ServerSentEvent} 包装后交给 WebFlux，
     * 由框架负责写出 {@code event: xxx\ndata: {...}\n\n} 的 SSE 报文。
     * 若这里自己拼 SSE 文本，框架会把整段文本<b>当作 data 值再包一层</b>，
     * 产出 {@code data:event: message_start} 这种畸形输出，
     * 客户端解析不到任何有效事件（表现为 "empty or malformed response"）。
     * <p>
     * 事件名通过 {@link Event#name} 单独携带。
     */
    private Event event(String name, Map<String, Object> payload) {
        try {
            return new Event(name, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("事件序列化失败 {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 一个 Anthropic SSE 事件：事件名 + data 载荷（裸 JSON）
     * <p>
     * controller 负责转成 {@code ServerSentEvent}，由 WebFlux 写出标准 SSE 报文。
     */
    public record Event(String name, String data) {
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return "";
        }
        return v.asText();
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isNumber()) ? v.asInt() : 0;
    }

    private static Map<String, Object> defaultUsage() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", 0);
        u.put("output_tokens", 0);
        return u;
    }

    /** 供测试断言用：当前是否已发过 message_start */
    boolean isMessageStarted() {
        return messageStarted;
    }

    /** 供测试断言用：已分配的 block 数 */
    int blockCount() {
        return nextIndex;
    }

    private static void addIfNotNull(List<Event> list, Event e) {
        if (e != null) {
            list.add(e);
        }
    }
}
