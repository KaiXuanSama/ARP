package com.kaixuan.agentreproxy.dto;

/**
 *签到历史日志条目（响应用）
 * <p>
 * 与 {@link com.kaixuan.agentreproxy.entity.CheckinLogRecord} 字段对齐，
 * 但 {@code extra} 反序列化为 {@code Object}（通常是 Map），便于前端直接消费。
 * 若 extra 为 null 或解析失败，{@code extra}返回 null。
 */
public record CheckinLogItem(
 Long id,
 Long accountId,
 String checkinType,
 Long checkinTime,
 Object extra
) {
}
