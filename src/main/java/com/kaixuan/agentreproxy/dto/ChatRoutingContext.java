package com.kaixuan.agentreproxy.dto;

/**
 * chat 路由解析结果:同时携带"目标账号 id"和"使用的下游 key id"
 * <p>
 * 设计动机:/v1/chat/completions 路由选号后,需要:
 * <ul>
 * <li>{@code accountId} —— 给 {@code UpstreamClient.postChatStreamForAccount} 路由上游请求</li>
 * <li>{@code keyId} —— 给 {@code DownstreamApiKeyService.recordCall} 累加 call_count</li>
 * </ul>
 * 如果只返回 {@code accountId} 就需要 controller 额外再查一次 {@code downstream_api_key} 表
 * 来获取 keyId —— 重复查表。直接把这个 record 暴露给 controller 减少一次 SQL。
 */
public record ChatRoutingContext(
        Long accountId,
        Long keyId
) {
}
