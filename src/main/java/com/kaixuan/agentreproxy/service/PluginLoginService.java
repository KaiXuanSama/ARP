package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.constants.UpstreamConstants;
import com.kaixuan.agentreproxy.dto.PluginLoginPollResponse;
import com.kaixuan.agentreproxy.dto.PluginLoginSessionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 插件授权登录服务 —— 扫码登录新链路（2026-10，替换已失效的书签回传方案）
 *
 * <h3>背景</h3>
 * 官方封堵了浏览器侧 {@code /console/login/enterprise} 凭证领取端点，书签回传链路失效。
 * 新链路改为<b>服务端插件授权流</b>（协议参考 codebuddy2api 的 browser_login.py，经原作者
 * 同意移植）—— 这是官方 CLI 插件自己的授权协议，服务端伪装官方 CLI 控制面请求：
 * <pre>
 * ① POST /v2/plugin/auth/state?platform=CLI  → 拿 state + authUrl（响应带 login-session cookie）
 * ② 用户浏览器打开 authUrl 扫码登录（本服务不再需要书签 / 回传 / CORS）
 * ③ GET  /v2/plugin/auth/token?state=...     → 轮询领取凭证（code=11217 表示尚未登录）
 * ④ GET  /v2/plugin/login/account?state=...  → Bearer accessToken 换 uid/nickname
 * ⑤ 落库（复用 AccountSaveService，同 uid 覆盖）
 * </pre>
 * <b>凭证永不出服务端</b>：poll 端点对前端只返回 uid/nickname/expiresAt，不返回 token。
 *
 * <h3>与 codebuddy2api 的行为对齐点</h3>
 * <ul>
 *   <li>authUrl 严格校验（防钓鱼）：https + host 必须等于上游后端 host + path=/login +
 *       query 的 state 与响应字段一致，任一不符即报错，不把可疑链接交给用户</li>
 *   <li>轮询节流 3 秒（nextPollAt），并有防重入锁 —— 并发 poll 直接返回 pending，
 *       不对上游叠加请求</li>
 *   <li>token 到期归一化：{@code expiresAt}（绝对秒/毫秒）优先于 {@code expiresIn}
 *       （相对秒），统一毫秒；缺失或已过期视为无效凭证</li>
 *   <li>flow 生命周期：内存存储（重启即失效，登录流程本就是分钟级操作）、TTL 5 分钟、
 *       同一 owner 重新发起会取消旧会话、全局并发上限 16</li>
 *   <li>落库幂等：成功结果缓存在 flow 里，重复 poll 返回同一结果、不重复落库</li>
 * </ul>
 *
 * <h3>与 codebuddy2api 的差异（技术栈决定）</h3>
 * <ul>
 *   <li>它是 FastAPI + httpx（带 cookie jar），本项目是 WebFlux + WebClient（无 cookie jar）——
 *       手动提取响应 {@code Set-Cookie} 的 {@code login-session} 值，轮询时手动拼 {@code Cookie} 头</li>
 *   <li>全链路 Reactor，禁止 {@code .block()}；DB 落库用 {@code boundedElastic} 包裹</li>
 * </ul>
 */
@Service
public class PluginLoginService {

    private static final Logger log = LoggerFactory.getLogger(PluginLoginService.class);

    /** 会话有效期：5 分钟（对齐 codebuddy2api 的 TTL） */
    private static final long SESSION_TTL_MS = 5 * 60 * 1000L;

    /** 全局并发授权会话上限（防资源耗尽；超限报错让用户稍后再试） */
    private static final int MAX_FLOWS = 16;

    /** 上游轮询节流间隔（毫秒）：与前端轮询周期（3s）一致 */
    private static final long POLL_INTERVAL_MS = 3000L;

    /** 「尚未登录完成」的业务码（上游约定） */
    private static final int CODE_LOGIN_PENDING = 11217;

    /** 「业务成功」码（上游信封约定 code=0） */
    private static final int CODE_OK = 0;

    private final WebClient webClient;
    private final AccountSaveService accountSaveService;
    private final ObjectMapper objectMapper;
    private final String cliVersion;

