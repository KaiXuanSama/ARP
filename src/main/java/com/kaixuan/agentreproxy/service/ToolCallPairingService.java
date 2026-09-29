package com.kaixuan.agentreproxy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用配对修复 —— 为下游漏发的工具响应补一条合成 {@code role:"tool"} 消息
 *
 * <h3>为什么需要</h3>
 * 上游对 {@code assistant.tool_calls} 与 {@code role:"tool"} 响应消息的配对<b>零容忍</b>：
 * 声明了 N 个工具调用却没有（或少于）N 条响应，上游直接返回
 * <pre>
 * HTTP 400
 * {"code":11148,"msg":"tool calls and tool results do not match, please start a new conversation and retry"}
 * </pre>
 * 而部分下游客户端在<b>工具返回图片</b>时会打破这个配对 —— 它们把图片当成一条新的
 * {@code user} 消息发出，却漏掉了应答工具调用的 {@code role:"tool"} 消息。
 *
 * <h3>实测对照（2026-09 抓包，根目录四个请求体样本）</h3>
 * <pre>
 * WorkBuddy 5.6.2（✅ 读图成功）
 *   [2] assistant  tool_calls=[call_00_w4eXak...]        ← 声明
 *   [3] tool       tool_call_id=call_00_w4eXak...        ← 应答 ✅
 *   [4] user       [text, image_url]                     ← 图片在 user 消息里
 *
 * WorkBuddy 5.1.7（❌ 11148）        Copilot Chat（❌ 11148）
 *   [2] assistant  tool_calls=[call_00_WC8M...]   [3] assistant  tool_calls=[call_00_0UnQv...]
 *   [3] user       [image_url]                    [4] user       [image_url, text]
 *                  ↑ 无应答 ❌                                    ↑ 无应答 ❌
 * </pre>
 * 两个失败样本的块顺序/有无 text 块<b>互不相同</b>却报同一错误码 ——
 * 说明这是工具配对问题，与 content 块顺序无关。
 *
 * <h3>修复策略（方案 B：还原意图，而非抹掉历史）</h3>
 * 检测到「assistant 声明了 tool_calls 但无任何消息应答」且「其后是带图片的 user 消息」时，
 * 在声明与那条 user 消息<b>之间</b>补一条 {@code role:"tool"} 消息，
 * 内容为占位文本（默认与 5.6.2 客户端实际发送的一致）。
 * <p>
 * 修复后的结构与 5.6.2 的原生合法形态一致，因此保留了"我调用了 Read 工具"的语义 ——
 * 模型知道图是自己读出来的，而不是把这段工具历史抹掉（那样会丢失上下文因果关系）。
 *
 * <h3>刻意保留的保守边界</h3>
 * <ul>
 *   <li>只处理「后续是<b>带图</b> user 消息」的场景。若悬空声明后不是带图消息，
 *       不臆造内容（占位文本会与真实语义不符），只记 warn 留痕 ——
 *       此时上游仍会返回 11148，但日志能直接指出原因</li>
 *   <li>不修改、不删除任何已有消息与字段，纯粹做插入</li>
 *   <li>图片块<b>只按 {@code type == "image_url"} 判定</b>。抓包确认下游发的
 *       text / image_url 块都带 {@code type} 字段，无需靠"有无 image_url 键"猜</li>
 * </ul>
 *
 * <h3>已知限制：多条声明时的顺序</h3>
 * 合成响应<b>追加在已有 tool 响应之后</b>，不做重排。因此当且仅当下游已发的响应
 * 本身按声明顺序排列时，最终结果是上游要求的顺序。
 * <p>
 * 不重排是刻意的取舍：移动已有消息比对消息做插入的风险大得多，而"客户端发出了乱序的
 * 工具响应"这一场景本身就会被上游拒绝（{@code tool 响应出现在声明之前 → 400}），
 * 本服务不去猜测客户端意图。真实抓包里这一路径从未触发。
 *
 * <h3>失败不阻断</h3>
 * 任何异常只记 warn，请求原样发出（与 {@link RequestTextReplaceService} 的约定一致）。
 */
@Service
public class ToolCallPairingService {

    /**
     * 合成工具响应的默认内容
     * <p>
     * 取值与上游认可的 WorkBuddy 5.6.2 客户端实际发送的内容一致。该占位文本只用于告诉模型
     * "这次工具调用有结果"，真正的结果（图片）在紧随其后的 user 消息里。
     */
    static final String DEFAULT_TOOL_RESULT_PLACEHOLDER = "(see attached image)";

    private static final Logger log = LoggerFactory.getLogger(ToolCallPairingService.class);

    private final String toolResultPlaceholder;

    public ToolCallPairingService(
            @Value("${custom.chat.tool-result-placeholder:" + DEFAULT_TOOL_RESULT_PLACEHOLDER + "}")
            String toolResultPlaceholder) {
        // 空串会让上游把工具结果视为空内容，故回退到默认值
        this.toolResultPlaceholder = (toolResultPlaceholder == null || toolResultPlaceholder.isBlank())
                ? DEFAULT_TOOL_RESULT_PLACEHOLDER
                : toolResultPlaceholder;
    }

