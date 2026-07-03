package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ModelsConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面板专用的模型清单端点
 * <p>
 * <strong>为什么不用 {@code /v1/models}</strong>:
 * <ul>
 *   <li>{@code /v1/models} 是 OpenAI 兼容契约端点,响应形态 {@code {object, data}}
 *       固定,且接受 {@code Authorization} 头按 key 白名单过滤 —— 这是面向
 *       OpenAI SDK 客户端的运行时契约</li>
 *   <li>管理面板(下游 Key 的"支持的模型"多选用)需要的是后端维护的<strong>全集</strong>,
 *       形态是内部数据,不应该受 OpenAI 协议形态约束</li>
 *   <li>两个端点关注点分离:
 *     <ul>
 *       <li>{@code /v1/models} —— "OpenAI 客户端看到哪些模型"(可能按 key 过滤)</li>
 *       <li>{@code /api/models} —— "管理面板知道后端支持哪些模型"(始终是全集)</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * 响应形态(内部契约,与 OpenAI 协议解耦):
 * <pre>
 * { "data": [ { "id": "auto", "family": "virtual", "contextLength": 172032 }, ... ] }
 * </pre>
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelsConfigService modelsConfig;

    public ModelsController(ModelsConfigService modelsConfig) {
        this.modelsConfig = modelsConfig;
    }

    /**
     * 返回后端维护的全集模型清单
     * <p>
     * 不过滤任何 key(也不接受 Authorization 头)—— 总是返回全集,
     * 供管理面板的下拉选择 / 表单多选 / 模型预览使用
     */
    @GetMapping
    public Mono<Map<String, Object>> list() {
        List<Map<String, Object>> data = modelsConfig.getModels().stream()
                .map(ModelsController::toItem)
                .toList();
        return Mono.just(Map.of("data", data));
    }

    /**
     * 内部契约的单条 model 形态
     * <p>
     * 用 {@link LinkedHashMap} 保字段顺序,前端读起来稳定
     */
    private static Map<String, Object> toItem(ModelConfig m) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", m.id());
        item.put("family", m.family());
        item.put("contextLength", m.contextLength());
        return item;
    }
}