    /**
     * 插件授权上游 Base URL（默认取 {@link UpstreamConstants#PLUGIN_AUTH_BASE_URL}）。
     * <p>
     * 实例字段而非直接用常量，是为了测试时指向 MockWebServer（包内测试可用
     * ReflectionTestUtils 覆盖）；生产环境永远不会变。
     */
    private String pluginBaseUrl = UpstreamConstants.PLUGIN_AUTH_BASE_URL;

    private final SecureRandom random = new SecureRandom();

    /** fid → flow（内存存储；重启即失效，可接受） */
    private final Map<String, Flow> flows = new ConcurrentHashMap<>();

    /** 单个授权会话的状态（仅服务端可见，绝不返回 token 给前端） */
    static final class Flow {
        final String owner;
        final String upstreamState;
        final String loginUrl;
        final String loginSessionCookie;
        final long expiresAt;
        /** 上游轮询节流：早于该时间戳的 poll 直接返回 pending（不打上游） */
        volatile long nextPollAt;
        /** 防重入：一次只允许一个 poll 真正打上游 */
        final AtomicBoolean polling = new AtomicBoolean(false);
        /** 领取到的凭证（落库前暂存；落库成功后清空并缓存结果） */
        volatile Map<String, Object> tokens;
        volatile String state;
        /** 落库成功的最终结果（幂等缓存） */
        volatile PluginLoginPollResponse saved;
        /** 已过期标记（expired 时置位，之后一律返回 expired） */
        volatile boolean expired;

        Flow(String owner, String upstreamState, String loginUrl, String loginSessionCookie, long expiresAt) {
            this.owner = owner;
            this.upstreamState = upstreamState;
            this.loginUrl = loginUrl;
            this.loginSessionCookie = loginSessionCookie;
            this.expiresAt = expiresAt;
        }
    }

    public PluginLoginService(WebClient.Builder webClientBuilder,
                              AccountSaveService accountSaveService,
                              ObjectMapper objectMapper,
                              @Value("${custom.login.cli-version:2.159.0}") String cliVersion) {
        this.webClient = webClientBuilder.clone().build();
        this.accountSaveService = accountSaveService;
        this.objectMapper = objectMapper;
        this.cliVersion = cliVersion;
    }

    // ============== ① 创建授权会话 ==============

    /**
     * 创建一次授权会话：向上游拿 state 与官方登录链接。
     * <p>
     * owner 是管理面板 token 的哈希 —— 同一用户重新发起会取消其旧会话（对齐
     * codebuddy2api 的「重新生成链接会取消同一管理会话此前的授权等待」）。
     *
     * @throws IllegalStateException 上游不可用 / 返回格式异常 / authUrl 校验失败
     */
    public Mono<PluginLoginSessionResponse> startSession(String owner) {
        return Mono.fromCallable(() -> {
            cleanExpiredFlows();
            cancelOwnerFlows(owner);
            if (flows.size() >= MAX_FLOWS) {
                throw new IllegalStateException("等待授权的会话过多，请稍后重试");
            }
            return owner;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(this::createFlowWithUpstream);
    }

    private Mono<PluginLoginSessionResponse> createFlowWithUpstream(String owner) {
        String uri = pluginBaseUrl + UpstreamConstants.PATH_PLUGIN_AUTH_STATE
                + "?platform=" + UpstreamConstants.LOGIN_PLATFORM;
        return webClient.post()
                .uri(URI.create(uri))
                .headers(h -> applyControlHeaders(h))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> handleStateResponse(owner, resp.headers().asHttpHeaders(),
                                resp.statusCode().value(), body)))
                .timeout(Duration.ofSeconds(20));
    }

