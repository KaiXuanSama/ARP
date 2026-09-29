package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.LoginSessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫码登录会话服务 —— 管理 state 与一次性 ticket
 *
 * <h3>背景</h3>
 * 新版 CodeBuddy 客户端的 {@code workbuddy-desktop.info} 对凭证字段启用了
 * 字段级加密（{@code $wbEncrypted} 信封），无法读取。但实测发现登录流程中
 * {@code POST /console/login/enterprise?state=<state>} 会返回<b>明文</b>凭证。
 * <p>
 * 完整链路（2026-09 抓包确认）：
 * <pre>
 * ① 本服务生成 state → 构造登录链接交给用户
 * ② 用户在浏览器打开链接、扫码登录（Keycloak 授权码流程）
 * ③ 服务端（BFF）用 PKCE verifier 兑换 token，并与 state 关联
 * ④ 浏览器调用 /console/login/enterprise?state=<state> 领取明文 token
 * </pre>
 *
 * <h3>为什么需要 ticket</h3>
 * 步骤 ④ 之后的回传是<b>跨域请求</b>（从 {@code codebuddy.cn} 到本服务），
 * 无法携带管理面板 token（存在 localStorage，跨域不带）。因此改用一次性 ticket：
 * <ul>
 *   <li>由本服务在生成登录会话时下发（该动作本身需管理面板 token）</li>
 *   <li>32 字节随机值，5 分钟过期，<b>用后即废</b></li>
 *   <li>仅内存存储 —— 进程重启即失效（可接受，登录流程本就是分钟级操作）</li>
 * </ul>
 * 若无此保护，等于开放了一个「任何人都能往服务器注入账号」的接口。
 *
 * <h3>为什么不校验 state</h3>
 * {@code state} 是 CodeBuddy 侧的关联键，本服务只负责生成与传递，
 * 无法验证其有效性（也不该验证 —— 有效性由上游判定）。
 * 本服务的职责边界是：保证<b>回传请求确实来自我们发起的会话</b>，这由 ticket 完成。
 */
@Service
public class LoginSessionService {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionService.class);

    /** CodeBuddy 登录页基址 */
    private static final String LOGIN_BASE_URL = "https://www.codebuddy.cn/login/";

    /**
     * 客户端版本号参数
     * <p>
     * 抓包确认登录页会读取该参数（当前客户端版本 5.1.7）。
     * 上游若加强校验需同步更新；实测传入后不影响流程。
     */
    private static final String DEFAULT_LOGIN_VERSION = "5.1.7";

    /** ticket 有效期：5 分钟 */
    private static final long TICKET_TTL_MS = 5 * 60 * 1000L;

    /** ticket 字节数（32 字节 → 64 位十六进制字符串） */
    private static final int TICKET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** ticket → 过期时间戳 */
    private final Map<String, Long> ticketStore = new ConcurrentHashMap<>();

    /**
     * 本服务的对外地址
     * <p>
     * 用于构造登录链接里的 {@code arp} 参数（书签脚本据此回传）。
     * 默认取本机地址；Docker / 反向代理部署时通过配置覆盖，
     * 否则会生成让用户浏览器无法访问的回传地址。
     */
    @Value("${custom.login.self-origin:http://localhost:8351}")
    private String selfOrigin;

    /** 登录页版本号（可配置，便于上游更新时快速调整） */
    @Value("${custom.login.version:" + DEFAULT_LOGIN_VERSION + "}")
    private String loginVersion;

    /**
     * 创建一次扫码登录会话
     *
     * @return state / 登录链接 / ticket / 过期时间
     */
    public LoginSessionResponse createSession() {
        cleanExpiredTickets();

        String state = UUID.randomUUID().toString();
        String ticket = generateTicket();
        long expiresAt = System.currentTimeMillis() + TICKET_TTL_MS;
        ticketStore.put(ticket, expiresAt);

        String loginUrl = buildLoginUrl(state, ticket);

        log.info("[扫码登录] 已创建会话 state={} ticket={}... 有效期 {} 分钟",
                state, ticket.substring(0, 8), TICKET_TTL_MS / 60000);
        log.info("[扫码登录] 登录链接: {}", loginUrl);

        return new LoginSessionResponse(state, loginUrl, ticket, expiresAt);
    }

    /**
     * 校验并消费 ticket（一次性）
     * <p>
     * 校验通过后立即从存储中移除 —— 防止同一 ticket 被重复使用。
     *
     * @param ticket 待校验的 ticket
     * @return true = 有效且已消费；false = 无效或已过期
     */
    public boolean consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        Long expiresAt = ticketStore.remove(ticket);   // 原子取出并删除
        if (expiresAt == null) {
            log.warn("[扫码登录] ticket 无效或已被使用: {}...",
                    ticket.length() > 8 ? ticket.substring(0, 8) : ticket);
            return false;
        }
        if (System.currentTimeMillis() > expiresAt) {
            log.warn("[扫码登录] ticket 已过期: {}...",
                    ticket.length() > 8 ? ticket.substring(0, 8) : ticket);
            return false;
        }
        return true;
    }

    /**
     * 构造登录链接
     * <p>
     * 额外附加两个自定义参数供书签脚本使用（CodeBuddy 登录页会忽略未知参数，实测无影响）：
     * <ul>
     *   <li>{@code arp} —— 本服务地址，书签脚本据此知道往哪回传</li>
     *   <li>{@code ticket} —— 一次性回传凭证</li>
     * </ul>
     * 这样用户无需手工输入任何信息，点开链接即可完成全流程。
     */
    private String buildLoginUrl(String state, String ticket) {
        String arp = selfOrigin == null ? "" : selfOrigin.trim();
        // 去掉末尾斜杠，避免拼接出双斜杠
        if (arp.endsWith("/")) {
            arp = arp.substring(0, arp.length() - 1);
        }
        return LOGIN_BASE_URL
                + "?platform=workbuddy"
                + "&state=" + urlEncode(state)
                + "&version=" + urlEncode(loginVersion)
                + "&arp=" + urlEncode(arp)
                + "&ticket=" + urlEncode(ticket);
    }

    /** 生成随机 ticket（32 字节 → 64 位十六进制） */
    private String generateTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 清理过期 ticket，避免长期运行后内存堆积 */
    private void cleanExpiredTickets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = ticketStore.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (it.next().getValue() < now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[扫码登录] 清理过期 ticket {} 个，剩余 {} 个", removed, ticketStore.size());
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 供测试/排查用：当前待使用的 ticket 数量 */
    public int pendingTicketCount() {
        return ticketStore.size();
    }
}
