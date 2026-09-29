package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.TokenImportRequest;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫码登录凭证导入服务 —— 把书签脚本回传的 token 落库
 *
 * <h3>数据落库形态</h3>
 * 复用现有的 {@link AccountSaveService#save}，把 token 包装成
 * {@link WorkbuddyDesktopInfo} 的嵌套结构后存入 {@code account_json}：
 * <pre>{@code
 * {
 *   "account": { "uid": "...", "nickname": "..." },
 *   "auth": { "accessToken": "...", "refreshToken": "...", "expiresAt": 1795337977, ... }
 * }
 * }</pre>
 * 这样 {@code AuthCredential} 的解析路径（{@code AccountExtraService} /
 * {@code UpstreamClient.applyAuth}）无需改动即可直接使用。
 *
 * <h3>为什么带 refreshToken 与有效期</h3>
 * 上游返回的 token 有效期约 55 天（refreshToken 60 天）。将过期时间落库后：
 * <ul>
 *   <li>前端可展示「剩余有效期」，避免账号静默失效</li>
 *   <li>为将来实现自动续期（M5）预留数据基础</li>
 * </ul>
 * 当前数据库 schema 未为 refreshToken 单独加列，相关信息存在
 * {@code account_json} 的 {@code auth} 节点内。
 */
@Service
public class TokenImportService {

    private static final Logger log = LoggerFactory.getLogger(TokenImportService.class);

    private final LoginSessionService loginSessionService;
    private final AccountSaveService accountSaveService;
    private final ObjectMapper objectMapper;

    public TokenImportService(LoginSessionService loginSessionService,
            AccountSaveService accountSaveService,
            ObjectMapper objectMapper) {
        this.loginSessionService = loginSessionService;
        this.accountSaveService = accountSaveService;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 ticket 并导入 token
     *
     * @param req 回传的 token 信息
     * @return 落库结果摘要（含 uid / nickname / 动作 / 有效期）
     * @throws IllegalArgumentException 参数非法或 ticket 无效
     */
    public Map<String, Object> importToken(TokenImportRequest req) {
        // ---- 1) 基础校验 ----
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (req.accessToken() == null || req.accessToken().isBlank()) {
            throw new IllegalArgumentException("accessToken 不能为空");
        }
        if (req.uid() == null || req.uid().isBlank()) {
            throw new IllegalArgumentException("uid 不能为空");
        }

        // ---- 2) 校验并消费 ticket（一次性）----
        // 放在最前面：ticket 无效时不应做任何后续处理，也不应泄露其他信息
        if (!loginSessionService.consumeTicket(req.ticket())) {
            throw new IllegalArgumentException("ticket 无效或已过期，请重新发起扫码登录");
        }

        // ---- 3) 构造 account_json（复用既有嵌套结构）----
        String accountJson;
        try {
            accountJson = buildAccountJson(req);
        } catch (Exception e) {
            log.error("[扫码导入] 构造 account_json 失败 uid={}", req.uid(), e);
            throw new IllegalStateException("凭证序列化失败: " + e.getMessage(), e);
        }

        // ---- 4) 落库（复用既有判重逻辑：UID + 凭证集）----
        AccountSaveService.SaveResult result =
                accountSaveService.save(req.uid(), accountJson, req.accessToken(), null);

        long expiresAt = req.expiresAt() != null ? req.expiresAt() : 0L;
        long remainingDays = expiresAt > 0
                ? Math.max(0, (expiresAt - System.currentTimeMillis()) / 86400000L)
                : -1;

        log.info("[扫码导入] 成功 action={} uid={} nickname={} 有效期剩余 {} 天",
                result.action(), req.uid(), req.nickname(),
                remainingDays >= 0 ? remainingDays : "未知");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.action().name().toLowerCase());
        out.put("uid", req.uid());
        out.put("nickname", req.nickname());
        out.put("expiresAt", expiresAt > 0 ? expiresAt : null);
        out.put("remainingDays", remainingDays >= 0 ? remainingDays : null);
        out.put("data", result.response());
        return out;
    }

    /**
     * 构造与 {@code workbuddy-desktop.info} 一致的嵌套 JSON
     * <p>
     * 保持与本地文件解析（{@link WorkbuddyDesktopInfo#fromFlatJson}）
     * 产出的结构一致，这样后续所有读取路径都统一走
     * {@code WorkbuddyDesktopInfo}，无需为"扫码导入"单开分支。
     */
    private String buildAccountJson(TokenImportRequest req) throws Exception {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("uid", req.uid());
        account.put("nickname", req.nickname());
        account.put("type", "personal");
        account.put("pluginEnabled", true);

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("accessToken", req.accessToken());
        auth.put("tokenType", "Bearer");
        // 上游返回的是「剩余秒数」（如 4752000），这里统一转成绝对时间戳，
        // 与本地 info 文件的 expiresAt 语义保持一致，便于统一比较与展示
        if (req.expiresAt() != null && req.expiresAt() > 0) {
            auth.put("expiresAt", req.expiresAt());
        }
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            auth.put("refreshToken", req.refreshToken());
        }
        if (req.refreshExpiresAt() != null && req.refreshExpiresAt() > 0) {
            auth.put("refreshExpiresAt", req.refreshExpiresAt());
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("account", account);
        root.put("auth", auth);
        // source 标记来源，便于排查时区分「本机文件导入」与「扫码导入」
        root.put("source", "scan-login");
        return objectMapper.writeValueAsString(root);
    }
}