    /** 解析 state 接口响应：信封校验 → authUrl 安全校验 → 建 flow（阻塞方法，调用方在 boundedElastic） */
    private PluginLoginSessionResponse handleStateResponse(String owner, HttpHeaders headers,
                                                           int statusCode, String body) {
        if (statusCode != 200) {
            log.warn("[插件授权] 创建会话失败：上游 HTTP {} body={}", statusCode, snippet(body));
            throw new IllegalStateException("授权服务暂时不可用，请稍后重试");
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[插件授权] 创建会话失败：响应非 JSON body={}", snippet(body));
            throw new IllegalStateException("授权服务返回格式异常，请重新生成链接");
        }
        JsonNode data = envelope.path("data");
        if (envelope.path("code").asInt(-1) != CODE_OK || !data.isObject()) {
            throw new IllegalStateException("无法生成授权链接，请稍后重试");
        }
        String upstreamState = data.path("state").asText(null);
        String authUrl = data.path("authUrl").asText(null);
        if (upstreamState == null || upstreamState.isBlank() || upstreamState.length() > 2048
                || authUrl == null || authUrl.isBlank() || authUrl.length() > 8192) {
            throw new IllegalStateException("授权服务缺少有效的登录信息");
        }
        verifyAuthUrl(authUrl, upstreamState);

        // 提取 login-session cookie（WebClient 无 cookie jar，手动保存、轮询手动带回）
        String cookie = extractCookie(headers);
        if (cookie == null) {
            log.warn("[插件授权] 上游响应未带 {} cookie，后续轮询可能 401", UpstreamConstants.LOGIN_SESSION_COOKIE);
        }

        String fid = generateFlowId();
        long expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        flows.put(fid, new Flow(owner, upstreamState, authUrl, cookie, expiresAt));
        log.info("[插件授权] 已创建会话 fid={}... owner={} 有效期 {} 分钟",
                fid.substring(0, Math.min(8, fid.length())), shortOwner(owner), SESSION_TTL_MS / 60000);
        return new PluginLoginSessionResponse(fid, authUrl, expiresAt, (int) (POLL_INTERVAL_MS / 1000));
    }

    /**
     * authUrl 安全校验（防钓鱼/防篡改）：协议 https、host 与上游后端一致、path=/login、
     * query 的 state 与响应字段一致。任一不符都视为上游异常，不把可疑链接交给用户。
     */
    private void verifyAuthUrl(String authUrl, String expectedState) {
        URI uri;
        try {
            uri = URI.create(authUrl);
        } catch (Exception e) {
            throw new IllegalStateException("授权链接校验失败，请重新生成");
        }
        URI backend = URI.create(pluginBaseUrl);
        boolean ok = "https".equals(uri.getScheme())
                && backend.getHost().equals(uri.getHost())
                && portMatchesBackend(uri, backend)
                && uri.getUserInfo() == null
                && "/login".equals(uri.getPath())
                && expectedState.equals(singleQueryParam(uri, "state"));
        if (!ok) {
            log.warn("[插件授权] authUrl 校验失败：{}", authUrl);
            throw new IllegalStateException("授权链接校验失败，请重新生成");
        }
    }

    /**
     * authUrl 端口与后端一致：生产后端是 https 默认端口（-1 或 443），
     * 测试指向 MockWebServer 时用其真实端口。协议语义不变：
     * https + 与后端同 host 同端口。
     */
    private static boolean portMatchesBackend(URI authUrl, URI backend) {
        int authPort = authUrl.getPort() == -1 ? 443 : authUrl.getPort();
        int backendPort = backend.getPort() == -1 ? 443 : backend.getPort();
        return authPort == backendPort;
    }

    private static String singleQueryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        List<String> hits = new java.util.ArrayList<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (k.equals(name)) {
                hits.add(v);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private String extractCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        for (String sc : setCookies) {
            if (sc.startsWith(UpstreamConstants.LOGIN_SESSION_COOKIE + "=")) {
                // 只取 name=value 段（分号后是 Path/Max-Age 等属性）
                int semi = sc.indexOf(';');
                return semi < 0 ? sc : sc.substring(0, semi);
            }
        }
        return null;
    }

    // ============== ②③ 轮询领取凭证 ==============

    /**
     * 轮询一次授权状态。
     * <p>
     * 节流：距上次真实轮询不足 3 秒时直接返回 pending（不打上游）。
     * 防重入：一个 flow 同时只有一个 poll 在打上游，并发请求直接 pending。
     * 幂等：成功落库后结果缓存，重复 poll 返回同一结果、不重复落库。
     *
     * @return pending / success / expired / error（见 {@link PluginLoginPollResponse}）
     */
    public Mono<PluginLoginPollResponse> pollSession(String owner, String fid) {
        Flow flow = flows.get(fid);
        if (flow == null || !flow.owner.equals(owner)) {
            // owner 不匹配视为不存在（不泄露其它用户会话的存在性）
            return Mono.just(PluginLoginPollResponse.expired());
        }
        if (flow.saved != null) {
            return Mono.just(flow.saved);
        }
        if (flow.expired || flow.expiresAt <= System.currentTimeMillis()) {
            flow.expired = true;
            removeIfDone(fid, flow);
            return Mono.just(PluginLoginPollResponse.expired());
        }
        long now = System.currentTimeMillis();
        if (now < flow.nextPollAt || !flow.polling.compareAndSet(false, true)) {
            // 节流窗口内 / 已有并发 poll 在途：直接 pending
            return Mono.just(PluginLoginPollResponse.pending());
        }
        flow.nextPollAt = now + POLL_INTERVAL_MS;
        return doPoll(fid, flow)
                .doFinally(sig -> flow.polling.set(false));
    }

