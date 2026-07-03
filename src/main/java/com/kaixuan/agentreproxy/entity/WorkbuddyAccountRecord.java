package com.kaixuan.agentreproxy.entity;

/**
 * 持久化的 CodeBuddy 账户记录
 * <p>
 * access_token 与 api_key 是等效的凭证，互补使用
 * （通常有 JWT 时不会同时存在 API Key）
 */
public record WorkbuddyAccountRecord(
        Long id,
        String uid,
        String accountJson,
        String accessToken,
        String apiKey,
        Integer enabled,
        String extra,
        Long createdAt,
        Long updatedAt
) {
    public static WorkbuddyAccountRecord ofNew(String uid, String accountJson, String accessToken, String apiKey) {
        return new WorkbuddyAccountRecord(null, uid, accountJson, accessToken, apiKey,1, null, null, null);
    }

    public boolean isEnabled() {
        return enabled == null || enabled !=0;
    }
}
