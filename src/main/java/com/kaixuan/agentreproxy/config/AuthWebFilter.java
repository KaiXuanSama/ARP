package com.kaixuan.agentreproxy.config;

import com.kaixuan.agentreproxy.service.AuthService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 管理面板 Token 鉴权 WebFilter
 * <p>
 * 拦截所有 {@code /api/**} 请求（除白名单路径外），
 * 从 {@code Authorization: Bearer <token>} 头中提取 token 并校验。
 * <p>
 * <strong>白名单（不拦截）</strong>：
 * <ul>
 *   <li>{@code /api/auth/**} — 登录/登出端点</li>
 *   <li>{@code /v1/**} — OpenAI 兼容端点（有独立的下游 API Key 鉴权）</li>
 *   <li>静态资源（非 {@code /api/} 开头的路径）</li>
 * </ul>
 * <p>
 * <strong>优先级</strong>：高于 {@link OpenAiWebExceptionHandler}，
 * 但由于 OpenAI 路径不经过本 filter，不冲突。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthWebFilter implements WebFilter {

    private final AuthService authService;

    public AuthWebFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 白名单：不需要管理面板 token 鉴权的路径
        if (!requiresAuth(path)) {
            return chain.filter(exchange);
        }

        // 提取 token
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7).trim();
        }

        // 校验
        if (!authService.validateToken(token)) {
            return unauthorized(exchange);
        }

        return chain.filter(exchange);
    }

    /**
     * 判断路径是否需要管理面板 token 鉴权
     */
    private boolean requiresAuth(String path) {
        // 只拦截 /api/** 路径
        if (!path.startsWith("/api/")) {
            return false;
        }
        // 登录/登出端点放行
        if (path.startsWith("/api/auth/")) {
            return false;
        }
        return true;
    }

    /**
     * 返回 401 JSON 响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"status\":\"error\",\"message\":\"未登录或登录已过期\"}".getBytes();
        var buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
