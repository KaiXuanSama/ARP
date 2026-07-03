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

    /** JWT 模式下的 Domain头 */
    public static final String JWT_DOMAIN = "www.codebuddy.cn";

    // ============== 上游路径常量 ==============
    // 注意：Billing 各端点对 /v2 前缀的兼容性不一致。
    // get-user-resource: /v2 路径同时支持 JWT 与 APIKey；旧路径仅 JWT 可用，APIKey 会401。
    // get-user-request-usage: /v2 路径404；旧路径存在（JWT 可用），暂不切换。

    /**资源包列表 */
    public static final String PATH_USER_RESOURCE = "/v2/billing/meter/get-user-resource";
    /** 时段用量明细（带 total） */
    public static final String PATH_USER_REQUEST_USAGE = "/billing/meter/get-user-request-usage";
    /** 每日签到（带 /v2 前缀） */
    public static final String PATH_DAILY_CHECKIN = "/v2/billing/meter/daily-checkin";

    private UpstreamConstants() {}
}
