package com.kaixuan.agentreproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库 Schema 迁移 — 在应用启动时自动执行增量迁移。
 * <p>
 * SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，
 * 因此通过 PRAGMA table_info 检查列是否存在后再执行。
 */
@Component
public class SchemaMigrationConfig implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
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
    // private void backfillCallLogKeyId() {
    //     Boolean done = jdbcTemplate.queryForList(
    //             "SELECT value FROM app_settings WHERE key = ?", String.class,
    //             "schema.call_log_keyid_backfilled").stream().findFirst()
    //             .map(s -> "1".equals(s)).orElse(false);
    //     if (Boolean.TRUE.equals(done)) {
    //         log.debug("[SchemaMigration] call_log key_id 已回填过,跳过");
    //         return;
    //     }
    //     Long firstId = jdbcTemplate.queryForList(
    //             "SELECT id FROM downstream_api_key ORDER BY id ASC LIMIT 1", Long.class).stream()
    //             .findFirst().orElse(null);
    //     if (firstId == null) {
    //         log.info("[SchemaMigration] downstream_api_key 表为空,跳过 call_log key_id 回填");
    //         return;
    //     }
    //     int rows = jdbcTemplate.update(
    //             "UPDATE downstream_api_key_call_log SET key_id = ? WHERE key_id IS NULL", firstId);
    //     log.info("[SchemaMigration] call_log key_id 回填:影响 {} 行,统一指向 id={}", rows, firstId);
    //     // 写幂等标志(无 ON CONFLICT,手工 upsert)
    //     try {
    //         int n = jdbcTemplate.update(
    //                 "UPDATE app_settings SET value = ?, updated_at = (strftime('%s','now')*1000) " +
    //                         "WHERE key = ?",
    //                 "\"1\"", "schema.call_log_keyid_backfilled");
    //         if (n == 0) {
    //             jdbcTemplate.update(
    //                     "INSERT INTO app_settings (key, value) VALUES (?, ?)",
    //                     "schema.call_log_keyid_backfilled", "\"1\"");
    //         }
    //     } catch (Exception e) {
    //         log.warn("[SchemaMigration] 写回填标志位失败(不影响主流程): {}", e.getMessage());
    //     }
    // }
}