package com.kaixuan.agentreproxy.dto;

import java.util.List;

/**
 * 请求文本替换规则设置的请求体（专有端点用）
 * <p>
 * 用途：在把请求发给上游<b>之前</b>，对请求体中的文本字段做替换。
 * 规则完全由使用者自行配置，本服务不内置任何规则。
 * <p>
 * 典型使用场景：
 * <ul>
 *   <li>脱敏：把内部项目代号、人名、路径替换成占位符</li>
 *   <li>纠正：统一术语拼写</li>
 *   <li>兼容性适配：某些上游对特定词组有额外处理，替换后可正常调用</li>
 * </ul>
 *
 * @param enabled 总开关；false 时所有规则都不生效（规则本身仍保留）
 * @param rules   规则列表，按数组顺序依次执行（前一条的输出是后一条的输入）
 */
public record TextReplaceSettingRequest(
        Boolean enabled,
        List<Rule> rules
) {

    /**
     * 单条替换规则
     *
     * @param name          规则名（便于识别，不参与匹配）
     * @param pattern       要匹配的内容
     * @param replacement   替换成什么（{@code null} 或空串 = 删除匹配内容）
     * @param regex         {@code true} = pattern 按正则解析；{@code false} = 按字面量
     * @param caseSensitive 是否区分大小写
     * @param scope         生效范围，见 {@link Scope}
     * @param enabled       单条规则开关
     */
    public record Rule(
            String name,
            String pattern,
            String replacement,
            Boolean regex,
            Boolean caseSensitive,
            String scope,
            Boolean enabled
    ) {}

    /**
     * 规则生效范围
     * <p>
     * 限定范围可以避免误伤 —— 例如只想改 system prompt 时，不应动用户的实际提问。
     */
    public enum Scope {
        /** 所有消息的 content（system + user + assistant） */
        ALL_MESSAGES("all_messages"),
        /** 仅 system 消息 */
        SYSTEM_ONLY("system_only"),
        /** 仅 user 消息 */
        USER_ONLY("user_only"),
        /** 工具的 description（不含工具名，避免破坏调用） */
        TOOL_DESCRIPTIONS("tool_descriptions"),
        /** 消息 content + 工具 description */
        MESSAGES_AND_TOOLS("messages_and_tools");

        private final String value;

        Scope(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Scope from(String s) {
            if (s == null || s.isBlank()) {
                return ALL_MESSAGES;
            }
            for (Scope sc : values()) {
                if (sc.value.equalsIgnoreCase(s.trim())) {
                    return sc;
                }
            }
            throw new IllegalArgumentException("未知的 scope: " + s
                    + "（可选：all_messages / system_only / user_only "
                    + "/ tool_descriptions / messages_and_tools）");
        }
    }
}
