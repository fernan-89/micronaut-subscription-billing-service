package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.AuditEntry;
import io.micronaut.core.annotation.Introspected;

import java.time.Instant;

/** Persistence form of an {@link AuditEntry}, embedded in both aggregates' documents. */
@Introspected
public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

    public static AuditEntryDocument fromDomain(AuditEntry entry) {
        return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus(), entry.toStatus(), entry.detail());
    }

    public AuditEntry toDomain() {
        return new AuditEntry(occurredAt, action, executor, fromStatus, toStatus, detail);
    }
}
