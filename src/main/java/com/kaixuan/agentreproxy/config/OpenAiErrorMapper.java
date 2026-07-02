package com.kaixuan.agentreproxy.config;

import com.kaixuan.agentreproxy.dto.OpenAiErrorResponse;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * 把 {@code SettingsService} 抛的 {@link IllegalArgumentException} 中文消息
 * 翻译成 OpenAI 兼容的错误响应({@link OpenAiErrorResponse})
 * <p>
 * 设计原则:
 * <ul>
 *   <li>{@code type} 选 OpenAI 标准三类之一:
 *       <ul>
 *         <li>{@code authentication_error} —— key 不存在 / 已禁用 / 已过期</li>
 *         <li>{@code invalid_request_error} —— 消息格式问题(messages 过少 / bearer 格式错)</li>
 *         <li>{@code permission_error} —— 额度用完(已通过鉴权但被业务策略拒绝)</li>
 *       </ul>
 *   </li>
 *   <li>{@code code} 是机器可读短码,程序分支判断用(下表)</li>
 *   <li>{@code message} 原文透传 —— 包含中文友好提示,B 端用户能看懂</li>
 * </ul>
 * <p>
 * <strong>code 列表</strong>(客户端应按 code 分支,不要按 message 字符串):
 * <table>
 *   <tr><th>message 包含</th><th>type</th><th>code</th><th>HTTP</th></tr>
 *   <tr><td>缺少 Authorization</td><td>invalid_request_error</td><td>missing_authorization</td><td>401</td></tr>
 *   <tr><td>Authorization 头缺少 token</td><td>invalid_request_error</td><td>missing_authorization</td><td>401</td></tr>
 *   <tr><td>无效的 API Key</td><td>authentication_error</td><td>invalid_api_key</td><td>401</td></tr>
 *   <tr><td>已禁用</td><td>authentication_error</td><td>api_key_disabled</td><td>401</td></tr>
 *   <tr><td>已过期</td><td>authentication_error</td><td>api_key_expired</td><td>401</td></tr>
 *   <tr><td>额度已用完</td><td>permission_error</td><td>credit_limit_exceeded</td><td>401</td></tr>
 *   <tr><td>指定模式但未选 / 账号不存在</td><td>permission_error</td><td>designated_account_invalid</td><td>400</td></tr>
 *   <tr><td>未配置对话消耗方式</td><td>invalid_request_error</td><td>consumption_not_configured</td><td>400</td></tr>
 *   <tr><td>chat.consumption 缺少 mode</td><td>invalid_request_error</td><td>consumption_invalid</td><td>400</td></tr>
 *   <tr><td>未知的对话消耗方式</td><td>invalid_request_error</td><td>consumption_unknown</td><td>400</td></tr>
 *   <tr><td>当前仅支持...暂未实现</td><td>invalid_request_error</td><td>mode_not_implemented</td><td>400</td></tr>
 *   <tr><td>messages 至少需要</td><td>invalid_request_error</td><td>messages_too_few</td><td>400</td></tr>
 *   <tr><td>无法解析的/无 key 命中的</td><td>permission_error</td><td>no_eligible_account</td><td>400</td></tr>
 *   <tr><td>其他</td><td>invalid_request_error</td><td>invalid_request</td><td>400</td></tr>
 * </table>
 */
public final class OpenAiErrorMapper {

    private OpenAiErrorMapper() {}

    /**
     * 把异常消息翻译成 OpenAI 错误响应
     *
     * @param message    service 抛出的 message(包含中文友好提示)
     * @param statusCode 控制器决定的 HTTP 状态码(401/400/...)
     * @return OpenAI 风格错误响应
     */
    public static OpenAiErrorResponse map(String message, HttpStatus statusCode) {
        String m = message == null ? "" : message;
        // 1) 鉴权链(401)
        if (m.contains("缺少 Authorization")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "missing_authorization");
        }
        if (m.contains("缺少 token")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "missing_authorization");
        }
        if (m.contains("无效的 API Key")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_AUTHENTICATION, null, "invalid_api_key");
        }
        if (m.contains("已禁用")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_AUTHENTICATION, null, "api_key_disabled");
        }
        if (m.contains("已过期")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_AUTHENTICATION, null, "api_key_expired");
        }
        if (m.contains("额度已用完")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_PERMISSION, null, "credit_limit_exceeded");
        }
        // 2) 业务配置链(400)
        if (m.contains("为指定模式") || m.contains("指定账号不存在")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_PERMISSION, null, "designated_account_invalid");
        }
        if (m.contains("未配置对话消耗方式")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "consumption_not_configured");
        }
        if (m.contains("缺少 mode")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "consumption_invalid");
        }
        if (m.contains("未知的对话消耗方式") || m.contains("未知的消耗方式")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "consumption_unknown");
        }
        if (m.contains("暂未实现")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "mode_not_implemented");
        }
        if (m.contains("messages 至少需要")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "messages_too_few");
        }
        if (m.contains("没有可用的") || m.contains("可用的临期") || m.contains("可用的量少") || m.contains("可用的量大")) {
            return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_PERMISSION, null, "no_eligible_account");
        }
        // 3) 兜底 —— 未识别 message,仍按 OpenAI 格式,code 用 invalid_request 让客户端看 message
        return new OpenAiErrorResponse(m, OpenAiErrorResponse.TYPE_INVALID_REQUEST, null, "invalid_request");
    }

    /**
     * 便捷方法:把 message 和 code 直接渲染成 OpenAI 标准 JSON 字节
     */
    public static Map<String, Object> toJsonBody(String message, HttpStatus statusCode) {
        return map(message, statusCode).toMap();
    }
}
