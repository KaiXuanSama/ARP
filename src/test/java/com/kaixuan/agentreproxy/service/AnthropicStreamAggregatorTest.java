package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnthropicStreamAggregator} 单元测试
 * <p>
 * 用真实抓包数据（2026-08 上游 {@code copilot.tencent.com/v1/messages} 实测）
 * 验证聚合逻辑。<b>不启动 Spring 容器</b>，纯 POJO 测试，跑得快。
 * <p>
 * <strong>为什么必须有这个测试</strong>：非流式聚合的第一版有个隐蔽 bug ——
 * controller 用 {@code String.join("", parts)} 把各个事件 JSON 粘成一行
 * {@code {...}{...}{...}}，Jackson {@code readTree} 只解析第一个对象就返回，
 * 导致 {@code message_start} 有值但所有 {@code content_block_delta} 全丢，
 * 表现为 {@code content} 恒为空数组。这类"看起来能跑、数据静默丢失"的问题
 * 靠手工调接口很难发现，必须用测试钉住。
 */
class AnthropicStreamAggregatorTest {

    private final AnthropicStreamAggregator aggregator =
            new AnthropicStreamAggregator(new ObjectMapper());

    /**
     * WebClient 解码 {@code text/event-stream} 后的真实形态：
     * Spring 的 SSE 解码器已剥掉 {@code event:} / {@code data:} 前缀，
     * 只把 data 的值作为 element 发下来。controller 用 {@code \n} 拼接。
     */
    private static final String DECODED_ELEMENTS = String.join("\n",
            "{\"message\":{\"content\":[],\"id\":\"msg_abc123\",\"model\":\"deepseek-v4-flash\","
                    + "\"role\":\"assistant\",\"stop_reason\":null,\"stop_sequence\":null,\"type\":\"message\","
                    + "\"usage\":{\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,"
                    + "\"input_tokens\":0,\"output_tokens\":0}},\"type\":\"message_start\"}",
            "{\"content_block\":{\"thinking\":\"\",\"type\":\"thinking\"},\"index\":0,\"type\":\"content_block_start\"}",
            "{\"delta\":{\"thinking\":\"我们\",\"type\":\"thinking_delta\"},\"index\":0,\"type\":\"content_block_delta\"}",
            "{\"delta\":{\"thinking\":\"需要回答\",\"type\":\"thinking_delta\"},\"index\":0,\"type\":\"content_block_delta\"}",
            "{\"index\":0,\"type\":\"content_block_stop\"}",
            "{\"content_block\":{\"text\":\"\",\"type\":\"text\"},\"index\":1,\"type\":\"content_block_start\"}",
            "{\"delta\":{\"text\":\"测试\",\"type\":\"text_delta\"},\"index\":1,\"type\":\"content_block_delta\"}",
            "{\"delta\":{\"text\":\"成功\",\"type\":\"text_delta\"},\"index\":1,\"type\":\"content_block_delta\"}",
            "{\"index\":1,\"type\":\"content_block_stop\"}",
            "{\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"type\":\"message_delta\","
                    + "\"usage\":{\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,"
                    + "\"input_tokens\":92,\"output_tokens\":16,\"total_tokens\":108}}",
            "{\"type\":\"message_stop\"}");

