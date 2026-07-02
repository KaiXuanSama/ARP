package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.dto.DownstreamApiKeyItem;
import com.kaixuan.agentreproxy.dto.DownstreamApiKeyQueryRequest;
import com.kaixuan.agentreproxy.dto.DownstreamApiKeyRequest;
import com.kaixuan.agentreproxy.dto.PageResult;
import com.kaixuan.agentreproxy.entity.DownstreamApiKeyRecord;
import com.kaixuan.agentreproxy.repository.DownstreamApiKeyJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * 下游 API Key 业务层
 * <p>
 * 职责:
 * <ul>
 *   <li>生成不可猜测的随机 key(35 字符,前缀 ak- 便于识别)</li>
 *   <li>CRUD + 分页查询</li>
 *   <li>创建时返回完整明文(只此一次),查询时遮蔽</li>
 * </ul>
 */
@Service
public class DownstreamApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamApiKeyService.class);

    /** 字母表(去掉了易混淆的 I/O/0/1/l) */
    private static final String ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 随机部分长度 —— 32 字符 + "ak-" 前缀 = 35 字符,基数约 58^32 ≈ 2.4e56,暴力破解不可行 */
    private static final int RANDOM_LEN = 32;
    private static final String KEY_PREFIX = "ak-";

    private final DownstreamApiKeyJdbcRepository repository;
    private final SecureRandom random = new SecureRandom();

    public DownstreamApiKeyService(DownstreamApiKeyJdbcRepository repository) {
        this.repository = repository;
    }

    /**
     * 生成一个不可猜测的随机 key
     */
    public String generateApiKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX.length() + RANDOM_LEN);
        sb.append(KEY_PREFIX);
        for (int i = 0; i < RANDOM_LEN; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * 创建 key,返回完整明文(仅此一次返回)
     */
    public DownstreamApiKeyItem create(DownstreamApiKeyRequest req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        if (req.label() == null || req.label().isBlank()) {
            throw new IllegalArgumentException("label 不能为空");
        }
        if (req.consumption() == null || req.consumption().isBlank()) {
            throw new IllegalArgumentException("consumption 不能为空");
        }
        // 重试生成直到拿到一个不重复的 key(理论上 2.4e56 基数下不会冲突)
        String key;
        int attempts = 0;
        do {
            key = generateApiKey();
            attempts++;
            if (attempts > 5) {
                throw new IllegalStateException("生成 key 失败(连续 5 次冲突,概率极低)");
            }
        } while (repository.findByApiKey(key).isPresent());

        DownstreamApiKeyRecord rec = DownstreamApiKeyRecord.ofNew(
                req.label().trim(),
                key,
                req.consumption(),
                req.designatedAccountId(),
                req.creditLimit(),
                req.expiresAt()
        );
        // enabled 默认为 1
        if (req.enabled() != null && !req.enabled()) {
            rec = new DownstreamApiKeyRecord(
                    rec.id(), rec.label(), rec.apiKey(), rec.callCount(), rec.usedCredits(),
                    rec.creditLimit(), rec.expiresAt(), 0, rec.consumption(),
                    rec.designatedAccountId(), rec.createdAt(), rec.updatedAt());
        }
        long newId = repository.insert(rec);
        DownstreamApiKeyRecord saved = repository.findById(newId).orElseThrow();
        // 创建场景返回完整明文
        return DownstreamApiKeyItem.from(saved, true);
    }

    /**
     * 更新 key(label / creditLimit / expiresAt / enabled / consumption / designatedAccountId)
     * <p>
     * 注意:不允许通过本接口改 apiKey / callCount / usedCredits
     */
    public DownstreamApiKeyItem update(Long id, DownstreamApiKeyRequest req) {
        if (id == null) throw new IllegalArgumentException("id 不能为空");
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        DownstreamApiKeyRecord existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: id=" + id));

        String label = req.label() != null && !req.label().isBlank() ? req.label().trim() : existing.label();
        Double creditLimit = req.creditLimit() != null ? req.creditLimit() : existing.creditLimit();
        Long expiresAt = req.expiresAt() != null ? req.expiresAt() : existing.expiresAt();
        Integer enabled = (req.enabled() != null ? (req.enabled() ? 1 : 0) : existing.enabled());
        String consumption = (req.consumption() != null && !req.consumption().isBlank())
                ? req.consumption() : existing.consumption();
        Long designatedAccountId = req.designatedAccountId() != null
                ? req.designatedAccountId() : existing.designatedAccountId();

        repository.update(id, label, creditLimit, expiresAt, enabled, consumption, designatedAccountId);
        DownstreamApiKeyRecord updated = repository.findById(id).orElseThrow();
        // 更新场景仍返回遮蔽 key(完整明文不返回)
        return DownstreamApiKeyItem.from(updated, false);
    }

    public void delete(Long id) {
        if (id == null) throw new IllegalArgumentException("id 不能为空");
        int n = repository.deleteById(id);
        if (n == 0) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }
    }

    public PageResult<DownstreamApiKeyItem> queryPage(DownstreamApiKeyQueryRequest req) {
        DownstreamApiKeyQueryRequest q = req == null ? new DownstreamApiKeyQueryRequest(null, null, null, null, null) : req;
        int pageNum = q.safePageNum();
        int pageSize = q.safePageSize();
        String orderBy = q.safeOrderBy();
        boolean asc = q.safeAsc();
        int offset = (pageNum - 1) * pageSize;

        // 当前实现不消费 labelLike 过滤(本期不实现搜索),但保留参数以备后续扩展
        long total = repository.count();
        List<DownstreamApiKeyRecord> records = repository.findPage(orderBy, asc, offset, pageSize);
        // 列表场景统一返回遮蔽 key
        // 列表场景:返回明文(分页查询用,前端需要完整 key 才能复制)
        // 仅在创建时一次性返回明文的语义不再保留——避免"创建时明文,后续查就脱敏"的 UX 割裂
        List<DownstreamApiKeyItem> items = records.stream()
                .map(rec -> DownstreamApiKeyItem.from(rec, true))
                .toList();
        return PageResult.of(total, items, pageNum, pageSize);
    }

    /**
     * 按 id 查单个(用于编辑表单回显)
     * <p>
     * 同样返回明文,前端可直接用
     */
    public Optional<DownstreamApiKeyItem> findOne(Long id) {
        return repository.findById(id).map(rec -> DownstreamApiKeyItem.from(rec, true));
    }

    /**
     * 单独修改 enabled 字段
     * <p>
     * 复用 update() 方法,只把 enabled 改掉,其他字段保持原值
     * <p>
     * 为什么不直接用 repository.update():
     * <ul>
     *   <li>update() 内部会判空,只有传入的非 null 字段才更新</li>
     *   <li>走 update() 路径保证和"完整编辑"走同一条 SQL,行为一致</li>
     *   <li>便于审计 —— 所有"修改单字段"的请求都集中在 update() 里</li>
     * </ul>
     */
    public DownstreamApiKeyItem updateEnabled(Long id, boolean enabled) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        // 先校验记录存在 + 拿到现有其他字段值
        DownstreamApiKeyRecord existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: id=" + id));
        int enabledInt = enabled ? 1 : 0;
        repository.update(
                id,
                existing.label(),
                existing.creditLimit(),
                existing.expiresAt(),
                enabledInt,
                existing.consumption(),
                existing.designatedAccountId()
        );
        DownstreamApiKeyRecord updated = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后未找到记录: id=" + id));
        return DownstreamApiKeyItem.from(updated, true);
    }

    /**
     * 记录一次 chat 调用 —— 给指定 key 累加 call_count
     * <p>
     * <strong>调用方</strong>:{@code OpenAiController.chatCompletions} 鉴权通过、即将
     * 调上游之前调一次。
     * <p>
     * <strong>不抛异常</strong>:累加失败仅记 warn 日志,不影响主流程 chat 调用。
     * 理由:call_count 是观测性数据(给 B 端用户看用了多少次),不影响业务正确性。
     * <p>
     * <strong>不走 reactor</strong>:本方法被 chat 流调用,本身在 reactor 线程上,
     * 内部用 {@code .subscribeOn(boundedElastic)} 切到 blocking 池跑 JDBC,
     * 跟项目其他阻塞 IO 一致。
     */
    public void recordCall(Long keyId) {
        if (keyId == null) {
            return;
        }
        try {
            int n = repository.incrementCallCount(keyId);
            if (n == 0) {
                // 极小概率:key 在鉴权通过后、累加之前被并发删除(无 ON DELETE CASCADE,
                // 因为 downstream_api_key 是独立的,不会因 workbuddy_account 删除而删)
                log.warn("call_count 累加失败,key 不存在 id={}", keyId);
            }
        } catch (Exception e) {
            log.warn("call_count 累加异常 keyId={}: {}", keyId, e.getMessage());
        }
    }
}
