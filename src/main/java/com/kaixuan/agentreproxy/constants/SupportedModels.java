package com.kaixuan.agentreproxy.constants;

import java.util.List;

/**
 * 腾讯 CodeBuddy 支持的模型列表（本地硬编码）
 * <p>
 * 上游 /v2/v1/models 返回 404，必须本地维护
 */
public final class SupportedModels {

    public record ModelInfo(String id, String family, int contextLength) {}

    private SupportedModels() {}

    public static final List<ModelInfo> MODELS = List.of(
            // 虚拟路由
            new ModelInfo("auto", "virtual", 168 * 1024),

            // DeepSeek
            new ModelInfo("deepseek-v4-pro", "deepseek", 1024 * 1024),
            new ModelInfo("deepseek-v4-flash", "deepseek", 1024 * 1024),
            new ModelInfo("deepseek-v3-2-volc", "deepseek", 128 * 1024),
            new ModelInfo("deepseek-v3-1", "deepseek", 128 * 1024),
            new ModelInfo("deepseek-v3-0324", "deepseek", 128 * 1024),
            new ModelInfo("deepseek-r1", "deepseek", 128 * 1024),

            // GLM
            new ModelInfo("glm-5.2", "glm", 1024 * 1024),
            new ModelInfo("glm-5.1", "glm", 200 * 1024),
            new ModelInfo("glm-5.0", "glm", 128 * 1024),
            new ModelInfo("glm-5.0-turbo", "glm", 200 * 1024),
            new ModelInfo("glm-5v-turbo", "glm", 200 * 1024),
            new ModelInfo("glm-4.7", "glm", 200 * 1024),
            new ModelInfo("glm-4.6", "glm", 200 * 1024),

            // MiniMax
            new ModelInfo("minimax-m3", "minimax", 1024 * 1024),
            new ModelInfo("minimax-m2.7", "minimax", 200 * 1024),
            new ModelInfo("minimax-m2.5", "minimax", 200 * 1024),

            // Kimi
            new ModelInfo("kimi-k2.6", "kimi", 256 * 1024),
            new ModelInfo("kimi-k2.5", "kimi", 1024 * 1024),

            // 混元
            new ModelInfo("hy3-preview", "hunyuan", 256 * 1024),
            new ModelInfo("hunyuan-chat", "hunyuan", 256 * 1024),
            new ModelInfo("hunyuan-2.0-thinking", "hunyuan", 256 * 1024)
    );
}
