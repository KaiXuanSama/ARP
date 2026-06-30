package com.kaixuan.agentreproxy.dto;

/**
 * 单个账号的签到执行结果
 * <p>
 * status 含义：
 *   - "checked_in"          签到成功
 *   - "already_checked_in"  当日已签到（上游返回 10001 或本地已有当日记录）
 *   - "error"               签到失败（业务码非 0/10001，或上游异常）
 * <p>
 * 上述前两个都视为"已签到"，前端统一显示"已签到"；error 时 message 字段会给出错误描述
 */
public record CheckinResultItem(
        Long accountId,
        String uid,
        String nickname,
        String status,
        String message
) {}
