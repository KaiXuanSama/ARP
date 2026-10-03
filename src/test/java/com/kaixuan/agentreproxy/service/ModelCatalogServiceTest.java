package com.kaixuan.agentreproxy.service;

import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;
import com.kaixuan.agentreproxy.model.ModelConfig;
import com.kaixuan.agentreproxy.repository.WorkbuddyAccountJdbcRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ModelCatalogService} 单元测试 —— 钉住刷新与过滤契约
 * <p>
 * 上游调用用 Mockito 桩掉 {@link UpstreamClient#fetchModelConfig}
 * （HTTP 行为已由 UpstreamClientModelConfigTest 覆盖），本类聚焦：
 * 条目过滤/映射、失败跳过与保旧值、无账号报错、单飞、元信息。
 */
class ModelCatalogServiceTest {

    private UpstreamClient upstreamClient;
    private WorkbuddyAccountJdbcRepository repository;
    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        upstreamClient = mock(UpstreamClient.class);
        repository = mock(WorkbuddyAccountJdbcRepository.class);
        service = new ModelCatalogService(upstreamClient, repository);
    }

    // ============== 工具 ==============

    private static WorkbuddyAccountRecord account(long id, String uid) {
        return new WorkbuddyAccountRecord(id, uid, "{}", "jwt-" + id, null,
                1, null, 0L, 0L);
    }

    /** 构造上游 models 数组（每个元素 id 必填，其余可选） */
    private static ArrayNode models(Object... entries) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (Object e : entries) {
            if (e instanceof String id) {
                arr.addObject().put("id", id);
            } else if (e instanceof String[] parts) {
                // {id, vendor, maxInputTokens}
                var o = arr.addObject();
                o.put("id", parts[0]);
                if (parts.length > 1 && parts[1] != null) o.put("vendor", parts[1]);
                if (parts.length > 2) o.put("maxInputTokens", Integer.parseInt(parts[2]));
            }
        }
        return arr;
    }

    /** 带标签的条目（构造图像/视频模型） */
    private static com.fasterxml.jackson.databind.JsonNode taggedModel(String id, String tag) {
        var o = JsonNodeFactory.instance.objectNode();
        o.put("id", id);
        o.putArray("tags").add(tag);
        return o;
    }

    private void stubAccounts(WorkbuddyAccountRecord... accounts) {
        when(repository.findAll()).thenReturn(List.of(accounts));
    }

    private void stubUpstream(long accountId, ArrayNode models) {
        when(upstreamClient.fetchModelConfig(accountId))
                .thenReturn(Mono.just((com.fasterxml.jackson.databind.JsonNode) models));
    }

    private void stubUpstreamError(long accountId, String message) {
        when(upstreamClient.fetchModelConfig(accountId))
                .thenReturn(Mono.error(new IllegalStateException(message)));
    }

    private Map<String, Object> refresh() {
        return service.refresh().block(Duration.ofSeconds(10));
    }

    // ============== 刷新：过滤与映射 ==============

    @Test
    @DisplayName("成功刷新：剔除图像/视频模型与坏条目，映射 vendor→family、maxInputTokens→contextLength")
    void refreshFiltersAndMaps() {
        var raw = new ArrayNode(JsonNodeFactory.instance);
        raw.add(taggedModel("img-model", "text-to-image"));
        raw.add(taggedModel("video-model", "text-to-video"));
        raw.add(JsonNodeFactory.instance.objectNode().put("name", "no-id"));   // 缺 id
        raw.add(JsonNodeFactory.instance.textNode("not-an-object"));           // 非对象
        raw.add(models("deepseek-v4").get(0));
        raw.add(models("deepseek-v4").get(0));                                 // 重复 id
        var glm = JsonNodeFactory.instance.objectNode();
        glm.put("id", "glm-5.2").put("vendor", "zhipu").put("maxInputTokens", 1048576);
        raw.add(glm);

        stubAccounts(account(1, "uid-a"));
        stubUpstream(1, raw);

        Map<String, Object> meta = refresh();

        assertThat(meta.get("count")).isEqualTo(2);
        assertThat(meta.get("sourceAccountId")).isEqualTo(1L);
        assertThat(meta.get("sourceLabel")).isEqualTo("uid-a");
        assertThat((long) meta.get("fetchedAt")).isGreaterThan(0);

        List<ModelConfig> result = service.getModels();
        assertThat(result).extracting(ModelConfig::id)
                .containsExactly("deepseek-v4", "glm-5.2");
        // 无 vendor → unknown；有 vendor → family
        assertThat(result).extracting(ModelConfig::family)
                .containsExactly("unknown", "zhipu");
        // contextLength 映射
        assertThat(result.get(1).contextLength()).isEqualTo(1048576);
    }

    @Test
    @DisplayName("tags 缺失视为聊天模型；tags 类型无法识别则防御剔除")
    void tagSemantics() {
        var raw = new ArrayNode(JsonNodeFactory.instance);
        raw.add(models("plain-chat").get(0));   // 无 tags → 保留
        var weird = JsonNodeFactory.instance.objectNode();
        weird.put("id", "weird-tags");
        weird.put("tags", 42);                  // 非字符串/数组 → 剔除
        raw.add(weird);
        var strTag = JsonNodeFactory.instance.objectNode();
        strTag.put("id", "str-tag-img");
        strTag.put("tags", "text-to-image");    // 字符串形态标签命中 → 剔除
        raw.add(strTag);

        stubAccounts(account(1, "uid-a"));
        stubUpstream(1, raw);

        refresh();
        assertThat(service.getModels()).extracting(ModelConfig::id)
                .containsExactly("plain-chat");
    }

    // ============== 刷新：失败跳过与保旧值 ==============

    @Test
    @DisplayName("失败跳过：失败账号被顺延，剩余账号中一个成功即止")
    void failureSkipsToNextAccount() {
        // 洗牌导致账号顺序随机：断言必须与顺序无关 ——
        // #1 永远失败，#2/#3 均可成功，最终来源必为二者之一且目录正确
        stubAccounts(account(1, "uid-a"), account(2, "uid-b"), account(3, "uid-c"));
        stubUpstreamError(1, "code=12403 check ua");
        stubUpstream(2, models("glm-5.2"));
        stubUpstream(3, models("kimi-k3"));

        Map<String, Object> meta = refresh();

        // 来源必为成功账号之一
        assertThat(meta.get("sourceAccountId")).isIn(2L, 3L);
        // 目录来自来源账号
        List<String> ids = service.getModels().stream().map(ModelConfig::id).toList();
        assertThat(ids).hasSize(1).containsAnyOf("glm-5.2", "kimi-k3");
    }

    @Test
    @DisplayName("全部账号失败：报错且旧目录保留不动")
    void allFailKeepsOldCatalog() {
        // 先成功一次建立旧目录（只有账号 1）
        stubAccounts(account(1, "uid-a"));
        stubUpstream(1, models("old-model"));
        refresh();
        assertThat(service.getModels()).extracting(ModelConfig::id).containsExactly("old-model");

        // 再模拟全部失败（两个账号都打不通）：目录不动
        stubAccounts(account(1, "uid-a"), account(2, "uid-b"));
        stubUpstreamError(1, "code=12403");
        stubUpstreamError(2, "HTTP 401");

        assertThatThrownBy(this::refresh)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("所有账号均未成功");
        assertThat(service.getModels()).extracting(ModelConfig::id).containsExactly("old-model");
    }

    @Test
    @DisplayName("账号返回空目录：视为失败顺延（正常账号目录不会为空）")
    void emptyCatalogSkipsAccount() {
        stubAccounts(account(1, "uid-a"), account(2, "uid-b"));
        stubUpstream(1, models());                 // 空目录
        stubUpstream(2, models("kimi-k3"));

        Map<String, Object> meta = refresh();
        assertThat(meta.get("sourceAccountId")).isEqualTo(2L);
    }

    @Test
    @DisplayName("停用账号不参与遍历（与 chat 路由口径一致）")
    void disabledAccountExcluded() {
        WorkbuddyAccountRecord disabled = new WorkbuddyAccountRecord(1L, "uid-a", "{}",
                "jwt", null, Integer.valueOf(0), null, 0L, 0L);   // enabled=0
        stubAccounts(disabled);

        assertThatThrownBy(this::refresh)
                .hasMessageContaining("没有可用账号");
        org.mockito.Mockito.verify(upstreamClient, org.mockito.Mockito.never())
                .fetchModelConfig(org.mockito.ArgumentMatchers.any(Long.class));
    }

    @Test
    @DisplayName("无任何账号：报错")
    void noAccounts() {
        stubAccounts();
        assertThatThrownBy(this::refresh)
                .hasMessageContaining("没有可用账号");
    }

    // ============== 刷新：单飞 ==============

    @Test
    @DisplayName("单飞：已有刷新在跑时，并发调用直接报错")
    void refreshIsSingleFlight() {
        stubAccounts(account(1, "uid-a"));
        // 模拟慢上游：订阅后延迟返回
        when(upstreamClient.fetchModelConfig(1L)).thenReturn(
                Mono.just((com.fasterxml.jackson.databind.JsonNode) models("slow-model"))
                        .delayElement(Duration.ofSeconds(2)));

        var first = service.refresh();   // 未订阅完成
        first.subscribe();
        // 立刻发起第二次：应被单飞挡下
        assertThatThrownBy(this::refresh)
                .hasMessageContaining("进行中");
        first.block(Duration.ofSeconds(10));
        assertThat(service.getModels()).extracting(ModelConfig::id).containsExactly("slow-model");
    }

    // ============== 初始状态 ==============

    @Test
    @DisplayName("初始状态：目录为空列表、元信息为空 map")
    void initialEmptyState() {
        assertThat(service.getModels()).isEmpty();
        assertThat(service.getMeta()).isEmpty();
    }
}
