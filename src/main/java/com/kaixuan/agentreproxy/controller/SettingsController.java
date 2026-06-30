package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.dto.ConsumptionSettingValue;
import com.kaixuan.agentreproxy.dto.UpdateConsumptionSettingRequest;
import com.kaixuan.agentreproxy.service.SettingsService;
import org.springframework.http.ResponseEntity;
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
 * 当前支持的 key:
 * <ul>
 *   <li>chat.consumption —— 对话模式消耗方式,详见 {@code ConsumptionSettingValue}</li>
 * </ul>
 * <p>
 * 通用规则:GET 返回全部;PUT 单项覆写(目前只暴露 chat.consumption 这一个 key,后续加新 key 时
 * 在 SettingsService 加方法 + 在控制器加 @PutMapping 即可)
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

     /**
     * Settings 页面专用:直接返回 "chat.consumption" 的 value结构
     * <p>
     * 响应 shape仅为 {mode, accountId?},不包 {key,value,updatedAt} 外壳,这样前端能直接对齐显示。
     * 不存在时返回404,由前端按"首次未保存"处理。
     */
     @GetMapping("/chat.consumption")
     public ResponseEntity<ConsumptionSettingValue> getConsumption() {
     return settingsService.getConsumptionValue()
     .map(ResponseEntity::ok)
     .orElseGet(() -> ResponseEntity.notFound().build());
     }

    @GetMapping("/{key}")
    public AppSettingResponse getOne(@PathVariable String key) {
        return settingsService.getOne(key).orElse(null);
    }

    @PutMapping("/chat.consumption")
     public ConsumptionSettingValue updateConsumption(@RequestBody UpdateConsumptionSettingRequest req) {
        return settingsService.updateConsumption(req);
    }
}
