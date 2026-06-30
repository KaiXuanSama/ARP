package com.kaixuan.agentreproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * workbuddy-desktop.info 文件的完整数据模型
 * <p>
 * 兼容两种主流 JSON 格式：
 * <ul>
 *   <li><b>嵌套格式（CodeBuddy 官方）</b>：{ "account": {...}, "auth": {...} }</li>
 *   <li><b>扁平格式（antigravity-tools 导出）</b>：{ "uid": "...", "nickname": "...",
 *       "auth_token": "...", "auth_raw": "{...JSON 字符串...}", "domain": "..." }</li>
 * </ul>
 * <p>
 * 统一通过 {@link #fromFlatJson(String, ObjectMapper)} 入口解析，内部归一化为嵌套结构
 * 以便业务层（{@link com.kaixuan.agentreproxy.service.UpstreamClient} 等）无须关心来源格式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkbuddyDesktopInfo(
        Account account,
        Auth auth
) {

    private static final Logger log = LoggerFactory.getLogger(WorkbuddyDesktopInfo.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(
            String uid,
            String nickname,
            String uin,
            String type,
            boolean lastLogin,
            boolean pluginEnabled,
            String phoneNumber
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Auth(
            String accessToken,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            String tokenType,
            String scope,
            String domain,
            long lastRefreshTime,
            long expiresAt,
            long refreshExpiresAt
    ) {}

    // ============== 兼容解析入口 ==============

    /**
     * 统一解析入口：自动识别"嵌套"或"扁平"格式，归一化为 {@link WorkbuddyDesktopInfo}
     *
     * @param content      JSON 字符串（任意一种格式）
     * @param objectMapper Jackson 序列化器
     * @return 归一化后的对象
     */
    public static WorkbuddyDesktopInfo fromFlatJson(String content, ObjectMapper objectMapper) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("账户信息内容为空");
        }
        try {
            Map<String, Object> root = objectMapper.readValue(content, MAP_TYPE);
            // 检测扁平格式的特征字段：顶层存在 auth_token 或 (uid 且 顶层无 account 包装)
            if (root.containsKey("auth_token") || (root.containsKey("uid") && !root.containsKey("account"))) {
                return normalizeFlat(root, objectMapper);
            }
            // 嵌套格式：直接反序列化
            return objectMapper.convertValue(root, WorkbuddyDesktopInfo.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + (e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage()), e);
        }
    }

    /**
     * 扁平格式 → 嵌套格式归一化
     * <p>
     * 字段映射：
     * <ul>
     *   <li>uid            → account.uid</li>
     *   <li>nickname       → account.nickname</li>
     *   <li>auth_token     → auth.accessToken</li>
     *   <li>auth_raw       → 解析后填充 auth.expiresIn / refreshToken / refreshExpiresIn / tokenType / scope / domain</li>
     *   <li>domain         → auth.domain（若 auth_raw 中无则取这里）</li>
     * </ul>
     */
    private static WorkbuddyDesktopInfo normalizeFlat(Map<String, Object> flat, ObjectMapper objectMapper) {
        String uid = asString(flat.get("uid"));
        String nickname = asString(flat.get("nickname"));
        String authToken = asString(flat.get("auth_token"));
        String domain = asString(flat.get("domain"));
        String authRaw = asString(flat.get("auth_raw"));

        // 解析 auth_raw JSON 字符串获取更多字段
        long expiresIn = 0;
        long refreshExpiresIn = 0;
        String refreshToken = null;
        String tokenType = "Bearer";
        String scope = null;
        String authDomain = domain; // fallback
        String sessionState = null;

        if (authRaw != null && !authRaw.isBlank()) {
            try {
                Map<String, Object> raw = objectMapper.readValue(authRaw, MAP_TYPE);
                Object v;
                if ((v = raw.get("expiresIn")) instanceof Number n) expiresIn = n.longValue();
                if ((v = raw.get("refreshExpiresIn")) instanceof Number n) refreshExpiresIn = n.longValue();
                refreshToken = asString(raw.get("refreshToken"));
                tokenType = asStringOrDefault(raw.get("tokenType"), "Bearer");
                scope = asString(raw.get("scope"));
                sessionState = asString(raw.get("sessionState"));
                if (asString(raw.get("domain")) != null) {
                    authDomain = asString(raw.get("domain"));
                }
            } catch (Exception e) {
                log.debug("auth_raw 解析失败，按可获得字段继续: {}", e.getMessage());
            }
        }

        Account account = new Account(uid, nickname, null, null, false, false, null);
        Auth auth = new Auth(
                authToken,
                expiresIn,
                refreshToken,
                refreshExpiresIn,
                tokenType,
                scope,
                authDomain,
                0L, 0L, 0L
        );

        log.info("解析扁平格式账户信息 uid={} nickname={} sessionState={}",
                uid, nickname, sessionState);
        return new WorkbuddyDesktopInfo(account, auth);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String asStringOrDefault(Object o, String def) {
        String s = asString(o);
        return (s == null || s.isBlank()) ? def : s;
    }
}
