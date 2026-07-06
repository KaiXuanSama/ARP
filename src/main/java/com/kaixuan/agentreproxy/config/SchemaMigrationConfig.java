package com.kaixuan.agentreproxy.config;


import com.kaixuan.agentreproxy.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库 Schema 迁移 + 数据初始化 — 在应用启动时自动执行。
 * <p>
 * 职责：
 * <ul>
 *   <li>增量 ALTER TABLE ADD COLUMN（SQLite 不支持 IF NOT EXISTS）</li>
 *   <li>增量 CREATE INDEX</li>
 *   <li>初始化默认管理员凭证（admin.credential 不存在时创建 root/root）</li>
 * </ul>
 */
@Component
public class SchemaMigrationConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationConfig.class);

    private final JdbcTemplate jdbcTemplate;
    private final SettingsService settingsService;

    public SchemaMigrationConfig(JdbcTemplate jdbcTemplate, SettingsService settingsService) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsService = settingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        addColumnIfNotExists("workbuddy_account", "enabled", "INTEGER NOT NULL DEFAULT '1'");
        addColumnIfNotExists("downstream_api_key", "supported_models", "TEXT NULL");
        // 2026-07: 给 downstream_api_key_call_log 加 key_id 列(指回 downstream_api_key.id)
        addColumnIfNotExists("downstream_api_key_call_log", "key_id", "INTEGER NULL");
        // 2026-07: 加 account_id 列(指回 workbuddy_account.id,记录当时 chat 命中的账号)
        // 老数据无法兜底:旧日志不知道当时命中的是哪个账号,留 null 即可
        addColumnIfNotExists("downstream_api_key_call_log", "account_id", "INTEGER NULL");
        // 加列后再建索引 —— 不能放在 schema.sql 里,因为旧表没有这些列,
        // CREATE INDEX 会因"no such column"而失败,导致整个 schema.sql 回滚,应用启动失败
        addIndexIfNotExists("downstream_api_key_call_log", "idx_dstream_call_log_keyid", "key_id");
        addIndexIfNotExists("downstream_api_key_call_log", "idx_dstream_call_log_accountid", "account_id");
        // 老数据迁移:key_id IS NULL 的记录统一指向 id=1(用户要求保留这些孤儿日志,
        // 后续手动确认/删除;只跑一次,带标志位避免重复 UPDATE)
        // backfillCallLogKeyId();
        // 2026-07: 清理历史"usage":null 噪声日志
        // 见 OpenAiController.hasRealUsage —— 旧实现用 json.contains("\"usage\"") 嗅探,
        // 把中间 content chunk 的 "usage":null 也误判为结算 chunk 落库,产生 2w+ 无用行
        // 这次修复后,新调用不再产生,本迁移把历史的"明确无用"行(usage:null / 缺 usage)
        // 一次性删掉。带 app_settings 标志位保证幂等(只跑一次)
        // cleanupCallLogNullUsage();

        // ============== 数据初始化 ==============
        // 管理员凭证：不存在则创建默认 root/root
        settingsService.ensureDefaultAdminCredential();
    }

    private void addColumnIfNotExists(String table, String column, String definition) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(col -> column.equals(col.get("name")));
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[SchemaMigration] 已添加列 {}.{}", table, column);
        }
    }

    /**
     * 建索引(幂等:已存在则跳过)
     * <p>
     * 为什么放在这里而不是 schema.sql:旧表可能没有新列,CREATE INDEX 会因
     * "no such column"失败;先加列再建索引才安全。
     */
    private void addIndexIfNotExists(String table, String indexName, String column) {
        boolean exists = jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")").stream()
                .anyMatch(idx -> indexName.equals(idx.get("name")));
        if (!exists) {
            jdbcTemplate.execute("CREATE INDEX " + indexName + " ON " + table + "(" + column + ")");
            log.info("[SchemaMigration] 已建索引 {}.{}", table, indexName);
        }
    }

    /**
     * 2026-07: 老 call_log 数据没有 key_id,统一指到 id=1
     * <p>
     * <strong>TODO(手动)</strong>:用户会在迁移后手动 review / 删除这部分"统一指向 1"的孤儿日志。
     * <p>
     * 幂等性:用 app_settings key {@code schema.call_log_keyid_backfilled} 标记,重启不会重复跑。
     * <p>
     * 防御:downstream_api_key 为空时跳过回填,避免 FK 失败
     */
    @SuppressWarnings("unused")
    private void backfillCallLogKeyId() {
        Boolean done = jdbcTemplate.queryForList(
                "SELECT value FROM app_settings WHERE key = ?", String.class,
                "schema.call_log_keyid_backfilled").stream().findFirst()
                .map(s -> "1".equals(s)).orElse(false);
        if (Boolean.TRUE.equals(done)) {
            log.debug("[SchemaMigration] call_log key_id 已回填过,跳过");
            return;
        }
        Long firstId = jdbcTemplate.queryForList(
                "SELECT id FROM downstream_api_key ORDER BY id ASC LIMIT 1", Long.class).stream()
                .findFirst().orElse(null);
        if (firstId == null) {
            log.info("[SchemaMigration] downstream_api_key 表为空,跳过 call_log key_id 回填");
            return;
        }
        int rows = jdbcTemplate.update(
                "UPDATE downstream_api_key_call_log SET key_id = ? WHERE key_id IS NULL", firstId);
        log.info("[SchemaMigration] call_log key_id 回填:影响 {} 行,统一指向 id={}", rows, firstId);
        // 写幂等标志(无 ON CONFLICT,手工 upsert)
        try {
            int n = jdbcTemplate.update(
                    "UPDATE app_settings SET value = ?, updated_at = (strftime('%s','now')*1000) " +
                            "WHERE key = ?",
                    "\"1\"", "schema.call_log_keyid_backfilled");
            if (n == 0) {
                jdbcTemplate.update(
                        "INSERT INTO app_settings (key, value) VALUES (?, ?)",
                        "schema.call_log_keyid_backfilled", "\"1\"");
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 写回填标志位失败(不影响主流程): {}", e.getMessage());
        }
    }

    /**
     * 2026-07: 清理历史"usage":null / 缺 usage 字段的 call_log 噪声行
     * <p>
     * <strong>背景</strong>:OpenAiController.interceptChatChunk 之前用
     * {@code json.contains("\"usage\"")} 嗅探,把中间 content chunk 的
     * {@code "usage":null} 也误判为结算 chunk 落库,产生 2w+ 无用行。本次修复后,
     * 新 chat 调用已不再落这些行,本迁移把历史垃圾一次性清掉。
     * <p>
     * <strong>删除条件</strong>(全部满足才删):
     * <ul>
     *   <li>{@code content} 含 {@code "usage":null} —— 明确是中间 content chunk</li>
     *   <li>OR {@code content} 不含 {@code "usage"} 字符串 —— 缺 usage 字段(老格式 / 上游异常)</li>
     * </ul>
     * <p>
     * <strong>保留</strong>:
     * <ul>
     *   <li>有 {@code "credit"} 的真结算 chunk(content 含 "credit" 必然有真 usage 对象)</li>
     *   <li>老数据回填到 key_id=1 的孤儿日志(可能有意义,留给用户手动 review)</li>
     * </ul>
     * <p>
     * <strong>幂等性</strong>:用 app_settings key {@code schema.call_log_null_usage_cleaned}
     * 标记,只跑一次。SQLite 的 LIKE 在大表上走全表扫描,一次性 2w+ 行 DELETE 可能稍慢,
     * 启动时执行,无并发竞争,不需要分批。
     * <p>
     * <strong>不使用事务</strong>:SQLite 的 DELETE 自动在隐式事务里,失败回滚不影响其他迁移;
     * 成功了写标志位(独立小事务)。
     */
    @SuppressWarnings("unused")
    private void cleanupCallLogNullUsage() {
        String flagKey = "schema.call_log_null_usage_cleaned";
        Boolean done = jdbcTemplate.queryForList(
                "SELECT value FROM app_settings WHERE key = ?", String.class, flagKey)
                .stream().findFirst().map(s -> "1".equals(s)).orElse(false);
        if (Boolean.TRUE.equals(done)) {
            log.debug("[SchemaMigration] call_log null-usage 清理已跑过,跳过");
            return;
        }
        // SQLite LIKE 大小写不敏感(默认),匹配 "usage":null / "usage": null(可能有空格)
        // 也兼容 "usage":null,"token":... 的尾随形式
        int rows = jdbcTemplate.update(
                "DELETE FROM downstream_api_key_call_log " +
                        "WHERE content LIKE '%\"usage\":null%' " +
                        "   OR content LIKE '%\"usage\": null%' " +
                        "   OR (content NOT LIKE '%\"usage\"%' AND content NOT LIKE '%\"credit\"%')");
        log.info("[SchemaMigration] call_log 清理:删除 {} 行 \"usage\":null / 缺 usage 字段的噪声数据", rows);
        // 写幂等标志(同 backfillCallLogKeyId 的手工 upsert 模式)
        try {
            int n = jdbcTemplate.update(
                    "UPDATE app_settings SET value = ?, updated_at = (strftime('%s','now')*1000) WHERE key = ?",
                    "\"1\"", flagKey);
            if (n == 0) {
                jdbcTemplate.update(
                        "INSERT INTO app_settings (key, value) VALUES (?, ?)",
                        flagKey, "\"1\"");
            }
        } catch (Exception e) {
            log.warn("[SchemaMigration] 写清理标志位失败(不影响主流程): {}", e.getMessage());
        }
    }
}