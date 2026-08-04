package com.kaixuan.agentreproxy.config;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anthropic 风格错误响应映射器
 * <p>
 * 把内部错误消息 + HTTP 状态码，映射成 Anthropic 官方错误格式：
 * <pre>{@code
 * {
 *   "type": "error",
 *   "error": {
 *     "type": "authentication_error",
 *     "message": "无效的 API Key"
 *   }
 * }
 * }</pre>
 * <p>
 * <strong>与 OpenAI 格式的关键差异</strong>（这正是必须单独做一个映射器的原因）：
 * <ul>
 *   <li>Anthropic <b>顶层有 {@code type: "error"}</b>，OpenAI 没有</li>
 *   <li>Anthropic 的 {@code error} 对象里<b>没有 {@code code} / {@code param} 字段</b>，
 *       只有 {@code type} + {@code message}</li>
 *   <li>错误类型枚举不同：Anthropic 用 {@code invalid_request_error} /
 *       {@code authentication_error} / {@code permission_error} /
 *       {@code not_found_error} / {@code rate_limit_error} /
 *       {@code api_error} / {@code overloaded_error}</li>
 * </ul>
 * 若直接把 OpenAI 格式返回给 Anthropic SDK，SDK 会因为找不到顶层 {@code type}
 * 而抛解析异常，下游拿不到真实错因。
 *
 * @see <a href="https://docs.anthropic.com/en/api/errors">Anthropic API Errors</a>
 */
public final class AnthropicErrorMapper {

    private AnthropicErrorMapper() {
    }

    /**
     * 按状态码 + 消息内容映射为 Anthropic 错误 Map
     *
     * @param message 内部错误消息（会原样透传给下游，便于排查）
     * @param status  HTTP 状态码
     * @return 可直接序列化为 JSON 的 Anthropic 错误结构
     */
    public static Map<String, Object> map(String message, HttpStatus status) {
        String safeMessage = (message == null || message.isBlank())
                ? "请求处理失败"
                : message;

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", resolveType(status));
        error.put("message", safeMessage);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", error);
        return body;
    }

    /**
     * HTTP 状态码 → Anthropic 错误类型
     * <p>
     * 映射依据 Anthropic 官方文档的状态码约定
     */
    private static String resolveType(HttpStatus status) {
        if (status == null) {
            return "api_error";
        }
        return switch (status) {
            case BAD_REQUEST -> "invalid_request_error";
            case UNAUTHORIZED -> "authentication_error";
            case FORBIDDEN -> "permission_error";
            case NOT_FOUND -> "not_found_error";
            case PAYLOAD_TOO_LARGE -> "request_too_large";
            case TOO_MANY_REQUESTS -> "rate_limit_error";
            case SERVICE_UNAVAILABLE -> "overloaded_error";
            default -> status.is4xxClientError() ? "invalid_request_error" : "api_error";
        };
    }
}
