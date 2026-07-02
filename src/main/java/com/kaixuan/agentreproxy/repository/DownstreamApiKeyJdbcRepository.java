package com.kaixuan.agentreproxy.repository;

import com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 下游 API Key 仓储(JdbcTemplate,SQLite)
 * <p>
 * 风格与 {@link WorkbuddyAccountJdbcRepository} / {@link CheckinLogJdbcRepository} 一致。
 * <p>
 * 注意:不暴露 {@code findByApiKey} 的明文查询(本期内 chat 拦截逻辑不在此处做)。
 */
@Repository
public class DownstreamApiKeyJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<DownstreamApiKeyRecord> ROW_MAPPER = (rs, rowNum) -> new DownstreamApiKeyRecord(
            rs.getLong("id"),
            rs.getString("label"),
            rs.getString("api_key"),
            rs.getLong("call_count"),
            rs.getObject("used_credits") == null ? null : rs.getDouble("used_credits"),
            rs.getObject("credit_limit") == null ? null : rs.getDouble("credit_limit"),
            rs.getObject("expires_at") == null ? null : rs.getLong("expires_at"),
            rs.getInt("enabled"),
            rs.getString("consumption"),
            rs.getObject("designated_account_id") == null ? null : rs.getLong("designated_account_id"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    public DownstreamApiKeyJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(DownstreamApiKeyRecord rec) {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO downstream_api_key (label, api_key, call_count, used_credits, " +
                            "credit_limit, expires_at, enabled, consumption, designated_account_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, rec.label());
            ps.setString(2, rec.apiKey());
            ps.setLong(3, rec.callCount() == null ? 0L : rec.callCount());
            ps.setDouble(4, rec.usedCredits() == null ? 0.0 : rec.usedCredits());
            if (rec.creditLimit() == null) ps.setNull(5, java.sql.Types.REAL);
            else ps.setDouble(5, rec.creditLimit());
            if (rec.expiresAt() == null) ps.setNull(6, java.sql.Types.INTEGER);
            else ps.setLong(6, rec.expiresAt());
            ps.setInt(7, rec.enabled() == null ? 1 : rec.enabled());
            ps.setString(8, rec.consumption() == null ? "designated" : rec.consumption());
            if (rec.designatedAccountId() == null) ps.setNull(9, java.sql.Types.INTEGER);
            else ps.setLong(9, rec.designatedAccountId());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    public int update(Long id, String label, Double creditLimit, Long expiresAt,
                      Integer enabled, String consumption, Long designatedAccountId) {
        // 注意:apiKey / callCount / usedCredits 不允许通过 update 改变(只能累加)
        return jdbcTemplate.update(
                "UPDATE downstream_api_key SET label = ?, credit_limit = ?, expires_at = ?, " +
                        "enabled = ?, consumption = ?, designated_account_id = ?, " +
                        "updated_at = (strftime('%s', 'now') * 1000) WHERE id = ?",
                label, creditLimit, expiresAt, enabled, consumption, designatedAccountId, id);
    }

    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM downstream_api_key WHERE id = ?", id);
    }

    public java.util.Optional<DownstreamApiKeyRecord> findById(Long id) {
        List<DownstreamApiKeyRecord> list = jdbcTemplate.query(
                "SELECT * FROM downstream_api_key WHERE id = ?", ROW_MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 按 key 字面量查(后续 chat 拦截会用到)
     */
    public java.util.Optional<DownstreamApiKeyRecord> findByApiKey(String apiKey) {
        List<DownstreamApiKeyRecord> list = jdbcTemplate.query(
                "SELECT * FROM downstream_api_key WHERE api_key = ?", ROW_MAPPER, apiKey);
        return list.stream().findFirst();
    }

    /**
     * 原子累加 call_count —— /v1/chat/completions 每次成功鉴权后 +1
     * <p>
     * SQL 用 {@code SET call_count = call_count + 1} 直接累加,避免 read-modify-write 竞态。
     * 顺带刷新 {@code updated_at} 以便前端表格能展示"最近一次调用时间"。
     * <p>
     * 返回受影响的行数:0 = id 不存在(已被并发删除);1 = 累加成功。
     * <p>
     * <strong>不累加 used_credits</strong> —— 计费来源在上游 chat 响应的最后一条 stream chunk
     * (含 {@code usage.prompt_tokens / completion_tokens} 等),本期没有从上游解析该字段,
     * 字段保留在 schema 供后续接计费时使用。
     */
    public int incrementCallCount(Long id) {
        return jdbcTemplate.update(
                "UPDATE downstream_api_key SET call_count = call_count + 1, " +
                        "updated_at = (strftime('%s', 'now') * 1000) WHERE id = ?",
                id);
    }

    /**
     * 原子累加 used_credits —— 上游 chat 响应最终 chunk 解出 {@code usage.credit} 后调用
     * <p>
     * SQL 用 {@code SET used_credits = used_credits + ?} 直接累加,避免 read-modify-write 竞态。
     * 顺带刷新 {@code updated_at}。
     * <p>
     * <strong>不强制 credit_limit</strong> —— 即使超额也只累加,限额禁用由调用方(本期内
     * 不会,保留字段供后续业务接计费时使用)。
     *
     * @param id    downstream_api_key 主键
     * @param delta 累加值(从 {@code usage.credit} 解析;>=0,可能含小数)
     * @return 受影响行数
     */
    public int incrementUsedCredits(Long id, double delta) {
        if (id == null) return 0;
        return jdbcTemplate.update(
                "UPDATE downstream_api_key SET used_credits = used_credits + ?, " +
                        "updated_at = (strftime('%s', 'now') * 1000) WHERE id = ?",
                delta, id);
    }

    /**
     * 写入调用日志 —— 落库 {@code downstream_api_key_call_log} 表
     * <p>
     * 三列结构:id / content(完整 chunk JSON 字符串)/ created_at
     * <p>
     * 返回新行 id(本期内不消费,但保留供后续"日志详情页"用)
     */
    public long insertCallLog(String content) {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO downstream_api_key_call_log (content) VALUES (?)",
                    new String[]{"id"});
            ps.setString(1, content);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 总数(分页用) */
    public long count() {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM downstream_api_key", Long.class);
        return cnt == null ? 0L : cnt;
    }

    /**
     * 分页查询,orderBy 白名单(id / created_at / call_count / used_credits)
     */
    public List<DownstreamApiKeyRecord> findPage(String orderBy, boolean asc, int offset, int limit) {
        String direction = asc ? "ASC" : "DESC";
        String sql = "SELECT * FROM downstream_api_key " +
                "ORDER BY " + orderBy + " " + direction + " LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, limit, offset);
    }
}
