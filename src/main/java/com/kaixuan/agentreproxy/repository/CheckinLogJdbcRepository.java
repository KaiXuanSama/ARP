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

    /**
     * 删除指定账号在某个时间窗内（毫秒）的签到记录
     * <p>
     * 用于：code=0 签到成功后，覆写当日同类型的所有旧记录（包括 10001 占位）
     *
     * @return 受影响的行数
     */
    public int deleteByAccountIdInDateRange(long accountId, String checkinType, long startMs, long endMs) {
        return jdbcTemplate.update(
                "DELETE FROM checkin_log WHERE account_id = ? AND checkin_type = ? " +
                        "AND checkin_time BETWEEN ? AND ?",
                accountId, checkinType, startMs, endMs);
    }

    /**
     * 拉取所有签到日志（启动清理时使用）
     * <p>
     * 数据量预期不大（一天 N 个账号），全表扫描可接受。
     * 按 (account_id, checkin_type, checkin_time) 排序便于上层按"日"分组
     */
    public List<CheckinLogRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT id, account_id, checkin_type, checkin_time, extra FROM checkin_log " +
                        "ORDER BY account_id ASC, checkin_type ASC, checkin_time ASC",
                ROW_MAPPER);
    }

    /**
     * 按 id 列表批量删除（启动清理时使用）
     *
     * @return 受影响的行数
     */
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        return jdbcTemplate.update(
                "DELETE FROM checkin_log WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }

    /**
     * 统计指定账号 + 签到类型的日志总数（分页查询用）
     */
    public long countByAccountIdAndType(long accountId, String checkinType) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkin_log WHERE account_id = ? AND checkin_type = ?",
                Long.class, accountId, checkinType);
        return cnt == null ? 0L : cnt;
    }

    /**
     * 分页查询指定账号 + 签到类型的签到日志
     * <p>
     * 排序字段已由调用方做白名单校验（{@code CheckinLogQueryRequest.safeOrderBy()}），
     * 这里直接拼接到 SQL，**调用方必须保证 orderBy 来自白名单**，否则有 SQL 注入风险。
     *
     * @param accountId   账号 id
     * @param checkinType 签到类型
     * @param orderBy     排序字段（必须来自白名单：id / checkin_time / account_id）
     * @param asc         是否升序
     * @param offset      偏移量（= (pageNum - 1) * pageSize）
     * @param limit       每页条数
     */
    public List<CheckinLogRecord> findPageByAccountIdAndType(
            long accountId, String checkinType, String orderBy, boolean asc, int offset, int limit) {
        String direction = asc ? "ASC" : "DESC";
        String sql = "SELECT id, account_id, checkin_type, checkin_time, extra FROM checkin_log " +
                "WHERE account_id = ? AND checkin_type = ? " +
                "ORDER BY " + orderBy + " " + direction + " " +
                "LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, accountId, checkinType, limit, offset);
    }
}
