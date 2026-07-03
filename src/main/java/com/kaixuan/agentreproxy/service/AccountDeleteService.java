package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账户删除服务
 * <p>
 * 职责:删除一个 workbuddy_account 记录 + 触发 FK 级联清理签到日志
 * <p>
 * 为什么独立成 service(而不是合并到 {@link AccountSaveService}):
 * <ul>
 *   <li>命名清晰 —— "Save" 暗示"保存",把 delete 塞进去名不副实</li>
 *   <li>后续如果加"软删除 / 异步删除 / 备份"等扩展,这里加方法不影响 save 路径</li>
 * </ul>
 */
@Service
public class AccountDeleteService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeleteService.class);

    private final WorkbuddyAccountJdbcRepository accountRepository;

    public AccountDeleteService(WorkbuddyAccountJdbcRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * 按主键 id 删除账户
     * <p>
     * 关联清理(由 SQLite FK 自动处理):
     * <ul>
     *   <li>该账户的所有签到日志(checkin_log)→ CASCADE 删除</li>
     *   <li>所有指定该账户的下游 key(downstream_api_key.designated_account_id)→ SET NULL</li>
     * </ul>
     *
     * @param id workbuddy_account.id
     * @throws IllegalArgumentException id 为空或记录不存在
     */
    public void deleteById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        int rows = accountRepository.deleteById(id);
        if (rows == 0) {
            throw new IllegalArgumentException("账户不存在: id=" + id);
        }
        log.info("已删除账户 id={} (签到日志由 FK CASCADE 清理;下游 key 的 designated_account_id 由 FK SET NULL 处理)", id);
    }
}
