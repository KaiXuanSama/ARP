package com.kaixuan.agentreproxy.dto;

import com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord;

/**
 * 下游 API Key 响应条目
 * <p>
 * 出于安全考虑,列表接口(<code>findPage</code>)只返回 key 的前后各 4 位(中间用 * 遮蔽);
 * 创建接口(<code>POST</code>)返回完整明文,前端只展示一次后让用户保存。
 */
public record DownstreamApiKeyItem(
        Long id,
        String label,
        /** 创建/查看详情时为完整 key,列表场景为前后各 4 位遮蔽版 */
        String apiKey,
        boolean apiKeyFull,
        Long callCount,
        Double usedCredits,
        Double creditLimit,
        Long expiresAt,
        boolean enabled,
        String consumption,
        Long designatedAccountId,
        Long createdAt,
        Long updatedAt
) {
    public static DownstreamApiKeyItem from(DownstreamApiKeyRecord rec, boolean fullKey) {
        String displayKey = fullKey
                ? rec.apiKey()
                : maskApiKey(rec.apiKey());
        return new DownstreamApiKeyItem(
                rec.id(),
                rec.label(),
                displayKey,
                fullKey,
                rec.callCount(),
                rec.usedCredits(),
                rec.creditLimit(),
                rec.expiresAt(),
                rec.isEnabled(),
                rec.consumption(),
                rec.designatedAccountId(),
                rec.createdAt(),
                rec.updatedAt()
        );
    }

    /**
     * 把 32+ 位 key 遮蔽为前 4 + 中间 * + 后 4(总长 12 个字符)
     * <p>
     * 短 key(<= 12)原样返回(避免遮蔽后完全不可识别)
     */
    public static String maskApiKey(String key) {
        if (key == null || key.length() <= 12) return key;
        return key.substring(0, 4) + "********" + key.substring(key.length() - 4);
    }
}
