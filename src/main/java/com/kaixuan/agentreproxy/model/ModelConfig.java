package com.kaixuan.agentreproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * modelsConfig.json 中单条模型配置项
 * <p>
 * 字段: id / family / contextLength(对齐 {@code ModelsConfigService} 当前契约)。
 * 未来加字段(比如 displayName / pricing)时,通过 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 避免旧配置文件加载失败
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelConfig(
        String id,
        String family,
        int contextLength
) {}
