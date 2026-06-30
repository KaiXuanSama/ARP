package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 * 批量执行签到请求
 * <p>
 * accountIds 为 null 或空时，后端对所有"今日未签到"的账号发起签到
 */
public record CheckinExecuteRequest(
        List<Long> accountIds
) {}
