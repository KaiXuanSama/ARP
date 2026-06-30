package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.dto.AccountResponse;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.service.AccountSaveService;
import com.kaixuan.agentreproxy.service.AccountSaveService.SaveAction;
import com.kaixuan.agentreproxy.service.WorkbuddyInfoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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
    private final ObjectMapper objectMapper;

    public WorkbuddyInfoController(WorkbuddyInfoService workbuddyInfoService,
                                   AccountSaveService accountSaveService,
                                   ObjectMapper objectMapper) {
        this.workbuddyInfoService = workbuddyInfoService;
        this.accountSaveService = accountSaveService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/workbuddy-info")
    public Mono<WorkbuddyDesktopInfo> getWorkbuddyInfo() {
        return Mono.fromCallable(workbuddyInfoService::readInfo);
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
            WorkbuddyDesktopInfo info = objectMapper.convertValue(infoNode, WorkbuddyDesktopInfo.class);

            if (info == null || info.account() == null
                    || info.account().uid() == null || info.account().uid().isBlank()) {
                throw new IllegalArgumentException("uid 不可为空");
            }

            // 2) 解析 apiKey
            String apiKey = null;
            Object apiKeyNode = raw.get("apiKey");
            if (apiKeyNode instanceof String s) {
                apiKey = s;
            }

            String accessToken = info.auth() != null ? info.auth().accessToken() : null;
            boolean hasAccess = accessToken != null && !accessToken.isBlank();
            boolean hasApi = apiKey != null && !apiKey.isBlank();
            if (!hasAccess && !hasApi) {
                throw new IllegalArgumentException("accessToken 与 apiKey 至少需要存在一个");
            }

            String json = objectMapper.writeValueAsString(info);
            return accountSaveService.save(info.account().uid(), json, hasAccess ? accessToken : null, hasApi ? apiKey : null);
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
