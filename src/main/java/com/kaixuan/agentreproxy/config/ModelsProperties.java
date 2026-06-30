package com.kaixuan.agentreproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * models.* 配置项
 * <p>
 * 当前支持的配置项:
 * <ul>
 *   <li>{@code models.config.path} —— 外部模型配置文件路径(默认 {@code ./modelsConfig.json})</li>
 * </ul>
 * <p>
 * 配合 {@link com.kaixuan.agentreproxy.service.ModelsConfigService} 使用。
 * 见该 service 注释了解加载优先级与环境变量覆盖方式。
 */
@ConfigurationProperties(prefix = "models")
public class ModelsProperties {

    private Config config = new Config();

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public static class Config {
        /**
         * 外部模型配置文件路径(相对工作目录或绝对路径)。未设置或文件不存在时回退到 classpath 内置。
         */
        private String path = "./modelsConfig.json";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
