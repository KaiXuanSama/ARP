package com.kaixuan.agentreproxy.dto;

/**
 * 单个账号的签到执行结果（服务层内部结构）
 * <p>
 * result 保留原有 UI 语义；checkinTime 供流式接口把精确签到时间回传给前端
 */
public record CheckinExecutionResult(
        CheckinResultItem result,
        Long checkinTime
) {}
