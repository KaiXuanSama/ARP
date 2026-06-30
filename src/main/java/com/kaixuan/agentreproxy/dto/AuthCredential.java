package com.kaixuan.agentreproxy.dto;

/**
 * 上游请求的认证凭据
 * <p>
 * 由 {@code UpstreamClient.resolveAuth(accountId)} 根据数据库记录构建，{@code applyAuth} 按优先级拼头：
 * <ol>
 *   <li>有 accessToken → JWT 模式：Bearer + X-User-Id(若 uid 非空) + X-Domain</li>
 *   <li>否则有 apiKey → API Key 模式：仅 Bearer(不带 X-User-Id / X-Domain)</li>
 *   <li>都没有 → 抛 {@code MissingCredentialException}</li>
 * </ol>
 *
 * @param accessToken 来自 .info 导入的 JWT（可能为 null）
 * @param apiKey      来自手动输入的 API Key（可能为 null）
 * @param uidForHeader 仅在 JWT 模式下作为 X-User-Id 头使用（API Key 模式不带此头）
 * @param sourceMode   用于日志/metrics 区分凭证来源
 */
public record AuthCredential(
        String accessToken,
        String apiKey,
        String uidForHeader,
        SourceMode sourceMode
) {
    public enum SourceMode {
        /** .info 导入的 JWT */
        JWT,
        /** 用户手动输入的 API Key */
        API_KEY
    }
}
