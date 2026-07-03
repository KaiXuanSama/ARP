package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 读取本地 CodeBuddy 桌面端配置文件，获取账户和认证信息。
 * <p>
 * Windows 路径：%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\Auth\workbuddy-desktop.info
 * <p>
 * Linux 路径（Docker 容器场景）：CodeBuddy 桌面端无官方 Linux 路径，
 * 按 XDG 规范放在 ${XDG_DATA_HOME:-~/.local/share}/CodeBuddyExtension/...。
 * 也允许通过环境变量 WORKBUDDY_INFO_PATH 直接覆盖（适合把 .info 文件 bind mount 进容器）。
 */
@Service
public class WorkbuddyInfoService {

    private static final Logger log = LoggerFactory.getLogger(WorkbuddyInfoService.class);

    /**
     * 计算 .info 文件路径。每次调用都重算（环境变量可能在容器启动后才被注入），
     * 且容忍 null —— 即便两条候选路径都解析不出来，也只是返回 null，调用方
     * 在 Files.exists() 阶段会拿到 IOException，不再让 <clinit> NPE 把整个 Bean 拉爆。
     */
    static Path resolveInfoPath() {
        // 1) 显式覆盖（最高优先级，给 Docker mount 用）
        String override = System.getenv("WORKBUDDY_INFO_PATH");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }

        // 2) 按 OS 走默认路径
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Paths.get(localAppData, "CodeBuddyExtension", "Data", "Public", "Auth", "workbuddy-desktop.info");
            }
            return null;
        }

        // 3) Linux / macOS: XDG_DATA_HOME 优先，缺失则 $HOME/.local/share
        String xdgData = System.getenv("XDG_DATA_HOME");
        String home = System.getenv("HOME");
        String base;
        if (xdgData != null && !xdgData.isBlank()) {
            base = xdgData;
        } else if (home != null && !home.isBlank()) {
            base = home + "/.local/share";
        } else {
            // 容器里两条都没设 —— 返回 null 让 Files.exists() 走 IOException 分支
            return null;
        }
        return Paths.get(base, "CodeBuddyExtension", "Data", "Public", "Auth", "workbuddy-desktop.info");
    }

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
        Path infoPath = resolveInfoPath();
        if (infoPath == null || !Files.exists(infoPath)) {
            throw new IOException("CodeBuddy 配置文件不存在: " + infoPath);
        }

        String content = Files.readString(infoPath);
        log.debug("成功读取配置文件: {}", infoPath);
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
        Path infoPath = resolveInfoPath();
        return infoPath != null && Files.exists(infoPath);
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
