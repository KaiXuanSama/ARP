package com.kaixuan.agentreproxy.config;

import com.kaixuan.agentreproxy.dto.OpenAiErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误响应映射单元测试
 * <p>
 * 重点钉住「上游错误完整回传」这一契约：上游的原始状态码与响应体必须能被下游看到，
 * 而不是被加工成一句提示。上游错误的排查价值集中在 {@code code}（业务码，如 11148）
 * 与 {@code requestId}（可据此找上游客服），这些在加工后就会丢失。
 * <p>
 * 同时验证 OpenAI / Anthropic 两种格式都不破坏各自的顶层结构
 * （Anthropic SDK 依赖顶层 {@code type:"error"}，缺了会解析失败）。
 */
class ErrorMapperTest {

    /** 上游真实的 11148 错误响应体（2026-09 实测抓取） */
    // private static final String UPSTREAM_11148 = """
    //         {"code":11148,"msg":"tool calls and tool results do not match, please start a new conversation and retry",
    //          "requestId":"d5965230-72e4-454e-a25c-0e96eec3e2d8",
    //          "extError":{"code":"tool_calls_mismatch","StatusCode":400},
    //          "displayMsg":{"en":"The tool call history is incomplete. Please start a new conversation.",
    //                        "zh":"工具调用记录不完整，请重新发起对话。"}}
    //         """.replace('\n', ' ');

    private static Map<String, Object> upstreamInfo(int status, Object body) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", status);
        info.put("body", body);
        return info;
    }

    @Test
    @DisplayName("OpenAI 格式：upstream 字段携带上游状态码与完整响应体")
    void openAiCarriesUpstreamInfo() {
        Map<String, Object> upstream = upstreamInfo(400, Map.of(
                "code", 11148,
                "requestId", "d5965230-72e4-454e-a25c-0e96eec3e2d8",
                "displayMsg", Map.of("zh", "工具调用记录不完整，请重新发起对话。")));

        OpenAiErrorResponse resp = OpenAiErrorMapper
                .map("上游错误: 工具调用记录不完整，请重新发起对话。", HttpStatus.BAD_REQUEST)
                .withUpstream(upstream);

        Map<String, Object> body = resp.toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals("invalid_request_error", error.get("type"));
        assertTrue(error.get("message").toString().contains("工具调用记录不完整"));

        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) error.get("upstream");
        assertEquals(400, up.get("status"), "上游状态码必须原样回传");
        @SuppressWarnings("unchecked")
        Map<String, Object> upBody = (Map<String, Object>) up.get("body");
        assertEquals(11148, upBody.get("code"), "上游业务码必须保留");
        assertTrue(upBody.containsKey("requestId"), "requestId 必须保留（排查依赖它）");
    }

    @Test
    @DisplayName("OpenAI 格式：本地错误不带 upstream 字段（保持既有行为）")
    void openAiLocalErrorHasNoUpstream() {
        Map<String, Object> body = OpenAiErrorMapper
                .map("无效的 API Key", HttpStatus.UNAUTHORIZED).toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertFalse(error.containsKey("upstream"),
                "本地错误没有上游参与，不该出现 upstream 字段");
        assertEquals("invalid_api_key", error.get("code"));
    }

    @Test
    @DisplayName("Anthropic 格式：保留顶层 type:error，upstream 嵌在 error 内")
    void anthropicCarriesUpstreamInfo() {
        Map<String, Object> body = AnthropicErrorMapper.map(
                "上游错误: 工具调用记录不完整，请重新发起对话。",
                HttpStatus.BAD_REQUEST,
                upstreamInfo(400, Map.of("code", 11148)));

        // Anthropic SDK 依赖顶层 type:"error"，缺了会解析失败
        assertEquals("error", body.get("type"), "顶层必须有 type:error");

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals("invalid_request_error", error.get("type"));
        assertTrue(error.containsKey("upstream"), "上游信息必须带上");

        @SuppressWarnings("unchecked")
        Map<String, Object> up = (Map<String, Object>) error.get("upstream");
        assertEquals(400, up.get("status"));
    }

    @Test
    @DisplayName("Anthropic 格式：不带 upstream 时结构不变（向后兼容）")
    void anthropicWithoutUpstream() {
        Map<String, Object> body = AnthropicErrorMapper.map(
                "无效的 API Key", HttpStatus.UNAUTHORIZED);

        assertEquals("error", body.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals("authentication_error", error.get("type"));
        assertFalse(error.containsKey("upstream"));
    }

    @Test
    @DisplayName("上游状态码 → 错误类型映射（Anthropic 侧）")
    void upstreamStatusToAnthropicType() {
        // 上游 400（业务错误，如 11148 / 11102）→ invalid_request_error
        assertEquals("invalid_request_error",
                anthropicType(HttpStatus.BAD_REQUEST));
        // 上游 401（凭证失效，APISIX 返回 HTML）→ authentication_error
        assertEquals("authentication_error",
                anthropicType(HttpStatus.UNAUTHORIZED));
        // 上游 429 → rate_limit_error
        assertEquals("rate_limit_error",
                anthropicType(HttpStatus.TOO_MANY_REQUESTS));
        // 上游 5xx → api_error（表示上游故障而非请求问题）
        assertEquals("api_error",
                anthropicType(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @SuppressWarnings("unchecked")
    private static String anthropicType(HttpStatus status) {
        Map<String, Object> body = AnthropicErrorMapper.map("x", status);
        return ((Map<String, Object>) body.get("error")).get("type").toString();
    }
}
