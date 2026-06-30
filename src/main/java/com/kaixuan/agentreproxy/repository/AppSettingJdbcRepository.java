package com.kaixuan.agentreproxy.repository;

import com.kaixuan.agentreproxy.entity.AppSettingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AppSettingJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<AppSettingRecord> ROW_MAPPER = (rs, rowNum) -> new AppSettingRecord(
            rs.getLong("id"),
            rs.getString("key"),
            rs.getString("value"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    public AppSettingJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按 key 查一条
     */
    public Optional<AppSettingRecord> findByKey(String key) {
        if (key == null) return Optional.empty();
        List<AppSettingRecord> list = jdbcTemplate.query(
                "SELECT id, key, value, created_at, updated_at FROM app_settings WHERE key = ?",
                ROW_MAPPER, key);
        return list.stream().findFirst();
    }

    /**
     * 全部设置项(按 key 排序)
     */
    public List<AppSettingRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, key, value, created_at, updated_at FROM app_settings ORDER BY key ASC",
                ROW_MAPPER);
    }

    /**
     * 不存在则插入,存在则更新 value + updated_at,返回最新 row
     * <p>
     * SQLite 的 INSERT OR REPLACE 配合 UNIQUE 约束实现"upsert by key"
     */
    public AppSettingRecord upsert(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET " +
                        "value = excluded.value, " +
                        "updated_at = (strftime('%s', 'now') * 1000)",
                key, value);
        return findByKey(key).orElseThrow(() ->
                new IllegalStateException("upsert 之后查不到行: " + key));
    }
}
