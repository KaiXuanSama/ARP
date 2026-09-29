package com.kaixuan.agentreproxy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用配对修复单元测试
 * <p>
 * 钉住的契约：<b>上游对 {@code tool_calls} 与 {@code role:"tool"} 响应是零容忍配对</b>，
 * 声明了却没有响应会返回 {@code 400/11148}。部分下游客户端用工具读图时正是这样发请求的，
 * 本服务必须在其后补一条合成响应，否则整个请求被上游拒绝。
 * <p>
 * 前两个用例直接复刻根目录的真实抓包样本（{@code wb-v5.1.7} 与 {@code copilot}），
 * 第三个用例复刻成功样本（{@code wb-v5.6.2}）以确保<b>不误改</b>正常请求。
 * 这类问题的表现是"上游 400、本地无异常"，靠手工调接口很难定位，必须由测试锁住边界。
 */
class ToolCallPairingServiceTest {

    private static final String PLACEHOLDER = "(see attached image)";

    private final ToolCallPairingService service = new ToolCallPairingService(PLACEHOLDER);

    // ============== 构造工具 ==============

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text");
        b.put("text", text);
        return b;
    }

    private static Map<String, Object> imageBlock() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "image_url");
        b.put("image_url", Map.of("url", "data:image/png;base64,AAAA"));
        return b;
    }

    private static Map<String, Object> assistantDeclaring(String... ids) {
        List<Object> calls = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", "Read");
            fn.put("arguments", "{\"file_path\":\"D:\\\\test\\\\image.png\"}");
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", id);
            call.put("type", "function");
            call.put("function", fn);
            calls.add(call);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", "我来读取这张图片。");
        m.put("tool_calls", calls);
        return m;
    }

    private static Map<String, Object> toolResponse(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", id);
        m.put("content", "(see attached image)");
        return m;
    }

    private static Map<String, Object> userWithBlocks(Object... blocks) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", List.of(blocks));
        return m;
    }

    private static Map<String, Object> stringContent(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> body(Object... messages) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model", "deepseek-v4.1-flash");
        b.put("messages", new ArrayList<>(List.of(messages)));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> messages(Map<String, Object> body) {
        return (List<Object>) body.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> msgAt(Map<String, Object> body, int i) {
        return (Map<String, Object>) messages(body).get(i);
    }

    // ============== 真实样本复刻 ==============

    @Test
    @DisplayName("复刻 wb-v5.1.7：assistant 声明后直接跟 [image_url] 的 user 消息 → 补 tool 响应")
    void repairsWorkbuddy517Shape() {
        // 原始抓包：4 条消息，assistant 声明了 call_00_WC8M... 但全文无任何 tool 响应
        Map<String, Object> body = body(
                stringContent("system", "system prompt"),
                userWithBlocks(textBlock("cwd")),
                assistantDeclaring("call_00_WC8M8YddttYXd5YEZ3oE9874"),
                userWithBlocks(imageBlock()));   // ← 只有 image_url 块

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(1, repaired, "应补 1 条合成 tool 响应");
        List<Object> msgs = messages(body);
        assertEquals(5, msgs.size(), "消息数应从 4 增至 5");

        // 合成响应必须插在 assistant 声明与 user 图片消息之间
        Map<String, Object> inserted = msgAt(body, 3);
        assertEquals("tool", inserted.get("role"));
        assertEquals("call_00_WC8M8YddttYXd5YEZ3oE9874", inserted.get("tool_call_id"));
        assertEquals(PLACEHOLDER, inserted.get("content"));

        // 原有消息未被改动：user 图片消息仍在其后，位置正确
        Map<String, Object> userImg = msgAt(body, 4);
        assertEquals("user", userImg.get("role"));
        assertEquals(List.of("image_url"), blockTypes(userImg));
    }

    @Test
    @DisplayName("复刻 copilot：assistant 声明后跟 [image_url, text] 的 user 消息 → 补 tool 响应")
    void repairsCopilotShape() {
        // 原始抓包：图片块在前、text 块在后（顺序与 v5.1.7 不同，但同样报 11148）
        Map<String, Object> body = body(
                stringContent("system", "system prompt"),
                stringContent("user", "first"),
                stringContent("user", "second"),
                assistantDeclaring("call_00_0UnQvbfYBeJR3UQzgnT93765"),
                userWithBlocks(imageBlock(), textBlock("[Image URI: ...]")));

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(1, repaired);
        assertEquals(6, messages(body).size());

        Map<String, Object> inserted = msgAt(body, 4);
        assertEquals("tool", inserted.get("role"));
        assertEquals("call_00_0UnQvbfYBeJR3UQzgnT93765", inserted.get("tool_call_id"));

        // 块顺序不该被本服务改动 —— 那不是 11148 的成因
        Map<String, Object> userImg = msgAt(body, 5);
        assertEquals(List.of("image_url", "text"), blockTypes(userImg));
    }

    @Test
    @DisplayName("复刻 wb-v5.6.2（正常样本）：已有 tool 响应 → 不做任何修改")
    void leavesValidPairingUntouched() {
        Map<String, Object> body = body(
                stringContent("system", "system prompt"),
                userWithBlocks(textBlock("cwd")),
                assistantDeclaring("call_00_w4eXakLU3v3cF8NZQMOy5731"),
                toolResponse("call_00_w4eXakLU3v3cF8NZQMOy5731"),   // ← 合法应答
                userWithBlocks(textBlock("Attached image(s) from tool result:"), imageBlock()));

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(0, repaired, "配对完整时不应补任何东西");
        assertEquals(5, messages(body).size(), "消息数不应变化");
    }

    // ============== 边界 ==============

    @Test
    @DisplayName("悬空声明后不是带图 user 消息 → 不臆造内容，只留痕")
    void skipsWhenNextUserMessageHasNoImage() {
        Map<String, Object> body = body(
                stringContent("system", "system prompt"),
                assistantDeclaring("call_x"),
                stringContent("user", "纯文本追问"));   // 无图片

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(0, repaired, "非读图场景不应补占位内容");
        assertEquals(3, messages(body).size(), "消息数不应变化");
    }

    @Test
    @DisplayName("无 tool_calls 字段的消息一律跳过")
    void ignoresMessagesWithoutToolCalls() {
        Map<String, Object> body = body(
                stringContent("system", "p"),
                userWithBlocks(textBlock("a"), imageBlock()),
                stringContent("assistant", "普通回复"),
                userWithBlocks(imageBlock()));

        assertEquals(0, service.repairDanglingToolCalls(body));
        assertEquals(4, messages(body).size());
    }

    @Test
    @DisplayName("多图多声明：按声明顺序补多条，且一次补齐")
    void repairsMultipleDanglingCallsInOrder() {
        Map<String, Object> body = body(
                stringContent("system", "p"),
                assistantDeclaring("call_a", "call_b", "call_c"),
                userWithBlocks(imageBlock(), textBlock("t")));

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(3, repaired);
        assertEquals(6, messages(body).size());
        assertEquals("call_a", msgAt(body, 2).get("tool_call_id"));
        assertEquals("call_b", msgAt(body, 3).get("tool_call_id"));
        assertEquals("call_c", msgAt(body, 4).get("tool_call_id"));
        assertEquals("user", msgAt(body, 5).get("role"));
    }

    @Test
    @DisplayName("部分应答：只补缺失的那条，且在已有 tool 消息之后插入")
    void repairsOnlyMissingOnesAndInsertsAfterExistingToolMessages() {
        Map<String, Object> body = body(
                stringContent("system", "p"),
                assistantDeclaring("call_a", "call_b"),
                toolResponse("call_a"),                    // 只应答了 a
                userWithBlocks(imageBlock()));

        int repaired = service.repairDanglingToolCalls(body);

        assertEquals(1, repaired, "只应补缺失的 call_b");
        assertEquals(5, messages(body).size());

        // 插入点必须在已有 tool 响应之后，否则会破坏响应顺序
        assertEquals("call_a", msgAt(body, 2).get("tool_call_id"));
        assertEquals("call_b", msgAt(body, 3).get("tool_call_id"));
        assertEquals("user", msgAt(body, 4).get("role"));
    }

    @Test
    @DisplayName("tool 响应出现在声明之前（乱序）仍算已应答，不重复补")
    void countsOutOfOrderResponseAsAnswered() {
        Map<String, Object> body = body(
                stringContent("system", "p"),
                toolResponse("call_a"),                    // 提前出现（异常但不应重复补）
                assistantDeclaring("call_a"),
                userWithBlocks(imageBlock()));

        assertEquals(0, service.repairDanglingToolCalls(body));
    }

    @Test
    @DisplayName("幂等：重复调用不会叠加合成消息")
    void isIdempotent() {
        Map<String, Object> body = body(
                stringContent("system", "p"),
                assistantDeclaring("call_a"),
                userWithBlocks(imageBlock()));

        assertEquals(1, service.repairDanglingToolCalls(body));
        int afterFirst = messages(body).size();

        assertEquals(0, service.repairDanglingToolCalls(body), "第二次应为空操作");
        assertEquals(afterFirst, messages(body).size(), "消息数不应再变化");
    }

    @Test
    @DisplayName("畸形输入不抛异常（null / 无 messages / 非 List / 空列表）")
    void toleratesMalformedInput() {
        assertEquals(0, service.repairDanglingToolCalls(null));

        Map<String, Object> noMessages = new LinkedHashMap<>();
        noMessages.put("model", "x");
        assertEquals(0, service.repairDanglingToolCalls(noMessages));

        Map<String, Object> notList = new LinkedHashMap<>();
        notList.put("messages", "not-a-list");
        assertEquals(0, service.repairDanglingToolCalls(notList));

        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("messages", new ArrayList<>());
        assertEquals(0, service.repairDanglingToolCalls(empty));

        // 元素不是 Map
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("messages", new ArrayList<>(List.of("string-element", 42)));
        assertEquals(0, service.repairDanglingToolCalls(weird));

        // tool_calls 不是 List
        Map<String, Object> badCalls = new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("tool_calls", "oops");
        badCalls.put("messages", new ArrayList<>(List.of(m, userWithBlocks(imageBlock()))));
        assertEquals(0, service.repairDanglingToolCalls(badCalls));
    }

    @Test
    @DisplayName("声明缺 id 或 id 为空的条目被忽略（无法构造合法响应）")
    void ignoresCallsWithoutUsableId() {
        List<Object> calls = new ArrayList<>();
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("type", "function");
        calls.add(noId);
        Map<String, Object> emptyId = new LinkedHashMap<>();
        emptyId.put("id", "");
        calls.add(emptyId);

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("tool_calls", calls);

        Map<String, Object> body = body(
                stringContent("system", "p"),
                assistant,
                userWithBlocks(imageBlock()));

        assertEquals(0, service.repairDanglingToolCalls(body));
    }

    @Test
    @DisplayName("placeholder 为空白时回退默认值，保证合成响应内容非空")
    void fallsBackToDefaultPlaceholderWhenBlank() {
        ToolCallPairingService blank = new ToolCallPairingService("   ");
        Map<String, Object> body = body(
                stringContent("system", "p"),
                assistantDeclaring("call_a"),
                userWithBlocks(imageBlock()));

        assertEquals(1, blank.repairDanglingToolCalls(body));
        assertEquals(ToolCallPairingService.DEFAULT_TOOL_RESULT_PLACEHOLDER,
                msgAt(body, 2).get("content"));
    }

    @Test
    @DisplayName("不修改原始消息对象本身（仅插入新消息）")
    void doesNotMutateExistingMessages() {
        Map<String, Object> system = stringContent("system", "p");
        Map<String, Object> assistant = assistantDeclaring("call_a");
        Map<String, Object> user = userWithBlocks(imageBlock());
        Map<String, Object> body = body(system, assistant, user);

        service.repairDanglingToolCalls(body);

        // 原对象仍是同一引用，且字段未被改写
        assertSame(system, msgAt(body, 0));
        assertSame(assistant, msgAt(body, 1));
        assertSame(user, msgAt(body, 3));
        assertEquals("assistant", assistant.get("role"));
        assertNotNull(assistant.get("tool_calls"));
        assertEquals(1, ((List<?>) assistant.get("tool_calls")).size());
        assertNull(assistant.get("tool_call_id"), "不应给 assistant 加 tool_call_id");
    }

    /** 取出 content 数组里各块的 type，便于断言顺序未被破坏 */
    @SuppressWarnings("unchecked")
    private static List<String> blockTypes(Map<String, Object> msg) {
        Object content = msg.get("content");
        assertTrue(content instanceof List<?>, "content 应为数组");
        List<String> types = new ArrayList<>();
        for (Object b : (List<Object>) content) {
            types.add(String.valueOf(((Map<String, Object>) b).get("type")));
        }
        return types;
    }
}
