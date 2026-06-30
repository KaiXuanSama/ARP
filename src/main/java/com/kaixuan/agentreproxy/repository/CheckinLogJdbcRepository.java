package com.kaixuan.agentreproxy.repository;

import com.kaixuan.agentreproxy.entity.CheckinLogRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 JdbcTemplate 的签到日志仓储
 * <p>
 * 与 {@link WorkbuddyAccountJdbcRepository} 风格保持一致。
 * "当日"判定：由调用方传入毫秒时间窗（startMs, endMs），避开 SQLite strftime 时区陷阱。
 */
@Repository
public class CheckinLogJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CheckinLogRecord> ROW_MAPPER = (rs, rowNum) -> new CheckinLogRecord(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("checkin_type"),
            rs.getLong("checkin_time"),
            rs.getString("extra")
    );

    public CheckinLogJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条签到记录，返回新生成的 id
     */
    public long insert(long accountId, String checkinType, long checkinTimeMs, String extra) {
        // SQLite 的 last_insert_rowid() 通过 KeyHolder 拿
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO checkin_log (account_id, checkin_type, checkin_time, extra) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, accountId);
            ps.setString(2, checkinType);
            ps.setLong(3, checkinTimeMs);
            ps.setString(4, extra);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /**
     * 批量查询指定账号在某个时间窗内（毫秒）的签到记录
     * <p>
     * 用于"当日签到状态"批量查询，N 个账号一次 SQL 搞定
     *
     * @param accountIds 账号 id 列表
     * @param checkinType 签到类型（如 'daily'）
     * @param startMs 起始毫秒（含）
     * @param endMs 结束毫秒（含）
     * @return 命中的签到记录（每个账号可能多条，按 time DESC）
     */
    public List<CheckinLogRecord> findInDateRange(List<Long> accountIds, String checkinType, long startMs, long endMs) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        String placeholders = accountIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, account_id, checkin_type, checkin_time, extra FROM checkin_log " +
                "WHERE account_id IN (" + placeholders + ") AND checkin_type = ? " +
                "AND checkin_time BETWEEN ? AND ? " +
                "ORDER BY account_id ASC, checkin_time DESC";
        Object[] params = new Object[accountIds.size() + 3];
        for (int i = 0; i < accountIds.size(); i++) {
            params[i] = accountIds.get(i);
        }
        params[accountIds.size()] = checkinType;
        params[accountIds.size() + 1] = startMs;
        params[accountIds.size() + 2] = endMs;
        return jdbcTemplate.query(sql, ROW_MAPPER, params);
    }

    /**
     * 查询指定账号在某个时间窗内（毫秒）的签到记录
     */
    public List<CheckinLogRecord> findByAccountIdInDateRange(long accountId, String checkinType, long startMs, long endMs) {
        return jdbcTemplate.query(
                "SELECT id, account_id, checkin_type, checkin_time, extra FROM checkin_log " +
                        "WHERE account_id = ? AND checkin_type = ? " +
                        "AND checkin_time BETWEEN ? AND ? " +
                        "ORDER BY checkin_time DESC",
                ROW_MAPPER, accountId, checkinType, startMs, endMs);
    }
}
