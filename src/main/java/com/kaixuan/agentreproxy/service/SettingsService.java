package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.AppSettingResponse;
import com.kaixuan.agentreproxy.dto.ConsumptionSettingValue;
import com.kaixuan.agentreproxy.dto.UpdateConsumptionSettingRequest;
import com.kaixuan.agentreproxy.entity.AppSettingRecord;
import com.kaixuan.agentreproxy.repository.AppSettingJdbcRepository;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用设置 service
 * <p>
 * 职责:
 * <ul>
 *   <li>GET /api/settings —— 返回全部设置项(value 已解析为 Map)</li>
 *   <li>PUT /api/settings/chat.consumption —— 校验 + 清洗 + 持久化</li>
 * </ul>
 * <p>
 * 校验规则(针对 chat.consumption):
 * <ol>
 *   <li>mode 必填,且必须 ∈ {designated, least, expiring, most}</li>
 *   <li>mode="designated" 时,accountId 必填且必须指向 workbuddy_account 中已存在的记录</li>
 *   <li>mode≠"designated" 时,无论请求里是否带 accountId,都会被清空(满足"非指定模式清除指定账号字段"的业务规则)</li>
 * </ol>
 * <p>
 * 持久化策略:INSERT OR REPLACE by key,不存在则创建,存在则更新 value + updated_at
 */
@Service
public class SettingsService {

    public static final String KEY_CHAT_CONSUMPTION = "chat.consumption";

    /** 允许的 mode 值集合 */
    private static final java.util.Set<String> ALLOWED_MODES = java.util.Set.of(
            "designated", "least", "expiring", "most"
    );

    private final AppSettingJdbcRepository repository;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final ObjectMapper objectMapper;

    public SettingsService(AppSettingJdbcRepository repository,
                           WorkbuddyAccountJdbcRepository accountRepository,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    // ============== 读 ==============

    /**
     * 拿全部设置项(value 反序列化为 Map 便于前端 JSON.parse 后的 object 还原)
     */
    public List<AppSettingResponse> getAll() {
        List<AppSettingResponse> out = new ArrayList<>();
        for (AppSettingRecord rec : repository.findAll()) {
            out.add(toResponse(rec));
        }
        return out;
    }

    /**
     * 按 key 拿一条(找不到时返回空 Optional)
     */
    public Optional<AppSettingResponse> getOne(String key) {
        return repository.findByKey(key).map(this::toResponse);
    }

     /**
     *读取 "chat.consumption" 的强类型 value
     * <p>
     * 专供 Settings 页面与 OpenAI Chat 路由使用,避免前端每次都去拆通用的 {key,value,updatedAt} 外壳。
     * 找不到时返回 Optional.empty() → controller返404。
     */
     public Optional<ConsumptionSettingValue> getConsumptionValue() {
     return repository.findByKey(KEY_CHAT_CONSUMPTION)
     .map(rec -> readConsumptionValue(rec.value()));
     }

     /**
     * 为 OpenAI Chat 路由解析当前生效的“指定账号”主键
     * <p>
     *规则:
     * <ol>
     * <li>若根本未保存过设置 → 抛400,提示先去系统设置保存</li>
     * <li>若 mode不是 "designated" → 抛400,说明当前仅支持指定模式</li>
     * <li>若 mode="designated"但 accountId为空 → 抛400</li>
     * <li>若 accountId 指向的账号已删除 → 抛400</li>
     * </ol>
     * <p>
     *之所以放在 service 而不是 controller:
     * - 校验规则是业务规则,而不是 HTTP细节
     * -未来“量少优先 / 临期优先 /量多优先”实现时,仍然从这里扩展
     */
     public Long resolveDesignatedChatAccountId() {
     ConsumptionSettingValue setting = getConsumptionValue()
     .orElseThrow(() -> new IllegalArgumentException("未配置对话消耗方式，请先在系统设置中保存"));

     if (!"designated".equals(setting.mode())) {
     throw new IllegalArgumentException("当前仅支持“指定模式”，其余消耗方式暂未实现");
     }

     Long accountId = setting.accountId();
     if (accountId == null) {
     throw new IllegalArgumentException("当前为指定模式，但未选择指定账号");
     }
     if (accountRepository.findById(accountId).isEmpty()) {
     throw new IllegalArgumentException("指定账号不存在: " + accountId);
     }
     return accountId;
     }

    // ============== 写(只暴露 chat.consumption 这一个 key,避免泛 key 写入带来的校验扩散) ==============

    /**
     * 更新 "chat.consumption"
     * <p>
     * 业务规则:
     * - mode="designated" 时,accountId 必填且指向已存在的账户
     * - mode≠"designated" 时,清空 accountId 后再存
     */
     public ConsumptionSettingValue updateConsumption(UpdateConsumptionSettingRequest req) {
        if (req == null || req.mode() == null || req.mode().isBlank()) {
            throw new IllegalArgumentException("mode 必填");
        }
        String mode = req.mode().trim();
        if (!ALLOWED_MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "mode 非法: " + mode + "（允许值: designated / least / expiring / most）");
        }

        Long accountId = null;
        if ("designated".equals(mode)) {
            Long incoming = req.accountId();
            if (incoming == null) {
                throw new IllegalArgumentException("mode=designated 时 accountId 必填");
            }
            if (accountRepository.findById(incoming).isEmpty()) {
                throw new IllegalArgumentException("accountId 不存在: " + incoming);
            }
            accountId = incoming;
        }
        // 非 designated 模式:accountId 保持 null(满足"清除指定账号字段"业务规则)

        ConsumptionSettingValue value = new ConsumptionSettingValue(mode, accountId);
        try {
            String json = objectMapper.writeValueAsString(value.toMap());
         repository.upsert(KEY_CHAT_CONSUMPTION, json);
         return value;
        } catch (Exception e) {
            throw new IllegalStateException("序列化或持久化设置失败: " + e.getMessage(), e);
        }
    }

    // ============== 内部 ==============

    private AppSettingResponse toResponse(AppSettingRecord rec) {
        Object value;
        try {
            // 尝试解析成 Map;如果解析失败则降级为 String,前端按需处理
            value = objectMapper.readValue(rec.value(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            value = rec.value();
        }
        return new AppSettingResponse(rec.key(), value, rec.updatedAt());
    }

         private ConsumptionSettingValue readConsumptionValue(String json) {
         try {
         return objectMapper.readValue(json, ConsumptionSettingValue.class);
         } catch (Exception e) {
         throw new IllegalStateException("解析 chat.consumption失败: " + e.getMessage(), e);
         }
         }
}
