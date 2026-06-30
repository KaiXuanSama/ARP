package com.kaixuan.agentreproxy.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient 全局配置
 * <p>
 * 【防御性配置】将 WebClient 默认底层的 Reactor Netty 异步 DNS 解析器
 * 替换为 JDK 系统解析器（DefaultAddressResolverGroup）。
 * <p>
 * 原因：Reactor Netty 在 Windows 上自带的 DNS 解析路径脆弱，
 *      容易出现"首查失败 → 负缓存 10~30 分钟后自动恢复"的间歇性故障，
 *      而本项目仅在 Windows 环境运行，必须预防。详见
 *      /memories/springboot-pitfalls.md
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
