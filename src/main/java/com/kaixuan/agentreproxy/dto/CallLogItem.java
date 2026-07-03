package com.kaixuan.agentreproxy.dto;

/**
 * 下游 API Key 调用日志条目
 * <p>
 * 用于管理面板"调用日志"模态框:按下游 key 过滤,分页展示
 * <p>
 * <strong>content 字段</strong>:存的是上游 chat 响应的"最终结算 chunk"原始 JSON 字符串
 * (由 OpenAiController.interceptChatChunk 拦截后写入),CodeBuddy 字段随时变,
 * 这里不做反序列化,前端按需 JSON.parse
 *
 * @param id         主键
 * @param keyId      所属下游 key id(2026-07 新增,可能为 null = 审计孤儿日志)
 * @param accountId  本次 chat 路由命中的上游账号 id(2026-07 新增;null = 老数据
 *                   或账号已删)
 * @param content    完整 chunk JSON 字符串
 * @param createdAt  落库时间(毫秒)
 */
public record CallLogItem(
        Long id,
        Long keyId,
        Long accountId,
        String content,
        Long createdAt
) {
}
