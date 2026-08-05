package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.dto.TextReplaceSettingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 请求文本替换引擎
 * <p>
 * 在请求发往上游<b>之前</b>，按用户配置的规则对请求体中的文本字段做替换。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>不内置任何规则</b> —— 规则完全由使用者在管理面板配置，存于
 *       {@code app_settings.request.textReplace}。本服务只提供机制，不预设用途。</li>
 *   <li><b>范围可控</b> —— 每条规则可限定作用域（仅 system / 仅 user / 工具描述等），
 *       避免误伤。<b>工具名、参数名、model 字段永不修改</b>，因为改了会直接破坏功能。</li>
 *   <li><b>失败不阻断</b> —— 单条规则出错（如正则非法）只跳过该条并记日志，
 *       不影响请求本身。宁可少替换，也不能让用户的请求发不出去。</li>
 * </ul>
 *
 * <h3>作用位置</h3>
 * 同时服务于 OpenAI 与 Anthropic 两个端点：
 * <ul>
 *   <li>OpenAI：{@code messages[].content}、{@code tools[].function.description}</li>
 *   <li>Anthropic：{@code system}、{@code messages[].content}（含块数组）、
 *       {@code tools[].description}</li>
 * </ul>
 *
 * <h3>⚠️ 使用者责任</h3>
 * 本引擎是通用文本处理工具。使用者需自行确保所配置的规则符合上游服务条款
 * 与所在司法辖区的法律法规。项目作者不对具体规则的用途负责。
 */
@Service
public class RequestTextReplaceService {

    private static final Logger log = LoggerFactory.getLogger(RequestTextReplaceService.class);

    /** app_settings 中的 key */
    public static final String KEY_TEXT_REPLACE = "request.textReplace";

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    /**
     * 是否打印替换前后的完整请求体（排查用，默认关闭）
     * <p>
     * 与 chunk 日志<b>共用同一个开关</b> {@code custom.chunk-log.enabled}，
     * 一次打开就能把“请求发什么 → 替换成什么 → 上游回什么”一整条链路看完。
     * <p>
     * <b>日志量很大</b>（单个请求体可能上万字符），仅排查时临时开启。
     */
    @org.springframework.beans.factory.annotation.Value("${custom.chunk-log.enabled:false}")
    private boolean requestLogEnabled;

