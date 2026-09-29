package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.LoginSessionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginSessionService} 单元测试 —— 钉住 ticket 的安全契约
 * <p>
 * ticket 是「扫码登录回传」这条链路的唯一鉴权手段（该端点被 AuthWebFilter
 * 放行，不校验管理面板 token）。因此它的安全性直接决定：
 * <b>外部是否可能往本服务注入任意账号</b>。
 * <p>
 * 纯 POJO 测试，不启动 Spring 容器。
 */
class LoginSessionServiceTest {

    private LoginSessionService newService() {
        LoginSessionService svc = new LoginSessionService();
        // @Value 注入的字段在纯 POJO 测试中需手动设置
        ReflectionTestUtils.setField(svc, "selfOrigin", "http://localhost:8351");
        ReflectionTestUtils.setField(svc, "loginVersion", "5.1.7");
        return svc;
    }

    @Test
    @DisplayName("创建会话：返回完整字段，登录链接包含 state / arp / ticket")
    void createSession() {
        LoginSessionService svc = newService();
        LoginSessionResponse s = svc.createSession();

        assertNotNull(s.state(), "state 不能为空");
        assertNotNull(s.ticket(), "ticket 不能为空");
        assertEquals(64, s.ticket().length(), "ticket 应为 32 字节的十六进制（64 字符）");
        assertTrue(s.expiresAt() > System.currentTimeMillis(), "过期时间应在未来");

        String url = s.loginUrl();
        assertTrue(url.startsWith("https://www.codebuddy.cn/login/"), "应指向 CodeBuddy 登录页");
        assertTrue(url.contains("state=" + s.state()), "链接应携带 state");
        assertTrue(url.contains("platform=workbuddy"), "应带 platform 参数");
        // arp 与 ticket 是给书签脚本用的自定义参数，必须经过 URL 编码
        assertTrue(url.contains("arp=http%3A%2F%2Flocalhost%3A8351"),
                "arp 参数应为 URL 编码后的本服务地址，实际=" + url);
        assertTrue(url.contains("ticket=" + s.ticket()), "链接应携带 ticket");
    }

    @Test
    @DisplayName("ticket 一次性：第二次使用必须失败")
    void ticketIsSingleUse() {
        LoginSessionService svc = newService();
        String ticket = svc.createSession().ticket();

        assertTrue(svc.consumeTicket(ticket), "首次使用应成功");
        assertFalse(svc.consumeTicket(ticket), "第二次使用必须失败（一次性）");
    }

    @Test
    @DisplayName("ticket 随机性：两次会话的 state 与 ticket 都不同")
    void ticketsAreRandom() {
        LoginSessionService svc = newService();
        LoginSessionResponse a = svc.createSession();
        LoginSessionResponse b = svc.createSession();

        assertNotEquals(a.state(), b.state(), "state 必须每次不同");
        assertNotEquals(a.ticket(), b.ticket(), "ticket 必须每次不同");
    }

    @Test
    @DisplayName("非法 ticket：null / 空串 / 伪造值一律拒绝")
    void invalidTicketsRejected() {
        LoginSessionService svc = newService();
        svc.createSession();   // 建一个合法会话作为干扰

        assertFalse(svc.consumeTicket(null), "null 应拒绝");
        assertFalse(svc.consumeTicket(""), "空串应拒绝");
        assertFalse(svc.consumeTicket("   "), "空白串应拒绝");
        assertFalse(svc.consumeTicket("deadbeef".repeat(8)), "伪造 ticket 应拒绝");
        assertFalse(svc.consumeTicket("a".repeat(64)), "长度正确但值错误应拒绝");
    }

    @Test
    @DisplayName("ticket 过期：超过 TTL 后拒绝")
    void expiredTicketRejected() {
        LoginSessionService svc = newService();
        String ticket = svc.createSession().ticket();

        // 直接篡改存储中的过期时间（避免真的等 5 分钟）
        @SuppressWarnings("unchecked")
        var store = (java.util.Map<String, Long>)
                ReflectionTestUtils.getField(svc, "ticketStore");
        assertNotNull(store);
        store.put(ticket, System.currentTimeMillis() - 1000);   // 设为 1 秒前过期

        assertFalse(svc.consumeTicket(ticket), "过期 ticket 必须拒绝");
    }

    @Test
    @DisplayName("多个 ticket 互不干扰：各自独立有效")
    void multipleTicketsIndependent() {
        LoginSessionService svc = newService();
        String t1 = svc.createSession().ticket();
        String t2 = svc.createSession().ticket();

        assertEquals(2, svc.pendingTicketCount(), "应有 2 个待使用 ticket");

        assertTrue(svc.consumeTicket(t1), "t1 应有效");
        assertEquals(1, svc.pendingTicketCount(), "消费 t1 后剩 1 个");

        assertTrue(svc.consumeTicket(t2), "t2 不受 t1 影响，仍应有效");
        assertEquals(0, svc.pendingTicketCount(), "全部消费完");
    }

    @Test
    @DisplayName("创建会话时顺带清理过期 ticket（防止内存堆积）")
    void cleanupOnCreate() {
        LoginSessionService svc = newService();

        // 先塞两个已过期的
        @SuppressWarnings("unchecked")
        var store = (java.util.Map<String, Long>)
                ReflectionTestUtils.getField(svc, "ticketStore");
        assertNotNull(store);
        long past = System.currentTimeMillis() - 10_000;
        store.put("expired-1", past);
        store.put("expired-2", past);
        assertEquals(2, svc.pendingTicketCount());

        // 新建会话应触发清理
        svc.createSession();
        assertEquals(1, svc.pendingTicketCount(), "过期 ticket 应被清理，只剩新建的 1 个");
    }

    @Test
    @DisplayName("selfOrigin 末尾斜杠：不会拼出双斜杠")
    void trailingSlashHandled() {
        LoginSessionService svc = newService();
        ReflectionTestUtils.setField(svc, "selfOrigin", "https://arp.example.com/");

        String url = svc.createSession().loginUrl();
        // https://arp.example.com/ → 去掉尾斜杠再编码
        assertTrue(url.contains("arp=https%3A%2F%2Farp.example.com"),
                "应去掉末尾斜杠，实际=" + url);
        assertFalse(url.contains("arp=https%3A%2F%2Farp.example.com%2F&"),
                "不应出现双斜杠");
    }
}
