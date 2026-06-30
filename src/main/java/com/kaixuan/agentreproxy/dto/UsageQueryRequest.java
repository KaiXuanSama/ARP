package com.kaixuan.agentreproxy.dto;

/**
 * 用量明细查询请求
 *
 * @param startTime 开始时间 yyyy-MM-dd HH:mm:ss
 * @param endTime   结束时间 yyyy-MM-dd HH:mm:ss
 * @param pageNum   页码（默认 1）
 * @param pageSize  每页条数（默认 50）
 */
public record UsageQueryRequest(
        String startTime,
        String endTime,
        Integer pageNum,
        Integer pageSize
) {}