    private Mono<PluginLoginPollResponse> doPoll(String fid, Flow flow) {
        return fetchTokens(flow)
                .flatMap(optTokens -> {
                    if (optTokens.isEmpty()) {
                        return Mono.just(PluginLoginPollResponse.pending());
                    }
                    Map<String, Object> tokens = optTokens.get();
                    flow.tokens = tokens;

                    // 先做本地校验（domain + 有效期归一化），全部通过才花一次上游调用拿账号信息。
                    // 对齐 codebuddy2api 的行为：发现不可用凭证立即报错，不浪费后续请求。
                    String domainError = validateDomain(tokens);
                    if (domainError != null) {
                        return Mono.just(PluginLoginPollResponse.error(domainError));
                    }
                    long now = System.currentTimeMillis();
                    long expiresAt = resolveExpiresAt(tokens, "expiresAt", "expiresIn", now);
                    Long refreshExpiresAt = tryResolveExpiresAt(tokens, "refreshExpiresAt",
                            "refreshExpiresIn", now);
                    if (expiresAt <= 0 || expiresAt <= now) {
                        return Mono.just(PluginLoginPollResponse.error("授权服务没有返回有效的令牌到期时间"));
                    }
                    return fetchAccountAndSave(fid, flow, tokens, expiresAt, refreshExpiresAt);
                })
                .onErrorResume(e -> {
                    log.warn("[插件授权] 轮询失败 fid={}...: {}", shortFid(fid), e.getMessage());
                    return Mono.just(PluginLoginPollResponse.error(
                            "授权状态暂时无法获取，请稍后重试"));
                });
    }

