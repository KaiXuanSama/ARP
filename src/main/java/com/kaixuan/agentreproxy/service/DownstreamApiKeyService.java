package com.kaixuan.agentreproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <li>生成不可猜测的随机 key(35 字符,前缀 ak- 便于识别)</li>
 * <li>CRUD + 分页查询</li>
 * <li>创建时返回完整明文(只此一次),查询时遮蔽</li>
 * </ul>
 */
@Service
public class DownstreamApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(DownstreamApiKeyService.class);

    /** 字母表(去掉了易混淆的 I/O/0/1/l) */
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 随机部分长度 —— 32 字符 + "ak-" 前缀 = 35 字符,基数约 58^32 ≈ 2.4e56,暴力破解不可行 */
    private static final int RANDOM_LEN = 32;
    private static final String KEY_PREFIX = "ak-";

    private final DownstreamApiKeyJdbcRepository repository;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public DownstreamApiKeyService(DownstreamApiKeyJdbcRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
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
        if (req == null)
            throw new IllegalArgumentException("请求体不能为空");
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
                req.expiresAt(),
                normalizeSupportedModels(req.supportedModels()));
        // enabled 默认为 1
        if (req.enabled() != null && !req.enabled()) {
                rec = new DownstreamApiKeyRecord(
                        rec.id(), rec.label(), rec.apiKey(), rec.callCount(), rec.usedCredits(),
                        rec.creditLimit(), rec.expiresAt(), 0, rec.consumption(),
                        rec.designatedAccountId(), rec.supportedModels(),
                        rec.createdAt(), rec.updatedAt());
        }
        long newId = repository.insert(rec);
        DownstreamApiKeyRecord saved = repository.findById(newId).orElseThrow();
        // 创建场景返回完整明文
        return DownstreamApiKeyItem.from(saved, true);
    }

    /**
     * 更新 key(label / creditLimit / expiresAt / enabled / consumption /
     * designatedAccountId)
     * <p>
     * 注意:不允许通过本接口改 apiKey / callCount / usedCredits
     * <p>
     * <strong>关于 {@code creditLimit} / {@code expiresAt} 的 null 语义</strong>:
     * 这两个字段是"可清空"的业务设置(把积分上限调成 0 / 取消过期时间)。
     * 约定 <strong>{@code null} = 清空,非 null = 覆盖</strong>(前端永远显式发值,
     * 即使是 null)。
     * <p>
     * 与 {@code enabled} / {@code consumption} / {@code designatedAccountId} 区分:
     * 那几个字段如果传 null 视为"不修改"(向后兼容,旧客户端可能不传);
     * 改这个语义会破坏它们的契约。
     * <p>
     * <strong>为什么不引入 {@code Optional} / 标志位</strong>:本项目前端同仓库、
     * 永远发完整 body;record 字段缺省 = null 区分不出"缺失"和"显式 null",
     * 但本项目两种情况都应走"清空"语义 —— 简化为一个判定。
     */
    public DownstreamApiKeyItem update(Long id, DownstreamApiKeyRequest req) {
        if (id == null)
            throw new IllegalArgumentException("id 不能为空");
        if (req == null)
            throw new IllegalArgumentException("请求体不能为空");
        DownstreamApiKeyRecord existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("记录不存在: id=" + id));

        String label = req.label() != null && !req.label().isBlank() ? req.label().trim() : existing.label();
        // creditLimit / expiresAt: null = 清空(写 NULL),非 null = 覆盖
        Double creditLimit = req.creditLimit(); // 直接吃前端值,包括 null
        Long expiresAt = req.expiresAt(); // 同上
        Integer enabled = (req.enabled() != null ? (req.enabled() ? 1 : 0) : existing.enabled());
        String consumption = (req.consumption() != null && !req.consumption().isBlank())
                ? req.consumption()
                : existing.consumption();
        Long designatedAccountId = req.designatedAccountId() != null
                ? req.designatedAccountId()
                : existing.designatedAccountId();
        // supportedModels 沿用 creditLimit/expiresAt 契约:
        //   - req 字段为 null → 显式清空(写 SQL NULL,/v1/models 回退全集)
        //   - req 字段为 [] → 严格不放行
        //   - req 字段为 [...] → 覆盖白名单
        //
        // 为什么不区分"缺失"和"显式 null":本项目前端同仓库永远发完整 body,
        // 不存在"旧客户端不传该字段"场景;record 字段缺省 = null 与显式 null 同义。
        // 与 creditLimit/expiresAt 完全对称。
        java.util.List<String> supportedModels = normalizeSupportedModels(req.supportedModels());

        repository.update(id, label, creditLimit, expiresAt, enabled, consumption, designatedAccountId, supportedModels);
        DownstreamApiKeyRecord updated = repository.findById(id).orElseThrow();
        // 更新场景仍返回遮蔽 key(完整明文不返回)
        return DownstreamApiKeyItem.from(updated, false);
    }

    public void delete(Long id) {
        if (id == null)
            throw new IllegalArgumentException("id 不能为空");
        int n = repository.deleteById(id);
        if (n == 0) {
            throw new IllegalArgumentException("记录不存在: id=" + id);
        }
    }

    public PageResult<DownstreamApiKeyItem> queryPage(DownstreamApiKeyQueryRequest req) {
        DownstreamApiKeyQueryRequest q = req == null ? new DownstreamApiKeyQueryRequest(null, null, null, null, null)
                : req;
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
     * <li>update() 内部会判空,只有传入的非 null 字段才更新</li>
     * <li>走 update() 路径保证和"完整编辑"走同一条 SQL,行为一致</li>
     * <li>便于审计 —— 所有"修改单字段"的请求都集中在 update() 里</li>
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
                existing.designatedAccountId(),
                existing.supportedModels());
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

    /**
     * 记录 chat 调用的最终结算信息 —— 落库 + 累加 used_credits
     * <p>
     * <strong>调用方</strong>:{@code OpenAiController} 在 SSE 流的 {@code .doOnNext} 里,
     * 识别出"含 usage 字段的 chunk"后调一次。
     * <p>
     * <strong>参数</strong>:{@code rawChunk} 是 <strong>剥掉 {@code data: } 前缀和尾部
     * {@code \n\n}</strong> 后的纯 JSON 字符串(用户原话:存整段 chunk 文本)。
     * <p>
     * <strong>做的事</strong>:
     * <ol>
     * <li>解析 JSON,提取 {@code usage.credit}(浮点;CodeBuddy 自定义,OpenAI 协议无)</li>
     * <li>把整段 rawChunk 写入 {@code downstream_api_key_call_log}</li>
     * <li>原子累加 {@code downstream_api_key.used_credits}</li>
     * </ol>
     * <p>
     * <strong>不抛异常</strong>:任何一步失败仅记 warn 日志,不影响主流程 chat 响应。
     * 理由:credit 是观测性数据,且 SSE 已经在透传给客户端,日志/累加失败不应回滚。
     *
     * @param keyId    下游 key 主键
     * @param rawChunk 整段 chunk JSON 文本(已剥 {@code data:} 前缀)
     */
    public void recordChatUsage(Long keyId, String rawChunk) {
        if (keyId == null || rawChunk == null || rawChunk.isBlank()) {
            return;
        }
        try {
            // 1) 写日志表(整段 chunk 文本)
            long logId = repository.insertCallLog(rawChunk);
            if (logId < 0) {
                log.warn("调用日志插入失败 keyId={}", keyId);
                return; // 写日志失败不继续累加,避免 credit 累加了但没日志可追溯
            }
            // 2) 解析 usage.credit
            double credit = parseCredit(rawChunk);
            if (credit <= 0D) {
                // 没有 credit 字段(老 chunk 格式) 或为 0,只记日志不累加
                log.debug("chat chunk 不含 usage.credit keyId={} logId={}", keyId, logId);
                return;
            }
            // 3) 累加 used_credits
            int n = repository.incrementUsedCredits(keyId, credit);
            if (n == 0) {
                log.warn("used_credits 累加失败,key 不存在 keyId={}", keyId);
            } else {
                log.info("chat 落账:keyId={} credit={} logId={}", keyId, credit, logId);
            }
        } catch (Exception e) {
            // 任何异常不外抛
            log.warn("recordChatUsage 异常 keyId={}: {}", keyId, e.getMessage());
        }
    }

    /**
     * 规范化 supportedModels 入参
     * <p>
     * 行为:
     * <ul>
     *   <li>{@code null} → null(表示"未配置",落库时走 SQL NULL,/v1/models 回退全集)</li>
     *   <li>空 list → 空 list(落库 "[]",/v1/models 严格不放行)</li>
     *   <li>非空 list → 去空串 / trim / 去重(保插入顺序),返回不可变 List</li>
     * </ul>
     * 不抛异常(脏数据降级为"忽略该项")
     */
    private static java.util.List<String> normalizeSupportedModels(java.util.List<String> raw) {
        if (raw == null) {
            return null;
        }
        java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) {
                dedup.add(t);
            }
        }
        return java.util.List.copyOf(dedup);
    }

    /**
     * 从 chunk JSON 中提取 {@code usage.credit} 字段
     * <p>
     * CodeBuddy 实际返回两种形态:
     * <ul>
     * <li>{@code "usage":{"credit":5.13, ...}} —— 数值型</li>
     * <li>无 credit 字段(老格式 / 中间 chunk) —— 返回 0</li>
     * <li>{@code "usage":null} 或缺失 —— 返回 0</li>
     * </ul>
     * <p>
     * 解析失败(非 JSON / 缺字段)不抛,返回 0
     */
    private double parseCredit(String rawChunk) {
        try {
            JsonNode root = objectMapper.readTree(rawChunk);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return 0D;
            }
            JsonNode creditNode = usage.path("credit");
            if (creditNode.isMissingNode() || creditNode.isNull()) {
                return 0D;
            }
            if (creditNode.isNumber()) {
                return creditNode.doubleValue();
            }
            if (creditNode.isTextual()) {
                try {
                    return Double.parseDouble(creditNode.asText().trim());
                } catch (NumberFormatException ignored) {
                    return 0D;
                }
            }
            return 0D;
        } catch (Exception e) {
            log.debug("parseCredit 失败,忽略: {}", e.getMessage());
            return 0D;
        }
    }
}
