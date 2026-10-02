package com.kaixuan.agentreproxy.dto;

/**
 * 插件授权会话响应体（扫码登录新链路）
 * <p>
 * 用于「扫码添加账号」：服务端向上游创建授权会话（{@code /v2/plugin/auth/state}），
 * 把上游返回的官方登录页链接交给前端展示，用户在浏览器打开并扫码后，
 * 凭证由<b>服务端</b>轮询领取 —— token 不经过浏览器、无需书签回传。
 *
 * @param id        授权会话 id（服务端随机生成，非上游 state —— state 不暴露给浏览器）
 *                  <p>
 *                  前端凭它调 poll / cancel 端点。
 * @param loginUrl  上游返回的官方登录页链接（{@code https://copilot.tencent.com/login?platform=CLI&state=...}）
 *                  <p>
 *                  服务端已校验其安全性（https + 官方 host + path=/login + query state 与
 *                  上游返回一致），前端直接展示/打开即可。
 * @param expiresAt 会话过期时间（毫秒时间戳），前端用于倒计时；过期后 poll 返回 expired
 * @param interval  建议的前端轮询间隔（秒），当前为 3
 */
public record PluginLoginSessionResponse(
        String id,
        String loginUrl,
        long expiresAt,
        int interval
) {
}