    /**
     * GET /v2/plugin/auth/token：code=11217 → empty（pending）；code=0 → 凭证 map。
     * 其它 code 视为上游异常抛错。
     * <p>
     * 用 Optional 包装：Reactor 的 map/flatMap 不允许返回 null（会报
     * "mapper returned a null value"），pending 语义必须显式表达。
     */
    private Mono<java.util.Optional<Map<String, Object>>> fetchTokens(Flow flow) {
        String uri = pluginBaseUrl + UpstreamConstants.PATH_PLUGIN_AUTH_TOKEN
                + "?state=" + urlEncode(flow.upstreamState);
        return webClient.get()
                .uri(URI.create(uri))
                .headers(h -> {
                    applyControlHeaders(h);
                    applySessionCookie(h, flow);
                })
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> java.util.Optional.ofNullable(
                                parseTokenEnvelope(resp.statusCode().value(), body))));
    }

    /** 解析 token 信封；返回 null 表示尚未登录完成（pending）。阻塞方法，调用方在响应线程。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTokenEnvelope(int statusCode, String body) {
        if (statusCode != 200) {
            throw new IllegalStateException("上游 HTTP " + statusCode);
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("授权服务返回格式异常");
        }
        int code = envelope.path("code").asInt(-1);
        if (code == CODE_LOGIN_PENDING) {
            return null;
        }
        if (code != CODE_OK) {
            throw new IllegalStateException("上游授权未成功（code=" + code + "），请重新生成链接并登录");
        }
        JsonNode data = envelope.path("data");
        if (!data.isObject() || data.path("accessToken").asText("").isBlank()
                || data.path("refreshToken").asText("").isBlank()) {
            throw new IllegalStateException("授权服务没有返回完整凭据，请重新登录");
        }
        return objectMapper.convertValue(data, Map.class);
    }

    /** GET /v2/plugin/login/account（Bearer accessToken）→ 落库 */
    private Mono<PluginLoginPollResponse> fetchAccountAndSave(String fid, Flow flow,
                                                              Map<String, Object> tokens,
                                                              long expiresAt, Long refreshExpiresAt) {
        String accessToken = String.valueOf(tokens.get("accessToken"));
        String uri = pluginBaseUrl + UpstreamConstants.PATH_PLUGIN_LOGIN_ACCOUNT
                + "?state=" + urlEncode(flow.upstreamState);
        return webClient.get()
                .uri(URI.create(uri))
                .headers(h -> {
                    applyControlHeaders(h);
                    applySessionCookie(h, flow);
                    h.setBearerAuth(accessToken);
                })
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> parseAccountEnvelope(resp.statusCode().value(), body)))
                .timeout(Duration.ofSeconds(20))
                .flatMap(acct -> saveFlowResult(fid, flow, tokens, acct, expiresAt, refreshExpiresAt));
    }

    /** 解析账号信封：必须有 uid。阻塞方法，调用方在响应线程。 */
    private JsonNode parseAccountEnvelope(int statusCode, String body) {
        if (statusCode != 200) {
            throw new IllegalStateException("上游 HTTP " + statusCode);
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("授权服务返回格式异常");
        }
        JsonNode acct = envelope.path("data");
        if (envelope.path("code").asInt(-1) != CODE_OK || !acct.isObject()
                || acct.path("uid").asText("").isBlank()) {
            throw new IllegalStateException("授权已完成，但账号信息暂未获取成功，请稍后重试");
        }
        return acct;
    }

    /**
     * domain 校验：本服务仅支持国内版。上游返回其它区域（如 workbuddy.ai）说明
     * 登录到了别的站点，拒绝落库。合法（空 = 国内桌面端惯例）返回 null，否则返回错误文案。
     */
    private static String validateDomain(Map<String, Object> tokens) {
        String domain = tokens.get("domain") == null ? "" : String.valueOf(tokens.get("domain")).trim();
        if (!domain.isEmpty() && !UpstreamConstants.JWT_DOMAIN.equalsIgnoreCase(domain)) {
            return "授权账号属于不支持的站点（" + domain + "），请使用 codebuddy.cn 账号登录";
        }
        return null;
    }

    /**
     * 构造 account_json → 落库 → 缓存成功结果、清理 flow 中的敏感字段。
     * <p>
     * domain 与有效期校验已在 {@link #doPoll} 完成（先本地校验再打上游），
     * 这里只负责落库。落库走 boundedElastic（JDBC 阻塞操作不在事件循环上）。
     */
    private Mono<PluginLoginPollResponse> saveFlowResult(String fid, Flow flow,
                                                         Map<String, Object> tokens, JsonNode acct,
                                                         long expiresAt, Long refreshExpiresAt) {
        return Mono.fromCallable(() -> {
                    long now = System.currentTimeMillis();
                    if (flow.expiresAt <= now) {
                        flow.expired = true;
                        return PluginLoginPollResponse.expired();
                    }

                    String uid = acct.path("uid").asText();
                    String nickname = acct.path("nickname").asText(null);
                    String domain = tokens.get("domain") == null ? "" : String.valueOf(tokens.get("domain")).trim();

                    // account_json 与桌面端 .info 解析结果同构（account / auth 嵌套），
                    // 这样 AuthCredential 的既有解析路径无需改动
                    Map<String, Object> auth = new java.util.LinkedHashMap<>();
                    auth.put("accessToken", String.valueOf(tokens.get("accessToken")));
                    auth.put("refreshToken", String.valueOf(tokens.get("refreshToken")));
                    auth.put("domain", domain.isEmpty() ? UpstreamConstants.JWT_DOMAIN : domain);
                    auth.put("expiresAt", expiresAt);
                    if (refreshExpiresAt != null) {
                        auth.put("refreshExpiresAt", refreshExpiresAt);
                    }
                    Map<String, Object> account = new java.util.LinkedHashMap<>();
                    account.put("uid", uid);
                    String enterpriseId = acct.path("enterpriseId").asText("");
                    account.put("enterpriseId", enterpriseId);
                    account.put("nickname", nickname == null ? "" : nickname);
                    Map<String, Object> doc = Map.of("account", account, "auth", auth);
                    String accountJson = objectMapper.writeValueAsString(doc);

                    log.info("[插件授权] 会话成功 fid={}... uid={} 落库中", shortFid(fid), uid);
                    accountSaveService.save(uid, accountJson,
                            String.valueOf(tokens.get("accessToken")), null);

                    // 敏感字段用后即清；缓存成功结果保证幂等（token 不进缓存）
                    flow.tokens = null;
                    flow.state = null;
                    PluginLoginPollResponse result = PluginLoginPollResponse.success(
                            uid, nickname, expiresAt);
                    flow.saved = result;
                    return result;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 归一化到期时间：绝对时间优先（秒/毫秒自适应，阈值 1e11 —— 约 5138 年的秒数，
     * 之上按毫秒处理），否则用相对秒数推算。非法返回 0。
     */
    private static long resolveExpiresAt(Map<String, Object> tokens, String atKey, String inKey, long now) {
        Long v = tryResolveExpiresAt(tokens, atKey, inKey, now);
        return v == null ? 0L : v;
    }

    private static Long tryResolveExpiresAt(Map<String, Object> tokens, String atKey, String inKey, long now) {
        Object at = tokens.get(atKey);
        if (at instanceof Number n && n.doubleValue() > 0) {
            return toMillis(n.doubleValue());
        }
        if (at instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                if (d > 0) {
                    return toMillis(d);
                }
            } catch (NumberFormatException ignored) {
                // 落到 expiresIn 分支
            }
        }
        Object in = tokens.get(inKey);
        if (in instanceof Number n && n.doubleValue() > 0) {
            return now + (long) (n.doubleValue() * 1000);
        }
        if (in instanceof String s && !s.isBlank()) {
            try {
                double d = Double.parseDouble(s.trim());
                if (d > 0) {
                    return now + (long) (d * 1000);
                }
            } catch (NumberFormatException ignored) {
                // 无有效到期信息
            }
        }
        return null;
    }

    /** 秒/毫秒自适应：小于 1e11 视为秒（乘 1000），否则视为毫秒原样返回；返回 long 毫秒 */
    private static long toMillis(double value) {
        return value < 100_000_000_000L ? (long) (value * 1000) : (long) value;
    }

    // ============== ④ 取消会话 ==============

    /** 取消授权会话（owner 校验通过才删）。幂等：不存在也返回完成。 */
    public void cancelSession(String owner, String fid) {
        Flow flow = flows.get(fid);
        if (flow != null && flow.owner.equals(owner)) {
            flows.remove(fid, flow);
            log.info("[插件授权] 会话已取消 fid={}...", shortFid(fid));
        }
    }

    // ============== 内部工具 ==============

    private void cancelOwnerFlows(String owner) {
        flows.entrySet().removeIf(e -> e.getValue().owner.equals(owner));
    }

    private void cleanExpiredFlows() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Flow>> it = flows.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Flow f = it.next().getValue();
            if (f.expiresAt <= now && f.saved == null) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[插件授权] 清理过期会话 {} 个，剩余 {}", removed, flows.size());
        }
    }

    private void removeIfDone(String fid, Flow flow) {
        if (flow.expired) {
            flows.remove(fid, flow);
        }
    }

    /** 控制面伪装头（对齐 codebuddy2api control_headers + 浏览器授权的 Origin/Referer/Accept） */
    private void applyControlHeaders(HttpHeaders h) {
        String ua = "CLI/" + cliVersion + " CodeBuddy/" + cliVersion;
        h.set(HttpHeaders.USER_AGENT, ua);
        h.set(HttpHeaders.ORIGIN, UpstreamConstants.LOGIN_ORIGIN);
        h.set(HttpHeaders.REFERER, UpstreamConstants.LOGIN_ORIGIN + "/");
        h.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
        h.set("X-Requested-With", "XMLHttpRequest");
        h.set("X-Product", "SaaS");
    }

    private void applySessionCookie(HttpHeaders h, Flow flow) {
        if (flow.loginSessionCookie != null) {
            h.set(HttpHeaders.COOKIE, flow.loginSessionCookie);
        }
    }

    private String generateFlowId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private static String shortFid(String fid) {
        return fid == null ? "" : fid.substring(0, Math.min(8, fid.length()));
    }

    private static String shortOwner(String owner) {
        return owner == null ? "" : owner.substring(0, Math.min(8, owner.length()));
    }

    /** 供测试/排查用：当前存活的 flow 数 */
    public int flowCount() {
        return flows.size();
    }
}
