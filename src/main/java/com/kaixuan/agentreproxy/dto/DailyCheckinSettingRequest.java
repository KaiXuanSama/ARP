package com.kaixuan.agentreproxy.dto;

/**
 * 定时签到设置的请求体（专有端点用）
 * <p>
 * 与通用 {@code PUT /api/settings/{key}} 不同，本 DTO 是强类型的：
 * <ul>
 *   <li>{@code enabled} — 是否启用定时签到</li>
 *   <li>{@code time} — 触发时间，格式 HH:mm（enabled=true 时必填）</li>
 * </ul>
 */
public record DailyCheckinSettingRequest(
        Boolean enabled,
        String time
) {}
