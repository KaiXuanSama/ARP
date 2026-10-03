package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link UpstreamClient#fetchModelConfig} 单元测试 —— 钉住 /v3/config 调用契约
 * <p>
 * 覆盖：CLI 控制面 UA、凭证头注入、信封校验（code=0 / 12403 / 非 JSON / 缺 models）、
 * 返回原始数组节点。
 */
class UpstreamClientModelConfigTest {

    private MockWebServer upstream;
    private UpstreamClient client;

    private static final long ACCOUNT_ID = 1L;
    private static final String JWT = "fake-jwt-token";

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();

        WorkbuddyAccountJdbcRepository repository = mock(WorkbuddyAccountJdbcRepository.class);
        when(repository.findById(anyLong())).thenReturn(java.util.Optional.of(
                new WorkbuddyAccountRecord(ACCOUNT_ID, "uid-1", "{}", JWT, null,
                        1, null, 0L, 0L)));

        client = new UpstreamClient(WebClient.builder(), mock(WorkbuddyInfoService.class),
                repository, new ObjectMapper(), "2.159.0");
        // 上游指向 MockWebServer（url("/") 尾斜杠去掉，防 //v3 拼接）
        String base = upstream.url("/").toString();
        ReflectionTestUtils.setField(client, "controlBaseUrl", base.substring(0, base.length() - 1));
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.shutdown();
    }

    private MockResponse ok(String modelsJson) {
        return new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":0,\"data\":{\"models\":" + modelsJson + "}}");
    }

    @Test
    @DisplayName("成功：带 CLI 控制面 UA 与凭证头，返回原始 models 数组")
    void fetchSuccess() throws InterruptedException {
        upstream.enqueue(ok("[{\"id\":\"deepseek-v4\"},{\"id\":\"img\",\"tags\":[\"text-to-image\"]}]"));

        var models = client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10));

        assertThat(models).isNotNull();
        assertThat(models.size()).isEqualTo(2);   // 未过滤，原始条目

        RecordedRequest req = takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v3/config");
        assertThat(req.getHeader("User-Agent")).isEqualTo("CLI/2.159.0 CodeBuddy/2.159.0");
        assertThat(req.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + JWT);
        assertThat(req.getHeader("X-Domain")).isEqualTo("www.codebuddy.cn");
    }

    @Test
    @DisplayName("UA 拒绝：code=12403 → 报错（调用方跳过该账号）")
    void uaRejected() {
        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody("{\"code\":12403,\"msg\":\"check ua\"}"));

        assertThatThrownBy(() -> client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10)))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("12403");
    }

    @Test
    @DisplayName("HTTP 非 200 / 非 JSON / 缺 data.models → 报错")
    void malformedResponses() {
        upstream.enqueue(new MockResponse().setResponseCode(502));
        assertThatThrownBy(() -> client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10)))
                .hasMessageContaining("502");

        upstream.enqueue(new MockResponse().setResponseCode(200).setBody("not-json"));
        assertThatThrownBy(() -> client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10)))
                .hasMessageContaining("格式异常");

        // code=0 但 data.models 不是数组
        upstream.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"code\":0,\"data\":{\"models\":null}}"));
        assertThatThrownBy(() -> client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10)))
                .hasMessageContaining("data.models");
    }

    @Test
    @DisplayName("凭证过期（上游 401）→ 报错（调用方顺延下一账号）")
    void credentialRejected() {
        upstream.enqueue(new MockResponse().setResponseCode(401).setBody("{\"code\":401}"));

        assertThatThrownBy(() -> client.fetchModelConfig(ACCOUNT_ID).block(Duration.ofSeconds(10)))
                .hasMessageContaining("401");
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = upstream.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("应有请求发往上游").isNotNull();
        return req;
    }
}
