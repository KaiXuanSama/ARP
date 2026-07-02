package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 *通用分页响应（基于偏移量分页）
 * <p>
 *响应体字段：
 * <ul>
 * <li>{@code total} —— 总记录数</li>
 * <li>{@code pages} —— 总页数（= ceil(total / pageSize)，pageSize=0 时为0）</li>
 * <li>{@code list} —— 当前页数据列表</li>
 * <li>{@code pageNum} —— 当前页码（从1 开始）</li>
 * <li>{@code pageSize} ——每页条数</li>
 * </ul>
 *
 * @param <T>列表元素类型
 */
public record PageResult<T>(
 long total,
 int pages,
 List<T> list,
 int pageNum,
 int pageSize
) {
 /**
 *构造分页结果
 *
 * @param total 总记录数
 * @param list 当前页数据
 * @param pageNum 当前页码（1-based）
 * @param pageSize每页条数
 */
 public static <T> PageResult<T> of(long total, List<T> list, int pageNum, int pageSize) {
 int pages = pageSize <=0 ?0 : (int) Math.ceil((double) total / pageSize);
 return new PageResult<>(total, pages, list, pageNum, pageSize);
 }
}
