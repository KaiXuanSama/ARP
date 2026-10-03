package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.service.ModelCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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

    private final ModelCatalogService modelCatalog;

    public ModelsController(ModelCatalogService modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /**
     * 触发模型目录刷新 — {@code POST /api/models/refresh}
     * <p>
     * 全部启用账号随机洗牌后顺序尝试拉取 {@code /v3/config}，首个成功即止；
     * 失败时当前目录保留不动。返回刷新结果元信息（count / 来源账号 / 抓取时间）。
     * <p>
     * <b>需管理面板 token</b>（AuthWebFilter 正常拦截）。
     */
    @PostMapping("/refresh")
    public Mono<Map<String, Object>> refresh() {
        return modelCatalog.refresh()
                .onErrorMap(e -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        e.getMessage() == null ? "刷新失败" : e.getMessage()));
    }

    /**
     * 返回后端维护的全集模型清单 + 目录元信息
     * <p>
     * 数据源为内存模型目录（上游 /v3/config 真实快照）。不过滤任何 key
     * (也不接受 Authorization 头)—— 总是返回全集,
     * 供管理面板的下拉选择 / 表单多选 / 模型预览使用。
     * <p>
     * 目录为空时 data 为空数组，但<b>不报错</b>（与 /v1/models 的 503 口径不同：
     * 管理面板需要能正常渲染空态并引导管理员去刷新，而不是整个页面报错）。
     * meta 始终返回（未拉取时为空对象）。
     */
    @GetMapping
    public Mono<Map<String, Object>> list() {
        List<Map<String, Object>> data = modelCatalog.getModels().stream()
                .map(ModelsController::toItem)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("meta", modelCatalog.getMeta());
        return Mono.just(body);
    }

    /**
     * 返回上游原始模型条目 — {@code GET /api/models/raw}
     * <p>
     * 「查看模型列表」模态框的数据源：保留上游 /v3/config 的完整字段
     * （credits 计费倍率、maxInput/OutputTokens、reasoning 能力、tags 等），
     * 供管理者了解各模型的约束与能力。条目为拉取时的快照，含被目录过滤掉的
     * 非聊天模型（便于对照）。
     * <p>
     * <b>需管理面板 token</b>。目录为空时返回空数组（前端按钮此刻应为禁用态）。
     */
    @GetMapping("/raw")
    public Mono<Map<String, Object>> rawList() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", modelCatalog.getRawModels());
        body.put("meta", modelCatalog.getMeta());
        return Mono.just(body);
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
