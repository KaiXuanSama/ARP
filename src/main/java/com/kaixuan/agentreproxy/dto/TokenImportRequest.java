package com.kaixuan.agentreproxy.dto;

/**
 * 扫码登录后的 token 回传请求体
 * <p>
 * 由浏览器书签脚本发起（来自 {@code codebuddy.cn} 登录页），
 * 携带从 {@code /console/login/enterprise} 领取到的明文凭证。
 *
 * @param ticket           一次性回传凭证（由 {@code /api/accounts/login-session} 下发）
 * @param uid              账号 UID（取自 accessToken 的 JWT {@code sub} 字段）
 * @param nickname         账号昵称（可选，仅用于展示）
 * @param accessToken      上游访问令牌（JWT，有效期约 55 天）
 * @param refreshToken     刷新令牌（JWT，{@code typ:"Offline"}，有效期约 60 天；可选）
 * @param expiresAt        accessToken 过期时间（毫秒时间戳）
 * @param refreshExpiresAt refreshToken 过期时间（毫秒时间戳；可选）
 */
public record TokenImportRequest(
        String ticket,
        String uid,
        String nickname,
        String accessToken,
        String refreshToken,
        Long expiresAt,
        Long refreshExpiresAt
) {
}
