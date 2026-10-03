package com.kaixuan.agentreproxy.constants;

/**
 * 腾讯 CodeBuddy 上游 API 常量
 */
public final class UpstreamConstants {

    /** Chat/模型端点 Base URL（含 /v2） */
    public static final String CHAT_BASE_URL = "https://copilot.tencent.com/v2";

    /** Billing 端点 Base URL（无 /v2） */
    public static final String BILLING_BASE_URL = "https://copilot.tencent.com";

    /**
     * Anthropic Messages 端点 Base URL（含 /v1，<b>注意不是 /v2</b>）
     * <p>
     * 2026-08 实测：上游原生支持 Anthropic 协议，路径为 {@code /v1/messages}。
     * 与 Chat 的 {@code /v2/chat/completions} 前缀不同，极易搞混。
     * 探测结果：{@code /v2/messages}、{@code /messages}、
     * {@code /anthropic/v1/messages} 全部返回 {@code 404 Route Not Found}，
     * 只有 {@code /v1/messages} 可用。
     */
    public static final String ANTHROPIC_BASE_URL = "https://copilot.tencent.com/v1";

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

    /**
     * Anthropic Messages 路径（拼在 {@link #ANTHROPIC_BASE_URL} 之后）
     * <p>
     * 完整 URL：{@code https://copilot.tencent.com/v1/messages}
     */
    public static final String PATH_ANTHROPIC_MESSAGES = "/messages";

    // ============== 插件授权（扫码登录，2026-10 新链路） ==============
    //
    // 背景：官方封堵了浏览器侧 /console/login/enterprise 凭证领取端点，
    // 书签回传方案失效。新链路改为服务端插件授权流（协议参考 codebuddy2api，
    // 经原作者同意移植）：服务端创建 state → 用户浏览器打开登录页扫码 →
    // 服务端轮询领取凭证（token 永不出服务端，无需书签 / 回传 / CORS）。

    /** 插件授权 Base URL（与 BILLING_BASE_URL 相同 host，单独命名以示用途边界） */
    public static final String PLUGIN_AUTH_BASE_URL = "https://copilot.tencent.com";

    /** 创建授权会话：POST，返回 state 与 authUrl；响应带 login-session cookie，轮询时必须携带 */
    public static final String PATH_PLUGIN_AUTH_STATE = "/v2/plugin/auth/state";

    /** 轮询领取凭证：GET；code=11217 表示用户尚未完成登录（pending） */
    public static final String PATH_PLUGIN_AUTH_TOKEN = "/v2/plugin/auth/token";

    /** 领取账号信息：GET，Bearer accessToken，返回 uid / nickname / enterpriseId */
    public static final String PATH_PLUGIN_LOGIN_ACCOUNT = "/v2/plugin/login/account";

    /** 授权平台标识（上游按此区分调用方：桌面端 CLI / 插件等） */
    public static final String LOGIN_PLATFORM = "CLI";

    /** 授权流程请求的 Origin / Referer（伪装官方 CLI 控制面请求） */
    public static final String LOGIN_ORIGIN = "https://www.codebuddy.cn";

    /** state 创建接口返回的授权页 Cookie 名（轮询领取凭证时原样带回） */
    public static final String LOGIN_SESSION_COOKIE = "login-session";

    // ============== 模型目录（2026-10 动态化） ==============
    //
    // GET /v3/config 返回当前账号可用的模型目录（data.models）。
    // 与 Billing 同 host（无 /v2 前缀）；必须带 CLI 控制面 UA（CLI/{v} CodeBuddy/{v}），
    // 否则上游返回业务码 12403 "check ua"。

    /** 模型目录端点路径（拼在 BILLING_BASE_URL 同 host 上） */
    public static final String PATH_MODEL_CONFIG = "/v3/config";

    private UpstreamConstants() {}
}
