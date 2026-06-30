package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.config.ModelsProperties;
import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.model.ModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 模型配置加载服务
 * <p>
 * <strong>配置项</strong>:
 * <ul>
 *   <li>{@code models.config.path} —— 外部配置文件路径(默认 {@code ./modelsConfig.json})</li>
 * </ul>
 * 配置可在 <code>application.yml</code> 里写,也可通过命令行参数 / 环境变量覆盖:
 * <pre>
 * models:
 *   config:
 *     path: D:\conf\modelsConfig.json
 *
 * # 等价命令行:
 * mvnw spring-boot:run --models.config.path=D:\conf\modelsConfig.json
 *
 * # 等价环境变量(Spring 自动映射):
 * set MODELS_CONFIG_PATH=D:\conf\modelsConfig.json
 * </pre>
 * <p>
 * <strong>加载优先级</strong>(从高到低):
 * <ol>
 *   <li>{@code models.config.path} 指向的文件存在 → 读它</li>
 *   <li>不存在或路径为空 → 回退到 classpath 内置的 {@code models-config.default.json}</li>
 * </ol>
 * <p>
 * 配置在启动时一次性加载到内存,运行期不变更。改了配置请重启服务。
 * <p>
 * 加载失败会让服务启动失败 —— 故意如此,避免"配置写错了但服务跑起来"导致 200 响应里偷偷缺模型。
 */
@Service
public class ModelsConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelsConfigService.class);

    /** classpath 内的兜底配置文件名 */
    public static final String CLASSPATH_FALLBACK = "models-config.default.json";

    private final ObjectMapper objectMapper;
    private final ModelsProperties properties;
    private List<ModelConfig> models;

    public ModelsConfigService(ObjectMapper objectMapper, ModelsProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    void load() {
        this.models = loadFromBestSource();
        log.info("加载模型配置完成，共 {} 个模型", models.size());
    }

    /**
     * 获取已加载的模型列表(只读快照)
     */
    public List<ModelConfig> getModels() {
        return models;
    }

    // ============== 内部：按优先级加载 ==============

    private List<ModelConfig> loadFromBestSource() {
        String configured = properties.getConfig().getPath();
        if (configured == null || configured.isBlank()) {
            log.warn("models.config.path 为空，回退到 classpath 内置配置: {}", CLASSPATH_FALLBACK);
            return loadFromClasspath();
        }
        Path path = Paths.get(configured);
        if (!Files.isRegularFile(path)) {
            log.warn("models.config.path 指向的文件不存在 ({})，回退到 classpath 内置配置: {}",
                    path.toAbsolutePath(), CLASSPATH_FALLBACK);
            return loadFromClasspath();
        }
        return loadFromFile(path);
    }

    private List<ModelConfig> loadFromFile(Path path) {
        log.info("加载外部模型配置: {}", path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            return parseAndValidate(in, path.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "加载模型配置失败: " + path.toAbsolutePath() + " — " + e.getMessage(), e);
        }
    }

    private List<ModelConfig> loadFromClasspath() {
        log.info("回退到 classpath 内置配置: {}", CLASSPATH_FALLBACK);
        try (InputStream in = new ClassPathResource(CLASSPATH_FALLBACK).getInputStream()) {
            return parseAndValidate(in, "classpath:" + CLASSPATH_FALLBACK);
        } catch (IOException e) {
            // 兜底文件丢失 = 打包错误,直接挂
            throw new IllegalStateException(
                    "classpath 兜底模型配置丢失: " + CLASSPATH_FALLBACK, e);
        }
    }

    private List<ModelConfig> parseAndValidate(InputStream in, String source) throws IOException {
        ModelsConfig cfg = objectMapper.readValue(in, ModelsConfig.class);
        List<ModelConfig> list = cfg == null ? null : cfg.models();
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("模型配置为空(" + source + "): 请检查配置文件里 models 数组");
        }
        // 校验:id 必须非空且唯一(空配置比错误配置更隐蔽,启动期就拦下)
        var seen = new java.util.HashSet<String>();
        for (ModelConfig m : list) {
            if (m.id() == null || m.id().isBlank()) {
                throw new IllegalStateException("模型配置存在 id 为空的项(" + source + ")");
            }
            if (!seen.add(m.id())) {
                throw new IllegalStateException("模型配置存在重复 id: " + m.id() + " (" + source + ")");
            }
        }
        return List.copyOf(list);
    }
}
