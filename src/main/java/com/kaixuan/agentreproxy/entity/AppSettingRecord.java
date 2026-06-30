package com.kaixuan.agentreproxy.entity;

/**
 * 持久化的应用设置项（key-value）
 * <p>
 * key 唯一，value 是 JSON 字符串（前端按需解析）
 * <p>
 * 时间戳用毫秒（与 workbuddy_account / checkin_log 保持一致）
 */
public record AppSettingRecord(
        Long id,
        String key,
        String value,
        Long createdAt,
        Long updatedAt
) {}
