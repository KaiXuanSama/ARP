package com.kaixuan.agentreproxy.constants;

/**
 * 腾讯 CodeBuddy 上游 API 常量
 */
public final class UpstreamConstants {

    /** Chat/模型端点 Base URL（含 /v2） */
    public static final String CHAT_BASE_URL = "https://copilot.tencent.com/v2";

    /** Billing 端点 Base URL（无 /v2） */
    public static final String BILLING_BASE_URL = "https://copilot.tencent.com";

    /** 请求超时（秒） */
    public static final int TIMEOUT_SECONDS = 60;

    /** JWT 模式下的 Domain 头 */
    public static final String JWT_DOMAIN = "www.codebuddy.cn";

    private UpstreamConstants() {}
}
