package com.kaixuan.agentreproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模型目录的单条配置项（内存快照，来源上游 /v3/config）
 * <p>
 * 由 {@code ModelCatalogService} 在刷新时构造：id ← 上游条目 id、
 * family ← vendor（空则 unknown）、contextLength ← maxInputTokens。
 * <p>
 * 保留 {@code @JsonIgnoreProperties} 仅为兼容历史序列化场景（如将来再落盘）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConfig(
        String id,
        String family,
        int contextLength
) {}
