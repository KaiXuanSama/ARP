package com.kaixuan.agentreproxy.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 下游 API Key 仓储(JdbcTemplate,SQLite)
 * <p>
 * 风格与 {@link WorkbuddyAccountJdbcRepository} / {@link CheckinLogJdbcRepository}
 * 一致。
 * <p>
 * 注意:不暴露 {@code findByApiKey} 的明文查询(本期内 chat 拦截逻辑不在此处做)。
 */
@Repository
public class DownstreamApiKeyJdbcRepository {

        private static final Logger log = LoggerFactory.getLogger(DownstreamApiKeyJdbcRepository.class);

        private final JdbcTemplate jdbcTemplate;
        private final ObjectMapper objectMapper;
        /**
         * RowMapper 改为实例级别(非 static),因为反序列化 supported_models 需要用到本实例持有的 ObjectMapper。
         * <p>
         * 早期版本用 static lambda + placeholder ObjectMapper,实现复杂且容易 NPE;改为实例 lambda 后
         * 行为简单清晰 —— JdbcTemplate 内部每次 query 时会重新调用 lambda,代价可忽略。
         */
        private final RowMapper<DownstreamApiKeyRecord> rowMapper;

        public DownstreamApiKeyJdbcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
                this.jdbcTemplate = jdbcTemplate;
                this.objectMapper = objectMapper;
                this.rowMapper = (rs, rowNum) -> new DownstreamApiKeyRecord(
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
                                parseSupportedModels(rs.getString("supported_models")),
                                rs.getLong("created_at"),
                                rs.getLong("updated_at"));
        }

        /**
         * 解析 supported_models 列的 JSON 字符串
         * <p>
         * - 字段为 NULL → 返回 null(回退到全集)
         * - 字段为空字符串 → 返回 null(视为未配置,防御脏数据)
         * - 字段为合法 JSON 数组 → 反序列化为 List
         * - 字段为合法 JSON 但不是数组 → 记 warn,返回 null
         * - 字段为非法 JSON → 记 warn,返回 null
         */
        private List<String> parseSupportedModels(String raw) {
                if (raw == null || raw.isBlank()) {
                        return null;
                }
                String trimmed = raw.trim();
                try {
                        Object parsed = objectMapper.readValue(trimmed, Object.class);
                        if (parsed instanceof List<?> list) {
                                java.util.List<String> out = new java.util.ArrayList<>(list.size());
                                for (Object o : list) {
                                        if (o != null) {
                                                out.add(String.valueOf(o));
                                        }
                                }
                                return List.copyOf(out);
                        }
                        log.warn("supported_models 不是数组类型,忽略 raw={}", trimmed);
                        return null;
                } catch (Exception e) {
                        log.warn("supported_models 解析失败,忽略 raw={} err={}", trimmed, e.getMessage());
                        return null;
                }
        }

        public long insert(DownstreamApiKeyRecord rec) {
                org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                        var ps = connection.prepareStatement(
                                        "INSERT INTO downstream_api_key (label, api_key, call_count, used_credits, " +
                                                        "credit_limit, expires_at, enabled, consumption, designated_account_id, " +
                                                        "supported_models) " +
                                                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                        new String[] { "id" });
                        ps.setString(1, rec.label());
                        ps.setString(2, rec.apiKey());
                        ps.setLong(3, rec.callCount() == null ? 0L : rec.callCount());
                        ps.setDouble(4, rec.usedCredits() == null ? 0.0 : rec.usedCredits());
                        if (rec.creditLimit() == null)
                                ps.setNull(5, java.sql.Types.REAL);
                        else
                                ps.setDouble(5, rec.creditLimit());
                        if (rec.expiresAt() == null)
                                ps.setNull(6, java.sql.Types.INTEGER);
                        else
                                ps.setLong(6, rec.expiresAt());
                        ps.setInt(7, rec.enabled() == null ? 1 : rec.enabled());
                        ps.setString(8, rec.consumption() == null ? "designated" : rec.consumption());
                        if (rec.designatedAccountId() == null)
                                ps.setNull(9, java.sql.Types.INTEGER);
                        else
                                ps.setLong(9, rec.designatedAccountId());
                        ps.setString(10, serializeSupportedModels(rec.supportedModels()));
                        return ps;
                }, keyHolder);
                Number key = keyHolder.getKey();
                return key == null ? -1L : key.longValue();
        }

        public int update(Long id, String label, Double creditLimit, Long expiresAt,
                        Integer enabled, String consumption, Long designatedAccountId,
                        List<String> supportedModels) {
                // 注意:apiKey / callCount / usedCredits 不允许通过 update 改变(只能累加)
                // creditLimit / expiresAt 是"可清空"字段(传 null = 写 SQL NULL),需要用 setNull
                // 否则 JdbcTemplate 会因参数类型不匹配抛 InvalidDataAccessApiUsageException
                // supportedModels 同 creditLimit/expiresAt 约定:null = 清空(回退全集),非 null = 覆盖
                return jdbcTemplate.update(connection -> {
                        var ps = connection.prepareStatement(
                                        "UPDATE downstream_api_key SET label = ?, credit_limit = ?, expires_at = ?, " +
                                                        "enabled = ?, consumption = ?, designated_account_id = ?, " +
                                                        "supported_models = ?, " +
                                                        "updated_at = (strftime('%s', 'now') * 1000) WHERE id = ?");
                        ps.setString(1, label);
                        if (creditLimit == null)
                                ps.setNull(2, java.sql.Types.REAL);
                        else
                                ps.setDouble(2, creditLimit);
                        if (expiresAt == null)
                                ps.setNull(3, java.sql.Types.INTEGER);
                        else
                                ps.setLong(3, expiresAt);
                        ps.setInt(4, enabled);
                        ps.setString(5, consumption);
                        if (designatedAccountId == null)
                                ps.setNull(6, java.sql.Types.INTEGER);
                        else
                                ps.setLong(6, designatedAccountId);
                        ps.setString(7, serializeSupportedModels(supportedModels));
                        ps.setLong(8, id);
                        return ps;
                });
        }

        /**
         * 把 supportedModels 列表序列化成 JSON 字符串,写到 SQL TEXT 列
         * <p>
         * - null → null(写到 SQL NULL)
         * - 空列表 → "[]"(严格不放行,不是 null)
         * - 非空 → JSON 数组
         */
        private String serializeSupportedModels(List<String> models) {
                if (models == null) {
                        return null;
                }
                try {
                        return objectMapper.writeValueAsString(models);
                } catch (Exception e) {
                        log.warn("supported_models 序列化失败,落库为 null err={}", e.getMessage());
                        return null;
                }
        }

        public int deleteById(Long id) {
                return jdbcTemplate.update("DELETE FROM downstream_api_key WHERE id = ?", id);
        }

        public java.util.Optional<DownstreamApiKeyRecord> findById(Long id) {
                List<DownstreamApiKeyRecord> list = jdbcTemplate.query(
                                "SELECT * FROM downstream_api_key WHERE id = ?", rowMapper, id);
                return list.stream().findFirst();
        }

        /**
         * 按 key 字面量查(后续 chat 拦截会用到)
         */
        public java.util.Optional<DownstreamApiKeyRecord> findByApiKey(String apiKey) {
                List<DownstreamApiKeyRecord> list = jdbcTemplate.query(
                                "SELECT * FROM downstream_api_key WHERE api_key = ?", rowMapper, apiKey);
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
         * SQL 用 {@code SET used_credits = used_credits + ?} 直接累加,避免 read-modify-write
         * 竞态。
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
                if (id == null)
                        return 0;
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
         * <p>
         * <strong>keyId / accountId 必传</strong>:2026-07 起 call_log 增加这两列,
         * 所有新写入必须带。旧数据的回填由 {@code SchemaMigrationConfig} 在启动时完成
         * (key_id 统一指 1,account_id 不兜底,留 null)
         */
        public long insertCallLog(Long keyId, Long accountId, String content) {
                org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                        var ps = connection.prepareStatement(
                                        "INSERT INTO downstream_api_key_call_log (key_id, account_id, content) " +
                                                        "VALUES (?, ?, ?)",
                                        new String[] { "id" });
                        if (keyId == null) {
                                ps.setNull(1, java.sql.Types.INTEGER);
                        } else {
                                ps.setLong(1, keyId);
                        }
                        if (accountId == null) {
                                ps.setNull(2, java.sql.Types.INTEGER);
                        } else {
                                ps.setLong(2, accountId);
                        }
                        ps.setString(3, content);
                        return ps;
                }, keyHolder);
                Number key = keyHolder.getKey();
                return key == null ? -1L : key.longValue();
        }

        /**
         * 按 key_id 统计调用日志条数(分页用)
         * <p>
         * 包含 key_id = 0/负数的情况(旧数据回填到 id=1 时被自动修正),不会因 key 被删而丢失计数
         */
        public long countCallLogByKeyId(Long keyId) {
                if (keyId == null) return 0L;
                Long cnt = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM downstream_api_key_call_log WHERE key_id = ?",
                                Long.class, keyId);
                return cnt == null ? 0L : cnt;
        }

        /**
         * 按 key_id 分页查询调用日志
         * <p>
         * orderBy 白名单:
         * <ul>
         *   <li>{@code id} —— 按主键</li>
         *   <li>{@code created_at} —— 按时间</li>
         * </ul>
         * 调用方需自行白名单过滤(参考 {@code DownstreamApiKeyQueryRequest.safeOrderBy} 的做法)
         */
        public java.util.List<java.util.Map<String, Object>> findCallLogPage(
                        Long keyId, String orderBy, boolean asc, int offset, int limit) {
                if (keyId == null) return List.of();
                String direction = asc ? "ASC" : "DESC";
                String sql = "SELECT id, key_id, account_id, content, created_at " +
                                "FROM downstream_api_key_call_log " +
                                "WHERE key_id = ? " +
                                "ORDER BY " + orderBy + " " + direction + " LIMIT ? OFFSET ?";
                return jdbcTemplate.queryForList(sql, keyId, limit, offset);
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
                return jdbcTemplate.query(sql, rowMapper, limit, offset);
        }

        /**
         * 查某个 key 的"已用/上限"额度快照 —— 仅取两列
         * <p>
         * 用途:{@code SettingsService.resolveAccountForApiKey} 在鉴权时,需要
         * 比较 {@code usedCredits} 与 {@code creditLimit} 决定是否 401"额度已用完"。
         * <p>
         * <strong>不返回整行</strong>:避免 select * 拉 12 列(本表没那么多但仍是好习惯)。
         * <p>
         * <strong>creditLimit 为 null = 不限</strong>:本方法用 {@code Double} 包装,Null 表示不限,
         * 调用方判断 {@code limit == null} 即可放行。
         *
         * @return {@code CreditUsageSnapshot(usedCredits, creditLimit)};key 不存在时
         *         {@code null}
         */
        public CreditUsageSnapshot findUsageSnapshot(Long id) {
                // 用 queryForObject + RowMapper 比 query 更省,只取两列
                return jdbcTemplate.queryForObject(
                                "SELECT used_credits, credit_limit FROM downstream_api_key WHERE id = ?",
                                (rs, rowNum) -> {
                                        double used = rs.getDouble("used_credits");
                                        double limit = rs.getDouble("credit_limit");
                                        boolean limitWasNull = rs.wasNull();
                                        return new CreditUsageSnapshot(
                                                        used,
                                                        limitWasNull ? null : limit);
                                },
                                id);
        }

        /**
         * 额度快照 record —— service 层只关心两列;不暴露实体 record 避免误用其他字段
         */
        public record CreditUsageSnapshot(Double usedCredits, Double creditLimit) {
                /** 是否有上限(null = 不限) */
                public boolean hasLimit() {
                        return creditLimit != null;
                }

                /** 是否已用满(仅在 {@link #hasLimit()} 为 true 时有意义) */
                public boolean isExhausted() {
                        return creditLimit != null && usedCredits >= creditLimit;
                }
        }
}
