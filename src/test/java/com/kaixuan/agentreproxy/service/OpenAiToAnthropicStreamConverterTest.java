package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiToAnthropicStreamConverter} 单元测试
 * <p>
 * <strong>为什么必须有</strong>：Anthropic 协议对事件序列有严格契约
 * （start/stop 配对、index 连续、message_start 唯一）。违反时 SDK 会
 * <b>静默卡住</b>而不是报错，靠手工调接口极难发现。这些用例把契约钉死。
 * <p>
 * 测试数据来自 2026-08 对上游 {@code /v2/chat/completions} 的真实抓包。
 */
class OpenAiToAnthropicStreamConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenAiToAnthropicStreamConverter newConverter() {
        return new OpenAiToAnthropicStreamConverter(mapper, "deepseek-v4-flash");
    }

    /** 构造一个 OpenAI chunk 的 JSON 文本 */
    private static String chunk(String deltaJson, String finishReason) {
        return "{\"id\":\"abc-123\",\"model\":\"deepseek-v4-flash\","
                + "\"object\":\"chat.completion.chunk\",\"created\":1785892561,"
                + "\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + ",\"logprobs\":null,\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":null}";
    }

    /**
     * 把转换器产出的 Event 列表转成 (事件名, data JSON) 便于断言
     * <p>
     * 注意：转换器<b>只产出 data 载荷</b>，不含 {@code event:}/{@code data:} 前缀 ——
     * SSE 报文由 WebFlux 的 {@code ServerSentEvent} 负责写出。这个契约很重要：
     * 若转换器自己拼 SSE 文本，框架会二次包装成 {@code data:event: xxx} 的畸形报文。
     */
    private List<Map.Entry<String, JsonNode>> parse(
            List<OpenAiToAnthropicStreamConverter.Event> raw) {
        List<Map.Entry<String, JsonNode>> events = new ArrayList<>();
        for (var e : raw) {
            try {
                events.add(Map.entry(e.name(), mapper.readTree(e.data())));
            } catch (Exception ex) {
                throw new AssertionError("事件 data 不是合法 JSON: " + e.data(), ex);
            }
        }
        return events;
    }

    /** 校验 Anthropic 事件序列的核心契约 */
    private void assertContract(List<Map.Entry<String, JsonNode>> events) {
        assertFalse(events.isEmpty(), "不能是空事件流");
        assertEquals("message_start", events.get(0).getKey(), "首个事件必须是 message_start");
        assertEquals("message_stop", events.get(events.size() - 1).getKey(),
                "末个事件必须是 message_stop");

        long starts = events.stream().filter(e -> e.getKey().equals("message_start")).count();
        assertEquals(1, starts, "message_start 只能出现一次");

        // start/stop 必须配对，且 index 一致
        List<Integer> openStack = new ArrayList<>();
        int maxIndex = -1;
        for (var e : events) {
            switch (e.getKey()) {
                case "content_block_start" -> {
                    int idx = e.getValue().get("index").asInt();
                    assertTrue(openStack.isEmpty(),
                            "上一个 block 未 stop 就开新 block，index=" + idx);
                    assertEquals(maxIndex + 1, idx, "index 必须连续递增");
                    maxIndex = idx;
                    openStack.add(idx);
                }
                case "content_block_stop" -> {
                    int idx = e.getValue().get("index").asInt();
                    assertFalse(openStack.isEmpty(), "没有对应 start 的 stop，index=" + idx);
                    assertEquals(openStack.remove(openStack.size() - 1), idx,
                            "stop 的 index 必须与 start 匹配");
                }
                case "content_block_delta" -> {
                    int idx = e.getValue().get("index").asInt();
                    assertFalse(openStack.isEmpty(), "delta 出现在 block 之外，index=" + idx);
                    assertEquals(openStack.get(openStack.size() - 1), idx,
                            "delta 的 index 必须是当前打开的 block");
                }
                default -> { /* message_* 事件无需检查 index */ }
            }
        }
        assertTrue(openStack.isEmpty(), "流结束时仍有未关闭的 block: " + openStack);
    }

    @Test
    @DisplayName("思维链 + 正文：分成两个 block，顺序与内容正确")
    void thinkingThenText() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"role\":\"assistant\",\"content\":\"\"}", "")));
        raw.addAll(conv.convert(chunk("{\"reasoning_content\":\"The user\"}", "")));
        raw.addAll(conv.convert(chunk("{\"reasoning_content\":\" is asking\"}", "")));
        raw.addAll(conv.convert(chunk("{\"content\":\"你好\"}", "")));
        raw.addAll(conv.convert(chunk("{\"content\":\"世界\"}", "stop")));
        raw.addAll(conv.finish());

        var events = parse(raw);
        assertContract(events);

        // 累积文本
        StringBuilder thinking = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (var e : events) {
            if (!e.getKey().equals("content_block_delta")) continue;
            var d = e.getValue().get("delta");
            String type = d.get("type").asText();
            if (type.equals("thinking_delta")) thinking.append(d.get("thinking").asText());
            if (type.equals("text_delta")) text.append(d.get("text").asText());
        }
        assertEquals("The user is asking", thinking.toString(), "思维链要完整累积");
        assertEquals("你好世界", text.toString(), "正文不能被思维链污染");

        // block 类型顺序
        List<String> blockTypes = events.stream()
                .filter(e -> e.getKey().equals("content_block_start"))
                .map(e -> e.getValue().get("content_block").get("type").asText())
                .toList();
        assertEquals(List.of("thinking", "text"), blockTypes);

        // stop_reason
        var md = events.stream().filter(e -> e.getKey().equals("message_delta"))
                .findFirst().orElseThrow();
        assertEquals("end_turn", md.getValue().get("delta").get("stop_reason").asText());
    }

    @Test
    @DisplayName("工具调用：arguments 分片转成 input_json_delta，id 透传")
    void toolCall() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"role\":\"assistant\",\"content\":\"\"}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"id\":\"call_00_ABC\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"search_code\",\"arguments\":\"\"},\"index\":0}]}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"function\":{\"name\":\"\",\"arguments\":\"{\\\"q\\\":\"},\"index\":0}]}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"function\":{\"name\":\"\",\"arguments\":\"\\\"abc\\\"}\"},\"index\":0}]}",
                "tool_calls")));
        raw.addAll(conv.finish());

        var events = parse(raw);
        assertContract(events);

        var start = events.stream()
                .filter(e -> e.getKey().equals("content_block_start"))
                .findFirst().orElseThrow();
        var cb = start.getValue().get("content_block");
        assertEquals("tool_use", cb.get("type").asText());
        assertEquals("call_00_ABC", cb.get("id").asText(), "tool id 必须原样透传");
        assertEquals("search_code", cb.get("name").asText());

        StringBuilder partial = new StringBuilder();
        for (var e : events) {
            if (!e.getKey().equals("content_block_delta")) continue;
            var d = e.getValue().get("delta");
            if (d.get("type").asText().equals("input_json_delta")) {
                partial.append(d.get("partial_json").asText());
            }
        }
        assertEquals("{\"q\":\"abc\"}", partial.toString(), "参数分片要能拼回合法 JSON");

        var md = events.stream().filter(e -> e.getKey().equals("message_delta"))
                .findFirst().orElseThrow();
        assertEquals("tool_use", md.getValue().get("delta").get("stop_reason").asText());
    }

    @Test
    @DisplayName("多个并行工具调用：每个占独立 block，index 不冲突")
    void multipleToolCalls() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"role\":\"assistant\",\"content\":\"\"}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"id\":\"call_00_A\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"\"},\"index\":0}]}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"},\"index\":0}]}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"id\":\"call_01_B\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"search_code\",\"arguments\":\"\"},\"index\":1}]}", "")));
        raw.addAll(conv.convert(chunk(
                "{\"tool_calls\":[{\"function\":{\"arguments\":\"{}\"},\"index\":1}]}", "tool_calls")));
        raw.addAll(conv.finish());

        var events = parse(raw);
        assertContract(events);

        List<String> ids = events.stream()
                .filter(e -> e.getKey().equals("content_block_start"))
                .map(e -> e.getValue().get("content_block").get("id").asText())
                .toList();
        assertEquals(List.of("call_00_A", "call_01_B"), ids, "两个工具各自独立成块");
    }

    @Test
    @DisplayName("usage 映射：prompt/completion → input/output，credit 不外泄")
    void usageMapping() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"content\":\"hi\"}", "")));
        // 结算 chunk：choices 为空 + usage 带 credit
        raw.addAll(conv.convert("{\"id\":\"abc-123\",\"model\":\"deepseek-v4-flash\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":15970,\"completion_tokens\":221,"
                + "\"total_tokens\":16191,\"prompt_cache_hit_tokens\":15872,"
                + "\"completion_thinking_tokens\":57,\"credit\":0.03}}"));
        raw.addAll(conv.finish());

        var events = parse(raw);
        assertContract(events);

        var md = events.stream().filter(e -> e.getKey().equals("message_delta"))
                .findFirst().orElseThrow();
        var usage = md.getValue().get("usage");
        assertEquals(15970, usage.get("input_tokens").asInt());
        assertEquals(221, usage.get("output_tokens").asInt());
        assertEquals(15872, usage.get("cache_read_input_tokens").asInt());

        assertFalse(raw.stream().anyMatch(e -> e.data().contains("credit")),
                "credit 是内部计费字段，不应出现在发给下游的流里");
    }

    @Test
    @DisplayName("finish_reason 映射：length → max_tokens")
    void stopReasonMapping() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"content\":\"x\"}", "length")));
        raw.addAll(conv.finish());
        var events = parse(raw);
        assertContract(events);
        var md = events.stream().filter(e -> e.getKey().equals("message_delta"))
                .findFirst().orElseThrow();
        assertEquals("max_tokens", md.getValue().get("delta").get("stop_reason").asText());
    }

    @Test
    @DisplayName("[DONE] 与重复 finish：幂等，不产生重复收尾事件")
    void doneIsIdempotent() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"content\":\"hi\"}", "stop")));
        raw.addAll(conv.convert("[DONE]"));
        raw.addAll(conv.finish());   // 重复调用
        raw.addAll(conv.finish());   // 再来一次

        var events = parse(raw);
        assertContract(events);
        assertEquals(1, events.stream().filter(e -> e.getKey().equals("message_stop")).count(),
                "message_stop 只能有一个");
        assertEquals(1, events.stream().filter(e -> e.getKey().equals("message_delta")).count(),
                "message_delta 只能有一个");
    }

    @Test
    @DisplayName("空流与脏数据：不抛异常，仍能输出合法收尾")
    void emptyAndDirtyInput() {
        var conv = newConverter();
        assertTrue(conv.convert("").isEmpty(), "空输入无输出");
        assertTrue(conv.convert(null).isEmpty(), "null 输入无输出");
        conv.convert("not-json");
        conv.convert("data: {坏JSON");

        var events = parse(conv.finish());
        // 没有任何 chunk 时，finish 仍要产生合法的收尾（message_start 未发过则只有收尾事件）
        assertTrue(events.stream().anyMatch(e -> e.getKey().equals("message_stop")));
    }

    @Test
    @DisplayName("思维链与正文交替：每次切换都正确开闭 block")
    void alternatingBlocks() {
        var conv = newConverter();
        List<OpenAiToAnthropicStreamConverter.Event> raw = new ArrayList<>();
        raw.addAll(conv.convert(chunk("{\"reasoning_content\":\"think1\"}", "")));
        raw.addAll(conv.convert(chunk("{\"content\":\"text1\"}", "")));
        raw.addAll(conv.convert(chunk("{\"reasoning_content\":\"think2\"}", "")));
        raw.addAll(conv.convert(chunk("{\"content\":\"text2\"}", "stop")));
        raw.addAll(conv.finish());

        var events = parse(raw);
        assertContract(events);

        List<String> types = events.stream()
                .filter(e -> e.getKey().equals("content_block_start"))
                .map(e -> e.getValue().get("content_block").get("type").asText())
                .toList();
        assertEquals(List.of("thinking", "text", "thinking", "text"), types,
                "每次类型切换都要新开 block");
    }
}
