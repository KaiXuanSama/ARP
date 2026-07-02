package com.kaixuan.agentreproxy.dto;

/**
 * 下游 API Key 新增 / 修改请求
 * <p>
 * 全部字段都允许 null(不更新),但创建时 label / consumption / enabled 必填(由控制器层校验)
 * <ul>
 * <li>{@code label} —— 必填,别名(便于管理)</li>
 * <li>{@code creditLimit} —— 可选,积分上限(null = 不限)</li>
 * <li>{@code expiresAt} —— 可选,有效期(毫秒时间戳,null = 永久)</li>
 * <li>{@code enabled} —— 启用状态,默认 true</li>
 * <li>{@code consumption} —— 消耗方式(字符串),默认 designated</li>
 * <li>{@code designatedAccountId} —— 指定账号 id(仅 consumption='designated' 时生效)</li>
 * </ul>
 */
public record DownstreamApiKeyRequest(
        String label,
        Double creditLimit,
        Long expiresAt,
        Boolean enabled,
        String consumption,
        Long designatedAccountId
) {
}
