package com.kaixuan.agentreproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "chat.consumption" 设置项的 value 结构
 * <p>
 * 后端持久化的是这个对象序列化后的 JSON 字符串
 * <p>
 * 字段:
 * <ul>
 *   <li>mode —— 消耗方式("designated" / "least" / "expiring" / "most")</li>
 *   <li>accountId —— 仅 mode="designated" 时有意义;非指定模式时为 null</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsumptionSettingValue(
        String mode,
        Long accountId
) {
    /**
     * 转成通用的 Map,方便 Jackson 序列化
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        // accountId 非空才写进 JSON,避免 "accountId": null 这种噪声
        if (accountId != null) m.put("accountId", accountId);
        return m;
    }
}
