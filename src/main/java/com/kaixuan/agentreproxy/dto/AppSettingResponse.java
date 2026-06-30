package com.kaixuan.agentreproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 通用设置项的响应外壳
 * <p>
 * key: 设置项名(例:"chat.consumption")
 * value: 任意 JSON 解析后的对象(由业务方定义结构)
 * updatedAt: 最后修改时间(毫秒)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSettingResponse(
        String key,
        Object value,
        Long updatedAt
) {}
