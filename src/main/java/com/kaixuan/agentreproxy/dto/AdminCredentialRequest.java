package com.kaixuan.agentreproxy.dto;

/**
 * 管理员凭证修改的请求体（专有端点用）
 * <p>
 * 设计规则：
 * <ul>
 *   <li>{@code newUsername} — 选填，非空时覆写用户名</li>
 *   <li>{@code newPassword} — 选填，非空时覆写密码（需 confirmPassword 一致）</li>
 *   <li>{@code confirmPassword} — 与 newPassword 配对校验</li>
 *   <li>{@code oldPassword} — 必填，用于验证当前密码（防止越权修改）</li>
 * </ul>
 */
public record AdminCredentialRequest(
        String newUsername,
        String newPassword,
        String confirmPassword,
        String oldPassword
) {}
