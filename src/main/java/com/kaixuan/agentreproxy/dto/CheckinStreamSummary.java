package com.kaixuan.agentreproxy.dto;

/**
 * 流式签到汇总
 */
public record CheckinStreamSummary(
        int okCount,
        int alreadyCount,
        int errCount,
        long durationMs,
        int total
) {}
