package com.kaixuan.agentreproxy.repository;

import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的 Workbuddy 账户仓储
 * <p>
 * 使用原因：R2DBC 对 SQLite 的方言支持尚不完善，
 * 阻塞式 JdbcTemplate 在 WebFlux 中以 Mono.fromCallable 包装使用即可。
 */
@Repository
public class WorkbuddyAccountJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<WorkbuddyAccountRecord> ROW_MAPPER = (rs, rowNum) -> new WorkbuddyAccountRecord(
            rs.getLong("id"),
            rs.getString("uid"),
            rs.getString("account_json"),
            rs.getString("access_token"),
            rs.getString("api_key"),
            rs.getString("extra"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    public WorkbuddyAccountJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<WorkbuddyAccountRecord> findByUid(String uid) {
        List<WorkbuddyAccountRecord> list = jdbcTemplate.query(
                "SELECT id, uid, account_json, access_token, api_key, extra, created_at, updated_at " +
                        "FROM workbuddy_account WHERE uid = ?",
                ROW_MAPPER, uid);
        return list.stream().findFirst();
    }

    public List<WorkbuddyAccountRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, uid, account_json, access_token, api_key, extra, created_at, updated_at " +
                        "FROM workbuddy_account ORDER BY id ASC",
                ROW_MAPPER);
    }

    public int insert(String uid, String accountJson, String accessToken, String apiKey) {
        return jdbcTemplate.update(
                "INSERT INTO workbuddy_account (uid, account_json, access_token, api_key) VALUES (?, ?, ?, ?)",
                uid, accountJson, accessToken, apiKey);
    }

    public int updateByUid(String uid, String accountJson, String accessToken, String apiKey) {
        return jdbcTemplate.update(
                "UPDATE workbuddy_account SET account_json = ?, access_token = ?, api_key = ?, " +
                        "updated_at = (strftime('%s', 'now') * 1000) WHERE uid = ?",
                accountJson, accessToken, apiKey, uid);
    }

    /**
     * 仅覆写 extra 字段（用于缓存账户的 Billing/Checkin 等扩展数据）
     */
    public int updateExtraByUid(String uid, String extra) {
        return jdbcTemplate.update(
                "UPDATE workbuddy_account SET extra = ?, " +
                        "updated_at = (strftime('%s', 'now') * 1000) WHERE uid = ?",
                extra, uid);
    }
}
