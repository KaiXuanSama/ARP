package com.kaixuan.agentreproxy.entity;

/**
 * 下游 API Key 持久化记录
 * <p>
 * 字段与 {@code downstream_api_key} 表一一对应。
 * <p>
 * 关键说明:
 * <ul>
 *   <li>{@code apiKey} —— 仅在创建时返回给前端一次,后续列表只显示前 N 位 + 后 N 位</li>
 *   <li>{@code enabled} —— 用 Integer 而非 boolean(SQLite 没有原生 boolean)</li>
 *   <li>{@code consumption} —— 字符串,与 Settings 页面的 ConsumptionMode 共享同一组常量</li>
 *   <li>{@code creditLimit} / {@code expiresAt} —— null 表示不限制</li>
 * </ul>
 */
public record DownstreamApiKeyRecord(
        Long id,
        String label,
        String apiKey,
        Long callCount,
        Double usedCredits,
        Double creditLimit,
        Long expiresAt,
        Integer enabled,
        String consumption,
        Long designatedAccountId,
        Long createdAt,
        Long updatedAt
) {
    public static DownstreamApiKeyRecord ofNew(String label, String apiKey, String consumption,
                                              Long designatedAccountId, Double creditLimit,
                                              Long expiresAt) {
        return new DownstreamApiKeyRecord(
                null, label, apiKey, 0L, 0.0, creditLimit, expiresAt,
                1, consumption, designatedAccountId, null, null);
    }

    /** 业务布尔:启用 */
    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }
}
