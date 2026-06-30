package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.AccountResponse;
import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 账户信息保存服务
 * <p>
 * access_token 与 api_key 是等效的凭证，互补使用。
 * 判重规则：以 "UID + 凭证集(accessToken + apiKey)" 为唯一指纹
 * - UID 不存在 → 新建记录（CREATED）
 * - UID 存在且 凭证集完全相同 → 拒绝写入（DUPLICATE）
 * - UID 存在且 凭证集存在差异 → 覆盖全部信息（OVERWRITTEN）
 */
@Service
public class AccountSaveService {

    private final WorkbuddyAccountJdbcRepository repository;

    public AccountSaveService(WorkbuddyAccountJdbcRepository repository) {
        this.repository = repository;
    }

    public SaveResult save(String uid, String accountJson, String accessToken, String apiKey) {
        Optional<WorkbuddyAccountRecord> existing = repository.findByUid(uid);

        if (existing.isEmpty()) {
            repository.insert(uid, accountJson, accessToken, apiKey);
            WorkbuddyAccountRecord saved = repository.findByUid(uid)
                    .orElseThrow(() -> new IllegalStateException("保存后未找到记录"));
            return new SaveResult(SaveAction.CREATED, AccountResponse.of(saved, "created"));
        }

        WorkbuddyAccountRecord current = existing.get();
        if (Objects.equals(current.accessToken(), accessToken)
                && Objects.equals(current.apiKey(), apiKey)) {
            return new SaveResult(SaveAction.DUPLICATE, AccountResponse.of(current, "duplicate"));
        }

        repository.updateByUid(uid, accountJson, accessToken, apiKey);
        WorkbuddyAccountRecord updated = repository.findByUid(uid)
                .orElseThrow(() -> new IllegalStateException("更新后未找到记录"));
        return new SaveResult(SaveAction.OVERWRITTEN, AccountResponse.of(updated, "updated"));
    }

    public enum SaveAction { CREATED, OVERWRITTEN, DUPLICATE }

    public record SaveResult(SaveAction action, AccountResponse response) {}
}
