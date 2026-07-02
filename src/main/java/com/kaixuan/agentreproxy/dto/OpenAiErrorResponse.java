package com.kaixuan.agentreproxy.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 风格的错误响应体
 * <p>
 * 标准格式见 https://platform.openai.com/docs/guides/error-codes/api-errors:
 * <pre>{@code
 * {
 *   "error": {
 *     "message": "Incorrect API key provided: ****. You can find your API key at https://platform.openai.com/account/api-keys.",
 *     "type": "invalid_request_error",
 *     "param": null,
 *     "code": "invalid_api_key"
 *   }
 * }
 * }</pre>
 * <p>
 * 我们复用此结构,但 {@code message} 用中文友好提示(后端开发者读得懂的下游用户),
 * {@code code} 用机器可读短码(便于客户端 if/else 分支),{@code type} 归到 OpenAI 三类之一。
 * <p>
 * 序列化时 {@code error} 是嵌套对象,不是字符串 —— 用 {@link #toMap()} 转成 LinkedHashMap
 * 保持字段顺序(message / type / param / code),Jackson 默认会按这个顺序输出。
 */
public record OpenAiErrorResponse(
        String message,
        String type,
        String param,
        String code
) {
    public static final String TYPE_INVALID_REQUEST = "invalid_request_error";
    public static final String TYPE_AUTHENTICATION = "authentication_error";
    public static final String TYPE_PERMISSION = "permission_error";
    public static final String TYPE_RATE_LIMIT = "rate_limit_error";
    public static final String TYPE_SERVER = "server_error";

    /**
     * 把当前 record 序列化成 OpenAI 兼容的 map 结构: {@code {"error": {...}}}
     * <p>
     * 用 {@link LinkedHashMap} 保证字段输出顺序;{@code param} 默认 null(OpenAI 标准字段
     * 但我们没有具体参数可以填)。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("message", message);
        inner.put("type", type);
        inner.put("param", param);
        inner.put("code", code);
        return Map.of("error", inner);
    }
}
