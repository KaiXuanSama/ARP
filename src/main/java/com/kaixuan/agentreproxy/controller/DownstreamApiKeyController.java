package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.DownstreamApiKeyItem;
import com.kaixuan.agentreproxy.dto.DownstreamApiKeyQueryRequest;
import com.kaixuan.agentreproxy.dto.DownstreamApiKeyRequest;
import com.kaixuan.agentreproxy.dto.PageResult;
import com.kaixuan.agentreproxy.service.DownstreamApiKeyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 下游 API Key 管理端点
 * <ul>
 *   <li>POST   /api/downstream-keys               —— 新增(返回完整明文)</li>
 *   <li>DELETE /api/downstream-keys/{id}         —— 删除</li>
 *   <li>PUT    /api/downstream-keys/{id}         —— 更新(返回遮蔽 key)</li>
 *   <li>GET    /api/downstream-keys               —— 分页查询(列表,返回遮蔽 key)</li>
 *   <li>GET    /api/downstream-keys/{id}         —— 单条详情(返回遮蔽 key,供编辑回显)</li>
 * </ul>
 * <p>
 * 响应统一包成 {@code {data: ...}} 结构,与现有 CheckinController / UpstreamController 风格一致。
 */
@RestController
@RequestMapping("/api/downstream-keys")
public class DownstreamApiKeyController {

    private final DownstreamApiKeyService service;

    public DownstreamApiKeyController(DownstreamApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    public Mono<Map<String, Object>> create(@RequestBody DownstreamApiKeyRequest req) {
        return Mono.fromCallable(() -> {
            DownstreamApiKeyItem created = service.create(req);
            return Map.<String, Object>of("data", created);
        });
    }

    @PutMapping("/{id}")
    public Mono<Map<String, Object>> update(@PathVariable Long id, @RequestBody DownstreamApiKeyRequest req) {
        return Mono.fromCallable(() -> {
            DownstreamApiKeyItem updated = service.update(id, req);
            return Map.<String, Object>of("data", updated);
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            service.delete(id);
            return Map.<String, Object>of("data", Map.of("deleted", true, "id", id));
        });
    }

    @PostMapping("/page")
    public Mono<Map<String, Object>> queryPage(@RequestBody(required = false) DownstreamApiKeyQueryRequest req) {
        return Mono.fromCallable(() -> {
            PageResult<DownstreamApiKeyItem> page = service.queryPage(req);
            return Map.<String, Object>of("data", page);
        });
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> getOne(@PathVariable Long id) {
        return Mono.fromCallable(() -> service.findOne(id)
                .map(it -> Map.<String, Object>of("data", it))
                .orElse(Map.of("data", null)));
    }

    /**
     * 切换某个 key 的启用状态(轻量级 PATCH,只传 enabled)
     * <p>
     * 路径设计为 PATCH /api/downstream-keys/{id}/enabled
     * <ul>
     *   <li>用 PATCH(部分更新)语义,只修改 enabled 字段</li>
     *   <li>路径带 /enabled 后缀 —— 显式说明"这是改启用状态专用",未来如加 suspended / archived 不会冲突</li>
     *   <li>请求体只接一个 enabled boolean,避免前端 PUT 一整个对象</li>
     * </ul>
     */
    @PatchMapping("/{id}/enabled")
    public Mono<Map<String, Object>> toggleEnabled(
            @PathVariable Long id,
            @RequestBody ToggleEnabledRequest req) {
        return Mono.fromCallable(() -> {
            if (req == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            DownstreamApiKeyItem updated = service.updateEnabled(id, req.enabled());
            return Map.<String, Object>of("data", updated);
        });
    }

    /**
     * 切换启用状态的轻量请求体 —— 只装 enabled 字段
     */
    public record ToggleEnabledRequest(boolean enabled) {}
}
