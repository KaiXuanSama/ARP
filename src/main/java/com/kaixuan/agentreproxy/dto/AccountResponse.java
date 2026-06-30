package com.kaixuan.agentreproxy.dto;

import com.kaixuan.agentreproxy.entity.WorkbuddyAccountRecord;

public record AccountResponse(
        Long id,
        String uid,
        String accountJson,
        String accessToken,
        String apiKey,
        String extra,
        Long createdAt,
        Long updatedAt,
        String action   // "created" / "updated" / "duplicate"
) {
    public static AccountResponse of(WorkbuddyAccountRecord record, String action) {
        return new AccountResponse(
                record.id(),
                record.uid(),
                record.accountJson(),
                record.accessToken(),
                record.apiKey(),
                record.extra(),
                record.createdAt(),
                record.updatedAt(),
                action
        );
    }
}
