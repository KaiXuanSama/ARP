package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 * chat 路由解析结果:同时携带"目标账号 id"、"使用的下游 key id"和"该 key 的模型白名单"
 * <p>
 * 设计动机:/v1/chat/completions 路由选号后,需要:
 * <ul>
 *   <li>{@code accountId} —— 给 {@code UpstreamClient.postChatStreamForAccount} 路由上游请求</li>
 *   <li>{@code keyId} —— 给 {@code DownstreamApiKeyService.recordCall} 累加 call_count</li>
 *   <li>{@code supportedModels} —— 给 {@code OpenAiController.chatCompletions} 做模型白名单校验,
 *       避免在 flatMapMany 同步阶段再查一次表</li>
 * </ul>
 * <p>
 * <strong>supportedModels 三态契约</strong>:
 * <ul>
 *   <li>{@code null} —— 未配置,不限制(回退全集)</li>
 *   <li>{@code []} —— 严格不放行任何模型</li>
 *   <li>{@code ["a","b"]} —— 只放行白名单内的模型 id</li>
 * </ul>
 */
public record ChatRoutingContext(
        Long accountId,
        Long keyId,
        List<String> supportedModels
) {
}
