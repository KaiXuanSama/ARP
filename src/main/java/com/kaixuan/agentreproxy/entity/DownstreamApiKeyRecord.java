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
 *   <li>{@code supportedModels} —— JSON 数组,存 model id 列表
 *     <ul>
 *       <li>{@code null} —— /v1/models 回退到全集(默认行为,向后兼容老数据)</li>
 *       <li>{@code []} —— 严格不放行任何模型</li>
 *       <li>{@code ["a","b"]} —— 只放行白名单内的模型 id</li>
 *     </ul>
 *     序列化由 Repository 层负责(用 {@code ObjectMapper}),Entity 仅承载反序列化后的 Java 形态。
 *   </li>
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
        /** JSON 数组反序列化结果;null 表示未配置(回退全集),空列表表示严格不放行 */
        java.util.List<String> supportedModels,
        Long createdAt,
        Long updatedAt
) {
    public static DownstreamApiKeyRecord ofNew(String label, String apiKey, String consumption,
                                              Long designatedAccountId, Double creditLimit,
                                              Long expiresAt, java.util.List<String> supportedModels) {
        return new DownstreamApiKeyRecord(
                null, label, apiKey, 0L, 0.0, creditLimit, expiresAt,
                1, consumption, designatedAccountId, supportedModels, null, null);
    }

    /** 业务布尔:启用 */
    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }
}
