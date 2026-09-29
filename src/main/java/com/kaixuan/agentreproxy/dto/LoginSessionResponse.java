package com.kaixuan.agentreproxy.dto;

/**
 * 扫码登录会话的响应体
 * <p>
 * 用于「扫码添加账号」流程：后端生成 state 与一次性 ticket，
 * 前端据此构造登录链接并引导用户扫码。
 *
 * @param state     登录流程关联键（UUID）
 *                  <p>
 *                  由本服务生成，随链接传给 CodeBuddy 登录页。
 *                  用户扫码完成后，凭该 state 即可领取 token
 *                  （实测可重复领取，非一次性）。
 * @param loginUrl  完整的 CodeBuddy 登录链接（含 state / arp / ticket 参数）
 * @param ticket    一次性回传凭证（32 字节随机十六进制，64 字符）
 *                  <p>
 *                  书签脚本领取到 token 后，凭此 ticket 调用
 *                  {@code POST /api/accounts/import-token} 回传 ——
 *                  该端点不校验管理面板 token（跨域请求带不上），
 *                  改用 ticket 鉴权。5 分钟过期、用后即废。
 * @param expiresAt ticket 过期时间（毫秒时间戳），前端用于倒计时提示
 */
public record LoginSessionResponse(
        String state,
        String loginUrl,
        String ticket,
        long expiresAt
) {
}
