package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.repository.AppSettingJdbcRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理面板认证服务 — Token 生成 / 校验 / 登录验证
 * <p>
 * Token 方案（内存存储，重启即失效）：
 * <ul>
 *   <li>登录成功 → 生成随机 token + 过期时间（12h），存入内存 map</li>
 *   <li>每次请求 → 从 Authorization 头取 token → 查内存 map → 校验过期</li>
 *   <li>重启服务 → 所有 token 失效，前端重新登录</li>
 * </ul>
 * <p>
 * 不用 JWT 的原因：单机部署、无集群需求、内存 map 更简单直接。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Token 有效期：12 小时（毫秒） */
    private static final long TOKEN_TTL_MS = 12L * 60 * 60 * 1000;

    /** Token 随机字节长度 */
    private static final int TOKEN_BYTES = 32;

    /** admin.credential key —— 与 SettingsService 保持一致 */
    private static final String KEY_ADMIN_CREDENTIAL = "admin.credential";

    /** 内存 token 存储：token → 过期时间戳 */
    private final ConcurrentHashMap<String, Long> tokenStore = new ConcurrentHashMap<>();

    private final AppSettingJdbcRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AppSettingJdbcRepository settingRepository, ObjectMapper objectMapper) {
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 登录验证 → 成功返回 token，失败抛 IllegalArgumentException
     */
    public String login(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }

        Map<String, Object> credential = readCredential();
        String storedUsername = credential.get("username") instanceof String u ? u : "root";
        String storedHash = credential.get("passwordHash") instanceof String h ? h : sha256("root");

        if (!storedUsername.equals(username.trim())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!storedHash.equals(sha256(password))) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 生成 token
        String token = generateToken();
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        tokenStore.put(token, expiresAt);

        // 清理过期 token（懒清理）
        cleanExpired();

        log.info("[Auth] 用户 {} 登录成功, token 有效至 {}", username,
                java.time.Instant.ofEpochMilli(expiresAt));
        return token;
    }

    /**
     * 校验 token 是否有效
     *
     * @return true=有效, false=无效或过期
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiresAt = tokenStore.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiresAt) {
            tokenStore.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 登出 — 主动移除 token
     */
    public void logout(String token) {
        if (token != null) {
            tokenStore.remove(token);
        }
    }

    // ============== 内部 ==============

    private Map<String, Object> readCredential() {
        return settingRepository.findByKey(KEY_ADMIN_CREDENTIAL)
                .map(rec -> {
                    try {
                        return objectMapper.readValue(rec.value(),
                                new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        return Map.<String, Object>of();
                    }
                })
                .orElseGet(() -> Map.of("username", "root", "passwordHash", sha256("root")));
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        tokenStore.entrySet().removeIf(e -> now > e.getValue());
    }

    private static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
