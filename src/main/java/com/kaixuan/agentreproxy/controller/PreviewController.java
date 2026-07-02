package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.service.SettingsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * 选号预览 controller —— 不绑定到 {@code app_settings} 表,纯粹做"按 mode 选账号"的预览
 * <p>
 * <strong>搬迁历史</strong>:原 {@code /api/settings/chat.consumption/preview-batch} 端点
 * 已被删除(全局 chat.consumption 设置废弃,前端不应再走 {@code /api/settings/...})。
 * 本 controller 用 {@code /api/preview} 命名空间,语义清晰 —— 跟 {@code /api/settings}
 * CRUD 端点职责分离。
 * <p>
 * 复用 {@link SettingsService#previewChatAccountId(String)} 与
 * {@link SettingsService#previewChatAccountIdBatch(List)} 方法 —— 不复制选号逻辑。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code POST /api/preview/chat-account-batch} —— 批量预览(原前端 {@code fetchPreviewByMode} 在用)</li>
 * </ul>
 *
 * <h3>请求体</h3>
 * <pre>{@code
 * { "modes": ["least", "most", "expiring"] }
 * }</pre>
 * <strong>字段可选</strong>:缺失/null 都视为空列表,返回 {@code {data: {}}}。
 *
 * <h3>响应</h3>
 * <pre>{@code
 * { "data": {
 *   "least":    ChatAccountPreview(...) | null,
 *   "most":     ChatAccountPreview(...) | null,
 *   "expiring": ChatAccountPreview(...) | null
 * }}
 * }</pre>
 * <strong>单 mode 失败不影响整体</strong>:失败的 mode 在响应 Map 中对应 null。
 */
@RestController
@RequestMapping("/api/preview")
public class PreviewController {

    private final SettingsService settingsService;

    public PreviewController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * 批量预览请求体 —— 字段可选,缺失或 null 都视为空 list
     */
    public record ChatAccountBatchRequest(List<String> modes) {}

    @PostMapping("/chat-account-batch")
    public Mono<Map<String, Object>> chatAccountBatch(
            @RequestBody(required = false) ChatAccountBatchRequest req) {
        List<String> modes = req == null ? List.of() : req.modes();
        return Mono.fromCallable(() -> settingsService.previewChatAccountIdBatch(modes))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> Map.<String, Object>of("data", result));
    }
}
