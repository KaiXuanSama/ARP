package com.kaixuan.agentreproxy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportTokenCorsFilter} 单元测试 —— 钉住 CORS 契约
 * <p>
 * <b>为什么需要这组测试</b>：这里踩过一个代价不小的坑 ——
 * 最初用 {@code @CrossOrigin(origins = "https://www.codebuddy.cn")}，
 * 语义是「<b>只</b>允许这一个来源」，于是任何其他 Origin 都在进入 controller
 * <b>之前</b>被 Spring 返回 {@code 403}（空响应体）。表现是：
 * <ul>
 *   <li>管理面板的「手动方案」提交失败（Origin = Vite dev 端口）</li>
 *   <li>反代部署时前端提交失败（Origin = 外部域名）</li>
 * </ul>
 * 而这两类请求<b>本不需要 CORS</b>（同源 / 代理），却被 CORS 判定拦死。
 * <p>
 * 因此本过滤器的核心契约是：<b>只补响应头，永不拒绝请求</b>。
 * 安全性由 ticket 保证，与响应头无关。这组测试把该契约钉住。
 * <p>
 * 纯单元测试，不启动 Spring 容器。
 */
class ImportTokenCorsFilterTest {

    private static final String TARGET = "/api/accounts/import-token";

    private ImportTokenCorsFilter newFilter() {
        return new ImportTokenCorsFilter("https://www.codebuddy.cn");
    }

    /** 记录 chain 是否被调用（即请求是否进入了业务逻辑） */
    private static final class ChainProbe implements WebFilterChain {
        final AtomicBoolean called = new AtomicBoolean(false);
        final AtomicReference<ServerWebExchange> exchange = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            called.set(true);
            this.exchange.set(exchange);
            return Mono.empty();
        }
    }

    @Test
    @DisplayName("白名单来源：补 ACAO 响应头，且请求继续进入业务逻辑")
    void allowedOriginGetsCorsHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(TARGET)
                        .header(HttpHeaders.ORIGIN, "https://www.codebuddy.cn")
                        .build());
        ChainProbe chain = new ChainProbe();

        newFilter().filter(exchange, chain).block();

        assertTrue(chain.called.get(), "白名单来源的请求必须继续传递");
        HttpHeaders h = exchange.getResponse().getHeaders();
        assertEquals("https://www.codebuddy.cn", h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).contains("POST"));
        assertTrue(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS).contains("Content-Type"));
        assertNotNull(h.getFirst(HttpHeaders.ACCESS_CONTROL_MAX_AGE));
        // 响应随 Origin 变化，必须带 Vary 以免中间缓存串味
        assertTrue(h.get(HttpHeaders.VARY).contains(HttpHeaders.ORIGIN), "应设置 Vary: Origin");
        // 不回传凭据（鉴权靠 ticket 而非 Cookie）
        assertNull(h.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "不应设置 Allow-Credentials");
    }

    @Test
    @DisplayName("非白名单来源：不补 CORS 头，但**仍然放行**（这是核心契约）")
    void nonWhitelistedOriginIsNotRejected() {
        // 这类来源包括：Vite dev 代理（同源被代理后 Origin 变为 5174）、反代后的外部域名。
        // 它们本来就不需要 CORS，绝不能被 403 拦死。
        for (String origin : new String[]{
                "http://localhost:5174",
                "http://localhost:8351",
                "https://arp.example.com",
                "https://evil.example"}) {

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(TARGET)
                            .header(HttpHeaders.ORIGIN, origin)
                            .build());
            ChainProbe chain = new ChainProbe();

            newFilter().filter(exchange, chain).block();

            assertTrue(chain.called.get(),
                    "来源 " + origin + " 不应被拒绝 —— 安全性由 ticket 保证，不靠 CORS");
            assertNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                    "来源 " + origin + " 不在白名单，不应收到 ACAO 头（浏览器据此拒绝读取）");
            // 关键：不返回 403。响应状态应保持未设置，交给后续业务逻辑决定。
            assertFalse(HttpStatus.FORBIDDEN.equals(exchange.getResponse().getStatusCode()),
                    "来源 " + origin + " 不应得到 403");
        }
    }

    @Test
    @DisplayName("无 Origin 头（curl / 服务端调用）：放行且不加 CORS 头")
    void noOriginPassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(TARGET).build());
        ChainProbe chain = new ChainProbe();

        newFilter().filter(exchange, chain).block();

        assertTrue(chain.called.get(), "无 Origin 的请求必须放行（如 curl 测试）");
        assertNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("OPTIONS 预检：直接 200 结束，不进入业务逻辑")
    void optionsPreflightShortCircuits() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, TARGET)
                        .header(HttpHeaders.ORIGIN, "https://www.codebuddy.cn")
                        .build());
        ChainProbe chain = new ChainProbe();

        newFilter().filter(exchange, chain).block();

        assertFalse(chain.called.get(), "预检不应进入业务逻辑（无请求体可解析）");
        assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
        assertEquals("https://www.codebuddy.cn",
                exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("其他路径不受影响：即使带 Origin 也不加任何 CORS 头")
    void otherPathsUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/accounts")
                        .header(HttpHeaders.ORIGIN, "https://www.codebuddy.cn")
                        .build());
        ChainProbe chain = new ChainProbe();

        newFilter().filter(exchange, chain).block();

        assertTrue(chain.called.get(), "非目标路径应直接放行");
        assertNull(exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "非目标路径不应被本过滤器加 CORS 头");
    }

    @Test
    @DisplayName("白名单可配置：多个来源（逗号分隔）均生效")
    void multipleAllowedOrigins() {
        ImportTokenCorsFilter filter = new ImportTokenCorsFilter(
                " https://www.codebuddy.cn , https://arp.example.com , ");

        for (String origin : new String[]{"https://www.codebuddy.cn", "https://arp.example.com"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(TARGET)
                            .header(HttpHeaders.ORIGIN, origin)
                            .build());
            newFilterProbe(filter, exchange, origin);
            assertEquals(origin,
                    exchange.getResponse().getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                    "配置里列出的来源 " + origin + " 应被放行（且忽略空白项）");
        }
    }

    private void newFilterProbe(ImportTokenCorsFilter filter, MockServerWebExchange exchange, String origin) {
        ChainProbe chain = new ChainProbe();
        filter.filter(exchange, chain).block();
        assertTrue(chain.called.get(), "来源 " + origin + " 应被放行");
    }
}