    @Test
    @DisplayName("聚合解码后的 element 流：正文与思维链分离，usage 取 message_delta")
    void aggregateDecodedElements() {
        Map<String, Object> msg = aggregator.aggregate(DECODED_ELEMENTS, "fallback-model");

        assertEquals("msg_abc123", msg.get("id"));
        assertEquals("message", msg.get("type"));
        assertEquals("assistant", msg.get("role"));
        assertEquals("deepseek-v4-flash", msg.get("model"));
        assertEquals("end_turn", msg.get("stop_reason"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        assertEquals(2, content.size(), "应有 thinking 与 text 两个 block");

        // index 0 = thinking，index 1 = text，顺序必须保持
        assertEquals("thinking", content.get(0).get("type"));
        assertEquals("我们需要回答", content.get(0).get("thinking"));
        assertEquals("text", content.get(1).get("type"));
        assertEquals("测试成功", content.get(1).get("text"),
                "正文不能被思维链污染");

        // usage 必须取 message_delta 的真实 token 数，不是 message_start 的 0 占位
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) msg.get("usage");
        assertEquals(92, ((Number) usage.get("input_tokens")).intValue());
        assertEquals(16, ((Number) usage.get("output_tokens")).intValue());
    }

    @Test
    @DisplayName("兼容原始 SSE 文本（带 event: / data: 前缀）")
    void aggregateRawSseText() {
        String raw = """
                event: message_start
                data: {"message":{"id":"msg_raw","model":"glm-5.2","role":"assistant","type":"message"},"type":"message_start"}

                event: content_block_start
                data: {"content_block":{"text":"","type":"text"},"index":0,"type":"content_block_start"}

                event: content_block_delta
                data: {"delta":{"text":"你好","type":"text_delta"},"index":0,"type":"content_block_delta"}

                event: message_delta
                data: {"delta":{"stop_reason":"end_turn"},"type":"message_delta","usage":{"input_tokens":5,"output_tokens":2}}

                event: message_stop
                data: {"type":"message_stop"}
                """;

        Map<String, Object> msg = aggregator.aggregate(raw, "fallback");

        assertEquals("msg_raw", msg.get("id"));
        assertEquals("glm-5.2", msg.get("model"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        assertEquals(1, content.size());
        assertEquals("你好", content.get(0).get("text"));
    }

    @Test
    @DisplayName("tool_use 块：partial_json 拼接后解析为对象")
    void aggregateToolUse() {
        String elements = String.join("\n",
                "{\"message\":{\"id\":\"msg_tool\",\"model\":\"deepseek-v4-pro\",\"role\":\"assistant\"},\"type\":\"message_start\"}",
                "{\"content_block\":{\"id\":\"toolu_01\",\"name\":\"get_weather\",\"input\":{},\"type\":\"tool_use\"},"
                        + "\"index\":0,\"type\":\"content_block_start\"}",
                "{\"delta\":{\"partial_json\":\"{\\\"city\\\":\",\"type\":\"input_json_delta\"},"
                        + "\"index\":0,\"type\":\"content_block_delta\"}",
                "{\"delta\":{\"partial_json\":\"\\\"北京\\\"}\",\"type\":\"input_json_delta\"},"
                        + "\"index\":0,\"type\":\"content_block_delta\"}",
                "{\"index\":0,\"type\":\"content_block_stop\"}",
                "{\"delta\":{\"stop_reason\":\"tool_use\"},\"type\":\"message_delta\","
                        + "\"usage\":{\"input_tokens\":30,\"output_tokens\":12}}");

        Map<String, Object> msg = aggregator.aggregate(elements, "fallback");

        assertEquals("tool_use", msg.get("stop_reason"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        assertEquals(1, content.size());
        Map<String, Object> block = content.get(0);
        assertEquals("tool_use", block.get("type"));
        assertEquals("toolu_01", block.get("id"));
        assertEquals("get_weather", block.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) block.get("input");
        assertEquals("北京", input.get("city"), "partial_json 必须拼接后再解析");
    }

    @Test
    @DisplayName("退化输入：多个 JSON 粘在同一行时不静默丢数据")
    void aggregateConcatenatedOnSingleLine() {
        // 这是第一版的 bug 形态：join("") 让所有事件挤在一行。
        // 现在 controller 用 join("\n") 已避免，但聚合器本身也应尽量兜住。
        String glued =
                "{\"message\":{\"id\":\"msg_glue\",\"role\":\"assistant\"},\"type\":\"message_start\"}"
                        + "{\"delta\":{\"text\":\"丢失\",\"type\":\"text_delta\"},\"index\":0,"
                        + "\"type\":\"content_block_delta\"}";

        Map<String, Object> msg = aggregator.aggregate(glued, "fallback");

        // 至少 message_start 能解析出来，且不抛异常
        assertEquals("msg_glue", msg.get("id"));
        assertNotNull(msg.get("content"));
    }

    @Test
    @DisplayName("空输入与脏数据：不抛异常，返回结构完整的兜底 Message")
    void aggregateEmptyAndDirty() {
        Map<String, Object> empty = aggregator.aggregate("", "fallback-model");
        assertEquals("fallback-model", empty.get("model"), "上游没给 model 时用请求里的");
        assertEquals("end_turn", empty.get("stop_reason"));
        assertTrue(((List<?>) empty.get("content")).isEmpty());
        assertNotNull(empty.get("usage"));

        Map<String, Object> dirty = aggregator.aggregate(
                "not-json\ndata: {坏JSON\n{\"type\":\"message_stop\"}", "fallback-model");
        assertNotNull(dirty.get("content"), "脏数据只跳过该行，不中断聚合");

        Map<String, Object> nullInput = aggregator.aggregate(null, "fallback-model");
        assertEquals("fallback-model", nullInput.get("model"));
    }
}