    public RequestTextReplaceService(SettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对 OpenAI 格式的请求体应用替换规则（原地修改）
     *
     * @param body OpenAI 请求体
     * @return 实际发生的替换次数（0 = 未启用或无命中）
     */
    @SuppressWarnings("unchecked")
    public int applyToOpenAiBody(Map<String, Object> body) {
        List<CompiledRule> rules = loadRules();
        if (rules.isEmpty() || body == null) {
            // 即使没规则也打印原始体 —— 排查时需要知道“到底发了什么”，
            // 而不只是“改了什么”。没规则时不打印“替换后”，避免重复刷屏。
            logBody("OpenAI", "原始（无生效规则，未做任何替换）", body);
            return 0;
        }
        logBody("OpenAI", "替换前", body);
        int count = 0;

        // messages[].content
        Object msgs = body.get("messages");
        if (msgs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> msg = (Map<String, Object>) m;
                String role = msg.get("role") instanceof String r ? r : "";
                Object content = msg.get("content");
                if (content instanceof String s) {
                    Result res = applyAll(rules, s, scopeOfRole(role));
                    if (res.changed()) {
                        msg.put("content", res.text());
                        count += res.count();
                    }
                }
            }
        }

        // tools[].function.description
        Object tools = body.get("tools");
        if (tools instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> t)) {
                    continue;
                }
                Object fnObj = ((Map<String, Object>) t).get("function");
                if (fnObj instanceof Map<?, ?> f) {
                    Map<String, Object> fn = (Map<String, Object>) f;
                    Object desc = fn.get("description");
                    if (desc instanceof String s) {
                        Result res = applyAll(rules, s, ScopeTarget.TOOL_DESC);
                        if (res.changed()) {
                            fn.put("description", res.text());
                            count += res.count();
                        }
                    }
                }
            }
        }
        if (count > 0) {
            log.info("[文本替换] OpenAI 请求体共替换 {} 处", count);
            logBody("OpenAI", "替换后", body);
        } else {
            log.debug("[文本替换] OpenAI 请求体无命中（生效规则 {} 条）", rules.size());
        }
        return count;
    }

    /**
     * 对 Anthropic 格式的请求体应用替换规则（原地修改）
     * <p>
     * 与 OpenAI 的差异：
     * <ul>
     *   <li>{@code system} 是顶层字段（可能是字符串或块数组）</li>
     *   <li>{@code messages[].content} 可能是块数组（{@code [{type:"text", text:"..."}]}）</li>
     *   <li>{@code tools[].description} 不包在 function 里</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public int applyToAnthropicBody(Map<String, Object> body) {
        List<CompiledRule> rules = loadRules();
        if (rules.isEmpty() || body == null) {
            logBody("Anthropic", "原始（无生效规则，未做任何替换）", body);
            return 0;
        }
        logBody("Anthropic", "替换前", body);
        int count = 0;

        // 顶层 system
        Object system = body.get("system");
        if (system instanceof String s) {
            Result res = applyAll(rules, s, ScopeTarget.SYSTEM);
            if (res.changed()) {
                body.put("system", res.text());
                count += res.count();
            }
        } else if (system instanceof List<?> blocks) {
            count += applyToBlocks(rules, (List<Object>) blocks, ScopeTarget.SYSTEM);
        }

        // messages[].content
        Object msgs = body.get("messages");
        if (msgs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> msg = (Map<String, Object>) m;
                String role = msg.get("role") instanceof String r ? r : "";
                ScopeTarget target = scopeOfRole(role);
                Object content = msg.get("content");
                if (content instanceof String s) {
                    Result res = applyAll(rules, s, target);
                    if (res.changed()) {
                        msg.put("content", res.text());
                        count += res.count();
                    }
                } else if (content instanceof List<?> blocks) {
                    count += applyToBlocks(rules, (List<Object>) blocks, target);
                }
            }
        }

        // tools[].description
        Object tools = body.get("tools");
        if (tools instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> t) {
                    Map<String, Object> tool = (Map<String, Object>) t;
                    Object desc = tool.get("description");
                    if (desc instanceof String s) {
                        Result res = applyAll(rules, s, ScopeTarget.TOOL_DESC);
                        if (res.changed()) {
                            tool.put("description", res.text());
                            count += res.count();
                        }
                    }
                }
            }
        }
        if (count > 0) {
            log.info("[文本替换] Anthropic 请求体共替换 {} 处", count);
            logBody("Anthropic", "替换后", body);
        } else {
            log.debug("[文本替换] Anthropic 请求体无命中（生效规则 {} 条）", rules.size());
        }
        return count;
    }

    /**
     * 打印完整请求体（排查用）
     * <p>
     * 只在 {@code custom.chunk-log.enabled=true} 时输出。为了便于肉眼对比，
     * 除了完整 JSON，还单独把 <b>system 与首条 user 消息</b>抽出来展示 ——
     * 实测中触发上游内容检测的内容基本都在这两处，而完整 JSON 往往上万字符、
     * 在日志里很难直接看出差异。
     */
    private void logBody(String protocol, String stage, Map<String, Object> body) {
        if (!requestLogEnabled || body == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            log.info("[请求体-{}-{}] 总长={} 字符\n{}", protocol, stage, json.length(), json);
            logKeyFields(protocol, stage, body);
        } catch (Exception e) {
            log.warn("[请求体-{}-{}] 序列化失败: {}", protocol, stage, e.getMessage());
        }
    }

    /** 抽出 system / 首条 user 单独展示，方便对比替换效果 */
    @SuppressWarnings("unchecked")
    private void logKeyFields(String protocol, String stage, Map<String, Object> body) {
        // Anthropic 的顶层 system
        Object topSystem = body.get("system");
        if (topSystem instanceof String s) {
            log.info("[请求体-{}-{}] system(顶层, {} 字符): {}",
                    protocol, stage, s.length(), abbreviate(s));
        } else if (topSystem instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> m && m.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            log.info("[请求体-{}-{}] system(顶层块数组, {} 字符): {}",
                    protocol, stage, sb.length(), abbreviate(sb.toString()));
        }

        // messages 里的 system / 首条 user
        Object msgs = body.get("messages");
        if (!(msgs instanceof List<?> list)) {
            return;
        }
        boolean userLogged = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> msg = (Map<String, Object>) m;
            String role = msg.get("role") instanceof String r ? r : "";
            String text = flattenToText(msg.get("content"));
            if ("system".equals(role)) {
                log.info("[请求体-{}-{}] messages.system ({} 字符): {}",
                        protocol, stage, text.length(), abbreviate(text));
            } else if ("user".equals(role) && !userLogged) {
                log.info("[请求体-{}-{}] messages.user首条 ({} 字符): {}",
                        protocol, stage, text.length(), abbreviate(text));
                userLogged = true;
            }
        }
    }

    /** 长文本截断：头尾各留一段（触发词通常在开头，但尾部也可能有线索） */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        final int head = 500;
        final int tail = 200;
        if (s.length() <= head + tail) {
            return s;
        }
        return s.substring(0, head)
                + "\n  ...(省略 " + (s.length() - head - tail) + " 字符)...\n  "
                + s.substring(s.length() - tail);
    }

    /**
     * 把 content 拍平成纯文本（仅用于日志展示）
     * <p>
     * content 可能是字符串，也可能是 Anthropic 的块数组
     * {@code [{type:"text", text:"..."}]}。
     */
    private static String flattenToText(Object content) {
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

    /** 处理 content 块数组里的 text 字段 */
    @SuppressWarnings("unchecked")
    private int applyToBlocks(List<CompiledRule> rules, List<Object> blocks, ScopeTarget target) {
        int count = 0;
        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> bm)) {
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) bm;
            Object text = block.get("text");
            if (text instanceof String s) {
                Result res = applyAll(rules, s, target);
                if (res.changed()) {
                    block.put("text", res.text());
                    count += res.count();
                }
            }
        }
        return count;
    }

    /** 对单段文本依次应用所有适用规则 */
    private Result applyAll(List<CompiledRule> rules, String text, ScopeTarget target) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        String current = text;
        int total = 0;
        for (CompiledRule rule : rules) {
            if (!rule.appliesTo(target)) {
                continue;
            }
            try {
                var matcher = rule.pattern().matcher(current);
                StringBuilder sb = new StringBuilder();
                int n = 0;
                while (matcher.find()) {
                    matcher.appendReplacement(sb,
                            java.util.regex.Matcher.quoteReplacement(rule.replacement()));
                    n++;
                }
                if (n > 0) {
                    matcher.appendTail(sb);
                    current = sb.toString();
                    total += n;
                    log.debug("[文本替换] 规则「{}」命中 {} 次", rule.name(), n);
                }
            } catch (Exception e) {
                // 单条规则失败不影响其他规则
                log.warn("[文本替换] 规则「{}」执行失败，已跳过: {}", rule.name(), e.getMessage());
            }
        }
        return new Result(current, total);
    }

    /** 按消息 role 映射到作用域目标 */
    private static ScopeTarget scopeOfRole(String role) {
        return switch (role == null ? "" : role.toLowerCase()) {
            case "system" -> ScopeTarget.SYSTEM;
            case "user" -> ScopeTarget.USER;
            default -> ScopeTarget.OTHER_MESSAGE;
        };
    }

    /**
     * 从 app_settings 读取并编译规则
     * <p>
     * 每次调用都重新读取 —— 保证管理面板改完规则<b>立即生效</b>，无需重启。
     * 单次 chat 请求只调用一次，开销可接受（一次 SQLite 主键查询）。
     */
    private List<CompiledRule> loadRules() {
        try {
            var setting = settingsService.getOne(KEY_TEXT_REPLACE).orElse(null);
            if (setting == null || setting.value() == null) {
                return List.of();
            }
            TextReplaceSettingRequest cfg =
                    objectMapper.convertValue(setting.value(), TextReplaceSettingRequest.class);
            if (cfg == null || !Boolean.TRUE.equals(cfg.enabled()) || cfg.rules() == null) {
                return List.of();
            }
            List<CompiledRule> out = new ArrayList<>();
            for (var r : cfg.rules()) {
                CompiledRule cr = compile(r);
                if (cr != null) {
                    out.add(cr);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[文本替换] 规则加载失败，本次不做替换: {}", e.getMessage());
            return List.of();
        }
    }

    /** 编译单条规则；非法规则返回 null（跳过而非抛异常） */
    private CompiledRule compile(TextReplaceSettingRequest.Rule r) {
        if (r == null || r.pattern() == null || r.pattern().isEmpty()) {
            return null;
        }
        if (Boolean.FALSE.equals(r.enabled())) {
            return null;
        }
        try {
            String raw = r.pattern();
            boolean isRegex = Boolean.TRUE.equals(r.regex());
            String patternStr = isRegex ? raw : Pattern.quote(raw);
            int flags = Boolean.TRUE.equals(r.caseSensitive()) ? 0 : Pattern.CASE_INSENSITIVE;
            Pattern p = Pattern.compile(patternStr, flags);
            var scope = TextReplaceSettingRequest.Scope.from(r.scope());
            String name = (r.name() == null || r.name().isBlank()) ? "(未命名)" : r.name();
            String replacement = r.replacement() == null ? "" : r.replacement();
            return new CompiledRule(name, p, replacement, scope);
        } catch (PatternSyntaxException e) {
            log.warn("[文本替换] 规则「{}」正则非法，已跳过: {}", r.name(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[文本替换] 规则「{}」编译失败，已跳过: {}", r.name(), e.getMessage());
            return null;
        }
    }

    /** 校验规则配置的合法性（供保存时调用，非法直接抛异常拒绝保存） */
    public void validate(TextReplaceSettingRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (req.enabled() == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        if (req.rules() == null) {
            return;
        }
        if (req.rules().size() > 100) {
            throw new IllegalArgumentException("规则数量不能超过 100 条");
        }
        for (int i = 0; i < req.rules().size(); i++) {
            var r = req.rules().get(i);
            String at = "第 " + (i + 1) + " 条规则";
            if (r == null) {
                throw new IllegalArgumentException(at + "为空");
            }
            if (r.pattern() == null || r.pattern().isEmpty()) {
                throw new IllegalArgumentException(at + "的匹配内容不能为空");
            }
            if (r.pattern().length() > 2000) {
                throw new IllegalArgumentException(at + "的匹配内容过长（上限 2000 字符）");
            }
            if (r.replacement() != null && r.replacement().length() > 2000) {
                throw new IllegalArgumentException(at + "的替换内容过长（上限 2000 字符）");
            }
            if (Boolean.TRUE.equals(r.regex())) {
                try {
                    Pattern.compile(r.pattern());
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(at + "的正则表达式非法: " + e.getDescription());
                }
            }
            // scope 合法性（非法会抛 IllegalArgumentException）
            TextReplaceSettingRequest.Scope.from(r.scope());
        }
    }

    /**
     * 预览规则效果（不落库、不发上游）
     *
     * @param req  规则配置
     * @param text 样例文本
     * @param scopeStr 模拟的作用域
     * @return 替换后的文本
     */
    public Map<String, Object> preview(TextReplaceSettingRequest req, String text, String scopeStr) {
        validate(req);
        List<CompiledRule> rules = new ArrayList<>();
        if (Boolean.TRUE.equals(req.enabled()) && req.rules() != null) {
            for (var r : req.rules()) {
                CompiledRule cr = compile(r);
                if (cr != null) {
                    rules.add(cr);
                }
            }
        }
        ScopeTarget target = switch (scopeStr == null ? "system" : scopeStr.toLowerCase()) {
            case "user" -> ScopeTarget.USER;
            case "tool_desc" -> ScopeTarget.TOOL_DESC;
            default -> ScopeTarget.SYSTEM;
        };
        Result res = applyAll(rules, text == null ? "" : text, target);
        return Map.of(
                "original", text == null ? "" : text,
                "result", res.text(),
                "replaceCount", res.count(),
                "activeRules", rules.size());
    }

    // ============== 内部类型 ==============

    /** 文本在请求体中的位置 */
    private enum ScopeTarget {
        SYSTEM, USER, OTHER_MESSAGE, TOOL_DESC
    }

    /** 编译后的规则 */
    private record CompiledRule(
            String name,
            Pattern pattern,
            String replacement,
            TextReplaceSettingRequest.Scope scope
    ) {
        boolean appliesTo(ScopeTarget target) {
            return switch (scope) {
                case ALL_MESSAGES -> target != ScopeTarget.TOOL_DESC;
                case SYSTEM_ONLY -> target == ScopeTarget.SYSTEM;
                case USER_ONLY -> target == ScopeTarget.USER;
                case TOOL_DESCRIPTIONS -> target == ScopeTarget.TOOL_DESC;
                case MESSAGES_AND_TOOLS -> true;
            };
        }
    }

    /** 替换结果 */
    private record Result(String text, int count) {
        boolean changed() {
            return count > 0;
        }
    }
}