    /**
     * 扫描 {@code messages}，为"下游声明了却从未应答"的 tool_call 补合成响应（原地修改）
     *
     * @param body OpenAI 格式请求体
     * @return 实际补发的工具响应条数（0 = 无需修复或未命中触发条件）
     */
    @SuppressWarnings("unchecked")
    public int repairDanglingToolCalls(Map<String, Object> body) {
        if (body == null) {
            return 0;
        }
        Object rawMessages = body.get("messages");
        if (!(rawMessages instanceof List<?>)) {
            return 0;
        }
        List<Object> messages = (List<Object>) rawMessages;
        if (messages.isEmpty()) {
            return 0;
        }

        try {
            // 第一遍：全量收集"已被应答"的 tool_call_id。
            // 必须全量而不是只看相邻消息 —— 不同客户端把 tool 消息放在哪儿并不统一。
            Set<String> answered = collectAnsweredIds(messages);

            // 第二遍：定位悬空声明并按需补发
            int repaired = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof Map<?, ?> msg)) {
                    continue;
                }
                List<String> dangling = findDanglingIds((Map<String, Object>) msg, answered);
                if (dangling.isEmpty()) {
                    continue;
                }

                // 跳过声明之后连续排列的 tool 消息 —— 插入点应落在"工具结果之后"，
                // 否则会把新响应插到已有响应前面，破坏响应顺序
                int insertAt = skipToolMessages(messages, i + 1);
                if (!isImageUserMessage(messages, insertAt)) {
                    // 非"工具读图"场景：不臆造占位内容，只留痕（上游仍会 400/11148）
                    log.warn("[工具配对] 发现 {} 条悬空 tool_call，但后续不是带图 user 消息，"
                            + "未做修复（上游将返回 11148）: {}", dangling.size(), dangling);
                    continue;
                }

                List<Object> synthetic = buildToolMessages(dangling);
                messages.addAll(insertAt, synthetic);
                repaired += synthetic.size();
                // 跳过刚插入的部分，避免对合成消息重复扫描
                i = insertAt + synthetic.size() - 1;
            }

            if (repaired > 0) {
                log.info("[工具配对] 已补发 {} 条合成 role=tool 响应（内容「{}」），规避上游 11148",
                        repaired, toolResultPlaceholder);
            }
            return repaired;
        } catch (Exception e) {
            // 修复失败不应影响请求本身 —— 原样发出，由上游给出真实错因
            log.warn("[工具配对] 修复过程异常，请求原样发出: {}", e.getMessage());
            return 0;
        }
    }

    /** 收集全量已被应答的 tool_call_id（任何 role 带 tool_call_id 都算，不限于 role=tool） */
    private static Set<String> collectAnsweredIds(List<Object> messages) {
        Set<String> answered = new HashSet<>();
        for (Object item : messages) {
            if (item instanceof Map<?, ?> m
                    && m.get("tool_call_id") instanceof String id && !id.isEmpty()) {
                answered.add(id);
            }
        }
        return answered;
    }

    /** 找出该消息里"声明了但无人应答"的 tool_call id（保持声明顺序） */
    private static List<String> findDanglingIds(Map<String, Object> msg, Set<String> answered) {
        Object rawCalls = msg.get("tool_calls");
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) {
            return List.of();
        }
        List<String> dangling = new ArrayList<>();
        for (Object c : calls) {
            if (c instanceof Map<?, ?> call
                    && call.get("id") instanceof String id && !id.isEmpty()
                    && !answered.contains(id)) {
                dangling.add(id);
            }
        }
        return dangling;
    }

    /** 从 {@code from} 起跳过连续排列的 {@code role:"tool"} 消息，返回首个非 tool 消息的下标 */
    private static int skipToolMessages(List<Object> messages, int from) {
        int i = from;
        while (i < messages.size()) {
            if (messages.get(i) instanceof Map<?, ?> m && "tool".equals(m.get("role"))) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /** 指定下标处是否为"content 是含图片块的数组"的 user 消息 */
    private static boolean isImageUserMessage(List<Object> messages, int index) {
        if (index < 0 || index >= messages.size()) {
            return false;
        }
        if (!(messages.get(index) instanceof Map<?, ?> m) || !"user".equals(m.get("role"))) {
            return false;
        }
        return containsImageBlock(m.get("content"));
    }

    /**
     * content 数组里是否含图片块
     * <p>
     * 只认 {@code type == "image_url"} —— 抓包确认下游发的 text / image_url 块都带
     * {@code type} 字段，因此不需要"有无 image_url 键"这种兜底启发式。
     */
    private static boolean containsImageBlock(Object content) {
        if (!(content instanceof List<?> blocks)) {
            return false;
        }
        for (Object b : blocks) {
            if (b instanceof Map<?, ?> blk && "image_url".equals(blk.get("type"))) {
                return true;
            }
        }
        return false;
    }

    /** 为每条悬空 id 构造一条合成工具响应（顺序与声明一致） */
    private List<Object> buildToolMessages(List<String> ids) {
        List<Object> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "tool");
            m.put("tool_call_id", id);
            m.put("content", toolResultPlaceholder);
            out.add(m);
        }
        return out;
    }
}
