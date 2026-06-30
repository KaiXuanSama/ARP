package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.AccountResponse;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import com.kaixuan.agentreproxy.service.AccountSaveService;
import com.kaixuan.agentreproxy.service.AccountSaveService.SaveAction;
import com.kaixuan.agentreproxy.service.WorkbuddyInfoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 兼容两种传参形式的请求体解析逻辑：
 *   1) { "info": {...}, "apiKey": "..." }   显式包装
 *   2) { "account": {...}, "auth": {...} }   直接传 WorkbuddyDesktopInfo
 */

@RestController
@RequestMapping("/api")
public class WorkbuddyInfoController {

    private final WorkbuddyInfoService workbuddyInfoService;
    private final AccountSaveService accountSaveService;
    private final WorkbuddyAccountJdbcRepository accountRepository;
    private final ObjectMapper objectMapper;

    public WorkbuddyInfoController(WorkbuddyInfoService workbuddyInfoService,
                                   AccountSaveService accountSaveService,
                                   WorkbuddyAccountJdbcRepository accountRepository,
                                   ObjectMapper objectMapper) {
        this.workbuddyInfoService = workbuddyInfoService;
        this.accountSaveService = accountSaveService;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/workbuddy-info")
    public Mono<WorkbuddyDesktopInfo> getWorkbuddyInfo() {
        return Mono.fromCallable(workbuddyInfoService::readInfo);
    }

    /**
     * 通过上传的 .info 文件解析账户信息
     */
    @PostMapping(value = "/import-info", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<WorkbuddyDesktopInfo> importInfo(@RequestPart("file") FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .map(content -> WorkbuddyDesktopInfo.fromFlatJson(content, objectMapper));
    }

    /**
     * 查询所有已保存的账户列表
     */
    @GetMapping("/accounts")
    public Mono<Map<String, Object>> listAccounts() {
        return Mono.fromCallable(() -> {
            java.util.List<WorkbuddyAccountRecord> records = accountRepository.findAll();
            return Map.<String, Object>of("data", records);
        });
    }

    /**
     * 保存账户信息
     * - 200: 新建（created）或 覆盖（updated）
     * - 400: 参数非法
     * - 409: 重复（duplicate）
     */
    @PostMapping("/accounts")
    public Mono<Map<String, Object>> saveAccount(@RequestBody Map<String, Object> raw) {
        return Mono.fromCallable(() -> {
            // 1) 解析 info：优先取 info 字段，否则把整个 body 当作 info
            Object infoNode = raw.containsKey("info") ? raw.get("info") : raw;
            WorkbuddyDesktopInfo info = parseInfo(infoNode, objectMapper);

            // 2) 解析 apiKey
            String apiKey = null;
            Object apiKeyNode = raw.get("apiKey");
            if (apiKeyNode instanceof String s) {
                apiKey = s;
            }

            String accessToken = info != null && info.auth() != null ? info.auth().accessToken() : null;
            boolean hasAccess = accessToken != null && !accessToken.isBlank();
            boolean hasApi = apiKey != null && !apiKey.isBlank();
            if (!hasAccess && !hasApi) {
                throw new IllegalArgumentException("accessToken 与 apiKey 至少需要存在一个");
            }

            // 3) 解析 UID：可能为空
            String uid = (info != null && info.account() != null) ? info.account().uid() : null;
            if (uid == null || uid.isBlank()) {
                // 没有 UID 时使用凭证的 SHA-256 前 16 位作为稳定指纹
                String seed = hasApi ? "apikey:" + apiKey : "accesstoken:" + accessToken;
                uid = "manual-" + sha256Short(seed);
            }

            String json = objectMapper.writeValueAsString(info);
            return accountSaveService.save(uid, json, hasAccess ? accessToken : null, hasApi ? apiKey : null);
        }).flatMap(result -> {
            if (result.action() == SaveAction.DUPLICATE) {
                return Mono.error(new DuplicateAccountException(result.response()));
            }
            return Mono.just(result);
        }).map(result -> Map.<String, Object>of(
                "status", result.action().name().toLowerCase(),
                "data", result.response()
        ));
    }

    private static String sha256Short(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 解析 info 字段：支持 Map（前端传来的任意 JSON）回退到字符串后经 fromFlatJson 归一化
     */
    private static WorkbuddyDesktopInfo parseInfo(Object infoNode, ObjectMapper objectMapper) {
        try {
            if (infoNode == null) {
                return new WorkbuddyDesktopInfo(null, null);
            }
            if (infoNode instanceof String s) {
                return WorkbuddyDesktopInfo.fromFlatJson(s, objectMapper);
            }
            // Map / List 等：先序列化为 JSON 字符串再走统一入口
            String json = objectMapper.writeValueAsString(infoNode);
            return WorkbuddyDesktopInfo.fromFlatJson(json, objectMapper);
        } catch (Exception e) {
            throw new IllegalArgumentException("账户信息解析失败: " + e.getMessage(), e);
        }
    }

    /** 409: UID + 凭证集 已存在 */
    public static class DuplicateAccountException extends RuntimeException {
        private final AccountResponse response;
        public DuplicateAccountException(AccountResponse response) {
            super("账户信息已存在且凭证未变化，拒绝写入");
            this.response = response;
        }
        public AccountResponse getResponse() { return response; }
    }

    @ExceptionHandler(DuplicateAccountException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicate(DuplicateAccountException ex) {
        return Map.of(
                "status", "duplicate",
                "message", ex.getMessage(),
                "data", ex.getResponse()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("status", "error", "message", ex.getMessage());
    }
}
