package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic 请求体 → OpenAI 请求体 转换器
 *
 * <h3>为什么需要</h3>
 * Claude Code 等客户端<b>硬编码走 Anthropic 协议</b>（{@code /v1/messages}），无法改用 OpenAI 端点。
 * 而上游的 Anthropic 端点<b>不返回 {@code credit} 字段</b>（实测确认），导致本服务无法统计积分消耗、
 * {@code credit_limit} 形同虚设。
 * <p>
 * 解决方案：下游说 Anthropic 协议，本服务转成 OpenAI 协议调上游（能拿到 credit），
 * 再把响应流翻译回 Anthropic 格式（见 {@link OpenAiToAnthropicStreamConverter}）。
 *
 * <h3>关键映射</h3>
 * <table border="1">
 *   <tr><th>Anthropic</th><th>OpenAI</th><th>备注</th></tr>
 *   <tr><td>{@code system}（顶层字符串/数组）</td><td>{@code messages[0]} role=system</td><td>提到消息列表最前</td></tr>
 *   <tr><td>{@code messages[].content} 数组</td><td>字符串 或 tool 消息</td><td>见下</td></tr>
 *   <tr><td>{@code tools[].input_schema}</td><td>{@code tools[].function.parameters}</td><td>包一层 function</td></tr>
 *   <tr><td>{@code tool_result} 块</td><td>role=tool 消息</td><td>需拆成独立消息</td></tr>
 *   <tr><td>{@code tool_use} 块</td><td>{@code assistant.tool_calls}</td><td>input 序列化为 arguments 字符串</td></tr>
 *   <tr><td>{@code thinking} 配置</td><td>{@code reasoning_effort}</td><td><b>思维链开关</b></td></tr>
 * </table>
 *
 * <h3>reasoning_effort（关键，实测发现）</h3>
 * 上游 OpenAI 端点<b>只有带 {@code reasoning_effort} 时才输出 {@code reasoning_content}</b>（思维链）。
 * 单一变量对照实验（2026-08，48 tools + 8735 字符 system 完全一致）：
 * <ul>
 *   <li>带 {@code reasoning_effort: "xhigh"} → 思维链 190 字符，{@code completion_thinking_tokens=40}，credit 0.03</li>
 *   <li>删掉该字段 → 思维链 <b>0 字符</b>，{@code completion_thinking_tokens=0}，credit <b>0.47</b></li>
 * </ul>
 * 注意后者 credit 反而高 15 倍（推测走了不同计费档位，样本量小未定论）。
 * <p>
 * 因此本转换器<b>默认注入 {@code reasoning_effort}</b>，保证 Claude Code 能看到思维链
 * （这是 Claude Code 的核心体验，丢了等于功能降级）。
 */
