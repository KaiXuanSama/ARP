package com.kaixuan.agentreproxy.dto;

/**
 *签到历史日志分页查询请求
 * <p>
 *基于偏移量的传统分页（支持跳页）。
 * <ul>
 * <li>{@code accountId} ——必填，指定要查的账号 id</li>
 * <li>{@code checkinType} —— 可选，默认 "daily"；未来可扩 "weekly" / "event_xxx"</li>
 * <li>{@code pageNum} —— 页码，从1 开始，默认1</li>
 * <li>{@code pageSize} ——每页条数，默认20，硬上限100（防止内存溢出）</li>
 * <li>{@code orderBy} ——排序字段，白名单：{@code id} / {@code checkin_time} / {@code account_id}，默认 {@code checkin_time}</li>
 * <li>{@code asc} —— 是否升序，默认 false（即降序，最新的在前）</li>
 * </ul>
 */
public record CheckinLogQueryRequest(
 Long accountId,
 String checkinType,
 Integer pageNum,
 Integer pageSize,
 String orderBy,
 Boolean asc
) {
 /**默认每页条数 */
 public static final int DEFAULT_PAGE_SIZE =20;
 /**每页条数硬上限 */
 public static final int MAX_PAGE_SIZE =100;
 /**默认页码 */
 public static final int DEFAULT_PAGE_NUM =1;
 /**默认签到类型 */
 public static final String DEFAULT_CHECKIN_TYPE = "daily";

 /**规范化后的页码（>=1） */
 public int safePageNum() {
 int n = pageNum == null ? DEFAULT_PAGE_NUM : pageNum;
 return n <1 ? DEFAULT_PAGE_NUM : n;
 }

 /**规范化后的每页条数（1..100） */
 public int safePageSize() {
 int s = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
 if (s <1) s = DEFAULT_PAGE_SIZE;
 if (s > MAX_PAGE_SIZE) s = MAX_PAGE_SIZE;
 return s;
 }

 /**规范化后的签到类型（null/空 → "daily"） */
 public String safeCheckinType() {
 return (checkinType == null || checkinType.isBlank()) ? DEFAULT_CHECKIN_TYPE : checkinType;
 }

 /**
 *规范化后的排序字段（白名单校验，防 SQL 注入）
 * <p>
 * 仅允许 {@code id} / {@code checkin_time} / {@code account_id}，其他一律回退到 {@code checkin_time}
 */
 public String safeOrderBy() {
 if (orderBy == null) return "checkin_time";
 return switch (orderBy.trim()) {
 case "id", "checkin_time", "account_id" -> orderBy.trim();
 default -> "checkin_time";
 };
 }

 /**规范化后的排序方向（null → false = DESC） */
 public boolean safeAsc() {
 return Boolean.TRUE.equals(asc);
 }
}
