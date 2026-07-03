package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.springframework.stereotype.Service;

/**
 * 上游账号启停服务
 * <p>
 * 启停状态只影响 /v1/chat 的账号路由选择,不影响管理页 Billing 刷新/签到。
 */
@Service
public class AccountEnabledService {

 private final WorkbuddyAccountJdbcRepository accountRepository;

 public AccountEnabledService(WorkbuddyAccountJdbcRepository accountRepository) {
 this.accountRepository = accountRepository;
 }

 public WorkbuddyAccountRecord updateEnabled(Long id, boolean enabled) {
 if (id == null) {
 throw new IllegalArgumentException("id不能为空");
 }
 if (accountRepository.findById(id).isEmpty()) {
 throw new IllegalArgumentException("账户不存在: id=" + id);
 }
 accountRepository.updateEnabled(id, enabled);
 return accountRepository.findById(id)
 .orElseThrow(() -> new IllegalArgumentException("账户不存在: id=" + id));
 }
}
