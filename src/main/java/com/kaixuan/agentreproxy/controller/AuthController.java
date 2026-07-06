package com.kaixuan.agentreproxy.controller;

import com.kaixuan.agentreproxy.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证端点 — 登录 / 登出
 * <p>
 * {@code /api/auth/*} 路径不受 Token 鉴权 WebFilter 拦截（白名单）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录 — {@code POST /api/auth/login}
     * <p>
     * 请求体：{@code {"username": "root", "password": "root"}}
     * <br>
     * 成功响应：{@code {"token": "hex64...", "expiresIn": 43200}}
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String token = authService.login(username, password);
        return Map.of(
                "token", token,
                "expiresIn", 12 * 60 * 60  // 秒
        );
    }

    /**
     * 登出 — {@code POST /api/auth/logout}
     * <p>
     * 请求头：{@code Authorization: Bearer <token>}
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7).trim());
        }
        return Map.of("status", "ok");
    }
}
