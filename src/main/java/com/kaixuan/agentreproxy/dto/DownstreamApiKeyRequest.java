package com.kaixuan.agentreproxy.dto;

import java.util.List;

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
 * <li>{@code supportedModels} —— 支持的模型 id 列表(可选)
 *   <ul>
 *     <li>不传 / 显式传 null —— /v1/models 回退到全集(默认行为)</li>
 *     <li>空数组 [] —— 严格不放行任何模型</li>
 *     <li>["a","b"] —— 只放行白名单内的模型</li>
 *   </ul>
 * </li>
 * </ul>
 */
public record DownstreamApiKeyRequest(
        String label,
        Double creditLimit,
        Long expiresAt,
        Boolean enabled,
        String consumption,
        Long designatedAccountId,
        List<String> supportedModels
) {
}
