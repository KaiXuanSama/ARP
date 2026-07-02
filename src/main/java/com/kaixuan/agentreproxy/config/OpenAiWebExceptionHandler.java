package com.kaixuan.agentreproxy.config;

import com.kaixuan.agentreproxy.dto.OpenAiErrorResponse;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 只处理 ResponseStatusException(OpenAI controller 抛的错误)
        // 其他异常 fall through(返回空 Mono)给 Spring 默认 handler
        if (!(ex instanceof org.springframework.web.server.ResponseStatusException rse)) {
            return Mono.empty();
        }

        // 状态码 + body
        HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = rse.getReason() != null ? rse.getReason() : rse.getMessage();
        OpenAiErrorResponse oa = OpenAiErrorMapper.map(message, status);
        Map<String, Object> body = oa.toMap();

        // 写响应
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // charset=UTF-8 让中文 message 正确编码
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        // 关闭 SSE 相关的头部(防止客户端按 SSE 解析)
        response.getHeaders().remove("Content-Type");
        response.getHeaders().set("Content-Type", "application/json;charset=UTF-8");

        // 序列化 body —— 用 Mono 异步写
        return Mono.just(body).flatMap(b -> {
            try {
                byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsBytes(b);
                var buffer = response.bufferFactory().wrap(bytes);
                return response.writeWith(Mono.just(buffer)).then();
            } catch (Exception e) {
                // 序列化失败,降级为纯文本
                byte[] fallback = ("{\"error\":{\"message\":\"序列化失败\",\"type\":\"server_error\",\"code\":\"internal\"}}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var buffer = response.bufferFactory().wrap(fallback);
                return response.writeWith(Mono.just(buffer)).then();
            }
        });
    }
}
