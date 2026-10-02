package com.kaixuan.agentreproxy.dto;

/**
 * 插件授权轮询响应体（扫码登录新链路）
 * <p>
 * 前端每 {@code interval} 秒调一次 poll 端点，按 {@code status} 分支处理：
 * <ul>
 *   <li>{@code pending} —— 用户尚未完成扫码，继续轮询</li>
 *   <li>{@code success} —— 凭证已由服务端领取并落库，展示账号信息后关闭弹窗。
 *       <b>token 不在响应里</b>（凭据永不出服务端）</li>
 *   <li>{@code expired} —— 会话已过期/被取消，前端应停止轮询并引导重新发起</li>
 *   <li>{@code error} —— 上游交互失败（非过期），展示 message 引导重试</li>
 * </ul>
 *
 * @param status     pending / success / expired / error
 * @param uid        账号 uid（仅 success 时非空）
 * @param nickname   账号昵称（仅 success 时非空，可能为 null）
 * @param expiresAt  accessToken 过期时间（毫秒，仅 success 时非空；前端可提示凭证有效期）
 * @param message    附加说明（仅 error 时非空；success/pending/expired 为 null）
 */
public record PluginLoginPollResponse(
        String status,
        String uid,
        String nickname,
        Long expiresAt,
        String message
) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_ERROR = "error";

    public static PluginLoginPollResponse pending() {
        return new PluginLoginPollResponse(STATUS_PENDING, null, null, null, null);
    }

    public static PluginLoginPollResponse success(String uid, String nickname, Long expiresAt) {
        return new PluginLoginPollResponse(STATUS_SUCCESS, uid, nickname, expiresAt, null);
    }

    public static PluginLoginPollResponse expired() {
        return new PluginLoginPollResponse(STATUS_EXPIRED, null, null, null, null);
    }

    public static PluginLoginPollResponse error(String message) {
        return new PluginLoginPollResponse(STATUS_ERROR, null, null, null, message);
    }
}
