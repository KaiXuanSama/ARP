package com.kaixuan.agentreproxy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 更新 "chat.consumption" 设置的请求体
 * <p>
 * 字段含义:
 * <ul>
 *   <li>mode —— 消耗方式,枚举值: "designated" / "least" / "expiring" / "most"</li>
 *   <li>accountId —— 仅在 mode="designated" 时必填,指 workbuddy_account.id 主键</li>
 * </ul>
 * <p>
 * 服务层会强制做以下清洗:
 *   - mode 非 "designated" 时,无论请求里是否带 accountId,都会被清空
 *   - mode="designated" 时,accountId 必须非空且指向已存在的账户
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateConsumptionSettingRequest(
        String mode,
        Long accountId
) {}
