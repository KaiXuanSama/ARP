package com.kaixuan.agentreproxy.dto;

/**
 * 流式签到事件
 * <p>
 * type:
 *   - started  单个账号开始处理
 *   - result   单个账号处理完成
 *   - summary  整批处理结束
 */
public record CheckinStreamEvent(
        String type,
        Long accountId,
        String uid,
        String nickname,
        Integer position,
        Integer total,
        String status,
        String message,
        Long checkinTime,
        Integer okCount,
        Integer alreadyCount,
        Integer errCount,
        Long durationMs
) {

    public static CheckinStreamEvent started(Long accountId,
                                             String uid,
                                             String nickname,
                                             int position,
                                             int total) {
        return new CheckinStreamEvent(
                "started",
                accountId,
                uid,
                nickname,
                position,
                total,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static CheckinStreamEvent result(CheckinResultItem item,
                                            int position,
                                            int total,
                                            Long checkinTime) {
        return new CheckinStreamEvent(
                "result",
                item.accountId(),
                item.uid(),
                item.nickname(),
                position,
                total,
                item.status(),
                item.message(),
                checkinTime,
                null,
                null,
                null,
                null
        );
    }

    public static CheckinStreamEvent summary(int okCount,
                                             int alreadyCount,
                                             int errCount,
                                             long durationMs,
                                             int total) {
        return new CheckinStreamEvent(
                "summary",
                null,
                null,
                null,
                total,
                total,
                null,
                null,
                null,
                okCount,
                alreadyCount,
                errCount,
                durationMs
        );
    }
}
