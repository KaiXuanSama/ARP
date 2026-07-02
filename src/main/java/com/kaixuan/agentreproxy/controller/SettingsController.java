package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 应用设置 REST 端点
 * <p>
 * <strong>已删除</strong>:所有 {@code /api/settings/chat.consumption*} 专用端点 —— 全局
 * {@code app_settings.chat.consumption} 设置已被下游 API Key 的 per-key {@code consumption}
 * 字段取代,chat 路由不再消费全局设置。SettingsService 同步删除了 {@code updateConsumption} /
 * {@code getConsumptionValue} / {@code KEY_CHAT_CONSUMPTION} 等。
 * <p>
 * <strong>当前</strong>:通用 CRUD 三个端点 —— GET 全部 / GET 单条 / PUT 单条。
 * 任意 key 任意 JSON value,通过 {@code app_settings} 表 + JSON 字符串存储。
 * <p>
 * <strong>选号预览</strong>已搬到独立 controller {@code /api/preview/chat-account-batch} —— 不再依赖
 * {@code chat.consumption} 概念。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<AppSettingResponse> data = settingsService.getAll();
        return Map.of("data", data);
    }

    @GetMapping("/{key}")
    public AppSettingResponse getOne(@PathVariable String key) {
        return settingsService.getOne(key).orElse(null);
    }

    /**
     * 通用设置 upsert —— {@code PUT /api/settings/{key}}
     * <p>
     * 用途:非 {@code chat.consumption} 的全局设置(定时签到 / 主题 / 默认分页大小 等)的统一入口。
     * <p>
     * <strong>请求体</strong>:任意 JSON 对象;value 由 service 序列化为 JSON 字符串入库。
     * 字段缺失(null)存 JSON {@code "null"} 字符串。
     * <p>
     * <strong>响应</strong>:跟 {@code GET /api/settings} 一致的 {@code {data: ...}} 结构,
     * 返回更新后的 AppSettingResponse(key, value, updatedAt)。
     * <p>
     * <strong>路径冲突</strong>:注意 {@code /{key}} 路径变量会"吃"所有非专用端点;专用端点
     * ({@code /chat.consumption}, {@code /chat.consumption/preview} 等)优先级更高
     * —— Spring MVC 按"具体路径优先于通配路径"匹配,不会被本端点拦截。
     */
    @PutMapping("/{key}")
    public Map<String, Object> updateOne(@PathVariable String key, @RequestBody(required = false) Object body) {
        settingsService.updateByKey(key, body);
        // 回包用最新 row,保证 updatedAt 反映本次写入
        return Map.of("data", settingsService.getOne(key).orElse(null));
    }
}
