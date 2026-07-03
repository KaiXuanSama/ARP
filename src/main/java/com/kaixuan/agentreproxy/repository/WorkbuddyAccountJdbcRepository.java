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

    /**
     * 按主键 id 查单条记录（用主键索引，比 findAll().stream().filter() 高效）
     */
    public Optional<WorkbuddyAccountRecord> findById(Long id) {
        if (id == null) return Optional.empty();
        List<WorkbuddyAccountRecord> list = jdbcTemplate.query(
                "SELECT id, uid, account_json, access_token, api_key, extra, created_at, updated_at " +
                        "FROM workbuddy_account WHERE id = ?",
                ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 按主键 id 批量查（IN 查询）。ids 为空/空集合时返回空列表
     */
    public List<WorkbuddyAccountRecord> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        String sql = "SELECT id, uid, account_json, access_token, api_key, extra, created_at, updated_at " +
                "FROM workbuddy_account WHERE id IN (" + placeholders + ") ORDER BY id ASC";
        return jdbcTemplate.query(sql, ROW_MAPPER, ids.toArray());
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

    /**
     * 按主键 id 删除账户
     * <p>
     * 关联数据清理:
     * <ul>
     *   <li>{@code checkin_log.account_id} → FK {@code ON DELETE CASCADE},数据库自动删除所有签到日志</li>
     *   <li>{@code downstream_api_key.designated_account_id} → FK {@code ON DELETE SET NULL},
     *       key 保留,designated_account_id 变 null(designated 模式下次请求会被业务校验拒绝)</li>
     * </ul>
     * <p>
     * 不在本方法里手动清签到日志 —— 留给 FK 处理,避免应用代码与数据库约束出现双源真相
     *
     * @return 受影响行数;0 = id 不存在
     */
    public int deleteById(Long id) {
        if (id == null) return 0;
        return jdbcTemplate.update("DELETE FROM workbuddy_account WHERE id = ?", id);
    }
}
