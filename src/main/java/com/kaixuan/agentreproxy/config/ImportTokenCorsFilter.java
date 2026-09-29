package com.kaixuan.agentreproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code /api/accounts/import-token} 的 CORS 响应头注入器
 * <p>
 * <b>为什么不用 {@code @CrossOrigin} / {@code CorsWebFilter}</b>：
 * Spring 内置的 CORS 处理是「白名单外一律 {@code 403}」，而本项目有多个合法的调用方：
 * <ul>
 *   <li>书签脚本 —— 来源 {@code https://www.codebuddy.cn}（真正跨域，必须加响应头）</li>
 *   <li>管理面板手动提交 —— 同源，但经 Vite dev proxy 转发时 Origin 会变成
 *       {@code http://localhost:5174}；Spring 判定为跨域而拒绝，导致开发环境不可用</li>
 *   <li>反代部署（nginx）—— Origin 是外部域名，与后端看到的 Host 可能不一致</li>
 * </ul>
 * 前两类之外都不需要 CORS 响应头（浏览器按同源策略自行处理），
 * 因此本过滤器只做「命中白名单则补响应头」，<b>不做任何拒绝</b>，
 * 避免把 Spring 的 CORS 判定强加给本不需要它的调用方。
 * <p>
 * <b>安全性</b>：本端点的鉴权靠一次性 ticket（见 {@code LoginSessionService}），
 * 响应头只决定浏览器是否把结果交给页面脚本，不构成授权。
 * 因此即便某个来源不在白名单，也只是读不到响应体，无法绕过 ticket 校验。
 * <p>
 * <b>预检请求</b>：{@code OPTIONS} 直接返回 200 并结束（不进业务逻辑）。
 * 来源不在白名单时不补响应头，浏览器会据此拒绝后续真实请求 —— 这正是期望行为。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ImportTokenCorsFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ImportTokenCorsFilter.class);

    /** 需要处理 CORS 的精确路径 */
    private static final String TARGET_PATH = "/api/accounts/import-token";

    private final Set<String> allowedOrigins;

    public ImportTokenCorsFilter(
            @Value("${custom.login.allowed-origins:https://www.codebuddy.cn}") String allowedOriginsRaw) {
        this.allowedOrigins = new LinkedHashSet<>(
                Arrays.stream(allowedOriginsRaw.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        log.info("扫码凭证回传端点的 CORS 白名单: {}", this.allowedOrigins);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!TARGET_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        String origin = exchange.getRequest().getHeaders().getFirst(HttpHeaders.ORIGIN);
        HttpHeaders headers = exchange.getResponse().getHeaders();

        if (origin != null && allowedOrigins.contains(origin)) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST, OPTIONS");
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
            // 不回传 Cookie 凭据：鉴权靠 ticket，开启凭据只会扩大攻击面
            headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600");
            // 响应随 Origin 变化，避免中间缓存串味
            headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        }

        // 预检请求不应进入业务逻辑（也无请求体可解析）
        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
