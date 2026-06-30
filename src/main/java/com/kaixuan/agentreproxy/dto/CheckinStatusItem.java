package com.kaixuan.agentreproxy.dto;

/**
 * 单个账号的签到状态信息
 * <p>
 * checkedIn 由本地 checkin_log 表 + 当日时间窗判定
 * <p>
 * checkinTime / checkinType 仅在 checkedIn=true 时有意义
 */
public record CheckinStatusItem(
        Long accountId,
        String uid,
        String nickname,
        boolean checkedIn,
        Long checkinTime,
        String checkinType
) {}
