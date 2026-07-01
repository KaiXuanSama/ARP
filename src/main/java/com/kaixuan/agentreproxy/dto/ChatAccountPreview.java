package com.kaixuan.agentreproxy.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * "chat.consumption 预览"接口的响应项 —— 用于让前端在不真正切换消耗方式的情况下,
 * 知道"如果我现在按 mode=X 路由,后端会选哪个账号".
 * <p>
 * 当前仅 {@code expiring} 模式可生成有效数据;{@code least} / {@code most} 模式
 * 字段会为 null,service 层在不支持时会抛 400,由 {@code GlobalExceptionHandler} 映射.
 *
 * @param accountId            数据库 workbuddy_account.id 主键(调用方路由时使用)
 * @param uid                 账户 UID
 * @param mode                实际生效的 mode(与请求参数一致)
 * @param packageCode         命中的流量包 code(仅 expiring 模式;least/most 当前为 null)
 * @param cycleEndTime        命中包的到期时间(仅 expiring;least/most 当前为 null)
 * @param cycleCapacityRemain 命中包当前余量(仅 expiring;least/most 当前为 null)
 */
public record ChatAccountPreview(
        Long accountId,
        String uid,
        String mode,
        String packageCode,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime cycleEndTime,
        Double cycleCapacityRemain
) {}
