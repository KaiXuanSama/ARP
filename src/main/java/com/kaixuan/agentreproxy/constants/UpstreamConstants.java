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

    // ============== 上游路径常量 ==============
    // 重要：所有 Billing 端点路径都不带 /v2 前缀
    //      （Antigravity 文档里都标了但容易遗漏，已通过抓包验证）

    /** 资源包列表 */
    public static final String PATH_USER_RESOURCE = "/billing/meter/get-user-resource";
    /** 时段用量明细（带 total） */
    public static final String PATH_USER_REQUEST_USAGE = "/billing/meter/get-user-request-usage";
    /** 每日签到（带 /v2 前缀） */
    public static final String PATH_DAILY_CHECKIN = "/v2/billing/meter/daily-checkin";

    private UpstreamConstants() {}
}
