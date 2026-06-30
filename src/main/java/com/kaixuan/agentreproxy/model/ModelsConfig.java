package com.kaixuan.agentreproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * modelsConfig.json 顶层结构
 * <p>
 * 用 {@code models} 数组包一层,方便未来加 metadata(如版本号、维护者、最后更新时间)而不破坏 schema
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelsConfig(
        List<ModelConfig> models
) {}
