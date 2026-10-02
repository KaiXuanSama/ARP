package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.PluginLoginSessionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PluginLoginService} 单元测试 —— 钉住插件授权链路的关键契约
 * <p>
 * 用 MockWebServer 模拟上游三个授权端点（state / token / account），
 * 覆盖：authUrl 防钓鱼校验、token 有效期归一化、domain 校验、
 * flow 生命周期（TTL / 节流 / 防重入 / owner 隔离 / 幂等 / 上限 / 同 owner 替换）。
 * <p>
 * 账号落库用 Mockito 桩掉 {@link AccountSaveService}（DB 不在本测试范围）。
 */
class PluginLoginServiceTest {

    private MockWebServer upstream;
    private PluginLoginService service;
    private AccountSaveService accountSaveService;

    /** 测试时钟偏移：与 MockWebServer 配合模拟「先 pending 后 success」 */
    private static final String STATE = "upstream-state-123";
    private static final String ACCESS_TOKEN = "fake-access-token";
    private static final String REFRESH_TOKEN = "fake-refresh-token";
    private static final String OWNER = "owner-hash";

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();

        accountSaveService = mock(AccountSaveService.class);
        service = new PluginLoginService(WebClient.builder(), accountSaveService,
                new ObjectMapper(), "2.159.0");
        // 上游指向 MockWebServer。url("/") 以 / 结尾，去掉尾斜杠避免拼出 //v2/...
        String base = upstream.url("/").toString();
        ReflectionTestUtils.setField(service, "pluginBaseUrl",
                base.substring(0, base.length() - 1));
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.shutdown();
    }

    // ============== 工具 ==============

    /** 订阅 Mono 并断言其唯一值（不依赖 reactor-test，避免为单测新增依赖） */
    private static <T> T blockValue(Mono<T> mono) {
        return mono.block(Duration.ofSeconds(10));
    }

    private static <T> void assertValue(Mono<T> mono, Consumer<T> assertions) {
        T value = mono.block(Duration.ofSeconds(10));
        assertThat(value).isNotNull();
        assertions.accept(value);
    }

    /** state 接口的成功响应（带 login-session cookie 与合法 authUrl） */
    private MockResponse stateOk() {
        String authUrl = upstream.url("/login?state=" + STATE).toString();
        // MockWebServer 的 url() 是 http:// —— authUrl 校验要求 https，改成 https 形态
        authUrl = authUrl.replaceFirst("^http://", "https://");
        return new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setHeader(HttpHeaders.SET_COOKIE, "login-session=abc123; Path=/; Max-Age=600")
                .setBody("{\"code\":0,\"data\":{\"state\":\"" + STATE + "\",\"authUrl\":\"" + authUrl + "\"}}");
    }

    private MockResponse tokenPending() {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":11217,\"msg\":\"login ing\"}");
    }

    /** 相对秒版 expiresIn（1 小时），走 expiresIn 归一化分支 */
    private MockResponse tokenOkRelative() {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"accessToken\":\"" + ACCESS_TOKEN
                        + "\",\"refreshToken\":\"" + REFRESH_TOKEN
                        + "\",\"domain\":\"www.codebuddy.cn\",\"expiresIn\":3600}}");
    }

    /** 绝对秒版 expiresAt，走绝对时间归一化分支 */
    private MockResponse tokenOkAbsoluteSeconds(long epochSecond) {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"accessToken\":\"" + ACCESS_TOKEN
                        + "\",\"refreshToken\":\"" + REFRESH_TOKEN
                        + "\",\"domain\":\"www.codebuddy.cn\",\"expiresAt\":" + epochSecond + "}}");
    }

    private MockResponse accountOk() {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"uid\":\"u-1\",\"nickname\":\"测试账号\",\"enterpriseId\":\"\"}}");
    }

    /** 落库桩：让 save 返回一个无害结果 */
    private void stubSave() {
        when(accountSaveService.save(anyString(), anyString(), anyString(), any()))
                .thenReturn(new AccountSaveService.SaveResult(
                        AccountSaveService.SaveAction.CREATED, null));
    }

    // ============== 创建会话 ==============

    @Test
    @DisplayName("创建会话：返回 id/loginUrl/interval，请求伪装 CLI 头，authUrl 已通过安全校验")
    void startSession() throws InterruptedException {
        upstream.enqueue(stateOk());

        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));
        assertThat(s.id()).hasSize(64);
        assertThat(s.loginUrl()).startsWith("https://").contains("/login").contains("state=" + STATE);
        assertThat(s.interval()).isEqualTo(3);
        assertThat(s.expiresAt()).isGreaterThan(System.currentTimeMillis());

        RecordedRequest req = takeRequest();
        assertThat(req.getHeader("User-Agent")).isEqualTo("CLI/2.159.0 CodeBuddy/2.159.0");
        assertThat(req.getHeader("Origin")).isEqualTo("https://www.codebuddy.cn");
        assertThat(req.getHeader("Referer")).isEqualTo("https://www.codebuddy.cn/");
        assertThat(req.getHeader("X-Requested-With")).isEqualTo("XMLHttpRequest");
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).contains("/v2/plugin/auth/state").contains("platform=CLI");
    }

    @Test
    @DisplayName("创建会话：authUrl 钓鱼 host（非上游后端）必须拒绝")
    void startSessionRejectsPhishingUrl() {
        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"state\":\"" + STATE
                        + "\",\"authUrl\":\"https://evil.example/login?state=" + STATE + "\"}}"));

        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.flowCount()).isZero();
    }

    @Test
    @DisplayName("创建会话：authUrl path 非 /login 或 state 与响应不一致必须拒绝")
    void startSessionRejectsBadPathOrState() {
        // path 错误
        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"state\":\"" + STATE + "\",\"authUrl\":\""
                        + upstream.url("/other?state=" + STATE).toString().replaceFirst("^http://", "https://") + "\"}}"));
        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);

        // state 不一致
        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"state\":\"" + STATE + "\",\"authUrl\":\""
                        + upstream.url("/login?state=other").toString().replaceFirst("^http://", "https://") + "\"}}"));
        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.flowCount()).isZero();
    }

    @Test
    @DisplayName("创建会话：上游非 200 / 非 JSON 信封 → 明确报错")
    void startSessionUpstreamErrors() {
        upstream.enqueue(new MockResponse().setResponseCode(502));
        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);

        upstream.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));
        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);

        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":1,\"msg\":\"boom\"}"));
        assertThatThrownBy(() -> blockValue(service.startSession(OWNER)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(service.flowCount()).isZero();
    }

    // ============== 轮询 ==============

    @Test
    @DisplayName("轮询：先 pending（code=11217），带 login-session cookie，超时节流直接 pending")
    void pollPendingAndThrottle() throws InterruptedException {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        upstream.enqueue(tokenPending());
        assertValue(service.pollSession(OWNER, s.id()),
                r -> assertThat(r.status()).isEqualTo("pending"));

        RecordedRequest tokenReq = takeLastRequest();
        assertThat(tokenReq.getMethod()).isEqualTo("GET");
        assertThat(tokenReq.getPath()).contains("/v2/plugin/auth/token").contains("state=" + STATE);
        assertThat(tokenReq.getHeader("Cookie")).isEqualTo("login-session=abc123");
        // 轮询请求也带伪装头
        assertThat(tokenReq.getHeader("User-Agent")).isEqualTo("CLI/2.159.0 CodeBuddy/2.159.0");

        // 节流窗口内：不再打上游（上游没有 enqueue 第二个响应，若打了会失败）
        assertValue(service.pollSession(OWNER, s.id()),
                r -> assertThat(r.status()).isEqualTo("pending"));
        assertThat(upstream.getRequestCount()).isEqualTo(2); // state + token 各一次
    }

    @Test
    @DisplayName("轮询成功：expiresIn 相对秒归一化为毫秒，落库并缓存结果（幂等）")
    void pollSuccessIdempotent() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        // 让节流窗口失效：直接改 nextPollAt 为过去
        Object flow = flowOf(s.id());
        ReflectionTestUtils.setField(flow, "nextPollAt", 0L);

        stubSave();
        upstream.enqueue(tokenOkRelative());
        upstream.enqueue(accountOk());

        long before = System.currentTimeMillis();
        assertValue(service.pollSession(OWNER, s.id()), r -> {
            assertThat(r.status()).isEqualTo("success");
            assertThat(r.uid()).isEqualTo("u-1");
            assertThat(r.nickname()).isEqualTo("测试账号");
            // expiresIn=3600 → now+3600_000（毫秒）
            assertThat(r.expiresAt()).isBetween(before + 3600_000 - 1000, System.currentTimeMillis() + 3600_000);
        });

        RecordedRequest accountReq = takeLastRequest();
        assertThat(accountReq.getMethod()).isEqualTo("GET");
        assertThat(accountReq.getPath()).contains("/v2/plugin/login/account");
        assertThat(accountReq.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + ACCESS_TOKEN);

        // 幂等：重复 poll 返回缓存结果，不再打上游、不重复落库
        assertValue(service.pollSession(OWNER, s.id()),
                r -> assertThat(r.status()).isEqualTo("success"));
        assertThat(upstream.getRequestCount()).isEqualTo(3); // state + token + account
    }

    @Test
    @DisplayName("轮询成功：绝对秒 expiresAt 归一化（秒→毫秒），account_json 含 auth 嵌套")
    void pollSuccessAbsoluteSecondsAndDocShape() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        Object flow = flowOf(s.id());
        ReflectionTestUtils.setField(flow, "nextPollAt", 0L);

        long nowSec = System.currentTimeMillis() / 1000;
        stubSave();
        upstream.enqueue(tokenOkAbsoluteSeconds(nowSec + 55 * 24 * 3600));   // 55 天后（秒）
        upstream.enqueue(accountOk());

        assertValue(service.pollSession(OWNER, s.id()), r -> {
            assertThat(r.status()).isEqualTo("success");
            // 秒被归一化为毫秒（约 55 天）
            long days = (r.expiresAt() - System.currentTimeMillis()) / 86400_000L;
            assertThat(days).isBetween(54L, 56L);
        });

        // 落库形态：account_json 为 account/auth 嵌套，auth 带 domain/expiresAt
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(accountSaveService).save(
                org.mockito.Mockito.eq("u-1"), captor.capture(),
                org.mockito.Mockito.eq(ACCESS_TOKEN), org.mockito.Mockito.isNull());
        var json = new ObjectMapper().readTree(captor.getValue());
        assertThat(json.path("account").path("uid").asText()).isEqualTo("u-1");
        assertThat(json.path("auth").path("accessToken").asText()).isEqualTo(ACCESS_TOKEN);
        assertThat(json.path("auth").path("refreshToken").asText()).isEqualTo(REFRESH_TOKEN);
        assertThat(json.path("auth").path("domain").asText()).isEqualTo("www.codebuddy.cn");
        assertThat(json.path("auth").path("expiresAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("domain 校验：上游返回不支持的 domain（如 workbuddy.ai）必须报错且不落库")
    void pollRejectsForeignDomain() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));
        ReflectionTestUtils.setField(flowOf(s.id()), "nextPollAt", 0L);

        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"accessToken\":\"" + ACCESS_TOKEN
                        + "\",\"refreshToken\":\"" + REFRESH_TOKEN
                        + "\",\"domain\":\"www.workbuddy.ai\",\"expiresIn\":3600}}"));

        assertValue(service.pollSession(OWNER, s.id()), r -> {
            assertThat(r.status()).isEqualTo("error");
            assertThat(r.message()).contains("不支持的站点");
        });
        org.mockito.Mockito.verify(accountSaveService, org.mockito.Mockito.never())
                .save(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("有效期缺失：expiresAt/expiresIn 都没有 → error 且不落库")
    void pollRejectsMissingExpiry() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));
        ReflectionTestUtils.setField(flowOf(s.id()), "nextPollAt", 0L);

        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"accessToken\":\"" + ACCESS_TOKEN
                        + "\",\"refreshToken\":\"" + REFRESH_TOKEN + "\",\"domain\":\"www.codebuddy.cn\"}}"));

        assertValue(service.pollSession(OWNER, s.id()),
                r -> assertThat(r.status()).isEqualTo("error"));
        org.mockito.Mockito.verify(accountSaveService, org.mockito.Mockito.never())
                .save(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("owner 隔离：别人的 fid 一律 expired（不泄露存在性）")
    void pollOwnerIsolation() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        assertValue(service.pollSession("someone-else", s.id()),
                r -> assertThat(r.status()).isEqualTo("expired"));
        assertThat(upstream.getRequestCount()).isEqualTo(1); // 未打上游
    }

    @Test
    @DisplayName("会话过期：TTL 已过 → expired，且 flow 被清理")
    void pollExpired() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        // 把 expiresAt 拨到过去
        Object flow = flowOf(s.id());
        ReflectionTestUtils.setField(flow, "expiresAt", System.currentTimeMillis() - 1000);

        assertValue(service.pollSession(OWNER, s.id()),
                r -> assertThat(r.status()).isEqualTo("expired"));
        assertThat(service.flowCount()).isZero();
    }

    @Test
    @DisplayName("取消会话：owner 校验通过才删；别人的取消无效")
    void cancelSession() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse s = blockValue(service.startSession(OWNER));

        service.cancelSession("someone-else", s.id());
        assertThat(service.flowCount()).isEqualTo(1);

        service.cancelSession(OWNER, s.id());
        assertThat(service.flowCount()).isZero();
    }

    @Test
    @DisplayName("同 owner 重新发起：旧会话被取消")
    void restartCancelsPrevious() throws Exception {
        upstream.enqueue(stateOk());
        PluginLoginSessionResponse first = blockValue(service.startSession(OWNER));

        upstream.enqueue(stateOk());
        PluginLoginSessionResponse second = blockValue(service.startSession(OWNER));

        assertThat(service.flowCount()).isEqualTo(1);
        assertValue(service.pollSession(OWNER, first.id()),
                r -> assertThat(r.status()).isEqualTo("expired"));

        // 新会话真实轮询：token + account（若返回 pending 说明上游未回凭证，也算通过）
        Object flow = flowOf(second.id());
        ReflectionTestUtils.setField(flow, "nextPollAt", 0L);
        stubSave();
        upstream.enqueue(tokenOkRelative());
        upstream.enqueue(accountOk());
        assertValue(service.pollSession(OWNER, second.id()),
                r -> assertThat(r.status()).isEqualTo("success"));
    }

    @Test
    @DisplayName("并发上限：第 17 个会话必须拒绝")
    void flowLimit() throws Exception {
        for (int i = 0; i < 16; i++) {
            upstream.enqueue(stateOk());
            blockValue(service.startSession("owner-" + i));
        }
        assertThat(service.flowCount()).isEqualTo(16);

        upstream.enqueue(stateOk());
        assertThatThrownBy(() -> blockValue(service.startSession("owner-16")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ============== 内部工具 ==============

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("应有请求发往上游").isNotNull();
        return req;
    }

    /** 取最近一个请求：MockWebServer 的队列是 FIFO，测试只关心最后发出的那个 */
    private RecordedRequest takeLastRequest() throws InterruptedException {
        RecordedRequest last = null;
        RecordedRequest req;
        while ((req = upstream.takeRequest(200, TimeUnit.MILLISECONDS)) != null) {
            last = req;
        }
        assertThat(last).as("应有请求发往上游").isNotNull();
        return last;
    }

    private Object flowOf(String fid) {
        var flows = (java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "flows");
        return flows.get(fid);
    }
}
