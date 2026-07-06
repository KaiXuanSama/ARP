package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.AdminCredentialRequest;
import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.dto.DailyCheckinSettingRequest;
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
 * <strong>设计原则：全局读取 + 局部保存</strong>
 * <ul>
 *   <li>读取：{@code GET /api/settings}（一次拉全部）和 {@code GET /api/settings/{key}}（单条）</li>
 *   <li>保存：各设置项走各自的专有端点（带强类型 DTO + 业务校验），不提供通用写入端点</li>
 * </ul>
 * <p>
 * <strong>已删除</strong>：
 * <ul>
 *   <li>{@code PUT /api/settings/{key}} — 通用 upsert 端点（无类型校验，任意 JSON 入库）</li>
 *   <li>{@code /api/settings/chat.consumption*} — 全局 chat.consumption 相关端点</li>
 * </ul>
 * <p>
 * <strong>当前专有保存端点</strong>：
 * <ul>
 *   <li>{@code PUT /api/settings/schedule/daily-checkin} — 定时签到配置</li>
 *   <li>{@code PUT /api/settings/admin/credential} — 管理员凭证修改</li>
 * </ul>
 * 未来新增设置项时，注册新的 {@code PUT /api/settings/xxx/yyy} 专有端点即可。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // ============== 读取端点（全局） ==============

    @GetMapping
    public Map<String, Object> list() {
        List<AppSettingResponse> data = settingsService.getAll();
        return Map.of("data", data);
    }

    @GetMapping("/{key}")
    public AppSettingResponse getOne(@PathVariable String key) {
        return settingsService.getOne(key).orElse(null);
    }

    // ============== 专有保存端点 ==============

    /**
     * 定时签到设置专有保存 — {@code PUT /api/settings/schedule/daily-checkin}
     * <p>
     * <ul>
     *   <li>强类型请求体 {@link DailyCheckinSettingRequest}（不再是任意 JSON）</li>
     *   <li>带业务校验（enabled=true 时 time 必填且格式正确）</li>
     * </ul>
     * <p>
     * <strong>扩展模式</strong>：未来新增设置项（如主题、默认分页大小等），各自注册独立的
     * {@code PUT /api/settings/xxx/yyy} 端点 + 专有 DTO + 专有校验方法。
     */
    @PutMapping("/schedule/daily-checkin")
    public Map<String, Object> updateDailyCheckin(@RequestBody DailyCheckinSettingRequest req) {
        AppSettingResponse result = settingsService.updateDailyCheckinSetting(req);
        return Map.of("data", result);
    }

    /**
     * 管理员凭证修改专有保存 — {@code PUT /api/settings/admin/credential}
     * <p>
     * <ul>
     *   <li>强类型请求体 {@link AdminCredentialRequest}</li>
     *   <li>必须验证旧密码；新用户名和新密码至少填一项</li>
     *   <li>密码以 SHA-256 哈希存储，接口不返回密码哈希（脱敏）</li>
     * </ul>
     */
    @PutMapping("/admin/credential")
    public Map<String, Object> updateAdminCredential(@RequestBody AdminCredentialRequest req) {
        AppSettingResponse result = settingsService.updateAdminCredential(req);
        return Map.of("data", result);
    }
}
