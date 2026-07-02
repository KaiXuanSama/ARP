package com.kaixuan.agentreproxy.dto;

/**
 * 下游 API Key 列表分页查询请求
 * <p>
 * 基于偏移量的传统分页(支持跳页)。所有字段可选,缺省走默认。
 * <ul>
 * <li>{@code labelLike} —— 按 label 模糊搜索(可选)</li>
 * <li>{@code pageNum} —— 页码,从 1 开始,默认 1</li>
 * <li>{@code pageSize} —— 每页条数,默认 10,硬上限 100</li>
 * <li>{@code orderBy} —— 排序字段,白名单:id / created_at / call_count / used_credits,默认 created_at</li>
 * <li>{@code asc} —— 是否升序,默认 false(降序,最新在前)</li>
 * </ul>
 */
public record DownstreamApiKeyQueryRequest(
        String labelLike,
        Integer pageNum,
        Integer pageSize,
        String orderBy,
        Boolean asc
) {
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final String DEFAULT_ORDER_BY = "created_at";

    public int safePageNum() {
        int n = pageNum == null ? DEFAULT_PAGE_NUM : pageNum;
        return n < 1 ? DEFAULT_PAGE_NUM : n;
    }

    public int safePageSize() {
        int s = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (s < 1) s = DEFAULT_PAGE_SIZE;
        if (s > MAX_PAGE_SIZE) s = MAX_PAGE_SIZE;
        return s;
    }

    public String safeOrderBy() {
        if (orderBy == null) return DEFAULT_ORDER_BY;
        return switch (orderBy.trim()) {
            case "id", "created_at", "call_count", "used_credits" -> orderBy.trim();
            default -> DEFAULT_ORDER_BY;
        };
    }

    public boolean safeAsc() {
        return Boolean.TRUE.equals(asc);
    }
}
