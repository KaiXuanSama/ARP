package com.kaixuan.agentreproxy.entity;

/**
 * 持久化的签到日志记录
 * <p>
 * 一条记录 = 一次签到行为（同一账号可有多条）
 * <p>
 * "当日是否已签到"由业务层按 account_id + checkin_time 范围判定
 */
public record CheckinLogRecord(
        Long id,
        Long accountId,
        String checkinType,
        Long checkinTime,
        String extra
) {
    public static CheckinLogRecord ofNew(Long accountId, String checkinType, Long checkinTime, String extra) {
        return new CheckinLogRecord(null, accountId, checkinType, checkinTime, extra);
    }
}
