package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取本地 CodeBuddy 桌面端配置文件，获取账户和认证信息。
 * <p>
 * 文件路径：%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\Auth\workbuddy-desktop.info
 */
@Service
public class WorkbuddyInfoService {

    private static final Logger log = LoggerFactory.getLogger(WorkbuddyInfoService.class);

    private static final Path INFO_FILE_PATH = Path.of(
            System.getenv("LOCALAPPDATA"),
            "CodeBuddyExtension", "Data", "Public", "Auth", "workbuddy-desktop.info"
    );

    private final ObjectMapper objectMapper;

    public WorkbuddyInfoService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析配置文件
     *
     * @return 解析后的配置信息
     * @throws IOException 文件不存在或解析失败
     */
    public WorkbuddyDesktopInfo readInfo() throws IOException {
        if (!Files.exists(INFO_FILE_PATH)) {
            throw new IOException("CodeBuddy 配置文件不存在: " + INFO_FILE_PATH);
        }

        String content = Files.readString(INFO_FILE_PATH);
        log.debug("成功读取配置文件: {}", INFO_FILE_PATH);
        return WorkbuddyDesktopInfo.fromFlatJson(content, objectMapper);
    }

    /**
     * 获取当前有效的 accessToken
     *
     * @return accessToken 字符串
     * @throws IOException 文件不存在或解析失败
     */
    public String getAccessToken() throws IOException {
        WorkbuddyDesktopInfo info = readInfo();
        return info.auth().accessToken();
    }

    /**
     * 检查配置文件是否存在
     */
    public boolean isInfoFileAvailable() {
        return Files.exists(INFO_FILE_PATH);
    }

    /**
     * 安全读取配置（失败时返回空对象，不抛异常）
     */
    public reactor.core.publisher.Mono<WorkbuddyDesktopInfo> getAccountInfoSafely() {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            try {
                return readInfo();
            } catch (Exception e) {
                log.warn("读取 CodeBuddy 配置失败: {}", e.getMessage());
                return new WorkbuddyDesktopInfo(null, null);
            }
        });
    }
}
