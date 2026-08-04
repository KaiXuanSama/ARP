package com.kaixuan.agentreproxy.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * WebFlux 全局异常处理器 —— 把 {@link org.springframework.web.server.ResponseStatusException}
 * 渲染成 OpenAI 风格的错误 JSON
 * <p>
 * <strong>为什么需要</strong>:Spring Boot 3.x 默认启用 problem-details(RFC 7807),
 * 任何抛 {@code ResponseStatusException} 都会被 Spring 默认 handler 渲染成
 * <pre>{@code
 * {"timestamp":"...","path":"/v1/...","status":401,"error":"Unauthorized","requestId":"..."}
 * }</pre>
 * 这种格式 <strong>不带 message 字段</strong>,B 端下游用户看不到具体原因。
 * <p>
 * <strong>本处理器只处理 {@code ResponseStatusException}</strong>(OpenAI 路由用),
 * 其他异常 fall through 给 Spring 默认(problem-details),
 * 不影响 {@code GlobalExceptionHandler} 走的 {@code @ExceptionHandler} 路径(那些
 * 异常不是 {@code ResponseStatusException},不会进本处理器)。
 * <p>
 * <strong>优先级</strong>:{@code @Order(Ordered.HIGHEST_PRECEDENCE)} 让本处理器
 * 排在 Spring 默认 {@code DefaultErrorWebExceptionHandler} 之前。Spring 用
 * 第一个返回非空 {@code Mono<ServerResponse>} 的 handler;如果本处理器
 * {@code chain.next(exchange, throwable)} 让默认 handler 接管,行为退化。
 *
 * <h3>实现{@link ErrorWebExceptionHandler}接口</h3>
 * 不用 {@code @Component} + {@code @Order} 也能注册(WebFlux 会扫描),
 * 但显式实现 {@code ErrorWebExceptionHandler} 接口 + 最高优先级,确保不被默认
 * handler 抢先。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenAiWebExceptionHandler implements ErrorWebExceptionHandler {

    /** 共享 ObjectMapper —— 原实现每次异常都 new 一个，属于无谓开销 */
    private static final com.fasterxml.jackson.databind.ObjectMapper SHARED_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 上游错误（Anthropic 端点用 exchangeToFlux 显式抛出）→ 透传上游状态码与错因
        if (ex instanceof com.kaixuan.agentreproxy.service.UpstreamClient.UpstreamErrorException ue) {
            return writeError(exchange, resolveStatus(ue.getStatusCode()),
                    extractUpstreamMessage(ue.getResponseBody(), ue.getStatusCode()));
        }

        // 只处理 ResponseStatusException(OpenAI / Anthropic controller 抛的错误)
        // 其他异常 fall through(返回空 Mono)给 Spring 默认 handler
        if (!(ex instanceof org.springframework.web.server.ResponseStatusException rse)) {
            return Mono.empty();
        }

        HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = rse.getReason() != null ? rse.getReason() : rse.getMessage();
        return writeError(exchange, status, message);
    }

    /**
     * 按请求路径选择错误响应格式并写出
     * <p>
     * <strong>为什么要按路径分流</strong>:本处理器按<b>异常类型</b>拦截、不看路径,
     * 而 Anthropic 端点 {@code /v1/messages} 与 OpenAI 端点同在 {@code /v1} 下。
     * 若统一渲染成 OpenAI 格式,Anthropic 官方 SDK 会因顶层缺 {@code type} 字段
     * 而解析失败(它期待 {@code {"type":"error","error":{...}}})。
     */
    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
        String path = exchange.getRequest().getPath().value();
        Map<String, Object> body = isAnthropicPath(path)
                ? AnthropicErrorMapper.map(message, status)
                : OpenAiErrorMapper.map(message, status).toMap();

        var response = exchange.getResponse();
        response.setStatusCode(status);
        // charset=UTF-8 让中文 message 正确编码
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");

        return Mono.just(body).flatMap(b -> {
            try {
                byte[] bytes = SHARED_MAPPER.writeValueAsBytes(b);
                var buffer = response.bufferFactory().wrap(bytes);
                return response.writeWith(Mono.just(buffer)).then();
            } catch (Exception e) {
                byte[] fallback = ("{\"error\":{\"message\":\"序列化失败\",\"type\":\"server_error\",\"code\":\"internal\"}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var buffer = response.bufferFactory().wrap(fallback);
                return response.writeWith(Mono.just(buffer)).then();
            }
        });
    }

    /** Anthropic 兼容端点路径判定 */
    private static boolean isAnthropicPath(String path) {
        return path != null && path.startsWith("/v1/messages");
    }

    /** 上游状态码 → 本地状态码（无法解析时降级 502，表示上游异常而非本服务异常） */
    private static HttpStatus resolveStatus(int upstreamStatus) {
        HttpStatus s = HttpStatus.resolve(upstreamStatus);
        return s != null ? s : HttpStatus.BAD_GATEWAY;
    }

    /**
     * 从上游错误响应中提取可读的错因
     * <p>
     * 上游实测有两种形态:
     * <ul>
     *   <li>JSON:{@code {"code":11102,"error":"11102:model [x] service info not found",...}}</li>
     *   <li>HTML:APISIX 网关的 401 页面</li>
     * </ul>
     * HTML 形态无法提取有效信息,返回按状态码归纳的通用提示。
     */
    private static String extractUpstreamMessage(String rawBody, int statusCode) {
        if (rawBody == null || rawBody.isBlank()) {
            return "上游返回 " + statusCode + " 且无响应体";
        }
        String trimmed = rawBody.trim();
        // HTML(网关错误页)→ 不暴露给下游,给通用提示
        if (trimmed.startsWith("<")) {
            if (statusCode == 401 || statusCode == 403) {
                return "上游账号凭证无效或已过期,请在管理面板刷新该账号的 access_token";
            }
            return "上游网关返回 " + statusCode;
        }
        try {
            var node = SHARED_MAPPER.readTree(trimmed);
            for (String field : new String[] { "error", "message", "error_msg" }) {
                var v = node.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return "上游错误: " + v.asText();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON,走下面的截断兜底
        }
        return "上游错误: " + (trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed);
    }
}
