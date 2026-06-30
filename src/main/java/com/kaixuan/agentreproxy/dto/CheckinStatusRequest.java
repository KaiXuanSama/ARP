package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 * 批量查询签到状态请求
 * <p>
 * accountIds 为 null 或空时，后端查所有账号
 */
public record CheckinStatusRequest(
        List<Long> accountIds
) {}
