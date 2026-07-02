package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.CheckinExecuteRequest;
import com.kaixuan.agentreproxy.dto.CheckinLogQueryRequest;
import com.kaixuan.agentreproxy.dto.CheckinStatusItem;
import com.kaixuan.agentreproxy.dto.CheckinStatusRequest;
import com.kaixuan.agentreproxy.dto.CheckinStreamEvent;
import com.kaixuan.agentreproxy.dto.PageResult;
import com.kaixuan.agentreproxy.dto.CheckinLogItem;
import com.kaixuan.agentreproxy.service.CheckinService;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 每日签到相关端点
 * <ul>
 * <li>POST /api/checkin/status —— 批量查询当日签到状态（按本地 checkin_log 表）</li>
 * <li>POST /api/checkin/execute —— 批量执行签到（串行调上游）</li>
 * <li>POST /api/checkin/execute-stream —— 流式执行签到（NDJSON，逐账号返回结果）</li>
 * <li>POST /api/checkin/history —— 分页查询指定账号的签到历史日志（基于 offset 分页）</li>
 * </ul>
 * <p>
 * status / execute / execute-stream接受 {"accountIds":[1,2,3]}，accountIds 为 null/空 =作用于所有账号。
 * history接受 {"accountId":1, "pageNum":1, "pageSize":20, ...}，accountId必填。
 */
@RestController
@RequestMapping("/api/checkin")
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping("/status")
    public Mono<Map<String, Object>> queryStatus(@RequestBody(required = false) CheckinStatusRequest req) {
        return Mono.fromCallable(() -> {
            List<Long> ids = req == null ? null : req.accountIds();
            List<CheckinStatusItem> data = checkinService.queryStatus(ids);
            return Map.<String, Object>of("data", data);
        });
    }

    @PostMapping("/execute")
    public Mono<Map<String, Object>> executeCheckin(@RequestBody(required = false) CheckinExecuteRequest req) {
        List<Long> ids = req == null ? null : req.accountIds();
        return checkinService.executeBatch(ids)
                .map(data -> Map.<String, Object>of("data", data));
    }

    @PostMapping(value = "/execute-stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<CheckinStreamEvent> executeCheckinStream(@RequestBody(required = false) CheckinExecuteRequest req,
            ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-cache");
        response.getHeaders().set("X-Accel-Buffering", "no");
        List<Long> ids = req == null ? null : req.accountIds();
        boolean force = req != null && req.isForce();
        return checkinService.executeStream(ids, force);
    }

    /**
     * 查询指定账号的签到历史日志（分页）
     * <p>
     * 请求体示例：
     * <pre>{@code
     * {
     *   "accountId": 1,
     *   "checkinType": "daily",      // 可选，默认 "daily"
     *   "pageNum": 1,                // 可选，默认 1（从 1 开始）
     *   "pageSize": 20,              // 可选，默认 20，硬上限 100
     *   "orderBy": "checkin_time",   // 可选，白名单：id / checkin_time / account_id
     *   "asc": false                 // 可选，默认 false（降序，最新在前）
     * }
     * }</pre>
     * <p>
     * 响应体：
     * <pre>{@code
     * {
     *   "data": {
     *     "total": 42,
     *     "pages": 3,
     *     "list": [ { "id": 1, "accountId": 1, "checkinType": "daily", "checkinTime": 1782921643000, "extra": {...} } ],
     *     "pageNum": 1,
     *     "pageSize": 20
     *   }
     * }
     * }</pre>
     */
    @PostMapping("/history")
    public Mono<Map<String, Object>> queryHistory(@RequestBody CheckinLogQueryRequest req) {
        return Mono.fromCallable(() -> {
            PageResult<CheckinLogItem> page = checkinService.queryHistoryPage(req);
            return Map.<String, Object>of("data", page);
        });
    }
}
