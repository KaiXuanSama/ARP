package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.model.WorkbuddyDesktopInfo;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 账户扩展字段（extra）的读写服务
 * <p>
 * extra 是数据库中预留的 JSON 字段，用于存储与账户关联的扩展信息
 * （积分缓存、签到状态、未来的代理配置等）。每个子对象都带 fetchedAt 时间戳。
 */
@Service
public class AccountExtraService {

    private static final Logger log = LoggerFactory.getLogger(AccountExtraService.class);

    private final WorkbuddyAccountJdbcRepository repository;
    private final ObjectMapper objectMapper;

    public AccountExtraService(WorkbuddyAccountJdbcRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取某个账户的完整 extra（可能为 null）
     */
    public Map<String, Object> readExtra(String uid) {
        Optional<WorkbuddyAccountRecord> record = repository.findByUid(uid);
        if (record.isEmpty() || record.get().extra() == null || record.get().extra().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(record.get().extra(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("解析 extra JSON 失败 uid={}, err={}", uid, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 写入某个账户的完整 extra
     */
    public void writeExtra(String uid, Map<String, Object> extra) {
        try {
            String json = objectMapper.writeValueAsString(extra);
            repository.updateExtraByUid(uid, json);
        } catch (Exception e) {
            log.warn("序列化 extra JSON 失败 uid={}, err={}", uid, e.getMessage());
        }
    }

    /**
     * 合并更新某个 key 下的子对象（保留其他 key）
     */
    public void mergeKey(String uid, String key, Map<String, Object> value) {
        Map<String, Object> extra = readExtra(uid);
        extra.put(key, value);
        writeExtra(uid, extra);
    }

    /**
     * 根据 uid 读取数据库中存储的账户信息（token 等）
     *
     * @return 账户信息，不存在时返回 null
     */
    public WorkbuddyDesktopInfo readAccountInfo(String uid) {
        Optional<WorkbuddyAccountRecord> record = repository.findByUid(uid);
        if (record.isEmpty() || record.get().accountJson() == null || record.get().accountJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(record.get().accountJson(), WorkbuddyDesktopInfo.class);
        } catch (Exception e) {
            log.warn("解析 accountJson 失败 uid={}, err={}", uid, e.getMessage());
            return null;
        }
    }
}