@Component
public class AnthropicToOpenAiRequestConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicToOpenAiRequestConverter.class);

    /**
     * 默认的 reasoning_effort 取值
     * <p>
     * Anthropic 协议用 {@code thinking: {type:"enabled", budget_tokens:N}} 表达思考预算，
     * 与 OpenAI 的枚举字符串没有精确对应关系。这里按 budget_tokens 粗略分档，
     * 未配置 thinking 时用本默认值 —— 保证思维链默认开启。
     */
    private static final String DEFAULT_REASONING_EFFORT = "high";

    private final ObjectMapper objectMapper;

    public AnthropicToOpenAiRequestConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 转换 Anthropic 请求体为 OpenAI 请求体
     *
     * @param anthropicBody 下游发来的 Anthropic 标准请求体
     * @return OpenAI 格式请求体，可直接发给上游 {@code /v2/chat/completions}
     */
    public Map<String, Object> convert(Map<String, Object> anthropicBody) {
        Map<String, Object> out = new LinkedHashMap<>();

        // ---- 基础字段 ----
        out.put("model", anthropicBody.get("model"));
        // 上游只接受 stream=true（本服务的 OpenAI 端点也是这么强制的）
        out.put("stream", true);
        out.put("stream_options", Map.of("include_usage", true));

        // max_tokens：Anthropic 必填，OpenAI 可选
        if (anthropicBody.get("max_tokens") instanceof Number n) {
            out.put("max_tokens", n.intValue());
        }
        // 采样参数直接透传（两边同名同义）
        copyIfPresent(anthropicBody, out, "temperature");
        copyIfPresent(anthropicBody, out, "top_p");
        copyIfPresent(anthropicBody, out, "stop_sequences", "stop");

        // ---- 思维链开关（见类注释）----
        out.put("reasoning_effort", resolveReasoningEffort(anthropicBody));

        // ---- messages（含 system 前置）----
        out.put("messages", buildMessages(anthropicBody));

        // ---- tools ----
        List<Map<String, Object>> tools = convertTools(anthropicBody.get("tools"));
        if (!tools.isEmpty()) {
            out.put("tools", tools);
            Object choice = convertToolChoice(anthropicBody.get("tool_choice"));
            if (choice != null) {
                out.put("tool_choice", choice);
            }
        }
        return out;
    }

    /**
     * 把 Anthropic 的 {@code thinking} 配置映射为 OpenAI 的 {@code reasoning_effort}
     * <p>
     * Anthropic：{@code {"thinking": {"type": "enabled", "budget_tokens": 10000}}}
     * <br>
     * OpenAI：{@code "reasoning_effort": "low" | "medium" | "high" | "xhigh"}
     * <p>
     * 两者语义不完全对应，按 budget_tokens 粗略分档。
     * {@code type: "disabled"} 时返回 null 语义 —— 但注意上游不带该字段就不输出思维链，
     * 所以"禁用思考"直接不设置本字段即可。
     */
    private String resolveReasoningEffort(Map<String, Object> body) {
        Object thinking = body.get("thinking");
        if (!(thinking instanceof Map<?, ?> tm)) {
            // 下游没指定 → 默认开启（Claude Code 依赖思维链展示）
            return DEFAULT_REASONING_EFFORT;
        }
        Object type = tm.get("type");
        if ("disabled".equals(type)) {
            // 显式禁用：返回 low（上游没有"关闭"档；完全不传会让思维链消失，
            // 但那样 credit 反而更高，见类注释 —— 故用最低档而非不传）
            return "low";
        }
        Object budget = tm.get("budget_tokens");
        if (budget instanceof Number n) {
            int b = n.intValue();
            if (b <= 2048) return "low";
            if (b <= 8192) return "medium";
            if (b <= 24576) return "high";
            return "xhigh";
        }
        return DEFAULT_REASONING_EFFORT;
    }

    /**
     * 构造 OpenAI messages 列表
     * <p>
     * 处理三件事：
     * <ol>
     *   <li>Anthropic 的顶层 {@code system} → OpenAI 的第一条 role=system 消息</li>
     *   <li>content 数组拍平：{@code text} 块合并为字符串</li>
     *   <li>{@code tool_use} → {@code assistant.tool_calls}；
     *       {@code tool_result} → 独立的 role=tool 消息</li>
     * </ol>
     */
    private List<Map<String, Object>> buildMessages(Map<String, Object> body) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // 1) system 前置
        String system = extractSystemText(body.get("system"));
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }

        // 2) 逐条转换
        Object raw = body.get("messages");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    convertOneMessage(m, messages);
                }
            }
        }

        // 3) 上游要求 messages >= 2（本服务 OpenAI 端点的既有校验）。
        //    只有一条 user 消息时补一条最简 system，避免上游 400。
        //    注意：这会略微影响 prompt cache 命中，但比请求失败好。
        if (messages.size() < 2) {
            messages.add(0, Map.of("role", "system", "content", "You are a helpful assistant."));
        }
        return messages;
    }

    /**
     * 转换单条 Anthropic 消息，结果可能产生<b>多条</b> OpenAI 消息
     * （因为 tool_result 必须拆成独立的 role=tool 消息）
     */
    private void convertOneMessage(Map<?, ?> msg, List<Map<String, Object>> out) {
        String role = msg.get("role") instanceof String r ? r : "user";
        Object content = msg.get("content");

        // content 是纯字符串 —— 最简单的情况
        if (content instanceof String s) {
            out.add(Map.of("role", role, "content", s));
            return;
        }
        if (!(content instanceof List<?> blocks)) {
            out.add(Map.of("role", role, "content", ""));
            return;
        }

        // content 是块数组：需要按块类型分别处理
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();

        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> block)) {
                continue;
            }
            String type = block.get("type") instanceof String t ? t : "";
            switch (type) {
                case "text" -> {
                    Object t = block.get("text");
                    if (t instanceof String s && !s.isEmpty()) {
                        text.append(s);
                    }
                }
                case "thinking" -> {
                    // 历史轮次里的思维链不回传给上游 —— OpenAI 协议没有对应字段，
                    // 且回传会浪费 token。Anthropic 官方也允许省略。
                }
                case "tool_use" -> toolCalls.add(convertToolUse(block));
                case "tool_result" -> toolResults.add(convertToolResult(block));
                case "image" ->
                    // 图片块：OpenAI 用 content 数组 + image_url，当前上游模型多为纯文本，
                    // 暂不支持，记日志避免静默丢失
                    log.warn("暂不支持 image 块转换，已跳过");
                default -> log.debug("未知 content 块类型，跳过: {}", type);
            }
        }

        // tool_result 必须作为独立的 role=tool 消息，且要放在 assistant 消息之后
        if (!toolResults.isEmpty()) {
            out.addAll(toolResults);
        }

        // 有 tool_use → assistant 消息带 tool_calls
        if (!toolCalls.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "assistant");
            // OpenAI 要求 content 存在（可为空串）
            m.put("content", text.toString());
            m.put("tool_calls", toolCalls);
            out.add(m);
            return;
        }

        // 普通文本消息（tool_result 已单独加过，这里避免重复加空消息）
        if (text.length() > 0 || toolResults.isEmpty()) {
            out.add(Map.of("role", role, "content", text.toString()));
        }
    }

    /** Anthropic tool_use 块 → OpenAI tool_calls 项 */
    private Map<String, Object> convertToolUse(Map<?, ?> block) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", str(block.get("name")));
        // input 是对象，OpenAI 要求 arguments 是 JSON 字符串
        Object input = block.get("input");
        String args = "{}";
        if (input != null) {
            try {
                args = objectMapper.writeValueAsString(input);
            } catch (Exception e) {
                log.warn("tool_use.input 序列化失败，降级空对象: {}", e.getMessage());
            }
        }
        fn.put("arguments", args);

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", str(block.get("id")));
        call.put("type", "function");
        call.put("function", fn);
        return call;
    }

    /** Anthropic tool_result 块 → OpenAI role=tool 消息 */
    private Map<String, Object> convertToolResult(Map<?, ?> block) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", str(block.get("tool_use_id")));
        // content 可能是字符串，也可能是块数组
        Object c = block.get("content");
        m.put("content", flattenToText(c));
        return m;
    }

    /** Anthropic tools → OpenAI tools（包一层 function） */
    private List<Map<String, Object>> convertTools(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> t)) {
                continue;
            }
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", str(t.get("name")));
            Object desc = t.get("description");
            fn.put("description", desc instanceof String s ? s : "");
            // input_schema → parameters（结构本身兼容 JSON Schema，直接搬）
            Object schema = t.get("input_schema");
            fn.put("parameters", schema != null ? schema
                    : Map.of("type", "object", "properties", Map.of()));

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            out.add(tool);
        }
        return out;
    }

    /**
     * Anthropic tool_choice → OpenAI tool_choice
     * <ul>
     *   <li>{@code {type:"auto"}} → {@code "auto"}</li>
     *   <li>{@code {type:"any"}} → {@code "required"}</li>
     *   <li>{@code {type:"tool", name:"x"}} → {@code {type:"function", function:{name:"x"}}}</li>
     * </ul>
     */
    private Object convertToolChoice(Object raw) {
        if (!(raw instanceof Map<?, ?> tc)) {
            return null;
        }
        String type = str(tc.get("type"));
        return switch (type) {
            case "auto" -> "auto";
            case "any" -> "required";
            case "none" -> "none";
            case "tool" -> Map.of("type", "function",
                    "function", Map.of("name", str(tc.get("name"))));
            default -> null;
        };
    }

    /**
     * 提取 system 文本
     * <p>
     * Anthropic 的 system 可以是字符串，也可以是 {@code [{type:"text", text:"..."}]} 数组
     */
    private String extractSystemText(Object system) {
        if (system == null) {
            return null;
        }
        if (system instanceof String s) {
            return s;
        }
        return flattenToText(system);
    }

    /** 把「字符串 或 块数组」拍平成纯文本 */
    private String flattenToText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object t = m.get("text");
                    if (t instanceof String s) {
                        sb.append(s);
                    }
                } else if (item instanceof String s) {
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        copyIfPresent(from, to, key, key);
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to,
            String fromKey, String toKey) {
        Object v = from.get(fromKey);
        if (v != null) {
            to.put(toKey, v);
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s : (o == null ? "" : String.valueOf(o));
    }
}
