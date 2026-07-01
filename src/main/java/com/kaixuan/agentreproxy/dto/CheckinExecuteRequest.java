package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 * 批量执行签到请求
 * <p>
 * accountIds 为 null 或空时，后端对所有"今日未签到"的账号发起签到
 * <p>
 * force 为 true 时，跳过本地"当日已签到"过滤，强制调用上游（测试用）
 */
public record CheckinExecuteRequest(
        List<Long> accountIds,
        Boolean force
) {
    public boolean isForce() {
        return Boolean.TRUE.equals(force);
    }
}
