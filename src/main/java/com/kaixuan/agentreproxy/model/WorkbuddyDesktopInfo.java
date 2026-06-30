package com.kaixuan.agentreproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * workbuddy-desktop.info 文件的完整数据模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkbuddyDesktopInfo(
        Account account,
        Auth auth
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            String uid,
            String nickname,
            String uin,
            String type,
            boolean lastLogin,
            boolean pluginEnabled,
            String phoneNumber
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Auth(
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            String tokenType,
            String scope,
            String domain,
            long lastRefreshTime,
            long expiresAt,
            long refreshExpiresAt
    ) {}
}
